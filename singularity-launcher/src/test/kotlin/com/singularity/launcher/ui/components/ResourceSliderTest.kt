// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResourceSliderTest {

    @Test
    fun `resourceSliderColorLevel returns GREEN below 60pct`() {
        assertEquals(ResourceLevel.GREEN, resourceSliderColorLevel(value = 4000, maxValue = 8000))
        assertEquals(ResourceLevel.GREEN, resourceSliderColorLevel(value = 4799, maxValue = 8000))
    }

    @Test
    fun `resourceSliderColorLevel returns YELLOW between 60 and 85pct`() {
        assertEquals(ResourceLevel.YELLOW, resourceSliderColorLevel(value = 4800, maxValue = 8000))  // 60%
        assertEquals(ResourceLevel.YELLOW, resourceSliderColorLevel(value = 6500, maxValue = 8000))  // 81%
        assertEquals(ResourceLevel.YELLOW, resourceSliderColorLevel(value = 6799, maxValue = 8000))  // 84.98%
    }

    @Test
    fun `resourceSliderColorLevel returns RED at or above 85pct`() {
        assertEquals(ResourceLevel.RED, resourceSliderColorLevel(value = 6800, maxValue = 8000))  // 85%
        assertEquals(ResourceLevel.RED, resourceSliderColorLevel(value = 8000, maxValue = 8000))  // 100%
    }

    @Test
    fun `formatMBasGB converts MB to GB string`() {
        assertEquals("8 GB", formatMBasGB(8192))
        assertEquals("4 GB", formatMBasGB(4096))
        assertEquals("16 GB", formatMBasGB(16384))
    }

    @Test
    fun `formatMBasGB rounds to 1 decimal when not whole GB`() {
        assertEquals("8.5 GB", formatMBasGB(8704))  // 8704 / 1024 = 8.5
        assertEquals("6.7 GB", formatMBasGB(6861))  // 6861 / 1024 ≈ 6.7
    }

    @Test
    fun `threadsLowWarning true for threads less than or equal 4`() {
        assertTrue(threadsLowWarning(threads = 2))
        assertTrue(threadsLowWarning(threads = 4))
        assertFalse(threadsLowWarning(threads = 5))
        assertFalse(threadsLowWarning(threads = 8))
    }
}
