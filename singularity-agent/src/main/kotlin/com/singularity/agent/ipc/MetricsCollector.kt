// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.ipc

import com.singularity.agent.threading.ThreadingEngine
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Zbiera metryki z ThreadingEngine + JVM i emituje je przez IpcServer co 1s.
 *
 * Dane źródłowe:
 * - TPS: mierzone przez tickCompleted() callback wywoływany z TickExecutor po każdym ticku
 * - FPS: ustawiany z MC render thread przez reportFps()
 * - RAM: JVM MemoryMXBean (heap used/max)
 * - GPU: ustawiany z render hook przez reportGpuPercent()
 * - Active regions / entity count / chunks: ThreadingEngine gettery
 * - CPU per thread: JVM ThreadMXBean (czas CPU per wątek singularity-*)
 */
class MetricsCollector(
    private val ipcServer: IpcServer,
    private val threadingEngine: ThreadingEngine
) {
    private val logger = LoggerFactory.getLogger(MetricsCollector::class.java)

    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val threadBean = ManagementFactory.getThreadMXBean()

    private var scheduler: ScheduledExecutorService? = null

    // TPS tracking: zliczamy ticki w oknie 1s
    private val ticksInWindow = AtomicInteger(0)
    @Volatile private var lastTps: Float = 0f
    private var lastTpsCalcTime = System.nanoTime()

    // FPS i GPU ustawiane z render thread
    @Volatile private var currentFps: Int = 0
    @Volatile private var currentGpuPercent: Float = 0f

    // Poprzednie czasy CPU per wątek (do delta %)
    @Volatile private var previousCpuTimes = mapOf<Long, Long>()
    @Volatile private var previousCpuTimestamp = System.nanoTime()

    fun start() {
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "singularity-metrics-collector").apply { isDaemon = true }
        }
        scheduler!!.scheduleAtFixedRate(
            { collectAndBroadcast() },
            1, 1, TimeUnit.SECONDS
        )
        logger.info("MetricsCollector started (1s interval)")
    }

    /**
     * Wywoływane z TickExecutor po zakończeniu każdego ticka serwera.
     * Thread-safe — może być wołane z dowolnego wątku.
     */
    fun tickCompleted() {
        ticksInWindow.incrementAndGet()
    }

    /**
     * Ustawiany z MC render thread (client-side).
     * Na serwerze: pozostaje 0.
     */
    fun reportFps(fps: Int) {
        currentFps = fps
    }

    /**
     * Ustawiany z render hook — procent wykorzystania GPU.
     * Wymaga LWJGL query (NV_gpu_busy) lub platform-specific API.
     */
    fun reportGpuPercent(percent: Float) {
        currentGpuPercent = percent
    }

    private fun collectAndBroadcast() {
        try {
            val metrics = collect()
            ipcServer.broadcastMetrics(metrics)
        } catch (e: Exception) {
            logger.error("Metrics collection failed: {}", e.message, e)
        }
    }

    private fun collect(): IpcProtocol.MetricsPayload {
        val heap = memoryBean.heapMemoryUsage

        // TPS: ile ticków naliczono od ostatniego pomiaru
        val now = System.nanoTime()
        val ticksElapsed = ticksInWindow.getAndSet(0)
        val timeDeltaSec = (now - lastTpsCalcTime) / 1_000_000_000.0
        lastTpsCalcTime = now
        if (timeDeltaSec > 0.5) {
            lastTps = (ticksElapsed / timeDeltaSec).toFloat()
        }

        // CPU per singularity thread — delta % since last collection
        val cpuPerThread = collectCpuPerThread()

        return IpcProtocol.MetricsPayload(
            tps = lastTps,
            fps = currentFps,
            ramUsed = heap.used,
            ramMax = heap.max,
            gpuPercent = currentGpuPercent,
            activeRegions = threadingEngine.getTotalActiveRegions(),
            entityCount = threadingEngine.getTotalEntityCount(),
            loadedChunks = threadingEngine.getLoadedChunkCount(),
            pendingChunks = threadingEngine.getPendingChunkCount(),
            cpuPerThread = cpuPerThread
        )
    }

    /**
     * Oblicza % CPU per wątek singularity-* jako deltę od ostatniego pomiaru.
     * ThreadMXBean.getThreadCpuTime() zwraca nanoseconds cumulative.
     * Delta / elapsed_time * 100 = CPU% per thread.
     */
    private fun collectCpuPerThread(): List<Float> {
        val now = System.nanoTime()
        val elapsed = now - previousCpuTimestamp
        if (elapsed <= 0) return emptyList()

        val currentTimes = mutableMapOf<Long, Long>()
        val cpuPercents = mutableListOf<Float>()

        val allIds = threadBean.allThreadIds
        for (id in allIds) {
            val info = threadBean.getThreadInfo(id) ?: continue
            val name = info.threadName
            // Tylko wątki singularity (dimension pools, chunk gen/io, IPC, metrics)
            if (!name.startsWith("singularity-")) continue

            val cpuTime = threadBean.getThreadCpuTime(id)
            if (cpuTime < 0) continue
            currentTimes[id] = cpuTime

            val prevTime = previousCpuTimes[id] ?: cpuTime
            val deltaCpu = cpuTime - prevTime
            val percent = (deltaCpu.toDouble() / elapsed.toDouble() * 100.0).toFloat()
            cpuPercents.add(percent.coerceIn(0f, 100f))
        }

        previousCpuTimes = currentTimes
        previousCpuTimestamp = now

        return cpuPercents.take(32) // protocol max
    }

    fun stop() {
        scheduler?.shutdown()
        try {
            if (scheduler?.awaitTermination(2, TimeUnit.SECONDS) == false) {
                scheduler?.shutdownNow()
            }
        } catch (_: InterruptedException) {}
        logger.info("MetricsCollector stopped")
    }
}
