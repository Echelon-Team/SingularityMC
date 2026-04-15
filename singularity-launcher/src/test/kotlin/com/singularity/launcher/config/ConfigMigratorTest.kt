package com.singularity.launcher.config

import com.singularity.launcher.ui.theme.ThemeMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import java.nio.file.Path

class ConfigMigratorTest {

    // === detectVersion() ===

    @Test
    fun `detectVersion returns PRE_VERSIONING when configVersion absent`() {
        val json = """{"language":"pl","theme":"END"}"""
        assertEquals(ConfigVersion.PRE_VERSIONING, ConfigMigrator.detectVersion(json))
    }

    @Test
    fun `detectVersion parses configVersion integer`() {
        val json = """{"configVersion":1,"language":"pl"}"""
        assertEquals(ConfigVersion(1), ConfigMigrator.detectVersion(json))
    }

    @Test
    fun `detectVersion handles arbitrary version numbers (forward compat)`() {
        val json = """{"configVersion":999}"""
        assertEquals(ConfigVersion(999), ConfigMigrator.detectVersion(json))
    }

    @Test
    fun `detectVersion on integer overflow (greater than Int MAX) returns PRE_VERSIONING`() {
        val json = """{"configVersion":99999999999999}"""  // > Int.MAX
        assertEquals(ConfigVersion.PRE_VERSIONING, ConfigMigrator.detectVersion(json))
    }

    @Test
    fun `detectVersion on negative configVersion returns PRE_VERSIONING (corruption signal)`() {
        val json = """{"configVersion":-5}"""
        assertEquals(ConfigVersion.PRE_VERSIONING, ConfigMigrator.detectVersion(json))
    }

    @Test
    fun `detectVersion on corrupted JSON returns PRE_VERSIONING`() {
        assertEquals(ConfigVersion.PRE_VERSIONING, ConfigMigrator.detectVersion("{corrupted"))
    }

    @Test
    fun `detectVersion on empty string returns PRE_VERSIONING`() {
        assertEquals(ConfigVersion.PRE_VERSIONING, ConfigMigrator.detectVersion(""))
    }

    @Test
    fun `detectVersion on non-integer configVersion returns PRE_VERSIONING`() {
        val json = """{"configVersion":"1"}"""  // string, not int
        assertEquals(ConfigVersion.PRE_VERSIONING, ConfigMigrator.detectVersion(json))
    }

    @Test
    fun `detectVersion on non-object root (array) returns PRE_VERSIONING`() {
        assertEquals(ConfigVersion.PRE_VERSIONING, ConfigMigrator.detectVersion("[]"))
    }

    // === migrate() field mappings ===

    @Test
    fun `migrate from v0 preserves language field`() {
        val oldJson = """{"language":"en","theme":"END"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals("en", result.language)
    }

    @Test
    fun `migrate sets configVersion to CURRENT`() {
        val oldJson = """{"language":"pl"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(ConfigVersion.CURRENT, result.configVersion)
    }

    @Test
    fun `migrate with missing theme uses default END`() {
        val oldJson = """{"language":"pl"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(ThemeMode.END, result.theme)
    }

    @Test
    fun `migrate with invalid theme value uses default END`() {
        val oldJson = """{"language":"pl","theme":"INVALID_THEME"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(ThemeMode.END, result.theme)
    }

    @Test
    fun `migrate handles lowercase theme names (case-insensitive)`() {
        val oldJson = """{"theme":"end"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(ThemeMode.END, result.theme)
    }

    @Test
    fun `migrate handles mixed-case theme names`() {
        val oldJson = """{"theme":"EnD"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(ThemeMode.END, result.theme)
    }

    @Test
    fun `migrate with whitespace-padded theme value falls back to default (no trim)`() {
        // Documents intentional non-trim behavior — ' END ' doesn't match valueOf.
        val oldJson = """{"theme":" END "}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(ThemeMode.END, result.theme)  // default, because parse failed
    }

    @Test
    fun `migrate preserves valid uppercase theme values`() {
        val oldJson = """{"theme":"AETHER"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(ThemeMode.AETHER, result.theme)
    }

    @Test
    fun `migrate preserves window geometry`() {
        val oldJson = """{"windowX":100,"windowY":200,"windowWidth":1600,"windowHeight":900}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(100, result.windowX)
        assertEquals(200, result.windowY)
        assertEquals(1600, result.windowWidth)
        assertEquals(900, result.windowHeight)
    }

    @Test
    fun `migrate with missing window geometry uses defaults`() {
        val oldJson = """{"language":"pl"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(-1, result.windowX)
        assertEquals(-1, result.windowY)
        assertEquals(1280, result.windowWidth)
        assertEquals(800, result.windowHeight)
    }

    @Test
    fun `migrate preserves updateChannel BETA`() {
        val oldJson = """{"updateChannel":"BETA"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(UpdateChannel.BETA, result.updateChannel)
    }

    @Test
    fun `migrate with invalid updateChannel uses default STABLE`() {
        val oldJson = """{"updateChannel":"INVALID_CHANNEL"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(UpdateChannel.STABLE, result.updateChannel)
    }

    @Test
    fun `migrate handles mixed-case updateChannel values`() {
        val oldJson = """{"updateChannel":"bEtA"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(UpdateChannel.BETA, result.updateChannel)
    }

    @Test
    fun `migrate preserves boolean flags`() {
        val oldJson = """
            {
                "discordRpcEnabled": true,
                "telemetryEnabled": true,
                "autoCheckUpdates": false,
                "debugLogsEnabled": true,
                "autoStartWithSystem": true
            }
        """.trimIndent()
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(true, result.discordRpcEnabled)
        assertEquals(true, result.telemetryEnabled)
        assertEquals(false, result.autoCheckUpdates)
        assertEquals(true, result.debugLogsEnabled)
        assertEquals(true, result.autoStartWithSystem)
    }

    @Test
    fun `migrate with missing boolean flags uses defaults`() {
        val oldJson = """{"language":"pl"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(false, result.discordRpcEnabled)
        assertEquals(false, result.telemetryEnabled)
        assertEquals(true, result.autoCheckUpdates)
        assertEquals(false, result.debugLogsEnabled)
        assertEquals(false, result.autoStartWithSystem)
    }

    @Test
    fun `migrate preserves string fields jvmExtraArgs`() {
        val oldJson = """{"jvmExtraArgs":"-Xmx8G -XX:+UseG1GC"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals("-Xmx8G -XX:+UseG1GC", result.jvmExtraArgs)
    }

    @Test
    fun `migrate preserves nullable lastActive ids`() {
        val oldJson = """{"lastActiveAccountId":"account-1","lastActiveInstanceId":"instance-42"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals("account-1", result.lastActiveAccountId)
        assertEquals("instance-42", result.lastActiveInstanceId)
    }

    @Test
    fun `migrate with missing lastActive ids returns null`() {
        val oldJson = """{}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertNull(result.lastActiveAccountId)
        assertNull(result.lastActiveInstanceId)
    }

    @Test
    fun `migrate preserves instancesDir as Path`() {
        val oldJson = """{"instancesDir":"D:\\Games\\MC"}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(Path.of("D:\\Games\\MC"), result.instancesDir)
    }

    @Test
    fun `migrate with blank (empty) instancesDir maps to null (defensive)`() {
        // Prevents Path.of("") silent trap AND LauncherSettings init{} require failure.
        val oldJson = """{"instancesDir":""}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertNull(result.instancesDir)
    }

    @Test
    fun `migrate with whitespace-only instancesDir maps to null (defensive)`() {
        val oldJson = """{"instancesDir":"   "}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertNull(result.instancesDir)
    }

    @Test
    fun `migrate from v1 (same version) produces equivalent current-version settings (no-op case)`() {
        val v1Json = """
            {
                "configVersion":1,
                "theme":"AETHER",
                "language":"en",
                "windowWidth":1920,
                "updateChannel":"BETA"
            }
        """.trimIndent()
        val result = ConfigMigrator.migrate(v1Json)
        assertEquals(ConfigVersion.CURRENT, result.configVersion)
        assertEquals(ThemeMode.AETHER, result.theme)
        assertEquals("en", result.language)
        assertEquals(1920, result.windowWidth)
        assertEquals(UpdateChannel.BETA, result.updateChannel)
    }

    @Test
    fun `migrate on empty JSON object produces default LauncherSettings with CURRENT version`() {
        val result = ConfigMigrator.migrate("{}")
        assertEquals(ConfigVersion.CURRENT, result.configVersion)
        assertEquals("pl", result.language)
        assertEquals(ThemeMode.END, result.theme)
    }

    @Test
    fun `migrate with explicit JSON null values uses defaults`() {
        // JsonNull tolerated — all helpers use *OrNull variants.
        val oldJson = """{"language":null,"theme":null,"jvmExtraArgs":null,"instancesDir":null}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals("pl", result.language)
        assertEquals(ThemeMode.END, result.theme)
        assertEquals("", result.jvmExtraArgs)
        assertNull(result.instancesDir)
    }

    @Test
    fun `migrate gracefully handles type-mismatched fields by using defaults`() {
        // Real corrupted configs may have wrong types (hand-edited, partial write, fork).
        // Graceful degradation — user gets defaults instead of crash.
        val oldJson = """{"windowX":"not-int","autoCheckUpdates":"maybe","windowWidth":3.14}"""
        val result = ConfigMigrator.migrate(oldJson)
        assertEquals(-1, result.windowX)
        assertEquals(true, result.autoCheckUpdates)
        assertEquals(1280, result.windowWidth)  // 3.14 rejected by intOrNull
    }

    @Test
    fun `migrate on malformed JSON throws (fail-fast for caller)`() {
        assertTrue(
            runCatching { ConfigMigrator.migrate("{corrupted") }.isFailure,
            "Migrator should fail-fast on unparseable JSON so caller can decide (backup/default)",
        )
    }

    @Test
    fun `migrate on non-object root throws (fail-fast for caller)`() {
        assertTrue(
            runCatching { ConfigMigrator.migrate("[]") }.isFailure,
            "Non-object root is corrupted config — fail-fast",
        )
    }
}
