package com.singularity.launcher.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import java.nio.file.Path

class LauncherSettingsVersionTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default LauncherSettings has configVersion CURRENT`() {
        val settings = LauncherSettings()
        assertEquals(ConfigVersion.CURRENT, settings.configVersion)
        assertEquals(LauncherSettings.CURRENT_CONFIG_VERSION, settings.configVersion)
    }

    @Test
    fun `ConfigVersion CURRENT is at least 1`() {
        assertTrue(ConfigVersion.CURRENT.value >= 1)
    }

    @Test
    fun `ConfigVersion PRE_VERSIONING is 0`() {
        assertEquals(0, ConfigVersion.PRE_VERSIONING.value)
    }

    @Test
    fun `ConfigVersion is Comparable`() {
        assertTrue(ConfigVersion.PRE_VERSIONING < ConfigVersion.CURRENT)
        assertTrue(ConfigVersion(5) > ConfigVersion(3))
    }

    @Test
    fun `ConfigVersion toString prefixes v`() {
        assertEquals("v1", ConfigVersion(1).toString())
        assertEquals("v42", ConfigVersion(42).toString())
    }

    @Test
    fun `default LauncherSettings has instancesDir null`() {
        val settings = LauncherSettings()
        assertNull(settings.instancesDir)
    }

    @Test
    fun `default LauncherSettings has autoStartWithSystem false`() {
        val settings = LauncherSettings()
        assertEquals(false, settings.autoStartWithSystem)
    }

    @Test
    fun `blank (empty) instancesDir throws at construction (fail-fast)`() {
        // Path.of("") yields an empty Path whose toString() is blank — caught by init{} require.
        // OS-specific whitespace paths (e.g. "   " on Windows) throw InvalidPathException
        // inside Path.of before even reaching our require — still covered as IllegalArgumentException.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            LauncherSettings(instancesDir = Path.of(""))
        }
        assertTrue(ex.message!!.contains("blank"))
    }

    @Test
    fun `serialization roundtrip preserves configVersion as integer on wire`() {
        val original = LauncherSettings(configVersion = ConfigVersion(1), language = "pl")
        val serialized = json.encodeToString(LauncherSettings.serializer(), original)
        assertTrue(serialized.contains("\"configVersion\": 1"))
        val deserialized = json.decodeFromString(LauncherSettings.serializer(), serialized)
        assertEquals(ConfigVersion(1), deserialized.configVersion)
        assertEquals("pl", deserialized.language)
    }

    @Test
    fun `serialization roundtrip preserves instancesDir as Path`() {
        val original = LauncherSettings(instancesDir = Path.of("D:\\Games\\MC"))
        val serialized = json.encodeToString(LauncherSettings.serializer(), original)
        val deserialized = json.decodeFromString(LauncherSettings.serializer(), serialized)
        assertEquals(Path.of("D:\\Games\\MC"), deserialized.instancesDir)
    }

    @Test
    fun `serialization roundtrip preserves autoStartWithSystem`() {
        val original = LauncherSettings(autoStartWithSystem = true)
        val serialized = json.encodeToString(LauncherSettings.serializer(), original)
        val deserialized = json.decodeFromString(LauncherSettings.serializer(), serialized)
        assertEquals(true, deserialized.autoStartWithSystem)
    }

    @Test
    fun `missing configVersion in old JSON defaults to 0 when parsed as JsonObject`() {
        // Documents the ConfigMigrator contract: migrator MUST pre-parse as JsonObject
        // to detect missing configVersion (→ pre-versioning v0 → migrate needed).
        val oldJson = """{"language":"pl","theme":"END"}"""
        val parsed = Json.parseToJsonElement(oldJson).jsonObject
        val rawVersion = parsed["configVersion"]?.jsonPrimitive?.intOrNull ?: 0
        assertEquals(0, rawVersion)
        assertEquals(ConfigVersion.PRE_VERSIONING, ConfigVersion(rawVersion))
    }

    @Test
    fun `TRAP decodeFromString on legacy JSON returns CURRENT not 0`() {
        // This test LOCKS IN the contract gotcha: if a caller bypasses ConfigMigrator and
        // goes directly through decodeFromString on legacy JSON, kotlinx applies the data class
        // default (CURRENT) and migration is silently skipped. Migrator MUST pre-parse
        // as JsonObject first — see LauncherSettings KDoc warning.
        val legacyJson = """{"language":"pl","theme":"END"}"""
        val settings = json.decodeFromString(LauncherSettings.serializer(), legacyJson)
        assertEquals(
            ConfigVersion.CURRENT, settings.configVersion,
            "decodeFromString applies default CURRENT to missing field — this is the trap!",
        )
    }

    @Test
    fun `future configVersion with unknown fields deserializes gracefully (downgrade scenario)`() {
        // Spec 8.1: if user runs older launcher on newer config, parse must succeed with
        // ignoreUnknownKeys=true. Future schema additions survive round-trip.
        val futureJson = """{"configVersion":999,"futureFeature":"xyz","language":"en","theme":"END"}"""
        val settings = json.decodeFromString(LauncherSettings.serializer(), futureJson)
        assertEquals(ConfigVersion(999), settings.configVersion)
        assertEquals("en", settings.language)
    }

    @Test
    fun `existing fields preserved (backward compat of schema)`() {
        // Schema lock — changes to these field names/defaults require migration logic.
        val settings = LauncherSettings()
        assertEquals(false, settings.discordRpcEnabled)
        assertEquals(false, settings.telemetryEnabled)
        assertEquals(UpdateChannel.STABLE, settings.updateChannel)
        assertEquals(true, settings.autoCheckUpdates)
        assertEquals("", settings.jvmExtraArgs)
        assertEquals(false, settings.debugLogsEnabled)
    }
}
