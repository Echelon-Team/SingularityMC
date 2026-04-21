// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.split

import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.region.Region
import com.singularity.agent.threading.region.RegionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class RegionSplitMergeTest {
    private lateinit var splitMerge: RegionSplitMerge
    private val config = ThreadingConfig(splitLoadThreshold = 500, splitHysteresisTicks = 40, mergeLoadThreshold = 100, mergeHysteresisTicks = 200)

    @BeforeEach
    fun setup() { splitMerge = RegionSplitMerge(config) }

    private fun region(id: RegionId, entityCount: Int): Region {
        val r = Region(id, "overworld"); r.setEntityCount(entityCount); return r
    }

    @Test
    fun `region below threshold does not get split`() {
        val r = region(RegionId(0, 0), 100)
        repeat(100) { splitMerge.observeTick(r) }
        assertFalse(splitMerge.shouldSplit(r))
    }

    @Test
    fun `region above threshold for short time does not split (hysteresis)`() {
        val r = region(RegionId(0, 0), 600)
        repeat(20) { splitMerge.observeTick(r) }
        assertFalse(splitMerge.shouldSplit(r))
    }

    @Test
    fun `region above threshold for full hysteresis gets split`() {
        val r = region(RegionId(0, 0), 600)
        repeat(45) { splitMerge.observeTick(r) }
        assertTrue(splitMerge.shouldSplit(r))
    }

    @Test
    fun `region drops below threshold resets hysteresis`() {
        val r = region(RegionId(0, 0), 600)
        repeat(35) { splitMerge.observeTick(r) }
        r.setEntityCount(200)
        splitMerge.observeTick(r)
        r.setEntityCount(600)
        repeat(20) { splitMerge.observeTick(r) }
        assertFalse(splitMerge.shouldSplit(r))
    }

    @Test
    fun `merge requires longer hysteresis than split`() {
        assertTrue(config.mergeHysteresisTicks > config.splitHysteresisTicks)
    }

    @Test
    fun `recordSplit registers sub-regions as siblings`() {
        splitMerge.recordSplit(RegionId(0, 0), listOf(RegionId(0, 0), RegionId(0, 1)))
        assertTrue(splitMerge.areSiblings(RegionId(0, 0), RegionId(0, 1)))
    }

    @Test
    fun `non-siblings cannot merge`() {
        assertFalse(splitMerge.areSiblings(RegionId(0, 0), RegionId(5, 5)))
    }

    @Test
    fun `getSiblings returns sub-regions of same parent`() {
        splitMerge.recordSplit(RegionId(0, 0), listOf(RegionId(0, 0), RegionId(0, 1)))
        assertEquals(setOf(RegionId(0, 1)), splitMerge.getSiblings(RegionId(0, 0)))
    }

    @Test
    fun `shouldMerge requires full merge hysteresis period`() {
        val r = region(RegionId(0, 0), 50) // below mergeLoadThreshold=100
        repeat(100) { splitMerge.observeTick(r) }
        assertFalse(splitMerge.shouldMerge(r)) // 100 < 200 hysteresis

        repeat(105) { splitMerge.observeTick(r) }
        assertTrue(splitMerge.shouldMerge(r)) // 205 >= 200 hysteresis
    }

    @Test
    fun `shouldMerge resets when load rises above merge threshold`() {
        val r = region(RegionId(0, 0), 50)
        repeat(150) { splitMerge.observeTick(r) }
        r.setEntityCount(300) // above mergeLoadThreshold, in dead zone
        splitMerge.observeTick(r)
        r.setEntityCount(50) // back below
        repeat(50) { splitMerge.observeTick(r) }
        assertFalse(splitMerge.shouldMerge(r)) // counter reset, only 50 ticks
    }
}
