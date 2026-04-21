// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.memory

import com.singularity.agent.threading.config.ThreadingConfig
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory

/**
 * Monitor pamięci heap.
 *
 * Design spec 4.8.5:
 * - 80% heap → aggressive unloading chunków poza view distance
 * - 90% heap → pauza pre-generacji chunków
 * - OOM mimo to → crash + crash analyzer
 *
 * heapProvider mock'owalny do testów. Default: MemoryMXBean.
 */
class HeapMonitor(
    private val config: ThreadingConfig,
    private val heapProvider: () -> HeapInfo = { defaultHeapInfo() }
) {
    private val logger = LoggerFactory.getLogger(HeapMonitor::class.java)

    data class HeapInfo(val usedBytes: Long, val maxBytes: Long)

    enum class HeapState {
        NORMAL,
        AGGRESSIVE_UNLOAD,
        PAUSE_PREGEN
    }

    fun checkAndGetState(): HeapState {
        val usage = usagePercent()
        val state = when {
            usage >= config.heapPauseGenerationThreshold -> HeapState.PAUSE_PREGEN
            usage >= config.heapAggressiveUnloadThreshold -> HeapState.AGGRESSIVE_UNLOAD
            else -> HeapState.NORMAL
        }
        if (state != HeapState.NORMAL) {
            logger.debug("Heap state: {} (usage {}%)", state, (usage * 100).toInt())
        }
        return state
    }

    fun usagePercent(): Double {
        val info = heapProvider()
        return info.usedBytes.toDouble() / info.maxBytes.toDouble()
    }

    fun shouldAggressiveUnload(): Boolean =
        checkAndGetState() != HeapState.NORMAL

    fun shouldPauseGeneration(): Boolean =
        checkAndGetState() == HeapState.PAUSE_PREGEN

    companion object {
        private fun defaultHeapInfo(): HeapInfo {
            val bean = ManagementFactory.getMemoryMXBean()
            val heap = bean.heapMemoryUsage
            return HeapInfo(usedBytes = heap.used, maxBytes = heap.max)
        }
    }
}
