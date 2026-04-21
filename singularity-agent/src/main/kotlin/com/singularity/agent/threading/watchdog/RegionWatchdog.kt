// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.watchdog

import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.region.Region
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Watchdog monitoring region worker threads.
 *
 * ADDENDUM v2: Two-stage recovery:
 * 1. Cooperative abort: set region.shouldAbort = true (checked at safe points)
 * 2. Hard interrupt: only after cooperative abort ignored for 2x timeout
 */
class RegionWatchdog(private val config: ThreadingConfig) {
    private val logger = LoggerFactory.getLogger(RegionWatchdog::class.java)

    data class TrackedRegion(
        val region: Region,
        val owningThread: Thread,
        val startTimeMs: Long
    )

    private val tracked = ConcurrentHashMap<Region, TrackedRegion>()

    fun markStarted(region: Region, thread: Thread) {
        tracked[region] = TrackedRegion(region, thread, System.currentTimeMillis())
    }

    fun markCompleted(region: Region) {
        tracked.remove(region)
        region.shouldAbort = false // reset for next tick
    }

    fun isTracked(region: Region): Boolean = tracked.containsKey(region)

    fun findStuckRegions(): List<TrackedRegion> {
        val now = System.currentTimeMillis()
        return tracked.values.filter { now - it.startTimeMs > config.barrierTimeoutMs }
    }

    /**
     * Stage 1: Cooperative abort — set shouldAbort flag.
     * Region's tick loop checks this at safe points and breaks out cleanly.
     */
    fun requestAbort(region: Region) {
        region.shouldAbort = true
        logger.warn("Cooperative abort requested for region {}", region.id)
    }

    /**
     * Stage 2: Hard interrupt — only if cooperative abort was ignored.
     * Called after 2x barrierTimeoutMs if region still stuck.
     */
    fun interruptStuck(stuck: TrackedRegion) {
        logger.warn("Hard interrupt on stuck thread {} for region {} (cooperative abort ignored, stuck for {}ms)",
            stuck.owningThread.name, stuck.region.id, System.currentTimeMillis() - stuck.startTimeMs)
        stuck.owningThread.interrupt()
        stuck.region.setState(Region.State.STUCK)
    }

    fun trackedCount(): Int = tracked.size
}
