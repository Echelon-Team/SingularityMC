// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.navigation

import com.singularity.launcher.viewmodel.BaseViewModel

/**
 * Reactive navigation ViewModel. Implementuje `Navigator` interface żeby composables
 * mogły go dostać przez `LocalNavigator.current` (bez eksponowania dziedziczonego
 * BaseViewModel API).
 *
 * Każda metoda mutuje `StateFlow<NavigationState>` przez `updateState { }`, co
 * triggeruje recomposition wszystkich composables czytających `state.collectAsState()`.
 *
 * Instance żyje na poziomie `App.kt` (root composable) — NIE per-screen. Jeden
 * NavigationViewModel dla całego launchera, `DisposableEffect` w `App.kt` wywoła
 * `onCleared()` przy zamykaniu okna.
 */
class NavigationViewModel : BaseViewModel<NavigationState>(NavigationState()), Navigator {

    override fun navigateTo(screen: Screen) {
        updateState { it.navigateTo(screen) }
    }

    override fun openInstancePanel(instanceId: String) {
        updateState { it.openInstancePanel(instanceId) }
    }

    override fun openServerPanel(serverId: String) {
        updateState { it.openServerPanel(serverId) }
    }

    override fun backFromPanel() {
        updateState { it.backFromPanel() }
    }

    override fun openSettings(section: SettingsSection) {
        updateState { it.openSettings(section) }
    }

    override fun toggleAccountOverlay() {
        updateState { it.toggleAccountOverlay() }
    }
}
