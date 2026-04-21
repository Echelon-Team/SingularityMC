// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.tick

import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.pool.DimensionPool
import com.singularity.agent.threading.region.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TickExecutorTest {

    private lateinit var pool: DimensionPool
    private lateinit var grid: RegionGrid
    private lateinit var scheduler: RegionScheduler
    private lateinit var executor: TickExecutor
    private val config = ThreadingConfig(barrierTimeoutMs = 500) // short timeout for tests

    @BeforeEach
    fun setup() {
        pool = DimensionPool("overworld", 4)
        grid = RegionGrid("overworld", config)
        scheduler = RegionScheduler(grid, config)
        executor = TickExecutor(pool, scheduler, config)
    }

    @AfterEach
    fun cleanup() {
        pool.shutdown()
    }

    @Test
    fun `executeTick runs all active regions`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(1)
        grid.getOrCreate(RegionId(1, 0)).setEntityCount(1)
        grid.getOrCreate(RegionId(2, 0)).setEntityCount(1)

        val processed = ConcurrentHashMap.newKeySet<RegionId>()
        executor.executeTick(1, emptyList()) { phase, region ->
            if (phase == TickPhase.ENTITY_TICKING) processed.add(region.id)
        }
        assertEquals(3, processed.size)
    }

    @Test
    fun `executeTick skips idle regions`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(0)
        grid.getOrCreate(RegionId(1, 0)).setEntityCount(5)

        val processed = ConcurrentHashMap.newKeySet<RegionId>()
        executor.executeTick(1, emptyList()) { _, region -> processed.add(region.id) }
        assertFalse(processed.contains(RegionId(0, 0)))
        assertTrue(processed.contains(RegionId(1, 0)))
    }

    @Test
    fun `executeTick runs all 4 phases`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(1)

        val phasesSeen = ConcurrentHashMap.newKeySet<TickPhase>()
        executor.executeTick(1, emptyList()) { phase, _ -> phasesSeen.add(phase) }
        assertEquals(4, phasesSeen.size)
        assertTrue(phasesSeen.containsAll(TickPhase.entries))
    }

    @Test
    fun `executeTick updates lastTick on regions`() {
        val region = grid.getOrCreate(RegionId(0, 0))
        region.setEntityCount(1)
        executor.executeTick(42, emptyList()) { _, _ -> }
        assertEquals(42, region.getLastTick())
    }

    @Test
    fun `exception in handler does not break tick`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(1)
        grid.getOrCreate(RegionId(1, 0)).setEntityCount(1)

        val survivedCount = AtomicInteger(0)
        executor.executeTick(1, emptyList()) { _, region ->
            if (region.id == RegionId(0, 0)) throw RuntimeException("boom")
            survivedCount.incrementAndGet()
        }
        // Region 1 should survive (4 phases)
        assertTrue(survivedCount.get() > 0)
    }

    @Test
    fun `stuck region excluded from subsequent phases`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(1)
        grid.getOrCreate(RegionId(1, 0)).setEntityCount(1)

        val phasesPerRegion = ConcurrentHashMap<RegionId, MutableList<TickPhase>>()
        executor.executeTick(1, emptyList()) { phase, region ->
            phasesPerRegion.computeIfAbsent(region.id) { java.util.concurrent.CopyOnWriteArrayList() }.add(phase)
            // Region 0 sleeps in ENTITY_TICKING — will timeout
            if (region.id == RegionId(0, 0) && phase == TickPhase.ENTITY_TICKING) {
                Thread.sleep(config.barrierTimeoutMs + 200)
            }
        }
        // Region 1 should have all 4 phases
        val r1Phases = phasesPerRegion[RegionId(1, 0)]
        assertNotNull(r1Phases)
        assertEquals(4, r1Phases!!.size)

        // Region 0 should have 1 phase (ENTITY_TICKING) then excluded
        val r0Phases = phasesPerRegion[RegionId(0, 0)]
        assertNotNull(r0Phases)
        assertTrue(r0Phases!!.size < 4, "Stuck region should be excluded from later phases, got ${r0Phases.size}")
    }

    @Test
    fun `phases execute in strict order`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(1)

        val phaseOrder = java.util.concurrent.CopyOnWriteArrayList<TickPhase>()
        executor.executeTick(1, emptyList()) { phase, _ ->
            phaseOrder.add(phase)
        }
        assertEquals(4, phaseOrder.size)
        assertEquals(TickPhase.ENTITY_TICKING, phaseOrder[0])
        assertEquals(TickPhase.BLOCK_UPDATES, phaseOrder[1])
        assertEquals(TickPhase.REDSTONE, phaseOrder[2])
        assertEquals(TickPhase.COMMIT, phaseOrder[3])
    }

    @Test
    fun `regions processed in parallel within phase`() {
        repeat(4) { i -> grid.getOrCreate(RegionId(i, 0)).setEntityCount(1) }

        val threadIds = ConcurrentHashMap.newKeySet<Long>()
        executor.executeTick(1, emptyList()) { phase, _ ->
            if (phase == TickPhase.ENTITY_TICKING) {
                threadIds.add(Thread.currentThread().id)
                Thread.sleep(50) // give time for parallel execution
            }
        }
        assertTrue(threadIds.size >= 2, "Expected 2+ threads, got ${threadIds.size}")
    }

    @Test
    fun `empty schedule does not crash`() {
        assertDoesNotThrow {
            executor.executeTick(1, emptyList()) { _, _ -> }
        }
    }
}
