package com.singularity.agent

import com.singularity.agent.bootstrap.CacheVersionManager
import com.singularity.agent.bootstrap.MappingTableLoader
import com.singularity.agent.bootstrap.McJarScanner
import com.singularity.agent.bootstrap.ModBootstrap
import com.singularity.agent.bootstrap.VersionMetadata
import com.singularity.agent.cache.CacheKey
import com.singularity.agent.cache.TransformCache
import com.singularity.agent.classloader.JarRegistry
import com.singularity.agent.classloader.SingularityClassLoader
import com.singularity.agent.mixin.EngineMixinBytecodeSource
import com.singularity.agent.mixin.SingularityMixinService
import com.singularity.agent.mod.ModInitializer
import com.singularity.agent.module.ContractValidator
import com.singularity.agent.module.LoadedModule
import com.singularity.agent.module.ModuleLoader
import com.singularity.agent.pipeline.SingularityTransformer
import com.singularity.agent.registry.SingularityModRegistry
import com.singularity.agent.remapping.InheritanceTree
import com.singularity.agent.remapping.RemappingEngine
import org.slf4j.LoggerFactory
import org.spongepowered.asm.service.IMixinService
import java.lang.instrument.Instrumentation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ServiceLoader

/**
 * Entry point agenta SingularityMC (javaagent premain).
 *
 * Ladowany przez JVM z flaga -javaagent:singularity-agent.jar=<instanceDir>.
 * Metoda premain() wywolywana PRZED main() Minecrafta.
 *
 * Sub 2b FULL bootstrap sequence (steps 1-14, AD1-AD8 implementation):
 * 1. Parse args → instanceDir
 * 2. Version metadata (agentVer z MANIFEST)
 * 3. Load compat module JAR → contract validation (w try/finally z temp cleanup)
 * 4. Cache version check + bulk clearAll jesli stale
 * 5. Load 3 mapping tables (.tiny v2) z module JAR
 * 6. Find MC JAR (AD4 shared cache pattern)
 * 7. Scan MC JAR → InheritanceTree (SKIP_CODE fast header scan)
 * 8. Build JarRegistry (MC + module)
 * 9. Build RemappingEngine + TransformCache + SingularityTransformer
 * 10. Build EngineMixinBytecodeSource
 * 11. Build SingularityClassLoader z transformFunction
 * 12. ServiceLoader discover SingularityMixinService + inject bytecodeSource
 * 13. Set Thread.contextClassLoader = SingularityClassLoader (dla MC main thread)
 * 14. Cleanup stale cache dirKey's + write version.properties
 *
 * Sub 2c mod loading steps (15-20):
 * 15. Build SingularityModRegistry + ModInitializer (stub callbacki — real w Sub 2d/2e)
 * 16. ModBootstrap.loadMods — discovery, parse, dependency resolution, duplicates
 * 17. Per discovered mod: classLoader.addModJar + MixinConfigScanner.scanJar
 * 18. Mixins.addConfiguration per collected mixin config
 * 19. MixinBootstrap.init() — wyłącznie gdy są configs
 * 20. Register mods w SingularityModRegistry + ModInitializer.initialize (fazowa 1a→1b→2→3→4)
 *
 * BLOCKER fix (edge-case-hunter review): temp mapping dir jest lifecycled przez try/finally
 * + deleteRecursively zeby nie leakowac ~30-150MB per-launch w java.io.tmpdir.
 *
 * Referencja: design spec sekcja 5.3, implementation design sekcja 4.1, AD1-AD8.
 */
object AgentMain {

    private val logger = LoggerFactory.getLogger(AgentMain::class.java)

    /**
     * Kontrakty oferowane przez agenta. Modul musi wymagac PODZBIORU (agent ⊇ modul).
     */
    val OFFERED_CONTRACTS = setOf(
        "metadata",
        "remapping",
        "loader_emulation",
        "bridges",
        "hooks"
    )

    /**
     * Global mod registry — zainicjalizowany w bootstrap(), wypełniany w Sub 2c ModBootstrap.
     * Dostępny dla shimów w module compat (Sub 2d) przez bridge contracts.
     *
     * Nullable bo bootstrap może nie wystartować w standalone mode (brak instance args).
     */
    @Volatile
    var modRegistry: SingularityModRegistry? = null
        private set

    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        logger.info("SingularityMC Agent premain — starting bootstrap")
        logger.info("Agent args: {}", agentArgs ?: "none")
        logger.info("Can redefine classes: {}", inst.isRedefineClassesSupported)
        logger.info("Can retransform classes: {}", inst.isRetransformClassesSupported)

        if (agentArgs.isNullOrBlank()) {
            logger.info("No instance path — running in standalone mode (no bootstrap)")
            return
        }

        try {
            bootstrap(Paths.get(agentArgs))
        } catch (e: Exception) {
            logger.error("Agent bootstrap FAILED: {}", e.message, e)
            // Fail-fast: propagate do JVM, MC nie wystartuje
            throw RuntimeException("SingularityMC agent bootstrap failed", e)
        }
    }

    private fun bootstrap(instanceDir: Path) {
        // Step 1: Parse args
        val mcVersion = "1.20.1"  // TODO Sub 6: czytac z instance.json
        val modulesDir = instanceDir.resolve(".singularity/modules")
        val cacheRoot = instanceDir.resolve(".singularity/cache/transform")

        // Step 2: Version metadata
        val agentVersion = VersionMetadata.agentVersion
        logger.info("Agent version: {}", agentVersion)

        // Step 3: Load compat module JAR
        val modulePath = ModuleLoader.findModule(modulesDir, mcVersion)
            ?: throw IllegalStateException("No compat module found for MC $mcVersion in $modulesDir")

        // BLOCKER fix (edge-case-hunter review): temp dir utworzony PRZED try block,
        // cleanup w finally. Bez tego ~30-150MB leak per-launch w java.io.tmpdir
        // (3 pliki .tiny o rozmiarze 5-50MB kazdy).
        val tempMappingsDir = Files.createTempDirectory("singularity-mappings-")
        try {
            // LoadedModule lifecycle: eager-load-then-close (flag #15 decision).
            // Otwieramy JAR, czytamy descriptor + ekstraktujemy mapping files do temp, zamykamy.
            val (descriptor, mappingPaths) = ModuleLoader.loadModule(modulePath).use { module ->
                val validation = ContractValidator.validate(OFFERED_CONTRACTS, module.descriptor)
                if (!validation.isValid) {
                    throw IllegalStateException("Contract validation FAILED: ${validation.errorMessage}")
                }
                logger.info("Module contract validated: {}", module.descriptor.moduleId)

                val obfPath = extractModuleEntry(module, "mappings/obf-to-mojmap.tiny", tempMappingsDir)
                val srgPath = extractModuleEntry(module, "mappings/srg-to-mojmap.tiny", tempMappingsDir)
                val intermediaryPath = extractModuleEntry(module, "mappings/intermediary-to-mojmap.tiny", tempMappingsDir)

                Pair(module.descriptor, Triple(obfPath, srgPath, intermediaryPath))
            }

            val moduleVersion = descriptor.moduleVersion

            // Step 4: Cache version check + bulk invalidation (AD8)
            val cacheVersionManager = CacheVersionManager(cacheRoot)
            if (cacheVersionManager.isStale(agentVersion, moduleVersion)) {
                logger.info("Cache is stale (agent {} / module {}), clearing", agentVersion, moduleVersion)
                cacheVersionManager.clearAll()
            }

            // Step 5: Load 3 mapping tables
            val obfTable = MappingTableLoader.loadTinyV2(mappingPaths.first, "obf", "mojmap")
            val srgTable = MappingTableLoader.loadTinyV2(mappingPaths.second, "srg", "mojmap")
            val intermediaryTable = MappingTableLoader.loadTinyV2(mappingPaths.third, "intermediary", "mojmap")

            // Step 6: Find MC JAR (AD4 shared cache pattern)
            val mcJarPath = findMcJar(instanceDir, mcVersion)

            // Step 7: Build InheritanceTree from MC JAR scan
            val tree = InheritanceTree()
            if (mcJarPath != null) {
                McJarScanner.scanInto(mcJarPath, tree)
                logger.info("InheritanceTree built: {} classes", tree.size)
            } else {
                logger.warn("MC JAR not found — pipeline will not remap MC classes (tree size 0)")
            }

            // Step 8: Build JarRegistry
            val jarRegistry = JarRegistry()
            if (mcJarPath != null) jarRegistry.addJar(mcJarPath)
            jarRegistry.addJar(modulePath)

            // Step 9: RemappingEngine + TransformCache + SingularityTransformer
            val engine = RemappingEngine(obfTable, srgTable, intermediaryTable, tree)
            val transformCache = TransformCache(cacheRoot)
            val transformer = SingularityTransformer(
                jarRegistry = jarRegistry,
                remappingEngine = engine,
                transformCache = transformCache,
                agentVersion = agentVersion,
                moduleVersion = moduleVersion
            )

            // Step 10: MixinBytecodeSource
            val mixinBytecodeSource = EngineMixinBytecodeSource(jarRegistry, engine)

            // Step 11: SingularityClassLoader z transformFunction
            val parentClassLoader = AgentMain::class.java.classLoader
            val singularityClassLoader = SingularityClassLoader(
                parent = parentClassLoader,
                jarRegistry = jarRegistry,
                remappingEngine = engine,
                transformFunction = transformer::transform
            )

            // Step 12: ServiceLoader discover SingularityMixinService + inject
            // Iterator + try/catch per-next zeby omijac problematyczne services
            // (Mixin JAR bundled ma MixinServiceLaunchWrapper ktory fail bez LaunchWrapper)
            val mixinServices = ServiceLoader.load(IMixinService::class.java, parentClassLoader)
            var injected = false
            val iterator = mixinServices.iterator()
            while (iterator.hasNext()) {
                try {
                    val service = iterator.next()
                    if (service is SingularityMixinService) {
                        service.bytecodeSource = mixinBytecodeSource
                        injected = true
                        logger.info("SingularityMixinService discovered and bytecodeSource injected")
                        break
                    }
                } catch (_: Throwable) {
                    // Skip problematic service
                }
            }
            if (!injected) {
                logger.warn("SingularityMixinService NOT discovered via ServiceLoader — Mixin integration DEAD")
            }

            // Step 13: Set context classloader dla MC main thread
            Thread.currentThread().contextClassLoader = singularityClassLoader

            // Step 14: Cache cleanup + write version
            // Compute dirKeys z aktywnych jarHashes, NIE z transformer.dirKeyCache —
            // podczas bootstrap'u zero klas jeszcze nie bylo transformed, wiec
            // transformer.getAllDirKeys() zwraca pusty set. Cleanup byl no-op.
            // Fix (edge-case-hunter final review #1): computujemy dirKey dla KAZDEGO
            // zarejestrowanego JAR'a PRZED startem transform'ow → cleanup zna aktualne
            // entries i usuwa te ze stalych wersji (agentVer/moduleVer/jarHash roznice).
            val activeDirKeys = jarRegistry.getAllJarHashes()
                .map { jarHash -> CacheKey.dirKey(agentVersion, moduleVersion, jarHash) }
                .toSet()
            if (activeDirKeys.isNotEmpty()) {
                transformCache.cleanup(activeDirKeys)
            }
            cacheVersionManager.writeCurrent(agentVersion, moduleVersion)

            logger.info(
                "Agent bootstrap complete (Sub 2b): tree={} classes, jarRegistry={} classes, agentVer={}, moduleVer={}",
                tree.size, jarRegistry.size, agentVersion, moduleVersion
            )

            // Sub 2c: Mod loading pipeline
            // Step 15: SingularityModRegistry + stub ModInitializer (real wire w Sub 2d/2e)
            val registry = SingularityModRegistry()
            modRegistry = registry
            val initializer = ModInitializer(
                onPhase1a = { mod ->
                    logger.debug("Phase 1a (stub): Forge registry events for '{}' — real wire Sub 2d", mod.modId)
                },
                onPhase1b = { mod ->
                    logger.debug("Phase 1b (stub): Fabric onInitialize for '{}' — real wire Sub 2d", mod.modId)
                },
                onPhase2 = { mod ->
                    logger.debug("Phase 2 (stub): FMLCommonSetup for '{}' — real wire Sub 2d", mod.modId)
                },
                onPhase3 = { mod ->
                    logger.debug("Phase 3 (stub): InterMod events for '{}' — real wire Sub 2d", mod.modId)
                },
                onPhase4 = {
                    logger.info("Phase 4 (stub): Load Complete — real wire Sub 2d")
                }
            )

            // Steps 16-20: discovery → parse → deps → dedupe → classloader wire → mixin wire → registry → phased init
            val modsDir = instanceDir.resolve("mods")
            val modLoadResult = ModBootstrap.loadMods(
                modsDir = modsDir,
                addModJar = singularityClassLoader::addModJar,
                registry = registry,
                initializer = initializer
            )
            logger.info(
                "Sub 2c mod loading complete: {} mods registered, {} mixin configs loaded",
                modLoadResult.registeredCount, modLoadResult.loadedMixinConfigs.size
            )
        } finally {
            // BLOCKER fix (edge-case-hunter): cleanup temp mapping dir gwarantowany
            // nawet przy throw w srodku bootstrap(). deleteRecursively() na Windows
            // moze zwrócić false przy open handle — logujemy warning dla visibility.
            val cleaned = tempMappingsDir.toFile().deleteRecursively()
            if (cleaned) {
                logger.debug("Cleaned up temp mapping dir: {}", tempMappingsDir)
            } else {
                logger.warn("Failed to fully cleanup temp mapping dir: {} (partial delete, possibly Windows lock)", tempMappingsDir)
            }
        }
    }

    /**
     * Ekstrahuje pojedynczy plik z module JAR do temporary dir.
     * Uzywa `LoadedModule.readEntry()` (public API), NIE `module.jarFile` (private field).
     *
     * UWAGA: temp dir jest lifecycle'owany przez calling bootstrap() z try/finally +
     * deleteRecursively — nie ma auto-delete per file.
     */
    private fun extractModuleEntry(module: LoadedModule, entryName: String, tempDir: Path): Path {
        if (!module.hasEntry(entryName)) {
            throw IllegalStateException("Module JAR missing required entry: $entryName")
        }
        val bytes = module.readEntry(entryName)
        val targetPath = tempDir.resolve(entryName.substringAfterLast('/'))
        Files.createDirectories(targetPath.parent)
        Files.write(targetPath, bytes)
        return targetPath
    }

    /**
     * Znajduje MC JAR zgodnie z AD4 shared cache pattern.
     *
     * Priority:
     * 1. instance.json mcJar field (absolute path override) — TODO Sub 6
     * 2. ~/.singularitymc/versions/<mcVersion>/<mcVersion>.jar
     * 3. null (agent dziala, nie remapuje MC classes)
     */
    private fun findMcJar(instanceDir: Path, mcVersion: String): Path? {
        // TODO Sub 6: parse instance.json mcJar field (override)
        val sharedCache = Paths.get(System.getProperty("user.home"))
            .resolve(".singularitymc/versions/$mcVersion/$mcVersion.jar")
        return if (Files.exists(sharedCache)) sharedCache else null
    }
}
