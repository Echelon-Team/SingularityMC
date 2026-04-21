// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.components

import androidx.compose.runtime.Immutable

/**
 * Immutable chart data — @Immutable oznacza Compose że może skip recomposition
 * gdy data się nie zmienia (content-based equals).
 *
 * **FloatArray** zamiast `List<Float>` eliminuje boxing 60 Float obj per update
 * × 5 charts w MonitoringTab = ~300 less allocations/s (C1 perf v2).
 *
 * **equals/hashCode** content-based (Kotlin `data class` dla FloatArray daje
 * reference-based equality domyślnie — musimy override).
 */
@Immutable
class ChartData(
    val samples: FloatArray,
    val currentValue: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChartData) return false
        if (currentValue != other.currentValue) return false
        if (!samples.contentEquals(other.samples)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + currentValue.hashCode()
        return result
    }

    /**
     * Push nowy sample, rolling window maxSamples. Returns new ChartData
     * (immutable — nie mutuje tego instance).
     *
     * **Performance**: `copyOfRange + plus` alokuje 2 arrays — akceptowalne dla
     * 60 samples × 1Hz update rate. Jeśli rate rośnie, zastąp mutable `ArrayDeque<Float>`
     * w ViewModel + snapshot do FloatArray tylko przy StateFlow emit.
     */
    fun pushSample(newValue: Float, maxSamples: Int): ChartData {
        val newSamples = if (samples.size < maxSamples) {
            samples + newValue
        } else {
            // Roll: drop first, append new
            val rolled = FloatArray(maxSamples)
            System.arraycopy(samples, 1, rolled, 0, maxSamples - 1)
            rolled[maxSamples - 1] = newValue
            rolled
        }
        return ChartData(newSamples, newValue)
    }

    companion object {
        val EMPTY = ChartData(FloatArray(0), 0f)
    }
}

/**
 * Pure math helpers dla chart scaling — testowalne bez Compose Canvas env.
 */
object RealTimeChartMath {
    /**
     * Skaluje wartość do współrzędnej Y na canvas (0 = top, height = bottom).
     * Clamps wartości poza range do krawędzi.
     */
    fun scaleY(value: Float, min: Float, max: Float, height: Float): Float {
        val range = (max - min).coerceAtLeast(0.001f)
        val normalized = ((value - min) / range).coerceIn(0f, 1f)
        return height * (1f - normalized)  // Y inverted: 0 top, height bottom
    }
}
