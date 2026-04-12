package com.singularity.agent.loadingscreen

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Ładuje tipsy dla loading screen z JSON bundlowanego w agent JAR.
 *
 * Format:
 * {
 *   "tips": [
 *     {"pl": "Czy wiesz...", "en": "Did you know..."}
 *   ]
 * }
 */
object TipsLoader {

    private val logger = LoggerFactory.getLogger(TipsLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Tip(val pl: String, val en: String)

    @Serializable
    data class TipsFile(val tips: List<Tip>)

    /**
     * Ładuje tipsy z resource path. Szuka w classpath agenta.
     * Fallback: jeśli nie znalezione w agent JAR, próbuje SingularityClassLoader.
     */
    fun loadTips(
        resourcePath: String = "/loading-tips.json",
        fallbackClassLoader: ClassLoader? = null
    ): List<Tip> {
        // Najpierw agent classloader
        var stream = TipsLoader::class.java.getResourceAsStream(resourcePath)
        // Fallback na przekazany classloader (np. SingularityClassLoader z compat module)
        if (stream == null && fallbackClassLoader != null) {
            stream = fallbackClassLoader.getResourceAsStream(resourcePath.removePrefix("/"))
        }
        if (stream == null) {
            logger.warn("Tips file not found: {}", resourcePath)
            return emptyList()
        }
        return try {
            val content = stream.bufferedReader().use { it.readText() }
            val tipsFile = json.decodeFromString<TipsFile>(content)
            logger.info("Loaded {} tips from {}", tipsFile.tips.size, resourcePath)
            tipsFile.tips
        } catch (e: Exception) {
            logger.error("Failed to parse tips: {}", e.message)
            emptyList()
        }
    }

    fun randomTip(tips: List<Tip>, language: String): String? {
        if (tips.isEmpty()) return null
        val tip = tips.random()
        return if (language == "pl") tip.pl else tip.en
    }
}
