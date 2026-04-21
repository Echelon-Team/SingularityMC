// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.bootstrap

import com.singularity.agent.mixin.MixinConfigScanner
import com.singularity.agent.mod.ModInfo
import com.singularity.agent.mod.ModInitializer
import com.singularity.agent.mod.ModLoadingOrchestrator
import com.singularity.agent.registry.SingularityModRegistry
import com.singularity.common.model.LoaderType
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Bootstrap modów — integracja Sub 2c w AgentMain.bootstrap() po Step 14 (cache cleanup).
 *
 * Sekwencja:
 * 1. Discovery — `ModLoadingOrchestrator.discoverAndParse(modsDir)`
 * 2. Dla każdego moda (w topological order):
 *    - `classLoader.addModJar(jarPath)` — rejestruje JAR do JarRegistry (class loading visible)
 *    - Zbiera mixin configs z `ModInfo.mixinConfigs` (primary) + `MixinConfigScanner.scanJar()` (fallback)
 * 3. `mixinBootstrapInit()` raz — **PRZED** addConfiguration (domyślnie `MixinBootstrap.init()`)
 * 4. Dla każdego mixin config: `mixinConfigInjector(config)` (domyślnie `Mixins.addConfiguration()`)
 * 5. Rejestracja modów w `SingularityModRegistry`
 * 6. `ModInitializer.initialize(forgeMods, fabricMods)` — fazowa inicjalizacja (1a→1b→2→3→4)
 *
 * **Kolejność Mixin init**: `MixinBootstrap.init()` MUSI być wywołany PRZED
 * `Mixins.addConfiguration()`. Verified against FabricMixinBootstrap.java (linie 59+93):
 * init() → pętla addConfiguration(). Bez tej kolejności `Mixins.addConfiguration()` queue'uje
 * configs do not-yet-initialized env → fragile, zależy od tolerant queue behavior w Mixin 0.8.7.
 *
 * Mixin framework injection points są wyciągnięte jako lambdy, żeby test mogł podać no-op
 * bez odpalania prawdziwego Mixin framework (który próbuje otwierać Mixin configs z classpath
 * i wymaga real running MC).
 *
 * W Sub 2c `ModInitializer` dostaje logging-only callbacki (no-op dla class loading).
 * W Sub 2d/2e real callbacki wiązane do EventBridge + shims.
 *
 * Referencja: Sub 2c plan + Sub 2b AgentMain bootstrap sequence.
 */
object ModBootstrap {

    private val logger = LoggerFactory.getLogger(ModBootstrap::class.java)

    data class Result(
        val discoveryResult: ModLoadingOrchestrator.DiscoveryResult,
        val registeredCount: Int,
        val loadedMixinConfigs: List<String>
    )

    /**
     * Wire mod loading pipeline do agent bootstrap.
     *
     * @param modsDir ścieżka do folderu `mods/` instancji
     * @param addModJar callback rejestrujący JAR do classloadera. W agent production to
     *                  `singularityClassLoader::addModJar`. Wyciągnięte jako lambda żeby
     *                  test mógł symulować failure (SingularityClassLoader.addModJar nie
     *                  jest `open` — bez lambdy nie da się mock'ować).
     * @param registry SingularityModRegistry (pusty, zostanie wypełniony)
     * @param initializer ModInitializer z callbackami (no-op w Sub 2c, real w Sub 2d)
     * @param mixinConfigInjector callback wywoływany per mixin config name (default: `Mixins.addConfiguration`)
     * @param mixinBootstrapInit callback wywoływany raz po dodaniu configs (default: `MixinBootstrap.init`)
     * @return Result z liczbą zarejestrowanych modów + listą mixin configs
     */
    fun loadMods(
        modsDir: Path,
        addModJar: (Path) -> String,
        registry: SingularityModRegistry,
        initializer: ModInitializer,
        mixinConfigInjector: (String) -> Unit = ::defaultMixinConfigInjector,
        mixinBootstrapInit: () -> Unit = ::defaultMixinBootstrapInit
    ): Result {
        logger.info("ModBootstrap: starting mod loading from {}", modsDir)

        // 1. Discovery + parse + dependency resolution + duplicates
        val discoveryResult = ModLoadingOrchestrator.discoverAndParse(modsDir)
        logger.info(
            "ModBootstrap discovery: {} mods, {} duplicates, {} resolution errors, {} unknown JARs",
            discoveryResult.mods.size,
            discoveryResult.duplicates.size,
            discoveryResult.resolutionErrors.size,
            discoveryResult.unknownJars.size
        )

        // Log duplicates and resolution errors (warn only — user decides in launcher UI Sub 4)
        for (dup in discoveryResult.duplicates) {
            when (dup) {
                is com.singularity.agent.mod.DuplicateDetector.DuplicateAction.KeepNewer ->
                    logger.warn(
                        "Duplicate: keeping {} v{}, older v{} should be removed",
                        dup.keep.modId, dup.keep.version, dup.remove.version
                    )
                is com.singularity.agent.mod.DuplicateDetector.DuplicateAction.ConflictingIds ->
                    logger.error(
                        "Conflicting mod IDs: {} (from {}) vs {} (from {})",
                        dup.modA.name, dup.modA.jarPath.fileName,
                        dup.modB.name, dup.modB.jarPath.fileName
                    )
                is com.singularity.agent.mod.DuplicateDetector.DuplicateAction.CrossLoaderSameId ->
                    logger.info(
                        "Cross-loader same modId '{}': {} mods from different ecosystems — both remain",
                        dup.mods.first().modId, dup.mods.map { it.loaderType }.distinct()
                    )
            }
        }
        for (err in discoveryResult.resolutionErrors) {
            when (err) {
                is com.singularity.agent.mod.DependencyResolver.DependencyError.MissingRequired ->
                    logger.error(
                        "Missing required dependency: {} requires {} (range: {})",
                        err.requiredBy, err.missingModId, err.versionRange ?: "any"
                    )
                is com.singularity.agent.mod.DependencyResolver.DependencyError.CyclicDependency ->
                    logger.error("Cyclic dependency among mods: {}", err.involvedMods)
            }
        }

        // 2. Add mod JARs to classloader + collect mixin configs.
        // Track failed JARs — jeśli addModJar throws, mod nie powinien być zarejestrowany
        // ani przechodzić phased init (jego klasy i tak są niedostępne).
        val mixinConfigs = linkedSetOf<String>() // preserve insertion order, dedupe
        val failedJars = mutableSetOf<java.nio.file.Path>()
        for (mod in discoveryResult.sortedMods) {
            try {
                addModJar(mod.jarPath)
            } catch (e: Exception) {
                logger.error(
                    "Failed to add mod JAR {} to classloader — mod '{}' will be skipped: {}",
                    mod.jarPath.fileName, mod.modId, e.message, e
                )
                failedJars.add(mod.jarPath)
                continue
            }

            // Primary: mixin configs z ModInfo (parser już je wyciągnął)
            mixinConfigs.addAll(mod.mixinConfigs)

            // Fallback: scan JAR bezpośrednio (redundancy — parser mógł przeoczyć object-form)
            try {
                mixinConfigs.addAll(MixinConfigScanner.scanJar(mod.jarPath))
            } catch (e: Exception) {
                logger.warn("MixinConfigScanner failed for {}: {}", mod.jarPath.fileName, e.message)
            }
        }
        val successfulMods = discoveryResult.sortedMods.filter { it.jarPath !in failedJars }
        logger.info(
            "ModBootstrap: {} JARs added to classloader ({} failed), {} mixin configs collected",
            successfulMods.size, failedJars.size, mixinConfigs.size
        )

        // 3. MixinBootstrap.init() — raz, ZAWSZE, nawet przy zero configs.
        // Musi być PRZED addConfiguration — inaczej Mixins.addConfiguration queue'uje
        // configs do not-yet-initialized environment (fragile, tolerant queue only).
        // Verified order: FabricMixinBootstrap.java:59+93 → init() PRZED loop addConfiguration.
        try {
            mixinBootstrapInit()
            logger.info("MixinBootstrap.init() complete")
        } catch (e: Exception) {
            logger.error("MixinBootstrap.init() failed: {}", e.message, e)
        }

        // 4. Wire mixin configs do Mixin framework PO init
        for (config in mixinConfigs) {
            try {
                mixinConfigInjector(config)
                logger.debug("Mixin config registered: {}", config)
            } catch (e: Exception) {
                logger.error("Failed to add mixin config '{}': {}", config, e.message)
            }
        }
        if (mixinConfigs.isNotEmpty()) {
            logger.info("Registered {} mixin configs post-init", mixinConfigs.size)
        }

        // 5. Register mods in SingularityModRegistry — TYLKO successfully loaded
        // (failedJars excluded — ich klasy nie są dostępne via classloader,
        // shim próbujący zdispatch'ować entrypoint dostałby NCDF).
        for (mod in successfulMods) {
            registry.register(modToRegistryEntry(mod))
        }
        logger.info("ModBootstrap: {} mods registered in SingularityModRegistry", registry.size)

        // 6. Phased initialization — 1a (Forge+MULTI) → 1b (Fabric+MULTI) → 2 → 3 → 4.
        // MULTI mods dostają OBA fazy (Forge registry + Fabric onInitialize) zgodnie ze
        // spec 5A.5 i 5A.7 — są widoczne dla obu loader shims.
        val forgeMods = successfulMods.filter {
            it.loaderType == LoaderType.FORGE ||
                it.loaderType == LoaderType.NEOFORGE ||
                it.loaderType == LoaderType.MULTI
        }
        val fabricMods = successfulMods.filter {
            it.loaderType == LoaderType.FABRIC || it.loaderType == LoaderType.MULTI
        }
        initializer.initialize(forgeMods, fabricMods)

        logger.info("ModBootstrap: loading pipeline COMPLETE")
        return Result(
            discoveryResult = discoveryResult,
            registeredCount = registry.size,
            loadedMixinConfigs = mixinConfigs.toList()
        )
    }

    /**
     * Konwertuje ModInfo (heavy, agent-internal) do RegisteredMod (lightweight, registry entry).
     */
    internal fun modToRegistryEntry(mod: ModInfo): SingularityModRegistry.RegisteredMod =
        SingularityModRegistry.RegisteredMod(
            modId = mod.modId,
            version = mod.version,
            name = mod.name,
            loaderType = mod.loaderType,
            side = mod.side
        )

    /**
     * Default mixin config injector — wywołuje `Mixins.addConfiguration(configName)`.
     * Wyciągnięte jako osobna funkcja żeby test mógł ominąć prawdziwy Mixin framework.
     */
    private fun defaultMixinConfigInjector(configName: String) {
        org.spongepowered.asm.mixin.Mixins.addConfiguration(configName)
    }

    /**
     * Default Mixin bootstrap initializer — wywołuje `MixinBootstrap.init()`.
     * Wyciągnięte jako osobna funkcja żeby test mógł ominąć prawdziwy Mixin framework.
     */
    private fun defaultMixinBootstrapInit() {
        org.spongepowered.asm.launch.MixinBootstrap.init()
    }
}
