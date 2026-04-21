// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Persistent [LauncherSettings] store with schema migration + corruption-proof backup.
 *
 * **Load flow (per spec 8.1):**
 * 1. Missing file → default settings with auto-detected language (first-run behavior).
 * 2. Exists but not syntactically valid JSON → backup raw to
 *    `File-Backups/launcher.json.backup-corrupt-{ts}` and return defaults (no silent data loss).
 * 3. Version < [ConfigVersion.CURRENT] → backup to `File-Backups/launcher.json.backup-v{n}-{ts}`,
 *    migrate via [ConfigMigrator.migrate], save migrated result.
 * 4. Version == [ConfigVersion.CURRENT] → normal decode. If parse fails, backup corrupt + default.
 * 5. Version > [ConfigVersion.CURRENT] (downgrade scenario) → decode with `ignoreUnknownKeys=true`
 *    for forward compat; unknown fields from future launcher silently tolerated (spec-expected
 *    graceful loss if user saves in older launcher).
 *
 * **Paths:**
 * - `settingsFile` lives in user data dir (`%APPDATA%\SingularityMC\launcher.json` Win,
 *   `~/.config/singularitymc/launcher.json` Linux) — per [DataPaths].
 * - `backupDir` lives in install path (`<install_path>\File-Backups\`) — per spec 4.11.
 *
 * **Backup retention:** [cleanupOldBackups] keeps last 3 backups (all tags share one cap),
 * deletes older by lastModified time.
 *
 * **Robustness:** All backup/cleanup operations use best-effort try-catch — a failing backup dir
 * (permission issue, readonly volume) does NOT crash the launcher; the load path returns defaults
 * with warnings logged.
 *
 * **Thread safety:** NOT thread-safe. `update()` does load-transform-save without locking.
 * Intended for single-writer (UI thread) usage. Concurrent launcher instances would race.
 */
class LauncherSettingsStore(
    private val settingsFile: Path,
    private val backupDir: Path,
) {
    init {
        requireNotNull(settingsFile.parent) {
            "settingsFile must have a parent directory (got: $settingsFile)"
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): LauncherSettings {
        // Defensive cleanup: leftover .tmp from crashed save.
        val tmpPath = settingsFile.resolveSibling("${settingsFile.fileName}.tmp")
        try {
            Files.deleteIfExists(tmpPath)
        } catch (e: Exception) {
            logger.debug("Could not remove stale {}: {}", tmpPath, e.message)
        }

        if (!Files.exists(settingsFile)) {
            val systemLang = Locale.getDefault().language
            val supportedLang = if (systemLang == "pl") "pl" else "en"
            logger.info("First run — creating default LauncherSettings (language={})", supportedLang)
            return LauncherSettings(language = supportedLang)
        }

        val rawJson = try {
            Files.readString(settingsFile)
        } catch (e: Exception) {
            logger.warn("Cannot read {}: {} — using defaults", settingsFile, e.message)
            return LauncherSettings()
        }

        // Syntactic check first: if raw JSON doesn't parse as object, it's corrupted
        // (not legitimately "v0"). Backup with "corrupt" tag to distinguish in File-Backups.
        val syntacticallyValid = runCatching {
            Json.parseToJsonElement(rawJson).jsonObject
        }.isSuccess
        if (!syntacticallyValid) {
            val backupPath = backupRawSafely(rawJson, tag = "corrupt")
            logger.warn(
                "Corrupted launcher.json — backup={}, using defaults",
                backupPath ?: "(backup failed)",
            )
            cleanupOldBackupsSafely()
            return LauncherSettings()
        }

        val detectedVersion = ConfigMigrator.detectVersion(rawJson)

        return when {
            detectedVersion < ConfigVersion.CURRENT -> loadAndMigrate(rawJson, detectedVersion)
            detectedVersion == ConfigVersion.CURRENT -> loadCurrent(rawJson)
            else -> loadFuture(rawJson, detectedVersion)
        }
    }

    private fun loadAndMigrate(rawJson: String, fromVersion: ConfigVersion): LauncherSettings {
        logger.info(
            "Migrating {} from {} to {}",
            settingsFile.fileName,
            fromVersion,
            ConfigVersion.CURRENT,
        )
        val backupPath = backupRawSafely(rawJson, tag = "v${fromVersion.value}")
        if (backupPath != null) {
            logger.info("Pre-migration backup saved to {}", backupPath)
        } else {
            logger.warn("Pre-migration backup failed — proceeding with migration WITHOUT backup")
        }

        val migrated = try {
            ConfigMigrator.migrate(rawJson)
        } catch (e: Exception) {
            logger.warn(
                "Migration failed ({}), using defaults; original: {}",
                e.message,
                backupPath ?: "(no backup)",
            )
            cleanupOldBackupsSafely()
            return LauncherSettings()
        }

        save(migrated)
        cleanupOldBackupsSafely()
        return migrated
    }

    private fun loadCurrent(rawJson: String): LauncherSettings =
        decodeOrBackupDefaults(rawJson, context = "current-version")

    private fun loadFuture(rawJson: String, version: ConfigVersion): LauncherSettings {
        logger.info(
            "Encountered future config {} (current is {}) — decoding with ignoreUnknownKeys",
            version,
            ConfigVersion.CURRENT,
        )
        return decodeOrBackupDefaults(rawJson, context = "future-version-$version")
    }

    /** Shared decode-or-fallback path for current + future versions. */
    private fun decodeOrBackupDefaults(rawJson: String, context: String): LauncherSettings {
        return try {
            json.decodeFromString(LauncherSettings.serializer(), rawJson)
        } catch (e: Exception) {
            val backupPath = backupRawSafely(rawJson, tag = "corrupt")
            logger.warn(
                "Decode failed ({}) for {}: {} — backup={}, using defaults",
                context,
                settingsFile.fileName,
                e.message,
                backupPath ?: "(backup failed)",
            )
            cleanupOldBackupsSafely()
            LauncherSettings()
        }
    }

    fun save(settings: LauncherSettings) {
        val parent = settingsFile.parent
            ?: error("settingsFile has no parent — cannot save (should be enforced by init{})")
        Files.createDirectories(parent)
        val tmp = settingsFile.resolveSibling("${settingsFile.fileName}.tmp")
        val content = json.encodeToString(LauncherSettings.serializer(), settings)
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            logger.warn(
                "Atomic move not supported for {}, falling back to non-atomic replace",
                settingsFile,
            )
            Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun update(transform: (LauncherSettings) -> LauncherSettings): LauncherSettings {
        val current = load()
        val updated = transform(current)
        save(updated)
        return updated
    }

    /**
     * Best-effort backup copy of raw JSON content to backup dir with timestamped filename.
     * Returns the backup path on success, or null on failure (logged but not thrown).
     *
     * @param tag suffix after "launcher.json.backup-" (e.g. "v0" or "corrupt")
     */
    private fun backupRawSafely(rawJson: String, tag: String): Path? {
        return try {
            Files.createDirectories(backupDir)
            val timestamp = Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
            val backupPath = backupDir.resolve("launcher.json.backup-$tag-$timestamp")
            Files.writeString(backupPath, rawJson)
            backupPath
        } catch (e: Exception) {
            logger.warn("Backup to {} failed: {}", backupDir, e.message)
            null
        }
    }

    /** Best-effort cleanup wrapper — never throws. */
    private fun cleanupOldBackupsSafely() {
        try {
            cleanupOldBackups()
        } catch (e: Exception) {
            logger.warn("Backup cleanup failed: {}", e.message)
        }
    }

    /**
     * Keep only the [BACKUP_RETENTION] most recently modified `launcher.json.backup-*` files.
     * Older backups are deleted to prevent unbounded disk growth.
     *
     * Implementation: sort descending by mtime, drop first N (newest), delete the rest.
     */
    fun cleanupOldBackups() {
        if (!Files.exists(backupDir)) return
        val backups = Files.list(backupDir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("launcher.json.backup-") }
                .toList()
        }
        // Newest first: keep first N via descending sort by mtime, delete the rest.
        val newestFirst = backups.sortedByDescending { Files.getLastModifiedTime(it) }
        newestFirst.drop(BACKUP_RETENTION).forEach {
            try {
                Files.delete(it)
                logger.debug("Deleted old backup {}", it.fileName)
            } catch (e: Exception) {
                logger.warn("Failed to delete old backup {}: {}", it, e.message)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LauncherSettingsStore::class.java)

        /** Max number of launcher.json backups retained across all backup types. */
        const val BACKUP_RETENTION = 3

        /**
         * Default store using [DataPaths] for settings file and [InstallPaths] for backups.
         * Logs a warning if running under [InstallLocation.DevFallback] (dev mode) — File-Backups
         * will be created under user.dir instead of a real install directory.
         */
        fun default(install: InstallLocation = InstallPaths.current()): LauncherSettingsStore {
            if (install is InstallLocation.DevFallback) {
                logger.warn(
                    "InstallPaths: dev mode (no jpackage.app-path); File-Backups will be in {}",
                    install.path,
                )
            }
            val paths = DataPaths.default()
            return LauncherSettingsStore(
                settingsFile = paths.launcherConfigFile,
                backupDir = install.path.resolve("File-Backups"),
            )
        }
    }
}
