// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.module

import com.singularity.common.contracts.ModuleDescriptorData
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.jar.JarFile

/**
 * Parsowanie singularity-module.json z wnętrza JAR modułu compat.
 *
 * Referencja: design spec sekcja 3, implementation design sekcja 4.1.
 */
object ModuleDescriptor {

    private val logger = LoggerFactory.getLogger(ModuleDescriptor::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private const val DESCRIPTOR_PATH = "singularity-module.json"

    /**
     * Parsuje deskryptor z JAR modułu.
     * @throws IllegalArgumentException jeśli JAR nie zawiera singularity-module.json
     * @throws kotlinx.serialization.SerializationException jeśli JSON jest niepoprawny
     */
    fun parseFromJar(jarFile: JarFile): ModuleDescriptorData {
        val entry = jarFile.getJarEntry(DESCRIPTOR_PATH)
            ?: throw IllegalArgumentException(
                "JAR '${jarFile.name}' nie zawiera $DESCRIPTOR_PATH — to nie jest moduł SingularityMC"
            )

        // use{} na BufferedReader zamyka underlying InputStream nawet przy throw
        // z readText() lub decodeFromString. Bez tego InputStream leak (edge-case-hunter flag).
        val jsonContent = jarFile.getInputStream(entry).bufferedReader().use { it.readText() }
        logger.debug("Parsing module descriptor from {}: {} bytes", jarFile.name, jsonContent.length)

        return json.decodeFromString<ModuleDescriptorData>(jsonContent).also {
            logger.info("Module loaded: {} v{} for MC {}", it.moduleId, it.moduleVersion, it.minecraftVersion)
        }
    }

    /**
     * Parsuje deskryptor z InputStream (do testów i alternatywnego ładowania).
     */
    fun parseFromStream(input: InputStream): ModuleDescriptorData {
        val jsonContent = input.bufferedReader().readText()
        return json.decodeFromString<ModuleDescriptorData>(jsonContent)
    }
}
