// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.snapshot

import com.singularity.agent.threading.region.RegionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

class SnapshotPoolTest {

    @Test
    fun `acquire returns fresh snapshot when pool empty`() {
        val pool = SnapshotPool(maxSize = 4)
        val snapshot = pool.acquire(RegionId(0, 0), tickNumber = 1)
        assertNotNull(snapshot)
        assertEquals(RegionId(0, 0), snapshot.regionId)
        assertEquals(1, snapshot.tickNumber)
    }

    @Test
    fun `release returns snapshot to pool`() {
        val pool = SnapshotPool(maxSize = 4)
        val snapshot = pool.acquire(RegionId(0, 0), tickNumber = 1)
        pool.release(snapshot)
        assertEquals(1, pool.availableCount())
    }

    @Test
    fun `acquire reuses released snapshot`() {
        val pool = SnapshotPool(maxSize = 4)
        val first = pool.acquire(RegionId(0, 0), tickNumber = 1)
        pool.release(first)
        val second = pool.acquire(RegionId(1, 1), tickNumber = 2)
        assertEquals(RegionId(1, 1), second.regionId)
        assertEquals(2, second.tickNumber)
    }

    @Test
    fun `pool capped at maxSize`() {
        val pool = SnapshotPool(maxSize = 2)
        val a = pool.acquire(RegionId(0, 0), 1)
        val b = pool.acquire(RegionId(0, 1), 1)
        val c = pool.acquire(RegionId(0, 2), 1)
        pool.release(a)
        pool.release(b)
        pool.release(c)
        assertTrue(pool.availableCount() <= 2)
    }

    @Test
    fun `setBlock and getBlock round-trip`() {
        val pool = SnapshotPool(maxSize = 4)
        val snapshot = pool.acquire(RegionId(0, 0), tickNumber = 1)
        snapshot.setBlock(5, 64, 7, 12345)
        assertEquals(12345, snapshot.getBlock(5, 64, 7))
    }

    @Test
    fun `getBlock returns 0 for unset position`() {
        val pool = SnapshotPool(maxSize = 4)
        val snapshot = pool.acquire(RegionId(0, 0), tickNumber = 1)
        assertEquals(0, snapshot.getBlock(100, 100, 100))
    }

    @Test
    fun `clear resets snapshot data`() {
        val pool = SnapshotPool(maxSize = 4)
        val snapshot = pool.acquire(RegionId(0, 0), tickNumber = 1)
        snapshot.setBlock(0, 0, 0, 5)
        snapshot.clear()
        assertEquals(0, snapshot.getBlock(0, 0, 0))
    }

    // --- ADDENDUM v2 tests ---

    @Test
    fun `setBlock after freeze throws IllegalStateException`() {
        val pool = SnapshotPool(maxSize = 4)
        val snapshot = pool.acquire(RegionId(0, 0), 1)
        snapshot.setBlock(0, 0, 0, 1)
        snapshot.freeze()
        assertThrows(IllegalStateException::class.java) {
            snapshot.setBlock(0, 0, 0, 2)
        }
    }

    @Test
    fun `negative Y values encoded correctly`() {
        val pool = SnapshotPool(maxSize = 4)
        val snapshot = pool.acquire(RegionId(0, 0), 1)
        snapshot.setBlock(0, -64, 0, 1)
        assertEquals(1, snapshot.getBlock(0, -64, 0))
        snapshot.setBlock(0, -1, 0, 2)
        assertEquals(2, snapshot.getBlock(0, -1, 0))
        snapshot.setBlock(0, 319, 0, 3)
        assertEquals(3, snapshot.getBlock(0, 319, 0))
    }

    @Test
    fun `getBlockEntity returns copied NBT data`() {
        val pool = SnapshotPool(maxSize = 4)
        val snapshot = pool.acquire(RegionId(0, 0), 1)
        val nbt = mapOf("Items" to listOf("stone"), "Lock" to "")
        snapshot.setBlockEntity(0, 64, 0, nbt)
        val retrieved = snapshot.getBlockEntity(0, 64, 0)
        assertNotNull(retrieved)
        assertEquals("", retrieved!!["Lock"])
    }

    @Test
    fun `setBlockEntity after freeze throws`() {
        val pool = SnapshotPool(maxSize = 4)
        val snapshot = pool.acquire(RegionId(0, 0), 1)
        snapshot.freeze()
        assertThrows(IllegalStateException::class.java) {
            snapshot.setBlockEntity(0, 0, 0, emptyMap())
        }
    }

    @Test
    fun `concurrent acquire never returns same instance to two threads`() {
        val pool = SnapshotPool(maxSize = 100)
        // Pre-fill pool
        repeat(100) {
            val s = pool.acquire(RegionId(0, 0), 1)
            pool.release(s)
        }

        val instances = ConcurrentHashMap.newKeySet<ImmutableSnapshot>()
        val latch = CountDownLatch(1)
        val threads = (0 until 100).map { i ->
            Thread {
                latch.await()
                val s = pool.acquire(RegionId(i, 0), 1)
                instances.add(s)
            }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join() }
        assertEquals(100, instances.size, "All acquired instances must be distinct")
    }
}
