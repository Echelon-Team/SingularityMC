package com.singularity.launcher.service.ipc

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

/**
 * IpcClient interface — abstrakcja dla komunikacji z agent JVM w MC process.
 *
 * **Sub 4:** `IpcClientMock` — sinewave/random mock data dla UI testing.
 * **Sub 5 Task 20 Step 2:** replaces z real socket connection do agent port.
 *
 * Interface signature musi być IDENTYCZNY między mock a real — drop-in replacement w
 * App.kt DI (Task 32).
 */
interface IpcClient {
    val metrics: StateFlow<GameMetrics?>
    val isConnected: StateFlow<Boolean>

    fun connect(instanceId: String)
    fun disconnect()
}

/**
 * Runtime metrics z agent JVM (real Sub 5) lub mock (Sub 4).
 *
 * **@Immutable** — Compose może skip recomposition gdy data nie zmieni się.
 * **FloatArray dla cpuPercentPerThread** — no boxing, consistent z RealTimeChart ChartData.
 */
@Immutable
data class GameMetrics(
    val tps: Double,                    // ticks per second (target 20)
    val fps: Double,                    // frames per second
    val ramUsedMb: Int,
    val ramTotalMb: Int,
    val cpuPercent: Double,
    val cpuPercentPerThread: FloatArray,
    val gpuPercent: Double,
    val activeRegions: Int,
    val entityCount: Int,
    val chunkCount: Int,
    val suggestions: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameMetrics) return false
        if (tps != other.tps) return false
        if (fps != other.fps) return false
        if (ramUsedMb != other.ramUsedMb) return false
        if (ramTotalMb != other.ramTotalMb) return false
        if (cpuPercent != other.cpuPercent) return false
        if (!cpuPercentPerThread.contentEquals(other.cpuPercentPerThread)) return false
        if (gpuPercent != other.gpuPercent) return false
        if (activeRegions != other.activeRegions) return false
        if (entityCount != other.entityCount) return false
        if (chunkCount != other.chunkCount) return false
        if (suggestions != other.suggestions) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tps.hashCode()
        result = 31 * result + fps.hashCode()
        result = 31 * result + ramUsedMb
        result = 31 * result + ramTotalMb
        result = 31 * result + cpuPercent.hashCode()
        result = 31 * result + cpuPercentPerThread.contentHashCode()
        result = 31 * result + gpuPercent.hashCode()
        result = 31 * result + activeRegions
        result = 31 * result + entityCount
        result = 31 * result + chunkCount
        result = 31 * result + suggestions.hashCode()
        return result
    }
}
