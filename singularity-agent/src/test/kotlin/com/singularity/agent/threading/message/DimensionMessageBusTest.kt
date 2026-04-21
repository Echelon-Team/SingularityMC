// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.message

import com.singularity.agent.threading.region.RegionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DimensionMessageBusTest {

    @Test
    fun `send creates queue for new target lazily`() {
        val bus = DimensionMessageBus()
        bus.send(RegionMessage.SetBlock(RegionId(0, 0), RegionId(1, 0), 0, 0, 0, 1))
        assertEquals(1, bus.drainQueueFor(RegionId(1, 0)).size)
    }

    @Test
    fun `drainQueueFor returns messages targeting that region from all sources`() {
        val bus = DimensionMessageBus()
        bus.send(RegionMessage.SetBlock(RegionId(0, 0), RegionId(1, 0), 0, 0, 0, 1))
        bus.send(RegionMessage.SetBlock(RegionId(2, 0), RegionId(1, 0), 0, 0, 0, 2))
        bus.send(RegionMessage.SetBlock(RegionId(0, 0), RegionId(0, 1), 0, 0, 0, 3))
        assertEquals(2, bus.drainQueueFor(RegionId(1, 0)).size)
        assertEquals(1, bus.drainQueueFor(RegionId(0, 1)).size)
    }

    @Test
    fun `drainQueueFor returns empty for region with no messages`() {
        assertTrue(DimensionMessageBus().drainQueueFor(RegionId(99, 99)).isEmpty())
    }

    @Test
    fun `pendingMessageCount reflects total across all queues`() {
        val bus = DimensionMessageBus()
        bus.send(RegionMessage.SetBlock(RegionId(0, 0), RegionId(1, 0), 0, 0, 0, 1))
        bus.send(RegionMessage.SetBlock(RegionId(0, 0), RegionId(0, 1), 0, 0, 0, 1))
        assertEquals(2, bus.pendingMessageCount())
    }

    @Test
    fun `migrateOnSplit redistributes messages to new regions`() {
        val bus = DimensionMessageBus()
        bus.send(RegionMessage.SetBlock(RegionId(0, 0), RegionId(1, 0), 10, 64, 10, 1))
        bus.send(RegionMessage.SetBlock(RegionId(0, 0), RegionId(1, 0), 200, 64, 200, 2))

        // Split region (1,0) into (2,0) and (2,1) based on block X
        bus.migrateOnSplit(
            oldRegionId = RegionId(1, 0),
            newRegionIds = setOf(RegionId(2, 0), RegionId(2, 1)),
            regionResolver = { msg ->
                val sb = msg as RegionMessage.SetBlock
                if (sb.blockX < 128) RegionId(2, 0) else RegionId(2, 1)
            }
        )

        assertEquals(1, bus.drainQueueFor(RegionId(2, 0)).size) // blockX=10 < 128
        assertEquals(1, bus.drainQueueFor(RegionId(2, 1)).size) // blockX=200 >= 128
        assertTrue(bus.drainQueueFor(RegionId(1, 0)).isEmpty()) // old region cleared
    }

    @Test
    fun `migrateOnSplit with no messages for old region is no-op`() {
        val bus = DimensionMessageBus()
        assertDoesNotThrow {
            bus.migrateOnSplit(RegionId(99, 99), emptySet()) { it.targetRegion }
        }
    }
}
