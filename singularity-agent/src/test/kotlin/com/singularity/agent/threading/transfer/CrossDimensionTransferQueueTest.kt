package com.singularity.agent.threading.transfer

import com.singularity.agent.threading.region.RegionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class CrossDimensionTransferQueueTest {
    @Test
    fun `enqueue and drain roundtrip`() {
        val queue = CrossDimensionTransferQueue()
        queue.enqueue(CrossDimensionTransfer(UUID.randomUUID(), "overworld", RegionId(0, 0), "the_nether", Triple(0.0, 64.0, 0.0), "entity"))
        val drained = queue.drainAll()
        assertEquals(1, drained.size)
        assertEquals("the_nether", drained[0].targetDimension)
    }

    @Test
    fun `drainAll empties queue`() {
        val queue = CrossDimensionTransferQueue()
        queue.enqueue(CrossDimensionTransfer(UUID.randomUUID(), "overworld", RegionId(0, 0), "the_nether", Triple(0.0, 64.0, 0.0), "entity"))
        queue.drainAll()
        assertTrue(queue.drainAll().isEmpty())
    }

    @Test
    fun `concurrent enqueue safe`() {
        val queue = CrossDimensionTransferQueue()
        val latch = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 20).map {
            Thread { latch.await(); queue.enqueue(CrossDimensionTransfer(UUID.randomUUID(), "overworld", RegionId(0, 0), "the_nether", Triple(0.0, 64.0, 0.0), "entity")) }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join() }
        assertEquals(20, queue.drainAll().size)
    }
}
