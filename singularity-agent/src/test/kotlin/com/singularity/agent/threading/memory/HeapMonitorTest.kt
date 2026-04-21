// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.memory

import com.singularity.agent.threading.config.ThreadingConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HeapMonitorTest {

    @Test
    fun `state NORMAL when usage below 80 percent`() {
        val monitor = HeapMonitor(
            config = ThreadingConfig(),
            heapProvider = { HeapMonitor.HeapInfo(usedBytes = 700, maxBytes = 1000) }
        )
        assertEquals(HeapMonitor.HeapState.NORMAL, monitor.checkAndGetState())
    }

    @Test
    fun `state AGGRESSIVE_UNLOAD when usage at 80 percent threshold`() {
        val monitor = HeapMonitor(
            config = ThreadingConfig(),
            heapProvider = { HeapMonitor.HeapInfo(usedBytes = 800, maxBytes = 1000) }
        )
        assertEquals(HeapMonitor.HeapState.AGGRESSIVE_UNLOAD, monitor.checkAndGetState())
    }

    @Test
    fun `state PAUSE_PREGEN when usage at 90 percent threshold`() {
        val monitor = HeapMonitor(
            config = ThreadingConfig(),
            heapProvider = { HeapMonitor.HeapInfo(usedBytes = 900, maxBytes = 1000) }
        )
        assertEquals(HeapMonitor.HeapState.PAUSE_PREGEN, monitor.checkAndGetState())
    }

    @Test
    fun `state PAUSE_PREGEN when usage above 90 percent`() {
        val monitor = HeapMonitor(
            config = ThreadingConfig(),
            heapProvider = { HeapMonitor.HeapInfo(usedBytes = 950, maxBytes = 1000) }
        )
        assertEquals(HeapMonitor.HeapState.PAUSE_PREGEN, monitor.checkAndGetState())
    }

    @Test
    fun `state transitions back to NORMAL when usage drops`() {
        var bytes = 950L
        val monitor = HeapMonitor(
            config = ThreadingConfig(),
            heapProvider = { HeapMonitor.HeapInfo(usedBytes = bytes, maxBytes = 1000) }
        )

        assertEquals(HeapMonitor.HeapState.PAUSE_PREGEN, monitor.checkAndGetState())
        bytes = 500
        assertEquals(HeapMonitor.HeapState.NORMAL, monitor.checkAndGetState())
    }

    @Test
    fun `usagePercent returns correct ratio`() {
        val monitor = HeapMonitor(
            config = ThreadingConfig(),
            heapProvider = { HeapMonitor.HeapInfo(usedBytes = 850, maxBytes = 1000) }
        )
        assertEquals(0.85, monitor.usagePercent(), 0.001)
    }

    @Test
    fun `shouldPauseGeneration true at 90 percent`() {
        val monitor = HeapMonitor(
            config = ThreadingConfig(),
            heapProvider = { HeapMonitor.HeapInfo(usedBytes = 900, maxBytes = 1000) }
        )
        assertTrue(monitor.shouldPauseGeneration())
    }

    @Test
    fun `shouldAggressiveUnload true at 80 percent`() {
        val monitor = HeapMonitor(
            config = ThreadingConfig(),
            heapProvider = { HeapMonitor.HeapInfo(usedBytes = 800, maxBytes = 1000) }
        )
        assertTrue(monitor.shouldAggressiveUnload())
    }
}
