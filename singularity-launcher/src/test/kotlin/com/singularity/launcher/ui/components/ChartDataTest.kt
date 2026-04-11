package com.singularity.launcher.ui.components

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChartDataTest {

    @Test
    fun `empty chart data has 0 samples and 0 current value`() {
        val data = ChartData(samples = FloatArray(0), currentValue = 0f)
        assertEquals(0, data.samples.size)
        assertEquals(0f, data.currentValue)
    }

    @Test
    fun `equals uses content equality for FloatArray`() {
        val a = ChartData(floatArrayOf(1f, 2f, 3f), currentValue = 3f)
        val b = ChartData(floatArrayOf(1f, 2f, 3f), currentValue = 3f)
        val c = ChartData(floatArrayOf(1f, 2f, 4f), currentValue = 4f)

        assertEquals(a, b, "Content-equal arrays should be equal")
        assertNotEquals(a, c, "Different arrays should not be equal")
    }

    @Test
    fun `hashCode matches equals contract`() {
        val a = ChartData(floatArrayOf(1f, 2f, 3f), currentValue = 3f)
        val b = ChartData(floatArrayOf(1f, 2f, 3f), currentValue = 3f)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `pushSample creates new ChartData with value appended and rolling window`() {
        val initial = ChartData(floatArrayOf(1f, 2f, 3f), currentValue = 3f)
        val next = initial.pushSample(newValue = 4f, maxSamples = 3)

        assertArrayEquals(floatArrayOf(2f, 3f, 4f), next.samples)
        assertEquals(4f, next.currentValue)
    }

    @Test
    fun `pushSample grows until maxSamples then rolls`() {
        var data = ChartData(FloatArray(0), 0f)
        for (i in 1..5) {
            data = data.pushSample(newValue = i.toFloat(), maxSamples = 3)
        }
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), data.samples)
        assertEquals(5f, data.currentValue)
    }

    @Test
    fun `scaleY normalizes value to canvas height`() {
        val height = 100f
        // value=50, range 0..100 → mid → y = height/2 = 50 (inverted canvas — Y=0 is top)
        assertEquals(50f, RealTimeChartMath.scaleY(value = 50f, min = 0f, max = 100f, height = height), 0.01f)
        // value=0 → y = height (bottom)
        assertEquals(100f, RealTimeChartMath.scaleY(0f, 0f, 100f, height), 0.01f)
        // value=100 → y = 0 (top)
        assertEquals(0f, RealTimeChartMath.scaleY(100f, 0f, 100f, height), 0.01f)
    }

    @Test
    fun `scaleY clamps out-of-range values`() {
        val height = 100f
        assertEquals(0f, RealTimeChartMath.scaleY(150f, 0f, 100f, height), "Clamped to top")
        assertEquals(100f, RealTimeChartMath.scaleY(-50f, 0f, 100f, height), "Clamped to bottom")
    }
}
