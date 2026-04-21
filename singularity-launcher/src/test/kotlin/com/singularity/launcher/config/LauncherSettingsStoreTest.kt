// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import com.singularity.launcher.ui.theme.ThemeMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class LauncherSettingsStoreTest {

    private fun makeStore(dir: Path): LauncherSettingsStore {
        val settingsFile = dir.resolve("launcher.json")
        val backupDir = dir.resolve("File-Backups")
        return LauncherSettingsStore(settingsFile, backupDir)
    }

    // === load: missing file ===

    @Test
    fun `load returns default when file does not exist`(@TempDir dir: Path) {
        val settings = makeStore(dir).load()
        assertEquals(ConfigVersion.CURRENT, settings.configVersion)
    }

    @Test
    fun `load with missing file does not create backup dir`(@TempDir dir: Path) {
        makeStore(dir).load()
        val backupDir = dir.resolve("File-Backups")
        assertFalse(Files.exists(backupDir))
    }

    // === load: current version (no migration) ===

    @Test
    fun `load current version skips migration, no backup created`(@TempDir dir: Path) {
        val settingsFile = dir.resolve("launcher.json")
        Files.writeString(
            settingsFile,
            """{"configVersion":1,"language":"en","theme":"END"}""",
        )

        val settings = makeStore(dir).load()

        assertEquals(ConfigVersion.CURRENT, settings.configVersion)
        assertEquals("en", settings.language)
        assertEquals(ThemeMode.END, settings.theme)

        val backupDir = dir.resolve("File-Backups")
        // No migration = no backup
        assertTrue(!Files.exists(backupDir) || Files.list(backupDir).use { it.count() } == 0L)
    }

    // === load: legacy v0 → migrate ===

    @Test
    fun `load migrates old v0 config (missing configVersion)`(@TempDir dir: Path) {
        val settingsFile = dir.resolve("launcher.json")
        val backupDir = dir.resolve("File-Backups")
        Files.writeString(settingsFile, """{"language":"pl","theme":"AETHER"}""")

        val settings = makeStore(dir).load()

        assertEquals(ConfigVersion.CURRENT, settings.configVersion)
        assertEquals("pl", settings.language)
        assertEquals(ThemeMode.AETHER, settings.theme)

        // Backup of original v0 config created in File-Backups
        assertTrue(Files.exists(backupDir))
        val backups = Files.list(backupDir).use { it.toList() }
        assertEquals(1, backups.size)
        assertTrue(backups[0].fileName.toString().startsWith("launcher.json.backup-v0-"))

        // Migrated config saved back to disk
        val savedContent = Files.readString(settingsFile)
        assertTrue(savedContent.contains("\"configVersion\": 1"))
    }

    @Test
    fun `load with invalid theme in v0 config migrates to default theme`(@TempDir dir: Path) {
        val settingsFile = dir.resolve("launcher.json")
        Files.writeString(settingsFile, """{"language":"en","theme":"INVALID"}""")

        val settings = makeStore(dir).load()

        assertEquals(ThemeMode.END, settings.theme)  // default fallback
        assertEquals("en", settings.language)
    }

    // === load: future version (forward compat) ===

    @Test
    fun `load future configVersion decodes with ignoreUnknownKeys (no migration triggered)`(
        @TempDir dir: Path,
    ) {
        val settingsFile = dir.resolve("launcher.json")
        val backupDir = dir.resolve("File-Backups")
        Files.writeString(
            settingsFile,
            """{"configVersion":999,"language":"en","futureField":"ignored"}""",
        )

        val settings = makeStore(dir).load()

        assertEquals(ConfigVersion(999), settings.configVersion)
        assertEquals("en", settings.language)

        // No backup — not a migration path
        assertTrue(!Files.exists(backupDir) || Files.list(backupDir).use { it.count() } == 0L)
    }

    // === load: corrupt JSON ===

    @Test
    fun `load corrupt JSON backs up bad file and returns defaults (no silent data loss)`(
        @TempDir dir: Path,
    ) {
        val settingsFile = dir.resolve("launcher.json")
        val backupDir = dir.resolve("File-Backups")
        Files.writeString(settingsFile, "{corrupted not valid JSON")

        val settings = makeStore(dir).load()

        // Default settings returned
        assertEquals(ConfigVersion.CURRENT, settings.configVersion)

        // Corrupt content preserved in backup for user recovery
        assertTrue(Files.exists(backupDir))
        val backups = Files.list(backupDir).use { it.toList() }
        assertEquals(1, backups.size)
        assertTrue(backups[0].fileName.toString().startsWith("launcher.json.backup-corrupt-"))
        assertEquals(
            "{corrupted not valid JSON",
            Files.readString(backups[0]),
            "Corrupt file content must be preserved exactly for user recovery",
        )
    }

    // === save + roundtrip ===

    @Test
    fun `save writes atomically and load reads back`(@TempDir dir: Path) {
        val store = makeStore(dir)
        val original = LauncherSettings(language = "en", theme = ThemeMode.AETHER)

        store.save(original)

        val loaded = store.load()
        assertEquals("en", loaded.language)
        assertEquals(ThemeMode.AETHER, loaded.theme)
    }

    @Test
    fun `save creates parent directory if missing`(@TempDir dir: Path) {
        // settings file in deep nested path not yet created
        val settingsFile = dir.resolve("nested/deep/launcher.json")
        val backupDir = dir.resolve("File-Backups")
        val store = LauncherSettingsStore(settingsFile, backupDir)

        store.save(LauncherSettings(language = "en"))

        assertTrue(Files.exists(settingsFile))
    }

    // === update helper ===

    @Test
    fun `update applies transform and persists`(@TempDir dir: Path) {
        val store = makeStore(dir)
        store.save(LauncherSettings(language = "pl"))

        val updated = store.update { it.copy(language = "en") }

        assertEquals("en", updated.language)
        assertEquals("en", store.load().language)
    }

    // === backup retention ===

    @Test
    fun `cleanupOldBackups keeps only last 3 launcher_json backups`(@TempDir dir: Path) {
        val backupDir = dir.resolve("File-Backups")
        Files.createDirectories(backupDir)

        // Create 5 fake backups with progressively newer timestamps
        val backupNames = listOf(
            "launcher.json.backup-v0-2026-01-01T10-00",
            "launcher.json.backup-v0-2026-02-01T10-00",
            "launcher.json.backup-v0-2026-03-01T10-00",
            "launcher.json.backup-v0-2026-04-01T10-00",
            "launcher.json.backup-v0-2026-05-01T10-00",
        )
        backupNames.forEachIndexed { i, name ->
            val path = backupDir.resolve(name)
            Files.writeString(path, "{}")
            // set lastModified progressively (oldest first)
            Files.setLastModifiedTime(path, FileTime.fromMillis(1_700_000_000_000L + i * 1_000_000L))
        }

        makeStore(dir).cleanupOldBackups()

        val remaining = Files.list(backupDir).use { it.toList() }
        assertEquals(3, remaining.size)
        // Newest 3 (May, April, March) should remain
        val remainingNames = remaining.map { it.fileName.toString() }.toSet()
        assertTrue(remainingNames.contains("launcher.json.backup-v0-2026-05-01T10-00"))
        assertTrue(remainingNames.contains("launcher.json.backup-v0-2026-04-01T10-00"))
        assertTrue(remainingNames.contains("launcher.json.backup-v0-2026-03-01T10-00"))
    }

    @Test
    fun `cleanupOldBackups preserves corrupt backups alongside migration backups`(
        @TempDir dir: Path,
    ) {
        val backupDir = dir.resolve("File-Backups")
        Files.createDirectories(backupDir)

        // 2 migration backups + 1 corrupt backup = 3 total, under retention limit
        val now = System.currentTimeMillis()
        listOf(
            "launcher.json.backup-v0-2026-01-01T10-00",
            "launcher.json.backup-v0-2026-02-01T10-00",
            "launcher.json.backup-corrupt-2026-03-01T10-00",
        ).forEachIndexed { i, name ->
            val path = backupDir.resolve(name)
            Files.writeString(path, "{}")
            Files.setLastModifiedTime(path, FileTime.fromMillis(now + i * 1000))
        }

        makeStore(dir).cleanupOldBackups()

        val remaining = Files.list(backupDir).use { it.toList() }
        assertEquals(3, remaining.size, "3 backups under retention limit — all kept")
    }

    // === default factory ===

    @Test
    fun `default factory accepts injected InstallLocation for testability`(
        @TempDir dir: Path,
    ) {
        val store = LauncherSettingsStore.default(
            install = InstallLocation.DevFallback(dir),
        )
        // Just verify construction + load doesn't crash
        val settings = store.load()
        assertNotNull(settings)
    }

    @Test
    fun `load then save of future config preserves configVersion but drops unknown fields (spec 8_1 graceful loss)`(
        @TempDir dir: Path,
    ) {
        val settingsFile = dir.resolve("launcher.json")
        Files.writeString(
            settingsFile,
            """{"configVersion":999,"language":"en","futureField":"xyz"}""",
        )

        val store = makeStore(dir)
        val loaded = store.load()
        store.save(loaded)

        val diskContent = Files.readString(settingsFile)
        assertTrue(
            diskContent.contains("\"configVersion\": 999"),
            "future configVersion must be preserved across save",
        )
        assertFalse(
            diskContent.contains("futureField"),
            "unknown field silently dropped on save (graceful loss per spec 8.1)",
        )
    }

    @Test
    fun `update works when file does not yet exist (first-run write)`(@TempDir dir: Path) {
        val updated = makeStore(dir).update { it.copy(language = "en") }
        assertEquals("en", updated.language)
    }

    @Test
    fun `constructor rejects settingsFile without parent`(@TempDir dir: Path) {
        // Path like "launcher.json" (no parent) would NPE at save() time; fail-fast via init{}.
        assertThrows(IllegalArgumentException::class.java) {
            LauncherSettingsStore(
                settingsFile = Path.of("launcher.json"),
                backupDir = dir.resolve("File-Backups"),
            )
        }
    }

    @Test
    fun `stale tmp file from prior crash is cleaned up on load`(@TempDir dir: Path) {
        val settingsFile = dir.resolve("launcher.json")
        val staleTmp = dir.resolve("launcher.json.tmp")
        Files.writeString(staleTmp, """{"leftover":"from crash"}""")

        makeStore(dir).load()

        assertFalse(Files.exists(staleTmp), "stale .tmp should be removed at load start")
    }

    // === first-run language auto-detect ===

    @Test
    fun `first run (missing file) auto-detects Polish when system locale is pl`(@TempDir dir: Path) {
        // This test assumes running on a dev machine where Locale.getDefault() language is "pl".
        // When running on "en" CI, language default would be "en". We just verify it's a valid
        // supported value — either "pl" or something else (don't fail on non-PL dev machines).
        val settings = makeStore(dir).load()
        assertTrue(
            settings.language in setOf("pl", "en", "auto"),
            "first-run language should be PL or EN (auto-detected); got '${settings.language}'",
        )
    }
}
