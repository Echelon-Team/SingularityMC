// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.loadingscreen

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LoadingScreenStateTest {

    @Test
    fun `initial state is zero progress`() {
        val state = LoadingScreenState()
        assertEquals(0, state.getProgress())
        assertEquals("Inicjalizacja...", state.getCurrentStage())
        assertFalse(state.finished)
    }

    @Test
    fun `setProgress clamps to 0-100`() {
        val state = LoadingScreenState()
        state.setProgress(-10)
        assertEquals(0, state.getProgress())
        state.setProgress(150)
        assertEquals(100, state.getProgress())
        state.setProgress(42)
        assertEquals(42, state.getProgress())
    }

    @Test
    fun `setCurrentStage updates text`() {
        val state = LoadingScreenState()
        state.setCurrentStage("Loading mods...")
        assertEquals("Loading mods...", state.getCurrentStage())
    }

    @Test
    fun `updateModStatus tracks mods`() {
        val state = LoadingScreenState()
        state.updateModStatus("sodium", "Sodium", LoadingScreenState.ModStatus.PENDING)
        state.updateModStatus("lithium", "Lithium", LoadingScreenState.ModStatus.LOADED)

        assertEquals(2, state.getTotalModCount())
        assertEquals(1, state.getLoadedModCount())

        val mods = state.getMods()
        assertEquals(2, mods.size)
        // sorted by modId
        assertEquals("lithium", mods[0].modId)
        assertEquals("sodium", mods[1].modId)
    }

    @Test
    fun `updateModStatus overwrites existing entry`() {
        val state = LoadingScreenState()
        state.updateModStatus("sodium", "Sodium", LoadingScreenState.ModStatus.PENDING)
        assertEquals(LoadingScreenState.ModStatus.PENDING, state.getMods().first().status)

        state.updateModStatus("sodium", "Sodium", LoadingScreenState.ModStatus.LOADED)
        assertEquals(1, state.getTotalModCount())
        assertEquals(LoadingScreenState.ModStatus.LOADED, state.getMods().first().status)
    }

    @Test
    fun `conflictCount tracks conflicts`() {
        val state = LoadingScreenState()
        assertEquals(0, state.getConflictCount())
        state.setConflictCount(3)
        assertEquals(3, state.getConflictCount())
    }

    @Test
    fun `slowModWarning tracks warning`() {
        val state = LoadingScreenState()
        assertNull(state.getSlowModWarning())
        state.setSlowModWarning("heavy-mod")
        assertEquals("heavy-mod", state.getSlowModWarning())
        state.setSlowModWarning(null)
        assertNull(state.getSlowModWarning())
    }

    @Test
    fun `currentTip tracks tip text`() {
        val state = LoadingScreenState()
        assertNull(state.getCurrentTip())
        state.setCurrentTip("Did you know?")
        assertEquals("Did you know?", state.getCurrentTip())
    }

    @Test
    fun `markFinished sets progress to 100 and finished flag`() {
        val state = LoadingScreenState()
        state.setProgress(50)
        assertFalse(state.finished)

        state.markFinished()
        assertTrue(state.finished)
        assertEquals(100, state.getProgress())
        assertEquals("Gotowe", state.getCurrentStage())
    }
}
