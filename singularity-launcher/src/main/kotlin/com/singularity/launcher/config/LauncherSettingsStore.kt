package com.singularity.launcher.config

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Atomic load/save dla LauncherSettings z `<userHome>/.singularitymc/launcher.json`.
 *
 * **Graceful fallback:** missing file → default settings. Corrupted JSON → log warning,
 * return default.
 */
class LauncherSettingsStore(private val settingsFile: Path) {

    companion object {
        fun default(): LauncherSettingsStore {
            val home = System.getProperty("user.home")
            val file = Path.of(home, ".singularitymc", "launcher.json")
            return LauncherSettingsStore(file)
        }
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): LauncherSettings {
        if (!Files.exists(settingsFile)) {
            // First launch — auto-detect system language
            val systemLang = java.util.Locale.getDefault().language // "pl", "en", "de", etc.
            val supportedLang = if (systemLang == "pl") "pl" else "en"
            return LauncherSettings(language = supportedLang)
        }
        return try {
            val content = Files.readString(settingsFile)
            json.decodeFromString(LauncherSettings.serializer(), content)
        } catch (e: Exception) {
            System.err.println("Warning: corrupted launcher.json — using defaults: ${e.message}")
            LauncherSettings()
        }
    }

    fun save(settings: LauncherSettings) {
        Files.createDirectories(settingsFile.parent)
        val tmp = settingsFile.resolveSibling("${settingsFile.fileName}.tmp")
        val content = json.encodeToString(LauncherSettings.serializer(), settings)
        Files.writeString(tmp, content)
        Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Update helper — read current, apply transform, write back.
     */
    fun update(transform: (LauncherSettings) -> LauncherSettings): LauncherSettings {
        val current = load()
        val updated = transform(current)
        save(updated)
        return updated
    }
}
