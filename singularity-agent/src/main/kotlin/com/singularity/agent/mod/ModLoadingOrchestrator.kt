// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Orkiestruje cały pipeline ładowania modów:
 * discovery → parsowanie → duplikaty → dependency resolution → (inicjalizacja osobno).
 *
 * Inicjalizacja (ModInitializer) wywoływana OSOBNO po tym pipeline —
 * wymaga najpierw załadowania classów i mixin bootstrap.
 *
 * Referencja: design spec sekcja 5A.1-5A.5.
 */
object ModLoadingOrchestrator {

    private val logger = LoggerFactory.getLogger(ModLoadingOrchestrator::class.java)

    data class DiscoveryResult(
        /** Wszystkie poprawnie sparsowane mody */
        val mods: List<ModInfo>,
        /** Mody posortowane topologicznie po zależnościach */
        val sortedMods: List<ModInfo>,
        /** Błędy parsowania i inne */
        val errors: List<String>,
        /** Wykryte duplikaty */
        val duplicates: List<DuplicateDetector.DuplicateAction>,
        /** Błędy dependency resolution */
        val resolutionErrors: List<DependencyResolver.DependencyError>,
        /** JARy bez rozpoznanych metadanych (typ UNKNOWN) */
        val unknownJars: List<Path>,
        /** JARy rozpoznane jako biblioteki */
        val libraryJars: List<Path>
    )

    /**
     * Skanuje folder mods/, parsuje metadane, wykrywa duplikaty, rozwiązuje zależności.
     *
     * @param modsDir ścieżka do folderu mods/ instancji
     * @return DiscoveryResult z modami, błędami i ostrzeżeniami
     */
    fun discoverAndParse(modsDir: Path): DiscoveryResult {
        logger.info("Starting mod discovery in: {}", modsDir)
        val errors = mutableListOf<String>()

        // 1. Discovery — skanuj JARy
        val discovered = ModDiscovery.scanDirectory(modsDir)
        logger.info("Discovered {} JAR files", discovered.size)

        val unknownJars = discovered.filter { it.loaderType == LoaderType.UNKNOWN }.map { it.jarPath }
        val libraryJars = discovered.filter { it.loaderType == LoaderType.LIBRARY }.map { it.jarPath }
        val modJars = discovered.filter { it.loaderType.isMod }

        // 2. Parsowanie metadanych
        val mods = mutableListOf<ModInfo>()
        for (jar in modJars) {
            try {
                val modInfo = parseDiscoveredMod(jar)
                if (modInfo != null) mods.add(modInfo)
            } catch (e: Exception) {
                val msg = "Failed to parse ${jar.jarPath.fileName}: ${e.message}"
                logger.error(msg)
                errors.add(msg)
            }
        }
        logger.info("Parsed {} mods successfully", mods.size)

        // 3. Duplikaty — detect + filter przed resolve
        val duplicates = DuplicateDetector.detect(mods)
        if (duplicates.isNotEmpty()) {
            logger.warn("Found {} duplicate mod ID(s)", duplicates.size)
        }

        // Krytyczna fix (edge-case-hunter L5): DependencyResolver.associateBy { modId } cicho
        // gubi duplikaty — jeśli user ma dwie wersje tego samego moda, resolver daje
        // fałszywy "cyclic dependency" error. Fix: usuń z listy przed resolve.
        val modsToRemove = duplicates
            .filterIsInstance<DuplicateDetector.DuplicateAction.KeepNewer>()
            .map { it.remove.jarPath }
            .toSet()
        val modsForResolution = mods.filter { it.jarPath !in modsToRemove }
        if (modsToRemove.isNotEmpty()) {
            logger.info(
                "Filtered {} older duplicate(s) from resolution input ({} mods remain)",
                modsToRemove.size, modsForResolution.size
            )
        }

        // 4. Dependency resolution
        val resolution = DependencyResolver.resolve(modsForResolution)
        if (resolution.errors.isNotEmpty()) {
            logger.error("Dependency resolution errors: {}", resolution.errors.size)
        }

        return DiscoveryResult(
            mods = mods,
            sortedMods = resolution.sortedMods,
            errors = errors,
            duplicates = duplicates,
            resolutionErrors = resolution.errors,
            unknownJars = unknownJars,
            libraryJars = libraryJars
        )
    }

    private fun parseDiscoveredMod(discovered: DiscoveredMod): ModInfo? {
        // Preserve discovered.loaderType across parser boundaries. Parsery hardcodują
        // loaderType (Fabric → FABRIC, Forge → FORGE), ale źródłowy typ z discovery
        // może być MULTI (Fabric+Forge metadata) lub NEOFORGE (Forge-format parser).
        // Bez copy() tracimy tag → VisibilityRules filterForForge nie widzi MULTI mod,
        // ModBootstrap phase split pomija MULTI dla Forge phases.
        return when (discovered.loaderType) {
            LoaderType.FABRIC ->
                FabricMetadataParser.parse(discovered.rawFabricJson!!, discovered.jarPath)

            LoaderType.FORGE ->
                ForgeMetadataParser.parse(discovered.rawModsToml!!, discovered.jarPath)

            LoaderType.NEOFORGE ->
                ForgeMetadataParser.parse(discovered.rawModsToml!!, discovered.jarPath)
                    .copy(loaderType = LoaderType.NEOFORGE)

            LoaderType.MULTI -> {
                // Parsuj Fabric (bogatsze metadane — entrypoints, mixins), ale zachowaj MULTI
                // tag żeby mod był widoczny dla Forge shims + dostawał Forge phases (1a/2/3).
                val fabricResult = FabricMetadataParser.parse(discovered.rawFabricJson!!, discovered.jarPath)

                // Flag #21 MULTI modId mismatch validation: jeśli Fabric i Forge metadata
                // w tym samym JAR mają RÓŻNE modId, loguj warning (half-broken multi-loader mod)
                try {
                    val forgeResult = ForgeMetadataParser.parse(discovered.rawModsToml!!, discovered.jarPath)
                    if (fabricResult.modId != forgeResult.modId) {
                        logger.warn(
                            "MULTI-loader mod {} has mismatched modId: fabric='{}', forge='{}'. Using fabric modId.",
                            discovered.jarPath.fileName, fabricResult.modId, forgeResult.modId
                        )
                    }
                } catch (e: Exception) {
                    logger.debug("Could not cross-validate MULTI mod Forge metadata: {}", e.message)
                }

                fabricResult.copy(loaderType = LoaderType.MULTI)
            }

            LoaderType.NONE, LoaderType.LIBRARY, LoaderType.UNKNOWN -> null // nie parsujemy
        }
    }
}
