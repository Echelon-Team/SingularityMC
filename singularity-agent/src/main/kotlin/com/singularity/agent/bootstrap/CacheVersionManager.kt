// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.bootstrap

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Zarzadza wersjonowaniem cache na poziomie bulk invalidation.
 *
 * AD8: Gdy agentVer lub moduleVer sie zmieniaja, cache jest stale — wyczysc cały cache root.
 * Na dysku: `<cacheRoot>/version.properties` z `agentVersion=...` + `moduleVersion=...`.
 *
 * Alternatywa (per-entry invalidation via CacheKey dirKey) dziala rownolegle — stary dirKey
 * zostanie usuniety przez TransformCache.cleanup(activeDirKeys). CacheVersionManager to
 * bulk pre-check ZANIM zaczniemy liczyc dirKey'e.
 */
class CacheVersionManager(private val cacheRoot: Path) {

    private val logger = LoggerFactory.getLogger(CacheVersionManager::class.java)
    private val versionFile = cacheRoot.resolve("version.properties")

    init {
        Files.createDirectories(cacheRoot)
    }

    /**
     * Sprawdza czy zapisane wersje roznia sie od aktualnych.
     * @return true jesli cache jest stale (potrzebny clearAll)
     */
    fun isStale(currentAgentVersion: String, currentModuleVersion: String): Boolean {
        if (!Files.exists(versionFile)) return true
        val props = Properties().apply {
            Files.newInputStream(versionFile).use { load(it) }
        }
        val storedAgent = props.getProperty("agentVersion") ?: return true
        val storedModule = props.getProperty("moduleVersion") ?: return true
        return storedAgent != currentAgentVersion || storedModule != currentModuleVersion
    }

    /**
     * Zapisuje aktualne wersje do version.properties.
     */
    fun writeCurrent(agentVersion: String, moduleVersion: String) {
        val props = Properties().apply {
            setProperty("agentVersion", agentVersion)
            setProperty("moduleVersion", moduleVersion)
        }
        Files.newOutputStream(versionFile).use { output ->
            props.store(output, "SingularityMC cache version metadata")
        }
    }

    /**
     * Usuwa wszystkie zawartosci cache root (zachowuje samo cacheRoot jako pusty dir).
     */
    fun clearAll() {
        if (!Files.exists(cacheRoot)) return
        Files.newDirectoryStream(cacheRoot).use { stream ->
            for (entry in stream) {
                val success = entry.toFile().deleteRecursively()
                if (!success) {
                    logger.warn("Failed to delete {} during cache clearAll", entry)
                }
            }
        }
        logger.info("Cleared cache root: {}", cacheRoot)
    }
}
