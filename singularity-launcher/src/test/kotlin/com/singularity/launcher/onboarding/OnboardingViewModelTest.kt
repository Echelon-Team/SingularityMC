// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun teardown() { Dispatchers.resetMain() }

    private fun createViewModel(): OnboardingViewModel {
        return OnboardingViewModel(HardwareDetector())
    }

    @Test
    fun `initial step is WELCOME`() {
        val vm = createViewModel()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.currentStep)
    }

    @Test
    fun `next advances step`() {
        val vm = createViewModel()
        vm.next()
        assertEquals(OnboardingStep.LOGIN, vm.state.value.currentStep)
    }

    @Test
    fun `back goes to previous step`() {
        val vm = createViewModel()
        vm.next() // → LOGIN
        vm.next() // → HARDWARE_DETECT
        vm.back() // → LOGIN
        assertEquals(OnboardingStep.LOGIN, vm.state.value.currentStep)
    }

    @Test
    fun `back from WELCOME stays at WELCOME`() {
        val vm = createViewModel()
        vm.back()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.currentStep)
    }

    @Test
    fun `skipTutorial jumps to FIRST_INSTANCE`() {
        val vm = createViewModel()
        vm.next() // → LOGIN
        vm.next() // → HARDWARE_DETECT
        vm.next() // → TELEMETRY
        vm.next() // → TUTORIAL
        vm.skipTutorial()
        assertEquals(OnboardingStep.FIRST_INSTANCE, vm.state.value.currentStep)
    }

    @Test
    fun `setLoginComplete updates state`() {
        val vm = createViewModel()
        assertFalse(vm.state.value.loginComplete)
        vm.setLoginComplete("TestPlayer")
        assertTrue(vm.state.value.loginComplete)
    }

    @Test
    fun `setTelemetryAccepted updates state`() {
        val vm = createViewModel()
        assertNull(vm.state.value.telemetryAccepted)
        vm.setTelemetryAccepted(true)
        assertTrue(vm.state.value.telemetryAccepted!!)
        vm.setTelemetryAccepted(false)
        assertFalse(vm.state.value.telemetryAccepted!!)
    }

    @Test
    fun `full wizard flow reaches COMPLETE`() {
        val vm = createViewModel()
        // WELCOME → LOGIN → HARDWARE → TELEMETRY → TUTORIAL → FIRST_INSTANCE → COMPLETE
        repeat(6) { vm.next() }
        assertEquals(OnboardingStep.COMPLETE, vm.state.value.currentStep)
    }
}
