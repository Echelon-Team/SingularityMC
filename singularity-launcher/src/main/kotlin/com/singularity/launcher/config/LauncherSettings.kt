// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.config

import com.singularity.launcher.ui.theme.ThemeMode
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
enum class UpdateChannel { STABLE, BETA }

/**
 * Globalne settingi launchera — persisted w `<user-data>/launcher.json`
 * (Win: `%APPDATA%\SingularityMC\`, Linux: `~/.config/singularitymc/`).
 *
 * **configVersion:** schema version marker used by [ConfigMigrator] to decide whether migration
 * is needed. Default is [ConfigVersion.CURRENT] (currently 1, v0.1.0+). Pre-versioning configs
 * (missing field) should be treated as version 0.
 *
 * **⚠️ IMPORTANT for migration logic:** because kotlinx-serialization applies data class defaults
 * during deserialization, `decodeFromString<LauncherSettings>(legacyJson)` will silently return
 * `configVersion = CURRENT` even when the JSON lacks the field — bypassing migration entirely.
 * [ConfigMigrator] MUST first parse as [kotlinx.serialization.json.JsonObject] and peek the
 * `configVersion` field via `?: ConfigVersion.PRE_VERSIONING` before deserializing to this class.
 *
 * **ThemeMode:** używamy `com.singularity.launcher.ui.theme.ThemeMode` (Task 2 Sub 4) —
 * single source of truth, nie duplikujemy enuma w config package.
 */
@Serializable
data class LauncherSettings(
    val configVersion: ConfigVersion = ConfigVersion.CURRENT,
    val theme: ThemeMode = ThemeMode.END,
    val language: String = "pl",
    val lastActiveAccountId: String? = null,
    val lastActiveInstanceId: String? = null,
    val windowX: Int = -1,  // -1 = centered
    val windowY: Int = -1,
    val windowWidth: Int = 1280,
    val windowHeight: Int = 800,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val autoCheckUpdates: Boolean = true,
    val jvmExtraArgs: String = "",
    val debugLogsEnabled: Boolean = false,
    val discordRpcEnabled: Boolean = false,
    val telemetryEnabled: Boolean = false,
    /**
     * Custom instances directory (null = default `<userDataDir>/instances`).
     * If set, must be a non-blank path; blank strings throw at construction.
     */
    @Serializable(with = PathSerializer::class)
    val instancesDir: Path? = null,
    /** Launch SingularityMC at Windows startup (Registry HKCU Run, set by installer). */
    val autoStartWithSystem: Boolean = false,
) {
    init {
        instancesDir?.let {
            require(it.toString().isNotBlank()) {
                "instancesDir must not be blank; use null for default"
            }
        }
    }

    companion object {
        /** Convenience alias for [ConfigVersion.CURRENT]. */
        val CURRENT_CONFIG_VERSION: ConfigVersion get() = ConfigVersion.CURRENT
    }
}
