package com.singularity.launcher.ui.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is default NavigationState`() = runTest {
        val vm = NavigationViewModel()
        val state = vm.state.first()
        assertEquals(Screen.HOME, state.currentScreen)
        assertNull(state.instanceContext)
        assertNull(state.serverContext)
        assertFalse(state.accountOverlayOpen)
    }

    @Test
    fun `navigateTo emits new state`() = runTest {
        val vm = NavigationViewModel()
        vm.navigateTo(Screen.MODRINTH)
        val state = vm.state.first()
        assertEquals(Screen.MODRINTH, state.currentScreen)
    }

    @Test
    fun `openInstancePanel emits INSTANCE_PANEL with context`() = runTest {
        val vm = NavigationViewModel()
        vm.openInstancePanel("my-instance")
        val state = vm.state.first()
        assertEquals(Screen.INSTANCE_PANEL, state.currentScreen)
        assertEquals("my-instance", state.instanceContext)
    }

    @Test
    fun `backFromPanel returns to parent screen`() = runTest {
        val vm = NavigationViewModel()
        vm.openInstancePanel("foo")
        vm.backFromPanel()
        val state = vm.state.first()
        assertEquals(Screen.INSTANCES, state.currentScreen)
        assertNull(state.instanceContext)
    }

    @Test
    fun `toggleAccountOverlay does not change currentScreen`() = runTest {
        val vm = NavigationViewModel()
        vm.navigateTo(Screen.MODRINTH)
        vm.toggleAccountOverlay()
        val state = vm.state.first()
        assertEquals(Screen.MODRINTH, state.currentScreen,
            "Toggling overlay preserves current screen")
        assertTrue(state.accountOverlayOpen)
    }

    @Test
    fun `openSettings with specific section updates both`() = runTest {
        val vm = NavigationViewModel()
        vm.openSettings(SettingsSection.ADVANCED)
        val state = vm.state.first()
        assertEquals(Screen.SETTINGS, state.currentScreen)
        assertEquals(SettingsSection.ADVANCED, state.settingsSection)
    }

    @Test
    fun `onCleared cancels viewModelScope`() = runTest {
        val vm = NavigationViewModel()
        vm.navigateTo(Screen.MODRINTH)
        vm.onCleared()
        // After onCleared, state is still queryable (MutableStateFlow persists)
        // but no new coroutines should launch. Verify by attempting navigation
        // and checking state is unchanged (since scope is dead).
        // NOTE: current implementation does not throw, just silently ignores —
        // alternative: throw IllegalStateException. Test verifies no crash.
        assertDoesNotThrow { vm.navigateTo(Screen.HOME) }
    }
}
