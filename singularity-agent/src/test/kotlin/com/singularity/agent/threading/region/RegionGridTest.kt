package com.singularity.agent.threading.region

import com.singularity.agent.threading.config.ThreadingConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class RegionGridTest {

    private lateinit var grid: RegionGrid
    private val config = ThreadingConfig(regionSizeChunks = 8) // 128 bloków

    @BeforeEach
    fun setup() {
        grid = RegionGrid("overworld", config)
    }

    @Test
    fun `getOrCreate returns same instance for same id`() {
        val a = grid.getOrCreate(RegionId(0, 0))
        val b = grid.getOrCreate(RegionId(0, 0))
        assertSame(a, b)
    }

    @Test
    fun `getOrCreate returns different instances for different ids`() {
        val a = grid.getOrCreate(RegionId(0, 0))
        val b = grid.getOrCreate(RegionId(1, 0))
        assertNotSame(a, b)
    }

    @Test
    fun `regionForBlock uses config regionSizeBlocks`() {
        val region = grid.regionForBlock(blockX = 200, blockZ = 300)
        assertEquals(1, region.id.x)
        assertEquals(2, region.id.z)
    }

    @Test
    fun `regionForBlock negative coordinates`() {
        val region = grid.regionForBlock(blockX = -50, blockZ = -200)
        assertEquals(-1, region.id.x)
        assertEquals(-2, region.id.z)
    }

    @Test
    fun `getActiveRegions returns only active regions`() {
        val r1 = grid.getOrCreate(RegionId(0, 0))
        val r2 = grid.getOrCreate(RegionId(1, 0))
        val r3 = grid.getOrCreate(RegionId(2, 0))

        r1.setEntityCount(10)
        r2.setEntityCount(0) // idle, no scheduled ticks
        r3.addScheduledTick() // active via scheduled ticks

        val active = grid.getActiveRegions()
        assertEquals(2, active.size)
        assertTrue(active.any { it.id == RegionId(0, 0) })
        assertTrue(active.any { it.id == RegionId(2, 0) })
    }

    @Test
    fun `getAllRegions returns all created regions`() {
        grid.getOrCreate(RegionId(0, 0))
        grid.getOrCreate(RegionId(1, 1))
        grid.getOrCreate(RegionId(-1, -1))
        assertEquals(3, grid.getAllRegions().size)
    }

    @Test
    fun `remove evicts region`() {
        grid.getOrCreate(RegionId(0, 0))
        assertTrue(grid.contains(RegionId(0, 0)))
        grid.remove(RegionId(0, 0))
        assertFalse(grid.contains(RegionId(0, 0)))
    }

    @Test
    fun `dimensionId is preserved`() {
        assertEquals("overworld", grid.dimensionId)
    }

    @Test
    fun `size returns count`() {
        assertEquals(0, grid.size)
        grid.getOrCreate(RegionId(0, 0))
        assertEquals(1, grid.size)
        grid.getOrCreate(RegionId(1, 0))
        assertEquals(2, grid.size)
        grid.getOrCreate(RegionId(0, 0)) // duplicate
        assertEquals(2, grid.size)
    }

    @Test
    fun `concurrent getOrCreate is thread-safe`() {
        val threads = (0 until 50).map { i ->
            Thread {
                repeat(10) {
                    grid.getOrCreate(RegionId(i % 5, 0))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(5, grid.size)
    }

    @Test
    fun `concurrent remove and getOrCreate race does not corrupt state`() {
        // Pre-populate
        val targetId = RegionId(0, 0)
        grid.getOrCreate(targetId)

        val latch = java.util.concurrent.CountDownLatch(1)
        val creators = (0 until 25).map {
            Thread { latch.await(); grid.getOrCreate(targetId) }
        }
        val removers = (0 until 25).map {
            Thread { latch.await(); grid.remove(targetId) }
        }
        (creators + removers).forEach { it.start() }
        latch.countDown()
        (creators + removers).forEach { it.join() }

        // After race: region either exists or doesn't — but grid is not corrupted
        // size is 0 or 1 (not negative, not 2+)
        assertTrue(grid.size in 0..1)
    }

    @Test
    fun `totalEntityCount sums across regions`() {
        grid.getOrCreate(RegionId(0, 0)).setEntityCount(10)
        grid.getOrCreate(RegionId(1, 0)).setEntityCount(20)
        grid.getOrCreate(RegionId(2, 0)).setEntityCount(0)
        assertEquals(30, grid.totalEntityCount())
    }
}
