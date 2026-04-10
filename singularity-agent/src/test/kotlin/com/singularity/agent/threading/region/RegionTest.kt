package com.singularity.agent.threading.region

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class RegionTest {

    @Test
    fun `new region is IDLE with 0 entities`() {
        val region = Region(RegionId(0, 0), "overworld")
        assertEquals(Region.State.IDLE, region.getState())
        assertEquals(0, region.getEntityCount())
        assertEquals(0, region.getScheduledTickCount())
        assertFalse(region.shouldAbort)
    }

    @Test
    fun `isActive false when no entities and no scheduled ticks`() {
        val region = Region(RegionId(0, 0), "overworld")
        assertFalse(region.isActive())
    }

    @Test
    fun `isActive true when entities present`() {
        val region = Region(RegionId(0, 0), "overworld")
        region.setEntityCount(5)
        assertTrue(region.isActive())
    }

    @Test
    fun `isActive true when scheduled ticks present but no entities`() {
        val region = Region(RegionId(0, 0), "overworld")
        region.addScheduledTick()
        assertTrue(region.isActive())
        assertEquals(0, region.getEntityCount())
        assertEquals(1, region.getScheduledTickCount())
    }

    @Test
    fun `scheduledTickCount increment and decrement`() {
        val region = Region(RegionId(0, 0), "overworld")
        region.addScheduledTick()
        region.addScheduledTick()
        assertEquals(2, region.getScheduledTickCount())
        region.removeScheduledTick()
        assertEquals(1, region.getScheduledTickCount())
    }

    @Test
    fun `claimOwnership succeeds when unowned`() {
        val region = Region(RegionId(0, 0), "overworld")
        assertTrue(region.claimOwnership(Thread.currentThread()))
        assertEquals(Thread.currentThread(), region.getOwner())
    }

    @Test
    fun `claimOwnership fails when already owned`() {
        val region = Region(RegionId(0, 0), "overworld")
        val other = Thread { }
        region.claimOwnership(other)
        assertFalse(region.claimOwnership(Thread.currentThread()))
    }

    @Test
    fun `releaseOwnership succeeds for owner`() {
        val region = Region(RegionId(0, 0), "overworld")
        val thread = Thread.currentThread()
        region.claimOwnership(thread)
        assertTrue(region.releaseOwnership(thread))
        assertNull(region.getOwner())
    }

    @Test
    fun `releaseOwnership fails for non-owner`() {
        val region = Region(RegionId(0, 0), "overworld")
        val owner = Thread { }
        region.claimOwnership(owner)
        assertFalse(region.releaseOwnership(Thread.currentThread()))
    }

    @Test
    fun `compareAndSetState works atomically`() {
        val region = Region(RegionId(0, 0), "overworld")
        assertTrue(region.compareAndSetState(Region.State.IDLE, Region.State.QUEUED))
        assertEquals(Region.State.QUEUED, region.getState())
        assertFalse(region.compareAndSetState(Region.State.IDLE, Region.State.PROCESSING))
    }

    @Test
    fun `shouldAbort flag is volatile and cross-thread visible`() {
        val region = Region(RegionId(0, 0), "overworld")
        assertFalse(region.shouldAbort)

        val seen = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val reader = Thread {
            latch.await()
            if (region.shouldAbort) seen.set(1)
        }
        reader.start()
        region.shouldAbort = true
        latch.countDown()
        reader.join()
        assertEquals(1, seen.get())
    }

    // --- Concurrent tests ---

    @Test
    fun `concurrent entity count modifications are consistent`() {
        val region = Region(RegionId(0, 0), "overworld")
        val latch = CountDownLatch(1)
        val threads = (0 until 100).map {
            Thread {
                latch.await()
                region.incrementEntityCount()
            }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join() }
        assertEquals(100, region.getEntityCount())
    }

    @Test
    fun `concurrent claimOwnership — only one thread wins`() {
        val region = Region(RegionId(0, 0), "overworld")
        val winners = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val threads = (0 until 50).map {
            Thread {
                latch.await()
                if (region.claimOwnership(Thread.currentThread())) {
                    winners.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
    }

    @Test
    fun `concurrent scheduled tick modifications are consistent`() {
        val region = Region(RegionId(0, 0), "overworld")
        val latch = CountDownLatch(1)
        val adders = (0 until 50).map {
            Thread { latch.await(); region.addScheduledTick() }
        }
        val removers = (0 until 30).map {
            Thread { latch.await(); region.addScheduledTick(); region.removeScheduledTick() }
        }
        (adders + removers).forEach { it.start() }
        latch.countDown()
        (adders + removers).forEach { it.join() }
        assertEquals(50, region.getScheduledTickCount()) // 50 adds + 30 add-remove = net 50
    }
}
