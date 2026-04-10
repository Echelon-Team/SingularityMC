package com.singularity.agent.threading.split

import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.region.Region
import com.singularity.agent.threading.region.RegionId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RegionSplitMerge(private val config: ThreadingConfig) {
    private val logger = LoggerFactory.getLogger(RegionSplitMerge::class.java)
    private val ticksAboveSplit = ConcurrentHashMap<RegionId, AtomicInteger>()
    private val ticksBelowMerge = ConcurrentHashMap<RegionId, AtomicInteger>()
    private val siblingships = ConcurrentHashMap<RegionId, MutableSet<RegionId>>()

    fun observeTick(region: Region) {
        val load = region.getEntityCount()
        val id = region.id
        if (load > config.splitLoadThreshold) {
            ticksAboveSplit.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
            ticksBelowMerge.remove(id)
        } else if (load < config.mergeLoadThreshold) {
            ticksBelowMerge.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
            ticksAboveSplit.remove(id)
        } else {
            ticksAboveSplit.remove(id)
            ticksBelowMerge.remove(id)
        }
    }

    fun shouldSplit(region: Region): Boolean =
        (ticksAboveSplit[region.id]?.get() ?: 0) >= config.splitHysteresisTicks

    fun shouldMerge(region: Region): Boolean =
        (ticksBelowMerge[region.id]?.get() ?: 0) >= config.mergeHysteresisTicks

    fun recordSplit(parent: RegionId, subRegions: List<RegionId>) {
        logger.info("Recording split: parent {} -> {} sub-regions", parent, subRegions.size)
        for (sub in subRegions) {
            val siblings = siblingships.computeIfAbsent(sub) { ConcurrentHashMap.newKeySet() }
            for (other in subRegions) {
                if (other != sub) siblings.add(other)
            }
        }
    }

    fun areSiblings(a: RegionId, b: RegionId): Boolean =
        siblingships[a]?.contains(b) == true

    fun getSiblings(region: RegionId): Set<RegionId> =
        siblingships[region]?.toSet() ?: emptySet()
}
