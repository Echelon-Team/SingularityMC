package com.singularity.launcher.ui.navigation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NavigationStateTest {

    @Test
    fun `all 10 screens defined`() {
        val screens = Screen.entries
        assertEquals(10, screens.size)
        // Top sidebar (7)
        assertTrue(screens.contains(Screen.HOME))
        assertTrue(screens.contains(Screen.INSTANCES))
        assertTrue(screens.contains(Screen.MODRINTH))
        assertTrue(screens.contains(Screen.SERVERS))
        assertTrue(screens.contains(Screen.SKINS))
        assertTrue(screens.contains(Screen.SCREENSHOTS))
        assertTrue(screens.contains(Screen.DIAGNOSTICS))
        // Bottom sidebar (1)
        assertTrue(screens.contains(Screen.SETTINGS))
        // Drill-down (2)
        assertTrue(screens.contains(Screen.INSTANCE_PANEL))
        assertTrue(screens.contains(Screen.SERVER_PANEL))
    }

    @Test
    fun `sidebar contains 8 screens`() {
        val sidebarScreens = Screen.entries.filter { it.inSidebar }
        assertEquals(8, sidebarScreens.size, "7 top + 1 bottom (Settings) = 8 sidebar items")
        assertFalse(sidebarScreens.contains(Screen.INSTANCE_PANEL), "Drill-down screens NOT in sidebar")
        assertFalse(sidebarScreens.contains(Screen.SERVER_PANEL), "Drill-down screens NOT in sidebar")
    }

    @Test
    fun `drill-down screens have correct sidebarIndicator parent`() {
        assertEquals(Screen.INSTANCES, Screen.INSTANCE_PANEL.sidebarIndicator,
            "INSTANCE_PANEL highlights INSTANCES in sidebar")
        assertEquals(Screen.SERVERS, Screen.SERVER_PANEL.sidebarIndicator,
            "SERVER_PANEL highlights SERVERS in sidebar")
    }

    @Test
    fun `non drill-down screens return themselves for sidebarIndicator`() {
        assertEquals(Screen.HOME, Screen.HOME.sidebarIndicator)
        assertEquals(Screen.SETTINGS, Screen.SETTINGS.sidebarIndicator)
        assertEquals(Screen.MODRINTH, Screen.MODRINTH.sidebarIndicator)
    }

    @Test
    fun `each screen has unique displayKey`() {
        val keys = Screen.entries.map { it.displayKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `all 5 settings sections defined`() {
        val sections = SettingsSection.entries
        assertEquals(5, sections.size)
        assertTrue(sections.contains(SettingsSection.APPEARANCE))
        assertTrue(sections.contains(SettingsSection.PERFORMANCE))
        assertTrue(sections.contains(SettingsSection.UPDATES))
        assertTrue(sections.contains(SettingsSection.INTEGRATIONS))
        assertTrue(sections.contains(SettingsSection.ADVANCED))
    }

    @Test
    fun `default navigation state is HOME with no contexts`() {
        val state = NavigationState()
        assertEquals(Screen.HOME, state.currentScreen)
        assertNull(state.instanceContext)
        assertNull(state.serverContext)
        assertEquals(SettingsSection.APPEARANCE, state.settingsSection)
        assertFalse(state.accountOverlayOpen)
    }

    @Test
    fun `navigateTo changes currentScreen and clears contexts`() {
        val state = NavigationState(
            currentScreen = Screen.INSTANCE_PANEL,
            instanceContext = "some-id"
        )
        val next = state.navigateTo(Screen.MODRINTH)
        assertEquals(Screen.MODRINTH, next.currentScreen)
        assertNull(next.instanceContext, "Context cleared on navigateTo")
        assertNull(next.serverContext)
    }

    @Test
    fun `openInstancePanel sets currentScreen to INSTANCE_PANEL and context`() {
        val state = NavigationState()
        val next = state.openInstancePanel("my-instance-id")
        assertEquals(Screen.INSTANCE_PANEL, next.currentScreen)
        assertEquals("my-instance-id", next.instanceContext)
        assertNull(next.serverContext)
    }

    @Test
    fun `openServerPanel sets currentScreen to SERVER_PANEL and context`() {
        val state = NavigationState()
        val next = state.openServerPanel("my-server-id")
        assertEquals(Screen.SERVER_PANEL, next.currentScreen)
        assertEquals("my-server-id", next.serverContext)
        assertNull(next.instanceContext)
    }

    @Test
    fun `backFromPanel from INSTANCE_PANEL returns to INSTANCES and clears context`() {
        val state = NavigationState(
            currentScreen = Screen.INSTANCE_PANEL,
            instanceContext = "foo"
        )
        val next = state.backFromPanel()
        assertEquals(Screen.INSTANCES, next.currentScreen)
        assertNull(next.instanceContext)
    }

    @Test
    fun `backFromPanel from SERVER_PANEL returns to SERVERS and clears context`() {
        val state = NavigationState(
            currentScreen = Screen.SERVER_PANEL,
            serverContext = "bar"
        )
        val next = state.backFromPanel()
        assertEquals(Screen.SERVERS, next.currentScreen)
        assertNull(next.serverContext)
    }

    @Test
    fun `backFromPanel from non-panel is no-op`() {
        val state = NavigationState(currentScreen = Screen.HOME)
        val next = state.backFromPanel()
        assertEquals(state, next, "backFromPanel on HOME should be no-op")
    }

    @Test
    fun `openSettings preserves settingsSection by default`() {
        val state = NavigationState(settingsSection = SettingsSection.PERFORMANCE)
        val next = state.openSettings()
        assertEquals(Screen.SETTINGS, next.currentScreen)
        assertEquals(SettingsSection.PERFORMANCE, next.settingsSection,
            "Preserved section when calling openSettings() without args")
    }

    @Test
    fun `openSettings with section updates settingsSection`() {
        val state = NavigationState()
        val next = state.openSettings(SettingsSection.ADVANCED)
        assertEquals(Screen.SETTINGS, next.currentScreen)
        assertEquals(SettingsSection.ADVANCED, next.settingsSection)
    }

    @Test
    fun `toggleAccountOverlay opens and closes overlay`() {
        var state = NavigationState()
        assertFalse(state.accountOverlayOpen)
        state = state.toggleAccountOverlay()
        assertTrue(state.accountOverlayOpen)
        state = state.toggleAccountOverlay()
        assertFalse(state.accountOverlayOpen)
    }

    @Test
    fun `accountOverlay can be open on any screen`() {
        val states = listOf(
            NavigationState(currentScreen = Screen.HOME),
            NavigationState(currentScreen = Screen.MODRINTH),
            NavigationState(currentScreen = Screen.INSTANCE_PANEL, instanceContext = "x"),
            NavigationState(currentScreen = Screen.SETTINGS, settingsSection = SettingsSection.ADVANCED)
        )
        for (state in states) {
            val withOverlay = state.toggleAccountOverlay()
            assertTrue(withOverlay.accountOverlayOpen,
                "Overlay should open on ${state.currentScreen}")
            assertEquals(state.currentScreen, withOverlay.currentScreen,
                "Overlay toggle does NOT change currentScreen")
        }
    }
}
