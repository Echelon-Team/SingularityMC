package com.singularity.launcher.config

import java.nio.file.Path

/**
 * Centralized file paths for SingularityMC user data (per spec section 4.11).
 *
 * On Windows: `%APPDATA%\SingularityMC\`
 * On Linux: `$XDG_CONFIG_HOME/singularitymc/` (fallback: `~/.config/singularitymc/`)
 *
 * **macOS:** current spec only covers Win + Linux. On macOS the class falls into the
 * `else` branch (XDG-style path). This is intentional simplification — if macOS ever
 * becomes an officially-supported target, the spec and this class should be revisited
 * (macOS GUI convention is `~/Library/Application Support/SingularityMC/`).
 *
 * Install-path resolution (Location 1 — binaries, `File-Backups/`) is a separate concern,
 * resolved at call sites of `LauncherSettingsStore` per plan Task 1.4.
 *
 * Empty-string environment variables are treated as absent (JVM on some Windows configs
 * returns "" for unset vars).
 *
 * Eager `val` initialization: APPDATA-missing errors surface at construction time,
 * preserving fail-fast invariants.
 */
class DataPaths(
    osName: String = System.getProperty("os.name"),
    appDataEnv: String? = System.getenv("APPDATA"),
    xdgConfigHome: String? = System.getenv("XDG_CONFIG_HOME"),
    userHome: String = System.getProperty("user.home"),
) {
    val userDataDir: Path = when {
        osName.lowercase().contains("windows") -> {
            val appData = appDataEnv?.takeIf { it.isNotBlank() }
                ?: error("APPDATA environment variable not set on Windows")
            Path.of(appData, "SingularityMC")
        }
        else -> {
            val configBase = xdgConfigHome?.takeIf { it.isNotBlank() }
                ?: Path.of(userHome, ".config").toString()
            Path.of(configBase, "singularitymc")
        }
    }

    val launcherConfigFile: Path = userDataDir.resolve("launcher.json")
    val accountsFile: Path = userDataDir.resolve("accounts.json")
    val instancesDir: Path = userDataDir.resolve("instances")
    val cacheDir: Path = userDataDir.resolve("cache")
    val logsDir: Path = userDataDir.resolve("logs")
    val javaDir: Path = userDataDir.resolve("java")

    companion object {
        fun default(): DataPaths = DataPaths()
    }
}
