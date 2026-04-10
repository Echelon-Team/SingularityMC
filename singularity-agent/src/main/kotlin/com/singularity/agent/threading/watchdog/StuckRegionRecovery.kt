package com.singularity.agent.threading.watchdog

import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.region.RegionId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recovery strategy for stuck regions — exponential backoff (TCP congestion control inspired).
 */
class StuckRegionRecovery(private val config: ThreadingConfig) {
    private val logger = LoggerFactory.getLogger(StuckRegionRecovery::class.java)
    private val stuckCounts = ConcurrentHashMap<RegionId, AtomicInteger>()

    fun computeFallbackTicks(stuckCount: Int): Int {
        if (stuckCount <= 0) return 0
        val multiplier = 1 shl (stuckCount - 1)
        val ticks = config.stuckFallbackTicks.toLong() * multiplier
        return ticks.coerceAtMost(config.stuckFallbackMaxTicks.toLong()).toInt()
    }

    fun recordStuck(region: RegionId): Int {
        val newCount = stuckCounts.computeIfAbsent(region) { AtomicInteger(0) }.incrementAndGet()
        logger.warn("Region {} stuck count incremented to {}", region, newCount)
        return newCount
    }

    fun recordStable(region: RegionId) { stuckCounts.remove(region) }

    fun getStuckCount(region: RegionId): Int = stuckCounts[region]?.get() ?: 0
}
