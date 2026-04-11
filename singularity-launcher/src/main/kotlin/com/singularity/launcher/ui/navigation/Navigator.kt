package com.singularity.launcher.ui.navigation

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstrakcja nawigacji — implementowana przez `NavigationViewModel`.
 *
 * Istnieje jako osobny interface (nie sam ViewModel) żeby:
 * 1. Móc łatwo mockować w testach composables (`FakeNavigator`)
 * 2. Dostarczać przez `LocalNavigator` CompositionLocal bez eksponowania
 *    całego ViewModelu (composables widzą tylko nawigację, nie `updateState` itp.)
 */
interface Navigator {
    val state: StateFlow<NavigationState>

    fun navigateTo(screen: Screen)
    fun openInstancePanel(instanceId: String)
    fun openServerPanel(serverId: String)
    fun backFromPanel()
    fun openSettings(section: SettingsSection = state.value.settingsSection)
    fun toggleAccountOverlay()
}
