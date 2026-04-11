package com.singularity.launcher.config

import com.singularity.launcher.ui.theme.ThemeMode
import kotlinx.serialization.Serializable

@Serializable
enum class UpdateChannel { STABLE, BETA }

/**
 * Globalne settingi launchera — persisted w `~/.singularitymc/launcher.json`.
 *
 * **Referenced w:** Task 22 SettingsScreen (GUI), Task 32 App.kt (wire), Task 25
 * backgrounds (theme check for Crossfade), Task 6 I18n (language load).
 *
 * **ThemeMode:** używamy `com.singularity.launcher.ui.theme.ThemeMode` (z Task 2) —
 * single source of truth, nie duplikujemy enuma w config package.
 */
@Serializable
data class LauncherSettings(
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
    val discordRpcEnabled: Boolean = false
)
