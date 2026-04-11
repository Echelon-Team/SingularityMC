package com.singularity.launcher.ui.screens.settings

import com.singularity.launcher.config.LauncherSettings
import com.singularity.launcher.config.UpdateChannel
import com.singularity.launcher.ui.navigation.SettingsSection
import com.singularity.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class FakeStore(initialSettings: LauncherSettings = LauncherSettings()) {
        var current = initialSettings
        var saveCount = 0
        fun load() = current
        fun save(settings: LauncherSettings) {
            current = settings
            saveCount++
        }
    }

    private fun viewModelWith(store: FakeStore): SettingsViewModel {
        return SettingsViewModel(
            loadSettings = { store.load() },
            saveSettings = { store.save(it) },
            dispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
        )
    }

    @Test
    fun `initial state loads settings`() = runTest {
        val store = FakeStore(LauncherSettings(theme = ThemeMode.AETHER, language = "en"))
        val vm = viewModelWith(store)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.first()
        assertEquals(ThemeMode.AETHER, state.settings.theme)
        assertEquals("en", state.settings.language)
    }

    @Test
    fun `setTheme updates state and saves`() = runTest {
        val store = FakeStore()
        val vm = viewModelWith(store)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setTheme(ThemeMode.AETHER)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ThemeMode.AETHER, vm.state.first().settings.theme)
        assertTrue(store.saveCount > 0)
    }

    @Test
    fun `setLanguage updates state and saves`() = runTest {
        val store = FakeStore()
        val vm = viewModelWith(store)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setLanguage("en")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("en", vm.state.first().settings.language)
    }

    @Test
    fun `setUpdateChannel updates`() = runTest {
        val store = FakeStore()
        val vm = viewModelWith(store)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setUpdateChannel(UpdateChannel.BETA)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(UpdateChannel.BETA, vm.state.first().settings.updateChannel)
    }

    @Test
    fun `setAutoCheckUpdates updates`() = runTest {
        val store = FakeStore()
        val vm = viewModelWith(store)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAutoCheckUpdates(false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.first().settings.autoCheckUpdates)
    }

    @Test
    fun `setJvmExtraArgs updates`() = runTest {
        val store = FakeStore()
        val vm = viewModelWith(store)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setJvmExtraArgs("-XX:+UseZGC")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("-XX:+UseZGC", vm.state.first().settings.jvmExtraArgs)
    }

    @Test
    fun `setDebugLogsEnabled updates`() = runTest {
        val store = FakeStore()
        val vm = viewModelWith(store)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setDebugLogsEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.first().settings.debugLogsEnabled)
    }

    @Test
    fun `setSection updates current section`() = runTest {
        val store = FakeStore()
        val vm = viewModelWith(store)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setSection(SettingsSection.ADVANCED)
        assertEquals(SettingsSection.ADVANCED, vm.state.first().currentSection)
    }

    @Test
    fun `initial section is APPEARANCE`() = runTest {
        val store = FakeStore()
        val vm = viewModelWith(store)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SettingsSection.APPEARANCE, vm.state.first().currentSection)
    }
}
