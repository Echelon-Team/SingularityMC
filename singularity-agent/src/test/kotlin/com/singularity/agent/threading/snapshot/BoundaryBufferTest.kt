// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.snapshot

import com.singularity.agent.threading.region.RegionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BoundaryBufferTest {

    @Test
    fun `buffer for region holds 8 neighbor snapshots`() {
        val buffer = BoundaryBuffer(centerRegion = RegionId(0, 0))
        assertEquals(0, buffer.snapshotCount())
        val pool = SnapshotPool(maxSize = 16)
        for (neighbor in RegionId(0, 0).neighbors()) {
            buffer.addNeighborSnapshot(pool.acquire(neighbor, tickNumber = 1))
        }
        assertEquals(8, buffer.snapshotCount())
    }

    @Test
    fun `getNeighborSnapshot returns snapshot for adjacent region`() {
        val buffer = BoundaryBuffer(centerRegion = RegionId(0, 0))
        val pool = SnapshotPool(maxSize = 4)
        val neighborSnap = pool.acquire(RegionId(1, 0), tickNumber = 1)
        neighborSnap.setBlock(blockX = 200, blockY = 64, blockZ = 50, blockStateId = 99)
        buffer.addNeighborSnapshot(neighborSnap)
        val retrieved = buffer.getNeighborSnapshot(RegionId(1, 0))
        assertNotNull(retrieved)
        assertEquals(99, retrieved!!.getBlock(200, 64, 50))
    }

    @Test
    fun `getNeighborSnapshot returns null for non-neighbor`() {
        val buffer = BoundaryBuffer(centerRegion = RegionId(0, 0))
        val pool = SnapshotPool(maxSize = 4)
        buffer.addNeighborSnapshot(pool.acquire(RegionId(1, 0), 1))
        assertNull(buffer.getNeighborSnapshot(RegionId(5, 5)))
    }

    @Test
    fun `getNeighborSnapshot returns null when no snapshot for that neighbor`() {
        val buffer = BoundaryBuffer(centerRegion = RegionId(0, 0))
        assertNull(buffer.getNeighborSnapshot(RegionId(1, 0)))
    }

    @Test
    fun `release returns all snapshots to pool`() {
        val pool = SnapshotPool(maxSize = 16)
        val buffer = BoundaryBuffer(centerRegion = RegionId(0, 0))
        for (neighbor in RegionId(0, 0).neighbors()) {
            buffer.addNeighborSnapshot(pool.acquire(neighbor, 1))
        }
        buffer.releaseAll(pool)
        assertEquals(0, buffer.snapshotCount())
        assertEquals(8, pool.availableCount())
    }

    @Test
    fun `addNeighborSnapshot rejects non-adjacent snapshot`() {
        val buffer = BoundaryBuffer(centerRegion = RegionId(0, 0))
        val pool = SnapshotPool(maxSize = 4)
        val nonAdjacent = pool.acquire(RegionId(5, 5), 1)
        assertThrows(IllegalArgumentException::class.java) {
            buffer.addNeighborSnapshot(nonAdjacent)
        }
    }
}
