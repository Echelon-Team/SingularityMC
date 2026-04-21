// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.region

import com.singularity.agent.threading.config.ThreadingConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class RegionSchedulerTest {

    private lateinit var grid: RegionGrid
    private lateinit var scheduler: RegionScheduler

    @BeforeEach
    fun setup() {
        grid = RegionGrid("overworld", ThreadingConfig())
        scheduler = RegionScheduler(grid)
    }

    @Test
    fun `empty grid produces empty schedule`() {
        assertTrue(scheduler.buildSchedule(emptyList()).isEmpty())
    }

    @Test
    fun `idle regions are skipped`() {
        grid.getOrCreate(RegionId(0, 0))
        assertTrue(scheduler.buildSchedule(emptyList()).isEmpty())
    }

    @Test
    fun `regions with entities are scheduled`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(5)
        val schedule = scheduler.buildSchedule(emptyList())
        assertEquals(1, schedule.size)
        assertEquals(RegionId(0, 0), schedule[0].id)
    }

    @Test
    fun `regions near player have HIGH priority first`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(1)
        grid.getOrCreate(RegionId(10, 10)).setEntityCount(1)
        val schedule = scheduler.buildSchedule(listOf(PlayerPosition(64, 64)))
        assertEquals(2, schedule.size)
        assertEquals(RegionId(0, 0), schedule[0].id)
        assertEquals(RegionId(10, 10), schedule[1].id)
    }

    @Test
    fun `priority HIGH for current region of player`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(1)
        assertEquals(RegionPriority.HIGH, scheduler.priorityFor(
            grid.get(RegionId(0, 0))!!, listOf(PlayerPosition(64, 64))
        ))
    }

    @Test
    fun `priority MEDIUM for adjacent regions`() {
        grid.getOrCreate(RegionId(1, 0)).setEntityCount(1)
        assertEquals(RegionPriority.MEDIUM, scheduler.priorityFor(
            grid.get(RegionId(1, 0))!!, listOf(PlayerPosition(64, 64))
        ))
    }

    @Test
    fun `priority LOW for distant regions`() {
        grid.getOrCreate(RegionId(20, 20)).setEntityCount(1)
        assertEquals(RegionPriority.LOW, scheduler.priorityFor(
            grid.get(RegionId(20, 20))!!, listOf(PlayerPosition(64, 64))
        ))
    }

    @Test
    fun `multiple players — minimum distance used`() {
        grid.getOrCreate(RegionId(1, 1)).setEntityCount(1)
        val players = listOf(PlayerPosition(64, 64), PlayerPosition(2000, 2000))
        assertEquals(RegionPriority.MEDIUM, scheduler.priorityFor(
            grid.get(RegionId(1, 1))!!, players
        ))
    }

    @Test
    fun `schedule ordering HIGH before MEDIUM before LOW`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(1)
        grid.getOrCreate(RegionId(1, 0)).setEntityCount(1)
        grid.getOrCreate(RegionId(10, 10)).setEntityCount(1)
        val schedule = scheduler.buildSchedule(listOf(PlayerPosition(64, 64)))
        assertEquals(3, schedule.size)
        assertEquals(RegionId(0, 0), schedule[0].id)
        assertEquals(RegionId(1, 0), schedule[1].id)
        assertEquals(RegionId(10, 10), schedule[2].id)
    }

    @Test
    fun `scheduledTick-only region is included in schedule`() {
        val region = grid.getOrCreate(RegionId(0, 0))
        // No entities, only scheduled ticks (redstone clock)
        region.addScheduledTick()
        region.addScheduledTick()
        assertEquals(0, region.getEntityCount())
        assertTrue(region.isActive())

        val schedule = scheduler.buildSchedule(emptyList())
        assertEquals(1, schedule.size)
        assertEquals(RegionId(0, 0), schedule[0].id)
    }
}
