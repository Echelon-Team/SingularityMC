package com.singularity.launcher.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Internationalization — fallback chain: currentLanguage → en → key literal.
 *
 * **Design:**
 * - Immutable — zmiana języka tworzy nowy `I18n` instance przez `switchLanguage()`
 * - Strings loaded from `resources/i18n/strings_{lang}.json` flat key-value format
 * - `get(key)` / `operator[]` zwraca wartość dla current language, fallback en, fallback key
 * - `en` jest SOURCE language — musi mieć wszystkie klucze. Inne języki mogą mieć mniej
 *   (brakujące klucze fallback na en). Completeness weryfikowany osobnym testem (Task 32).
 *
 * **Usage w Compose (przez LocalI18n):**
 * ```
 * val i18n = LocalI18n.current
 * Text(text = i18n["nav.home"])
 * ```
 */
class I18n(
    private val strings: Map<String, Map<String, String>>,
    val currentLanguage: String
) {
    val availableLanguages: Set<String> get() = strings.keys

    operator fun get(key: String): String {
        // Priority 1: current language
        strings[currentLanguage]?.get(key)?.let { return it }
        // Priority 2: fallback to English (source language)
        if (currentLanguage != "en") {
            strings["en"]?.get(key)?.let { return it }
        }
        // Priority 3: return key literal so developer sees missing i18n
        return key
    }

    fun switchLanguage(newLanguage: String): I18n = I18n(strings, newLanguage)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parsuje flat JSON key-value do Map<String, String>.
         * Format:
         * ```
         * {
         *   "nav.home": "Home",
         *   "nav.instances": "Instances"
         * }
         * ```
         */
        fun parseJson(jsonString: String): Map<String, String> {
            val obj = json.parseToJsonElement(jsonString) as JsonObject
            return obj.mapValues { (_, value) -> value.jsonPrimitive.content }
        }

        /**
         * Ładuje I18n z resources `/i18n/strings_{lang}.json` dla wszystkich języków.
         * Zwraca immutable I18n instance z current language.
         *
         * @param defaultLanguage Język startowy (domyślnie "pl")
         * @param languages Lista języków do załadowania (domyślnie ["pl", "en"])
         */
        fun loadFromResources(
            defaultLanguage: String = "pl",
            languages: List<String> = listOf("pl", "en")
        ): I18n {
            val strings = languages.associateWith { lang ->
                val resourcePath = "/i18n/strings_$lang.json"
                val stream = I18n::class.java.getResourceAsStream(resourcePath)
                    ?: error("i18n resource not found: $resourcePath")
                val content = stream.bufferedReader().use { it.readText() }
                parseJson(content)
            }
            return I18n(strings, defaultLanguage)
        }
    }
}
