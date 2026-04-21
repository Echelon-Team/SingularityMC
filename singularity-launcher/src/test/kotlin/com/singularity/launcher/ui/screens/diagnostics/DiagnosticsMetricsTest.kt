// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.diagnostics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DiagnosticsMetricsTest {

    @Test
    fun `empty metrics has zero length arrays`() {
        val metrics = DiagnosticsMetrics.EMPTY
        assertEquals(0, metrics.fpsHistory.size)
        assertEquals(0, metrics.tpsHistory.size)
        assertEquals(0, metrics.ramHistory.size)
    }

    @Test
    fun `pushSample adds value to history and respects maxSize`() {
        var metrics = DiagnosticsMetrics.EMPTY
        repeat(150) { idx ->
            metrics = DiagnosticsMetricsLogic.pushFps(metrics, idx.toFloat(), maxSamples = 100)
        }
        assertEquals(100, metrics.fpsHistory.size, "Bounded buffer 100")
        assertEquals(50f, metrics.fpsHistory.first(), 0.001f, "Oldest dropped, first is 50")
        assertEquals(149f, metrics.fpsHistory.last(), 0.001f)
    }

    @Test
    fun `equals uses content equals for FloatArray`() {
        val a = DiagnosticsMetrics(
            fpsHistory = floatArrayOf(60f, 61f, 62f),
            tpsHistory = floatArrayOf(20f, 20f, 20f),
            ramHistory = floatArrayOf(2048f, 2100f, 2050f),
            cpuHistory = floatArrayOf(40f, 45f, 42f),
            gpuHistory = floatArrayOf(35f, 38f, 36f)
        )
        val b = DiagnosticsMetrics(
            fpsHistory = floatArrayOf(60f, 61f, 62f),
            tpsHistory = floatArrayOf(20f, 20f, 20f),
            ramHistory = floatArrayOf(2048f, 2100f, 2050f),
            cpuHistory = floatArrayOf(40f, 45f, 42f),
            gpuHistory = floatArrayOf(35f, 38f, 36f)
        )
        assertEquals(a, b, "Same content = equal (content equals override)")
    }

    @Test
    fun `hashCode consistent with equals`() {
        val a = DiagnosticsMetrics(
            fpsHistory = floatArrayOf(60f, 61f),
            tpsHistory = floatArrayOf(20f, 20f),
            ramHistory = floatArrayOf(2048f, 2100f),
            cpuHistory = floatArrayOf(40f, 45f),
            gpuHistory = floatArrayOf(35f, 38f)
        )
        val b = a.copy()
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different content returns different equals`() {
        val a = DiagnosticsMetrics(
            fpsHistory = floatArrayOf(60f),
            tpsHistory = floatArrayOf(20f),
            ramHistory = floatArrayOf(2048f),
            cpuHistory = floatArrayOf(40f),
            gpuHistory = floatArrayOf(35f)
        )
        val b = DiagnosticsMetrics(
            fpsHistory = floatArrayOf(65f),  // different
            tpsHistory = floatArrayOf(20f),
            ramHistory = floatArrayOf(2048f),
            cpuHistory = floatArrayOf(40f),
            gpuHistory = floatArrayOf(35f)
        )
        assertNotEquals(a, b)
    }
}
