package com.singularity.launcher.ui.navigation

/**
 * Immutable state nawigacji launchera — pure data + transition methods.
 *
 * **Reactivity:** Ten data class sam NIE triggeruje recomposition Compose.
 * Żeby działało reactive, użyj przez `NavigationViewModel` (implementuje
 * `Navigator`) który wraps ten state w `StateFlow<NavigationState>`.
 *
 * **Field semantics:**
 * - `currentScreen`: aktualnie widoczny screen (1 z 10)
 * - `instanceContext`: id instancji gdy `currentScreen == INSTANCE_PANEL`, inaczej null
 * - `serverContext`: id serwera gdy `currentScreen == SERVER_PANEL`, inaczej null
 * - `settingsSection`: która sekcja Settings jest aktywna — zachowywana między
 *   wejściami na Settings (jeśli user był na PERFORMANCE → wyszedł → wrócił,
 *   znowu widzi PERFORMANCE, nie zresetowane do APPEARANCE)
 * - `accountOverlayOpen`: czy popup Account jest widoczny NAD aktualnym screen
 *   (overlay, nie peer view — decyzja D4 Mateusza 2026-04-11)
 *
 * **Why no previousScreen field:** back z drill-down paneli jest deterministyczny
 * (INSTANCE_PANEL → INSTANCES, SERVER_PANEL → SERVERS) — nie potrzebujemy tracking.
 * Jeśli kiedyś dojdzie kontekstowy "back" z historii → dodać `backStack: List<Screen>`.
 */
data class NavigationState(
    val currentScreen: Screen = Screen.HOME,
    val instanceContext: String? = null,
    val serverContext: String? = null,
    val settingsSection: SettingsSection = SettingsSection.APPEARANCE,
    val accountOverlayOpen: Boolean = false
) {
    /** Nawiguje do dowolnego screen (nie drill-down) — czyści contexty paneli. */
    fun navigateTo(screen: Screen): NavigationState =
        copy(
            currentScreen = screen,
            instanceContext = null,
            serverContext = null
        )

    /** Wchodzi w drill-down INSTANCE_PANEL dla konkretnej instancji. */
    fun openInstancePanel(instanceId: String): NavigationState =
        copy(
            currentScreen = Screen.INSTANCE_PANEL,
            instanceContext = instanceId,
            serverContext = null
        )

    /** Wchodzi w drill-down SERVER_PANEL dla konkretnego serwera. */
    fun openServerPanel(serverId: String): NavigationState =
        copy(
            currentScreen = Screen.SERVER_PANEL,
            serverContext = serverId,
            instanceContext = null
        )

    /**
     * Back z drill-down panelu do parent listy.
     * - INSTANCE_PANEL → INSTANCES (czyści instanceContext)
     * - SERVER_PANEL → SERVERS (czyści serverContext)
     * - inne screens → no-op
     */
    fun backFromPanel(): NavigationState = when (currentScreen) {
        Screen.INSTANCE_PANEL -> copy(currentScreen = Screen.INSTANCES, instanceContext = null)
        Screen.SERVER_PANEL -> copy(currentScreen = Screen.SERVERS, serverContext = null)
        else -> this
    }

    /**
     * Otwiera Settings screen. Bez argumentu — zachowuje ostatnio wybraną sekcję.
     * Z argumentem — ustawia konkretną sekcję (np. deep-link "Ustawienia → Wygląd").
     */
    fun openSettings(section: SettingsSection = settingsSection): NavigationState =
        copy(
            currentScreen = Screen.SETTINGS,
            settingsSection = section,
            instanceContext = null,
            serverContext = null
        )

    /** Toggle AccountOverlay popup — NIE zmienia currentScreen. */
    fun toggleAccountOverlay(): NavigationState =
        copy(accountOverlayOpen = !accountOverlayOpen)
}
