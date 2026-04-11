package com.singularity.launcher.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Enforce spójność i pełność i18n — catch'uje bugi które prawie poszły do produkcji:
 *
 * 1. **Key parity**: strings_pl.json i strings_en.json mają **dokładnie te same klucze**.
 *    Dryft PL↔EN = fallback-to-english ukrywa brakujące tłumaczenia i jest niewidoczne
 *    dopóki user nie zobaczy angielskiego stringa w polskim UI.
 *
 * 2. **Code coverage**: każdy `i18n["klucz"]` wywołanie w main kodzie MUSI mieć
 *    odpowiedni wpis w obu JSON'ach. Raw klucz pokazuje się w UI gdy go brakuje
 *    (`I18n.get` fallback na literal).
 *
 * 3. **Enum values**: Screen.displayKey, DiagnosticsTab.i18nKey, ServerTab.i18nKey,
 *    InstanceTab.i18nKey, InstanceSettingsTab.i18nKey, PreGenPreset.displayKey — wszystkie
 *    te enum'y trzymają i18n keys które też muszą być w JSON'ach.
 *
 * **Metoda**: Scan całego `src/main/kotlin/**/*.kt` regexem `i18n["..."]` + enum value
 * patterns. To nie jest perfect (np. przeoczy key w string templacie `i18n[someVar]`) ale
 * catch'uje 95% przypadków bez AST parsing'u.
 */
class I18nCompletenessTest {

    companion object {
        private const val PL_PATH = "src/main/resources/i18n/strings_pl.json"
        private const val EN_PATH = "src/main/resources/i18n/strings_en.json"
        private const val KOTLIN_SRC_DIR = "src/main/kotlin"

        // i18n["key"] lub i18n.get("key")
        private val I18N_CALL_PATTERN = Regex("""i18n(?:\[|\.get\()\s*"([^"]+)"\s*[\]\)]""")

        // Enum values which hold i18n keys — format: ENUM_VALUE("i18n.key.name")
        // Line-level: uppercase ENUM_CASE name followed by ("string") — NIE łapie
        // inline string literals typu Path("settings.json") które nie są enum values.
        private val ENUM_I18N_KEY_PATTERN = Regex(
            """^\s*[A-Z][A-Z0-9_]*\s*\(\s*"([a-z_]+(?:\.[a-z_]+)+)"""",
            RegexOption.MULTILINE
        )

        // Pliki enum które zawierają i18n keys (wiem bo sam je pisałem)
        private val ENUM_FILES = listOf(
            "src/main/kotlin/com/singularity/launcher/ui/navigation/Screen.kt",
            "src/main/kotlin/com/singularity/launcher/ui/navigation/SettingsSection.kt",
            "src/main/kotlin/com/singularity/launcher/ui/screens/diagnostics/DiagnosticsTab.kt",
            "src/main/kotlin/com/singularity/launcher/ui/screens/servers/panel/ServerTab.kt",
            "src/main/kotlin/com/singularity/launcher/ui/screens/instances/InstanceTab.kt",
            "src/main/kotlin/com/singularity/launcher/ui/screens/instances/settings/InstanceSettingsModalState.kt",
            "src/main/kotlin/com/singularity/launcher/config/InstanceRuntimeSettings.kt"
        )
    }

    private fun loadJsonKeys(path: String): Set<String> {
        val file = File(path)
        assertTrue(file.exists(), "i18n resource not found: $path")
        val content = file.readText()
        // Prosty JSON key extraction bez kotlinx.serialization (flat JSON only)
        val keyPattern = Regex("""^\s*"([^"]+)"\s*:""", RegexOption.MULTILINE)
        return keyPattern.findAll(content).map { it.groupValues[1] }.toSet()
    }

    private fun scanUsedKeys(): Set<String> {
        val used = mutableSetOf<String>()
        val srcDir = File(KOTLIN_SRC_DIR)
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { ktFile ->
            val content = ktFile.readText()
            I18N_CALL_PATTERN.findAll(content).forEach { m ->
                used.add(m.groupValues[1])
            }
        }
        // Enum value keys
        ENUM_FILES.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                val content = file.readText()
                ENUM_I18N_KEY_PATTERN.findAll(content).forEach { m ->
                    used.add(m.groupValues[1])
                }
            }
        }
        return used
    }

    @Test
    fun `strings_pl and strings_en have exactly the same keys`() {
        val plKeys = loadJsonKeys(PL_PATH)
        val enKeys = loadJsonKeys(EN_PATH)

        val onlyInPl = plKeys - enKeys
        val onlyInEn = enKeys - plKeys

        assertTrue(
            onlyInPl.isEmpty() && onlyInEn.isEmpty(),
            buildString {
                appendLine("strings_pl and strings_en must have identical key sets")
                if (onlyInPl.isNotEmpty()) {
                    appendLine("Keys in PL but not EN (${onlyInPl.size}):")
                    onlyInPl.sorted().forEach { appendLine("  - $it") }
                }
                if (onlyInEn.isNotEmpty()) {
                    appendLine("Keys in EN but not PL (${onlyInEn.size}):")
                    onlyInEn.sorted().forEach { appendLine("  - $it") }
                }
            }
        )
    }

    @Test
    fun `all i18n keys used in code exist in strings_pl`() {
        val plKeys = loadJsonKeys(PL_PATH)
        val usedKeys = scanUsedKeys()

        val missing = (usedKeys - plKeys).sorted()

        assertTrue(
            missing.isEmpty(),
            buildString {
                appendLine("Missing i18n keys in strings_pl.json (${missing.size}):")
                missing.forEach { appendLine("  - $it") }
            }
        )
    }

    @Test
    fun `all i18n keys used in code exist in strings_en`() {
        val enKeys = loadJsonKeys(EN_PATH)
        val usedKeys = scanUsedKeys()

        val missing = (usedKeys - enKeys).sorted()

        assertTrue(
            missing.isEmpty(),
            buildString {
                appendLine("Missing i18n keys in strings_en.json (${missing.size}):")
                missing.forEach { appendLine("  - $it") }
            }
        )
    }

    @Test
    fun `i18n scan finds non-zero keys (sanity check)`() {
        val usedKeys = scanUsedKeys()
        // Jeśli scan zwraca 0 to regex jest zły, test false positive nie ma sensu
        assertTrue(
            usedKeys.size > 100,
            "Expected 100+ i18n keys used in main kodzie, found ${usedKeys.size}. Regex pewnie zepsuty."
        )
    }
}
