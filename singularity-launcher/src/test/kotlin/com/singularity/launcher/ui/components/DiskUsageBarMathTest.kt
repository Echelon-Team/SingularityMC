// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DiskUsageBarMathTest {

    @Test
    fun `segments sum to 100 percent when totals match`() {
        val segments = DiskUsageBarMath.calculateSegmentWidths(
            totalGB = 10f,
            worldsGB = 2f,
            modsGB = 1f,
            backupsGB = 3f,
            configGB = 0.5f
        )
        val sum = segments.worldsFraction + segments.modsFraction + segments.backupsFraction +
                  segments.configFraction + segments.freeFraction
        assertEquals(1f, sum, 0.001f)
    }

    @Test
    fun `all segments are zero when total is zero`() {
        val segments = DiskUsageBarMath.calculateSegmentWidths(
            totalGB = 0f, worldsGB = 0f, modsGB = 0f, backupsGB = 0f, configGB = 0f
        )
        assertEquals(0f, segments.worldsFraction)
        assertEquals(0f, segments.modsFraction)
        assertEquals(0f, segments.backupsFraction)
        assertEquals(0f, segments.configFraction)
        assertEquals(1f, segments.freeFraction, "Zero usage = 100% free")
    }

    @Test
    fun `used exceeds total clamps free to zero`() {
        val segments = DiskUsageBarMath.calculateSegmentWidths(
            totalGB = 5f,
            worldsGB = 3f,
            modsGB = 2f,
            backupsGB = 2f,
            configGB = 0f
        )
        assertEquals(0f, segments.freeFraction, "Free never negative")
    }

    @Test
    fun `formatGB produces readable strings`() {
        assertEquals("1.5 GB", DiskUsageBarMath.formatGB(1.5f))
        assertEquals("10.0 GB", DiskUsageBarMath.formatGB(10f))
        assertEquals("0.5 GB", DiskUsageBarMath.formatGB(0.5f))
    }
}
