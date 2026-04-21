// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.background

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BackgroundAnimationTest {

    @Test
    fun `computeStarOpacity cycles 0_3 to 1_0`() {
        // At phase 0 → 0.65 + 0.35*sin(0) = 0.65 (middle of range, clamped to 0.3..1.0)
        assertEquals(0.65f, BackgroundAnimationLogic.computeStarOpacity(0.0), 0.01f)
        // At phase π/2 → 0.65 + 0.35*1 = 1.0 (max, sin=1)
        assertEquals(1.0f, BackgroundAnimationLogic.computeStarOpacity(Math.PI / 2), 0.01f)
        // At phase -π/2 → 0.65 + 0.35*(-1) = 0.3 (min, sin=-1, clamped)
        assertEquals(0.3f, BackgroundAnimationLogic.computeStarOpacity(-Math.PI / 2), 0.01f)
    }

    @Test
    fun `computeCloudOffset oscillates -10 to 10`() {
        assertEquals(0f, BackgroundAnimationLogic.computeCloudOffset(0.0), 0.01f)
        assertEquals(10f, BackgroundAnimationLogic.computeCloudOffset(Math.PI / 2), 0.01f)
        assertEquals(-10f, BackgroundAnimationLogic.computeCloudOffset(3 * Math.PI / 2), 0.01f)
    }

    @Test
    fun `shouldAnimate true when focused`() {
        assertTrue(BackgroundAnimationLogic.shouldAnimate(isWindowFocused = true))
    }

    @Test
    fun `shouldAnimate false when not focused (S2 perf v2)`() {
        assertFalse(BackgroundAnimationLogic.shouldAnimate(isWindowFocused = false))
    }
}
