package com.singularity.agent.threading.watchdog

import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.region.Region
import com.singularity.agent.threading.region.RegionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RegionWatchdogTest {

    @Test
    fun `markStarted records region tracking`() {
        val watchdog = RegionWatchdog(ThreadingConfig())
        val region = Region(RegionId(0, 0), "overworld")
        watchdog.markStarted(region, Thread.currentThread())
        assertTrue(watchdog.isTracked(region))
    }

    @Test
    fun `markCompleted removes region and resets shouldAbort`() {
        val watchdog = RegionWatchdog(ThreadingConfig())
        val region = Region(RegionId(0, 0), "overworld")
        region.shouldAbort = true
        watchdog.markStarted(region, Thread.currentThread())
        watchdog.markCompleted(region)
        assertFalse(watchdog.isTracked(region))
        assertFalse(region.shouldAbort)
    }

    @Test
    fun `findStuckRegions returns regions exceeding timeout`() {
        val config = ThreadingConfig(barrierTimeoutMs = 100)
        val watchdog = RegionWatchdog(config)
        val region1 = Region(RegionId(0, 0), "overworld")
        val region2 = Region(RegionId(1, 0), "overworld")
        watchdog.markStarted(region1, Thread.currentThread())
        Thread.sleep(150)
        watchdog.markStarted(region2, Thread.currentThread())
        val stuck = watchdog.findStuckRegions()
        assertEquals(1, stuck.size)
        assertEquals(RegionId(0, 0), stuck[0].region.id)
    }

    @Test
    fun `findStuckRegions empty when nothing stuck`() {
        val watchdog = RegionWatchdog(ThreadingConfig(barrierTimeoutMs = 5000))
        val region = Region(RegionId(0, 0), "overworld")
        watchdog.markStarted(region, Thread.currentThread())
        assertTrue(watchdog.findStuckRegions().isEmpty())
    }

    @Test
    fun `requestAbort sets shouldAbort flag`() {
        val watchdog = RegionWatchdog(ThreadingConfig())
        val region = Region(RegionId(0, 0), "overworld")
        assertFalse(region.shouldAbort)
        watchdog.requestAbort(region)
        assertTrue(region.shouldAbort)
    }

    @Test
    fun `recovery exponential backoff doubles fallback ticks`() {
        val config = ThreadingConfig(stuckFallbackTicks = 20, stuckFallbackMaxTicks = 200)
        val recovery = StuckRegionRecovery(config)
        assertEquals(20, recovery.computeFallbackTicks(stuckCount = 1))
        assertEquals(40, recovery.computeFallbackTicks(stuckCount = 2))
        assertEquals(80, recovery.computeFallbackTicks(stuckCount = 3))
        assertEquals(160, recovery.computeFallbackTicks(stuckCount = 4))
    }

    @Test
    fun `recovery caps at maxTicks`() {
        val config = ThreadingConfig(stuckFallbackTicks = 20, stuckFallbackMaxTicks = 100)
        val recovery = StuckRegionRecovery(config)
        assertEquals(100, recovery.computeFallbackTicks(stuckCount = 4))
        assertEquals(100, recovery.computeFallbackTicks(stuckCount = 10))
    }

    @Test
    fun `recovery resets after stable period`() {
        val recovery = StuckRegionRecovery(ThreadingConfig())
        val region = RegionId(0, 0)
        recovery.recordStuck(region)
        recovery.recordStuck(region)
        assertEquals(2, recovery.getStuckCount(region))
        recovery.recordStable(region)
        assertEquals(0, recovery.getStuckCount(region))
    }
}
