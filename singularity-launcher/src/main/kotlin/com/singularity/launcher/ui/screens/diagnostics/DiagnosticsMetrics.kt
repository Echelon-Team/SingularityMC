// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.diagnostics

import androidx.compose.runtime.Immutable

/**
 * Immutable metrics history dla MonitoringTab.
 *
 * **@Immutable annotation** — Compose pomija recomposition jeśli `equals` zwraca true.
 * FloatArray nie ma built-in content equals, więc musimy override `equals`/`hashCode`
 * manually używając `contentEquals`/`contentHashCode`.
 *
 * **FloatArray vs List<Float>** (C1 perf v2): FloatArray nie robi boxing per element,
 * co jest critical dla wykresów z 60 samples × 5 metrics = 300 samples updated co 250ms.
 *
 * **Bounded buffer:** `DiagnosticsMetricsLogic.pushX` helpers keep rolling window (drop oldest).
 */
@Immutable
class DiagnosticsMetrics(
    val fpsHistory: FloatArray,
    val tpsHistory: FloatArray,
    val ramHistory: FloatArray,
    val cpuHistory: FloatArray,
    val gpuHistory: FloatArray
) {
    companion object {
        val EMPTY = DiagnosticsMetrics(
            fpsHistory = FloatArray(0),
            tpsHistory = FloatArray(0),
            ramHistory = FloatArray(0),
            cpuHistory = FloatArray(0),
            gpuHistory = FloatArray(0)
        )
    }

    fun copy(
        fpsHistory: FloatArray = this.fpsHistory,
        tpsHistory: FloatArray = this.tpsHistory,
        ramHistory: FloatArray = this.ramHistory,
        cpuHistory: FloatArray = this.cpuHistory,
        gpuHistory: FloatArray = this.gpuHistory
    ): DiagnosticsMetrics = DiagnosticsMetrics(fpsHistory, tpsHistory, ramHistory, cpuHistory, gpuHistory)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiagnosticsMetrics) return false
        if (!fpsHistory.contentEquals(other.fpsHistory)) return false
        if (!tpsHistory.contentEquals(other.tpsHistory)) return false
        if (!ramHistory.contentEquals(other.ramHistory)) return false
        if (!cpuHistory.contentEquals(other.cpuHistory)) return false
        if (!gpuHistory.contentEquals(other.gpuHistory)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = fpsHistory.contentHashCode()
        result = 31 * result + tpsHistory.contentHashCode()
        result = 31 * result + ramHistory.contentHashCode()
        result = 31 * result + cpuHistory.contentHashCode()
        result = 31 * result + gpuHistory.contentHashCode()
        return result
    }
}

/**
 * Pure helpers dla pushing nowych sample do bounded history.
 */
object DiagnosticsMetricsLogic {

    private fun pushSample(current: FloatArray, newValue: Float, maxSamples: Int): FloatArray {
        val size = current.size
        return if (size < maxSamples) {
            FloatArray(size + 1).also {
                current.copyInto(it, 0)
                it[size] = newValue
            }
        } else {
            FloatArray(maxSamples).also {
                current.copyInto(it, 0, 1, maxSamples)
                it[maxSamples - 1] = newValue
            }
        }
    }

    fun pushFps(metrics: DiagnosticsMetrics, value: Float, maxSamples: Int = 60): DiagnosticsMetrics =
        metrics.copy(fpsHistory = pushSample(metrics.fpsHistory, value, maxSamples))

    fun pushTps(metrics: DiagnosticsMetrics, value: Float, maxSamples: Int = 60): DiagnosticsMetrics =
        metrics.copy(tpsHistory = pushSample(metrics.tpsHistory, value, maxSamples))

    fun pushRam(metrics: DiagnosticsMetrics, value: Float, maxSamples: Int = 60): DiagnosticsMetrics =
        metrics.copy(ramHistory = pushSample(metrics.ramHistory, value, maxSamples))

    fun pushCpu(metrics: DiagnosticsMetrics, value: Float, maxSamples: Int = 60): DiagnosticsMetrics =
        metrics.copy(cpuHistory = pushSample(metrics.cpuHistory, value, maxSamples))

    fun pushGpu(metrics: DiagnosticsMetrics, value: Float, maxSamples: Int = 60): DiagnosticsMetrics =
        metrics.copy(gpuHistory = pushSample(metrics.gpuHistory, value, maxSamples))

    fun pushAll(
        metrics: DiagnosticsMetrics,
        fps: Float,
        tps: Float,
        ram: Float,
        cpu: Float,
        gpu: Float,
        maxSamples: Int = 60
    ): DiagnosticsMetrics = DiagnosticsMetrics(
        fpsHistory = pushSample(metrics.fpsHistory, fps, maxSamples),
        tpsHistory = pushSample(metrics.tpsHistory, tps, maxSamples),
        ramHistory = pushSample(metrics.ramHistory, ram, maxSamples),
        cpuHistory = pushSample(metrics.cpuHistory, cpu, maxSamples),
        gpuHistory = pushSample(metrics.gpuHistory, gpu, maxSamples)
    )
}
