// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.transfer

import com.singularity.agent.threading.region.RegionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class EntityTransferQueueTest {

    @Test
    fun `enqueue and pendingForTarget round-trip`() {
        val queue = EntityTransferQueue()
        val uuid = UUID.randomUUID()
        queue.enqueueTransfer(EntityTransferQueue.PendingTransfer(uuid, "data", RegionId(0, 0), RegionId(1, 0)))
        val pending = queue.pendingForTarget(RegionId(1, 0))
        assertEquals(1, pending.size)
        assertEquals(uuid, pending[0].entityUuid)
    }

    @Test
    fun `pendingForTarget returns only matching transfers`() {
        val queue = EntityTransferQueue()
        queue.enqueueTransfer(EntityTransferQueue.PendingTransfer(UUID.randomUUID(), "a", RegionId(0, 0), RegionId(1, 0)))
        queue.enqueueTransfer(EntityTransferQueue.PendingTransfer(UUID.randomUUID(), "b", RegionId(0, 0), RegionId(0, 1)))
        queue.enqueueTransfer(EntityTransferQueue.PendingTransfer(UUID.randomUUID(), "c", RegionId(0, 0), RegionId(1, 0)))
        assertEquals(2, queue.pendingForTarget(RegionId(1, 0)).size)
        assertEquals(1, queue.pendingForTarget(RegionId(0, 1)).size)
    }

    @Test
    fun `commitTransfersFor removes groups from queue`() {
        val queue = EntityTransferQueue()
        queue.enqueueTransfer(EntityTransferQueue.PendingTransfer(UUID.randomUUID(), "a", RegionId(0, 0), RegionId(1, 0)))
        val committed = queue.commitTransfersFor(RegionId(1, 0))
        assertEquals(1, committed.size)
        assertEquals(0, queue.pendingForTarget(RegionId(1, 0)).size)
    }

    @Test
    fun `grouped transfer — passengers bundled`() {
        val queue = EntityTransferQueue()
        val horseUuid = UUID.randomUUID()
        val playerUuid = UUID.randomUUID()
        val group = EntityTransferQueue.TransferGroup(
            transfers = listOf(
                EntityTransferQueue.PendingTransfer(horseUuid, "horse", RegionId(0, 0), RegionId(1, 0)),
                EntityTransferQueue.PendingTransfer(playerUuid, "player", RegionId(0, 0), RegionId(1, 0))
            )
        )
        queue.enqueueGroupTransfer(group)

        val committed = queue.commitTransfersFor(RegionId(1, 0))
        assertEquals(1, committed.size) // one group
        assertEquals(2, committed[0].transfers.size) // two entities in group
    }

    @Test
    fun `concurrent enqueue is thread-safe`() {
        val queue = EntityTransferQueue()
        val latch = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 20).map { i ->
            Thread {
                latch.await()
                repeat(50) {
                    queue.enqueueTransfer(EntityTransferQueue.PendingTransfer(
                        UUID.randomUUID(), "data-$i", RegionId(0, 0), RegionId(1, 0)
                    ))
                }
            }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join() }
        assertEquals(1000, queue.totalPending())
    }

    @Test
    fun `totalPending counts across all groups`() {
        val queue = EntityTransferQueue()
        queue.enqueueTransfer(EntityTransferQueue.PendingTransfer(UUID.randomUUID(), "a", RegionId(0, 0), RegionId(1, 0)))
        queue.enqueueTransfer(EntityTransferQueue.PendingTransfer(UUID.randomUUID(), "b", RegionId(0, 0), RegionId(0, 1)))
        assertEquals(2, queue.totalPending())
    }

    @Test
    fun `commitTransfersFor returns empty for unknown target`() {
        val queue = EntityTransferQueue()
        assertTrue(queue.commitTransfersFor(RegionId(99, 99)).isEmpty())
    }
}
