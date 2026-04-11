package com.singularity.launcher.service.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mock implementation IpcClient — Sub 4 only. Generuje sinewave TPS + random FPS/CPU/RAM dla
 * UI testing bez real IPC connection do agent JVM.
 *
 * **Sub 5 Task 20 Step 2 replaces this class z real socket connection do agent IPC port.**
 * Sub 5 implementation has identical interface signature — drop-in replacement w App.kt DI.
 *
 * **Constructor scope param (C1 perf v1 fix):** scope przekazywany przez App.kt (Task 32),
 * żeby cancellation była kontrolowana przez owner — NIE internal scope który never cancelled.
 */
class IpcClientMock(
    private val scope: CoroutineScope
) : IpcClient {

    companion object {
        const val SAMPLE_INTERVAL_MS = 250L  // 4Hz
    }

    private val _metrics = MutableStateFlow<GameMetrics?>(null)
    override val metrics: StateFlow<GameMetrics?> = _metrics.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var generatorJob: Job? = null
    private var sampleTick = 0L  // for sinewave phase + linear ramp

    override fun connect(instanceId: String) {
        disconnect()  // Cancel any existing generator
        _isConnected.value = true
        sampleTick = 0L
        generatorJob = scope.launch {
            while (true) {
                _metrics.value = generateSample(sampleTick)
                sampleTick++
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    override fun disconnect() {
        generatorJob?.cancel()
        generatorJob = null
        _isConnected.value = false
        _metrics.value = null
    }

    /**
     * Generate mock sample.
     *
     * **TPS**: `19.5 + sin(tick * 0.1) * 0.5` — oscillates around 19.0..20.0
     * **FPS**: random 55..65
     * **RAM**: linear ramp 2048..4048 with wraparound, subtle variation
     * **CPU**: random 15..55 with sin modulation
     * **CPU per thread**: 8 threads z random 0..100 each
     * **GPU**: random 25..45
     */
    private fun generateSample(tick: Long): GameMetrics {
        val t = tick * 0.1  // time in arbitrary units
        val tps = 19.5 + sin(t) * 0.5
        val fps = 55.0 + Random.nextDouble() * 10.0  // 55..65
        val ramUsedMb = 2048 + ((tick % 80) * 25).toInt()  // 2048..4048, slowly grows
        val cpuPercent = 30.0 + sin(t * 0.5) * 15.0 + Random.nextDouble() * 10.0  // 15..55
        val gpuPercent = 30.0 + sin(t * 0.3) * 10.0 + Random.nextDouble() * 5.0

        val cpuPerThread = FloatArray(8) { idx ->
            val base = 20f + Random.nextFloat() * 40f
            val modulation = (sin(t + idx * 0.5) * 15f).toFloat()
            (base + modulation).coerceIn(0f, 100f)
        }

        return GameMetrics(
            tps = tps,
            fps = fps,
            ramUsedMb = ramUsedMb,
            ramTotalMb = 8192,
            cpuPercent = cpuPercent,
            cpuPercentPerThread = cpuPerThread,
            gpuPercent = gpuPercent,
            activeRegions = 12 + (tick % 5).toInt(),
            entityCount = 340 + (tick % 40).toInt(),
            chunkCount = 512 + (tick % 20).toInt(),
            suggestions = emptyList()
        )
    }
}
