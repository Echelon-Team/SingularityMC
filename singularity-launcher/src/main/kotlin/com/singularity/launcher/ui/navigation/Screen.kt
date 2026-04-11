package com.singularity.launcher.ui.navigation

/**
 * 10 peer screens launchera — zgodnie z decyzją D4 Mateusza 2026-04-11.
 *
 * **8 w sidebarze** (7 top + Settings bottom) + **2 drill-down** (INSTANCE_PANEL,
 * SERVER_PANEL, osiągane przez klik w kartę z listy INSTANCES/SERVERS, NIE pokazane
 * w sidebarze). `Account` NIE jest peer screen — to overlay/popup nad aktualnym
 * screen (zobacz NavigationState.accountOverlayOpen).
 *
 * `displayKey` to klucz do i18n (`strings_pl.json` / `strings_en.json` — Task 6).
 * `iconKey` to nazwa SVG z `resources/icons/` (Task 3 sidebar ładuje przez painterResource).
 * `inSidebar` — czy screen jest pokazany jako nav-item w sidebarze (drill-down = false).
 *
 * `sidebarIndicator` — który nav-item jest highlighted w sidebarze dla tego screen:
 *   - dla drill-down screens (INSTANCE_PANEL, SERVER_PANEL) — zwraca parent (INSTANCES, SERVERS)
 *   - dla wszystkich innych — zwraca self
 */
enum class Screen(
    val displayKey: String,
    val iconKey: String,
    val inSidebar: Boolean
) {
    // Top sidebar (7)
    HOME("nav.home", "home", inSidebar = true),
    INSTANCES("nav.instances", "instances", inSidebar = true),
    MODRINTH("nav.modrinth", "modrinth", inSidebar = true),
    SERVERS("nav.servers", "servers", inSidebar = true),
    SKINS("nav.skins", "skins", inSidebar = true),
    SCREENSHOTS("nav.screenshots", "screenshots", inSidebar = true),
    DIAGNOSTICS("nav.diagnostics", "diagnostics", inSidebar = true),

    // Bottom sidebar (1)
    SETTINGS("nav.settings", "settings", inSidebar = true),

    // Drill-down (2) — NIE w sidebarze, osiągane przez klik w konkretną instancję/serwer
    INSTANCE_PANEL("nav.instance_panel", "", inSidebar = false),
    SERVER_PANEL("nav.server_panel", "", inSidebar = false);

    val sidebarIndicator: Screen get() = when (this) {
        INSTANCE_PANEL -> INSTANCES
        SERVER_PANEL -> SERVERS
        else -> this
    }
}
