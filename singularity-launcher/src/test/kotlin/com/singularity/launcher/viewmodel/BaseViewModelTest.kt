package com.singularity.launcher.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class BaseViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private class TestViewModel : BaseViewModel<String>("initial") {
        fun update(newValue: String) = updateState { newValue }
        fun replace(newValue: String) = setState(newValue)
        suspend fun launchWork(): Boolean {
            var executed = false
            viewModelScope.launch { executed = true }
            return executed
        }
        val scope get() = viewModelScope
    }

    @Test
    fun `initial state is provided value`() = runTest {
        val vm = TestViewModel()
        assertEquals("initial", vm.state.first())
    }

    @Test
    fun `updateState emits new value`() = runTest {
        val vm = TestViewModel()
        vm.update("updated")
        assertEquals("updated", vm.state.first())
    }

    @Test
    fun `setState replaces value directly`() = runTest {
        val vm = TestViewModel()
        vm.replace("replaced")
        assertEquals("replaced", vm.state.first())
    }

    @Test
    fun `onCleared cancels viewModelScope`() = runTest {
        val vm = TestViewModel()
        assertTrue(vm.scope.isActive)
        vm.onCleared()
        assertFalse(vm.scope.isActive, "viewModelScope must be cancelled after onCleared()")
    }
}
