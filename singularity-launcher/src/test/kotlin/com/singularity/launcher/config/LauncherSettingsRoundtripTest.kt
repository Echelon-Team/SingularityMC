package com.singularity.launcher.config

import com.singularity.launcher.ui.theme.ThemeMode
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LauncherSettingsRoundtripTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default settings has sensible values`() {
        val settings = LauncherSettings()
        assertEquals(ThemeMode.END, settings.theme)
        assertEquals("pl", settings.language)
        assertEquals(UpdateChannel.STABLE, settings.updateChannel)
        assertTrue(settings.autoCheckUpdates)
        assertFalse(settings.debugLogsEnabled)
    }

    @Test
    fun `serialization roundtrip preserves all fields`() {
        val original = LauncherSettings(
            theme = ThemeMode.AETHER,
            language = "en",
            lastActiveAccountId = "acc-123",
            lastActiveInstanceId = "inst-456",
            windowX = 100,
            windowY = 200,
            windowWidth = 1280,
            windowHeight = 720,
            updateChannel = UpdateChannel.BETA,
            autoCheckUpdates = false,
            jvmExtraArgs = "-XX:+UseZGC",
            debugLogsEnabled = true,
            discordRpcEnabled = true
        )
        val serialized = json.encodeToString(LauncherSettings.serializer(), original)
        val deserialized = json.decodeFromString(LauncherSettings.serializer(), serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `deserialize missing field uses default`() {
        val minimalJson = """{"theme": "AETHER"}"""
        val settings = json.decodeFromString(LauncherSettings.serializer(), minimalJson)
        assertEquals(ThemeMode.AETHER, settings.theme)
        assertEquals("pl", settings.language)  // default
    }

    @Test
    fun `Store load-save roundtrip persists to file`() {
        val file = tempDir.resolve("launcher.json")
        val store = LauncherSettingsStore(file)

        // Initial load — default (file doesn't exist)
        val initial = store.load()
        assertEquals(LauncherSettings(), initial)

        // Save + reload
        val modified = LauncherSettings(theme = ThemeMode.AETHER, language = "en")
        store.save(modified)
        assertTrue(java.nio.file.Files.exists(file))

        val loaded = store.load()
        assertEquals(modified, loaded)
    }

    @Test
    fun `Store corrupted JSON returns default`() {
        val file = tempDir.resolve("launcher.json")
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(file, "{not valid json")

        val store = LauncherSettingsStore(file)
        val loaded = store.load()
        assertEquals(LauncherSettings(), loaded, "Corrupted JSON falls back to default")
    }
}
