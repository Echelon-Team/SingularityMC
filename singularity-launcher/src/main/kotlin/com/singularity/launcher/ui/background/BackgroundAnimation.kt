// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.background

import kotlin.math.sin

/**
 * Pure helpers dla background animations — testowalne bez Compose.
 */
object BackgroundAnimationLogic {

    /**
     * Gwiazdy: sin pulse 0.3..1.0 opacity z phase 0..2π.
     */
    fun computeStarOpacity(phase: Double): Float {
        val raw = 0.65f + 0.35f * sin(phase).toFloat()
        return raw.coerceIn(0.3f, 1.0f)
    }

    /**
     * Chmury Aether: gentle X offset -10..+10 px z sin.
     */
    fun computeCloudOffset(phase: Double): Float = 10f * sin(phase).toFloat()

    /**
     * S2 perf v2 + D9 decyzja: animacje pauzują gdy window nie ma focusa.
     */
    fun shouldAnimate(isWindowFocused: Boolean): Boolean = isWindowFocused
}
