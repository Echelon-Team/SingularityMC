package com.singularity.launcher.ui.screens.settings

import com.singularity.launcher.config.LauncherSettings
import com.singularity.launcher.config.UpdateChannel
import com.singularity.launcher.ui.navigation.SettingsSection
import com.singularity.launcher.ui.theme.ThemeMode
import com.singularity.launcher.viewmodel.BaseViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

data class SettingsScreenState(
    val settings: LauncherSettings = LauncherSettings(),
    val currentSection: SettingsSection = SettingsSection.APPEARANCE,
    val isLoading: Boolean = false
)

/**
 * ViewModel dla SettingsScreen. Konstruktor akceptuje load/save funkcje zamiast
 * `LauncherSettingsStore` bezpośrednio — ułatwia testy (FakeStore bez I/O).
 *
 * **Auto-save on change:** każda mutacja settings triggeruje save przez `saveSettings` —
 * user nie musi klikać "Save", changes są persisted natychmiast.
 */
class SettingsViewModel(
    private val loadSettings: () -> LauncherSettings,
    private val saveSettings: (LauncherSettings) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Swing
) : BaseViewModel<SettingsScreenState>(SettingsScreenState(), dispatcher) {

    init {
        load()
    }

    private fun load() {
        updateState { it.copy(isLoading = true) }
        viewModelScope.launch {
            // Small JSON read — no need to offload to IO dispatcher
            val loaded = loadSettings()
            updateState { it.copy(settings = loaded, isLoading = false) }
        }
    }

    private fun update(transform: (LauncherSettings) -> LauncherSettings) {
        updateState { it.copy(settings = transform(it.settings)) }
        viewModelScope.launch {
            // Small JSON write — caller can wrap in IO dispatcher if needed
            saveSettings(state.value.settings)
        }
    }

    fun setSection(section: SettingsSection) = updateState { it.copy(currentSection = section) }

    fun setTheme(theme: ThemeMode) = update { it.copy(theme = theme) }
    fun setLanguage(lang: String) = update { it.copy(language = lang) }
    fun setUpdateChannel(channel: UpdateChannel) = update { it.copy(updateChannel = channel) }
    fun setAutoCheckUpdates(enabled: Boolean) = update { it.copy(autoCheckUpdates = enabled) }
    fun setJvmExtraArgs(args: String) = update { it.copy(jvmExtraArgs = args) }
    fun setDebugLogsEnabled(enabled: Boolean) = update { it.copy(debugLogsEnabled = enabled) }
    fun setDiscordRpcEnabled(enabled: Boolean) = update { it.copy(discordRpcEnabled = enabled) }
}
