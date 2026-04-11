package com.singularity.launcher.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Runtime settings instancji — Pre-gen, Threading, ChunkGen params.
 *
 * Persisted w `<instance>/settings.json` (oddzielnie od `instance.json` który ma basic
 * config jak nazwa, wersja MC, type, RAM, threads).
 *
 * **Sub 4 scope:** GUI editing + persistence. Threading engine (Sub 3) i chunk gen backend
 * (Sub 5) konsumują te settings przez JSON parsing.
 */
@Serializable
data class InstanceRuntimeSettings(
    val regionSize: Int = 8,  // 4, 6, 8, 12, 16 allowed
    val gpuAcceleration: Boolean = false,
    val unloadDelaySec: Int = 300,  // 60..3600
    val memoryThresholdPercent: Int = 80,  // 50..95
    val manualThreadConfig: ManualThreadConfig? = null,  // null = auto, non-null = manual
    val preGenRadius: Int = 64,  // 16..256 chunks
    val preGenPreset: PreGenPreset = PreGenPreset.MEDIUM
)

/**
 * Pre-gen hardware preset — jeden klik ustawia radius dla danego poziomu sprzętu.
 */
@Serializable
enum class PreGenPreset(
    val displayKey: String,
    val descriptionKey: String,
    val defaultRadius: Int
) {
    POTATO("pre_gen.preset.potato", "pre_gen.preset.potato.desc", 32),
    MEDIUM("pre_gen.preset.medium", "pre_gen.preset.medium.desc", 64),
    HIGH("pre_gen.preset.high", "pre_gen.preset.high.desc", 128),
    FIREPLACE("pre_gen.preset.fireplace", "pre_gen.preset.fireplace.desc", 256)
}

/**
 * Manual thread pool configuration — tylko gdy user wybrał manual mode w SettingsModal
 * Threading tab. Null w InstanceRuntimeSettings = auto (engine decyduje).
 */
@Serializable
data class ManualThreadConfig(
    val overworldTick: Int = 4,   // 1-8
    val netherTick: Int = 2,       // 1-4
    val endTick: Int = 2,          // 1-4
    val chunkLoading: Int = 3,     // 1-6
    val renderPrep: Int = 2,       // 1-4
    val io: Int = 2                // 1-4
)

/**
 * Atomic load/save dla InstanceRuntimeSettings — per-instance `<instanceDir>/settings.json`.
 *
 * **Graceful fallback:** missing file → default settings. Corrupted JSON → log + default.
 */
object InstanceRuntimeSettingsStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(instanceDir: Path): InstanceRuntimeSettings {
        val file = instanceDir.resolve("settings.json")
        if (!Files.exists(file)) return InstanceRuntimeSettings()
        return try {
            val content = Files.readString(file)
            json.decodeFromString(InstanceRuntimeSettings.serializer(), content)
        } catch (e: Exception) {
            System.err.println("Warning: corrupted settings.json in ${instanceDir.fileName} — using defaults: ${e.message}")
            InstanceRuntimeSettings()
        }
    }

    fun save(instanceDir: Path, settings: InstanceRuntimeSettings) {
        Files.createDirectories(instanceDir)
        val file = instanceDir.resolve("settings.json")
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(InstanceRuntimeSettings.serializer(), settings))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
