// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.message

import com.singularity.agent.threading.region.RegionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RegionMessageQueueTest {

    private fun setBlock(from: RegionId, to: RegionId) =
        RegionMessage.SetBlock(from, to, 100, 64, 100, 1)

    @Test
    fun `enqueue and drainAvailable round-trip`() {
        val queue = RegionMessageQueue(initialCapacity = 16)
        queue.enqueue(setBlock(RegionId(0, 0), RegionId(1, 0)))
        queue.enqueue(setBlock(RegionId(0, 0), RegionId(1, 0)))
        val drained = queue.drainAvailable()
        assertEquals(2, drained.size)
        assertEquals(0, queue.size())
    }

    @Test
    fun `drainAvailable returns empty for empty queue`() {
        assertTrue(RegionMessageQueue().drainAvailable().isEmpty())
    }

    @Test
    fun `size reflects pending messages`() {
        val queue = RegionMessageQueue()
        assertEquals(0, queue.size())
        queue.enqueue(setBlock(RegionId(0, 0), RegionId(1, 0)))
        assertEquals(1, queue.size())
        queue.enqueue(setBlock(RegionId(0, 0), RegionId(1, 0)))
        assertEquals(2, queue.size())
    }

    @Test
    fun `concurrent enqueue is thread-safe`() {
        val queue = RegionMessageQueue()
        val latch = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 50).map {
            Thread { latch.await(); repeat(20) { queue.enqueue(setBlock(RegionId(0, 0), RegionId(1, 0))) } }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join() }
        assertEquals(1000, queue.size())
    }

    @Test
    fun `drainAvailable clears queue completely`() {
        val queue = RegionMessageQueue()
        repeat(100) { queue.enqueue(setBlock(RegionId(0, 0), RegionId(1, 0))) }
        val drained = queue.drainAvailable()
        assertEquals(100, drained.size)
        assertEquals(0, queue.size())
    }
}
