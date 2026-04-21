// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class StatusIndicatorTest {

    @Test
    fun `Status enum has 6 states`() {
        val values = Status.entries
        assertEquals(6, values.size)
        assertTrue(values.contains(Status.SUCCESS))
        assertTrue(values.contains(Status.WARNING))
        assertTrue(values.contains(Status.ERROR))
        assertTrue(values.contains(Status.INFO))
        assertTrue(values.contains(Status.IDLE))
        assertTrue(values.contains(Status.LOADING))
    }

    @Test
    fun `LOADING status is animated (pulsing)`() {
        assertTrue(Status.LOADING.isAnimated, "LOADING state must have pulsing animation")
    }

    @Test
    fun `SUCCESS ERROR INFO IDLE are not animated`() {
        assertFalse(Status.SUCCESS.isAnimated)
        assertFalse(Status.ERROR.isAnimated)
        assertFalse(Status.INFO.isAnimated)
        assertFalse(Status.IDLE.isAnimated)
    }

    @Test
    fun `WARNING is not animated`() {
        // Prototyp: WARNING (yellow) nie pulsuje. Tylko LOADING (także yellow) pulsuje.
        assertFalse(Status.WARNING.isAnimated)
    }
}
