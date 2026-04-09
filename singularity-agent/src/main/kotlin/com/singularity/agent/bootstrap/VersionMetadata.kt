package com.singularity.agent.bootstrap

import org.slf4j.LoggerFactory
import java.util.jar.Manifest

/**
 * Version metadata loader — czyta Implementation-Version z MANIFEST.MF.
 *
 * Uzywane przez CacheKey (AD2) do cache key computation: updated agent → nowy agentVer
 * → nowy dirKey → cache miss → nowy pipeline run.
 */
object VersionMetadata {

    private val logger = LoggerFactory.getLogger(VersionMetadata::class.java)

    /**
     * Lazy-loaded version agenta z MANIFEST.MF (shadow jar zawiera properly set
     * Implementation-Version). Fallback: "dev" jesli MANIFEST nie ma pola (np. IDE debug run).
     */
    val agentVersion: String by lazy {
        try {
            val resource = VersionMetadata::class.java.classLoader
                .getResource("META-INF/MANIFEST.MF")
            if (resource != null) {
                resource.openStream().use { input ->
                    val manifest = Manifest(input)
                    manifest.mainAttributes.getValue("Implementation-Version") ?: "dev"
                }
            } else "dev"
        } catch (e: Exception) {
            logger.warn("Failed to read agent version from MANIFEST.MF: {}", e.message)
            "dev"
        }
    }
}
