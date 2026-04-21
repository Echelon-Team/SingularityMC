// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.message

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pre-allocated message queue for cross-region communication.
 *
 * ADDENDUM v2: drainAvailable() (not drainAllAtomic — name was misleading about atomicity).
 */
class RegionMessageQueue(val initialCapacity: Int = 256) {
    private val queue = ConcurrentLinkedQueue<RegionMessage>()
    private val sizeCounter = AtomicInteger(0)

    fun enqueue(message: RegionMessage) {
        queue.offer(message)
        sizeCounter.incrementAndGet()
    }

    fun size(): Int = sizeCounter.get()

    /**
     * Drains all currently available messages. Not strictly atomic with concurrent
     * enqueue — messages added mid-drain may or may not be included. This is acceptable
     * because drain happens in commit phase (single consumer per region).
     */
    fun drainAvailable(): List<RegionMessage> {
        val drained = mutableListOf<RegionMessage>()
        while (true) {
            val msg = queue.poll() ?: break
            drained.add(msg)
            sizeCounter.decrementAndGet()
        }
        return drained
    }

    // Keep drainAll as alias for backward compat with plan code
    fun drainAll(): List<RegionMessage> = drainAvailable()
}
