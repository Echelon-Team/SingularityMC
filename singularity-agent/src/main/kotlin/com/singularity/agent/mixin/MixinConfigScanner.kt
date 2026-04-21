// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.mixin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Skanuje JARy modow po deklaracjach mixin configs.
 *
 * - Fabric: fabric.mod.json → pole "mixins" (array stringow LUB obiektow z polem "config")
 * - Forge/NeoForge: META-INF/mods.toml → sekcje [[mixins]] → pole config
 *
 * Multi-loader JARy (SAME mod dla Fabric + Forge) maja OBA pliki — zwracamy union.
 *
 * Referencja: design spec sekcja 5A.6 (punkt 5-6).
 */
object MixinConfigScanner {

    private val logger = LoggerFactory.getLogger(MixinConfigScanner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Skanuje JAR moda i zwraca liste nazw mixin config files (fabric + forge union).
     */
    fun scanJar(jarPath: Path): List<String> {
        val configs = mutableListOf<String>()
        JarFile(jarPath.toFile()).use { jar ->
            jar.getJarEntry("fabric.mod.json")?.let { entry ->
                val content = jar.getInputStream(entry).bufferedReader().use { it.readText() }
                configs.addAll(extractFromFabricModJson(content))
            }
            jar.getJarEntry("META-INF/mods.toml")?.let { entry ->
                val content = jar.getInputStream(entry).bufferedReader().use { it.readText() }
                configs.addAll(extractFromModsToml(content))
            }
        }
        if (configs.isNotEmpty()) {
            logger.debug("Found {} mixin configs in {}: {}", configs.size, jarPath.fileName, configs)
        }
        return configs
    }

    /**
     * Parsuje fabric.mod.json i wyciaga pole "mixins".
     * Pole moze byc array stringow (proste) LUB array obiektow z polem "config" (advanced).
     */
    fun extractFromFabricModJson(content: String): List<String> {
        return try {
            val root = json.parseToJsonElement(content).jsonObject
            val mixinsArray = root["mixins"]?.jsonArray ?: return emptyList()
            mixinsArray.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull
                    is JsonObject -> element["config"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse fabric.mod.json: {}", e.message)
            emptyList()
        }
    }

    /**
     * Parsuje mods.toml i wyciaga pola config z sekcji [[mixins]].
     * Prosty parser — szuka linii config = "nazwa.json" po naglowku [[mixins]].
     */
    fun extractFromModsToml(content: String): List<String> {
        val configs = mutableListOf<String>()
        var inMixinsSection = false

        for (line in content.lines()) {
            val trimmed = line.trim()
            when {
                trimmed == "[[mixins]]" -> inMixinsSection = true
                trimmed.startsWith("[[") && trimmed != "[[mixins]]" -> inMixinsSection = false
                trimmed.startsWith("[") && !trimmed.startsWith("[[") -> inMixinsSection = false
                inMixinsSection && trimmed.startsWith("config") -> {
                    val value = trimmed.substringAfter("=").trim().removeSurrounding("\"")
                    if (value.endsWith(".json")) {
                        configs.add(value)
                    }
                }
            }
        }
        return configs
    }
}
