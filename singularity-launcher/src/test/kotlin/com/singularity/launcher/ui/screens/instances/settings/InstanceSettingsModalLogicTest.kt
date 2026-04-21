// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances.settings

import com.singularity.launcher.config.InstanceRuntimeSettings
import com.singularity.launcher.config.ManualThreadConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InstanceSettingsModalLogicTest {

    @Test
    fun `initial state is not dirty`() {
        val settings = InstanceRuntimeSettings()
        val state = InstanceSettingsModalState(
            originalSettings = settings,
            workingSettings = settings
        )
        assertFalse(state.isDirty, "Same original and working — not dirty")
    }

    @Test
    fun `changing workingSettings marks dirty`() {
        val original = InstanceRuntimeSettings(regionSize = 8)
        val working = original.copy(regionSize = 12)
        val state = InstanceSettingsModalState(
            originalSettings = original,
            workingSettings = working
        )
        assertTrue(state.isDirty, "Different working from original — dirty")
    }

    @Test
    fun `revert restores working to original`() {
        val original = InstanceRuntimeSettings(regionSize = 8)
        val working = original.copy(regionSize = 16, memoryThresholdPercent = 70)
        var state = InstanceSettingsModalState(
            originalSettings = original,
            workingSettings = working
        )
        state = InstanceSettingsModalLogic.revert(state)
        assertEquals(original, state.workingSettings)
        assertFalse(state.isDirty)
    }

    @Test
    fun `commit sets working as new original`() {
        val original = InstanceRuntimeSettings(regionSize = 8)
        val working = original.copy(regionSize = 16)
        var state = InstanceSettingsModalState(
            originalSettings = original,
            workingSettings = working
        )
        state = InstanceSettingsModalLogic.commit(state)
        assertEquals(working, state.originalSettings)
        assertFalse(state.isDirty, "After commit nothing to save")
    }

    @Test
    fun `switchTab changes current tab`() {
        var state = InstanceSettingsModalState(
            originalSettings = InstanceRuntimeSettings(),
            workingSettings = InstanceRuntimeSettings()
        )
        state = InstanceSettingsModalLogic.switchTab(state, InstanceSettingsTab.THREADING)
        assertEquals(InstanceSettingsTab.THREADING, state.currentTab)
    }

    @Test
    fun `toggleManualThreadMode enables manual config with sensible defaults`() {
        var state = InstanceSettingsModalState(
            originalSettings = InstanceRuntimeSettings(),
            workingSettings = InstanceRuntimeSettings()
        )
        assertNull(state.workingSettings.manualThreadConfig, "Auto by default")

        state = InstanceSettingsModalLogic.toggleManualThreadMode(state, true)
        assertNotNull(state.workingSettings.manualThreadConfig)
        assertEquals(4, state.workingSettings.manualThreadConfig!!.overworldTick, "Default overworldTick = 4")
    }

    @Test
    fun `toggleManualThreadMode disable clears manual config`() {
        val initial = InstanceRuntimeSettings(manualThreadConfig = ManualThreadConfig())
        var state = InstanceSettingsModalState(
            originalSettings = initial,
            workingSettings = initial
        )
        state = InstanceSettingsModalLogic.toggleManualThreadMode(state, false)
        assertNull(state.workingSettings.manualThreadConfig, "Disabled = null (auto)")
    }

    @Test
    fun `updateRegionSize updates working`() {
        var state = InstanceSettingsModalState(
            originalSettings = InstanceRuntimeSettings(),
            workingSettings = InstanceRuntimeSettings()
        )
        state = InstanceSettingsModalLogic.updateRegionSize(state, 16)
        assertEquals(16, state.workingSettings.regionSize)
    }

    @Test
    fun `updateGpuAcceleration updates working`() {
        var state = InstanceSettingsModalState(
            originalSettings = InstanceRuntimeSettings(),
            workingSettings = InstanceRuntimeSettings()
        )
        state = InstanceSettingsModalLogic.updateGpuAcceleration(state, true)
        assertTrue(state.workingSettings.gpuAcceleration)
    }

    @Test
    fun `updateMemoryThreshold clamps to 50 min`() {
        var state = InstanceSettingsModalState(
            originalSettings = InstanceRuntimeSettings(),
            workingSettings = InstanceRuntimeSettings()
        )
        state = InstanceSettingsModalLogic.updateMemoryThreshold(state, 40)
        assertEquals(50, state.workingSettings.memoryThresholdPercent, "Clamped to 50 min")
    }

    @Test
    fun `updateMemoryThreshold clamps to 95 max`() {
        var state = InstanceSettingsModalState(
            originalSettings = InstanceRuntimeSettings(),
            workingSettings = InstanceRuntimeSettings()
        )
        state = InstanceSettingsModalLogic.updateMemoryThreshold(state, 100)
        assertEquals(95, state.workingSettings.memoryThresholdPercent, "Clamped to 95 max")
    }

    @Test
    fun `allowed region sizes returns 5 options`() {
        val sizes = InstanceSettingsModalLogic.allowedRegionSizes
        assertEquals(listOf(4, 6, 8, 12, 16), sizes)
    }

    @Test
    fun `updateOverworldThreads updates manual config`() {
        val initial = InstanceRuntimeSettings(manualThreadConfig = ManualThreadConfig())
        var state = InstanceSettingsModalState(
            originalSettings = initial,
            workingSettings = initial
        )
        state = InstanceSettingsModalLogic.updateManualThreads(state) { it.copy(overworldTick = 7) }
        assertEquals(7, state.workingSettings.manualThreadConfig!!.overworldTick)
    }

    @Test
    fun `updateManualThreads is no-op when manual config is null`() {
        var state = InstanceSettingsModalState(
            originalSettings = InstanceRuntimeSettings(),
            workingSettings = InstanceRuntimeSettings()
        )
        state = InstanceSettingsModalLogic.updateManualThreads(state) { it.copy(overworldTick = 7) }
        assertNull(state.workingSettings.manualThreadConfig, "No manual config to update — no-op")
    }
}
