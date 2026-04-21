// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.snapshot

import com.singularity.agent.threading.region.RegionId
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Object pool for ImmutableSnapshot reuse — minimizes GC pressure.
 *
 * ADDENDUM v2: AtomicInteger poolSize guard (fixes TOCTOU race on size check).
 */
class SnapshotPool(val maxSize: Int) {

    private val available = ConcurrentLinkedDeque<ImmutableSnapshot>()
    private val poolSize = AtomicInteger(0)

    fun acquire(regionId: RegionId, tickNumber: Long): ImmutableSnapshot {
        val snapshot = available.pollFirst()
        if (snapshot != null) {
            poolSize.decrementAndGet()
            snapshot.regionId = regionId
            snapshot.tickNumber = tickNumber
            return snapshot
        }
        val fresh = ImmutableSnapshot()
        fresh.regionId = regionId
        fresh.tickNumber = tickNumber
        return fresh
    }

    fun release(snapshot: ImmutableSnapshot) {
        snapshot.clear()
        // CAS loop — race-free pool size management
        while (true) {
            val current = poolSize.get()
            if (current >= maxSize) return // pool full, discard snapshot
            if (poolSize.compareAndSet(current, current + 1)) {
                available.offerLast(snapshot)
                return
            }
            // CAS failed — another thread raced us, retry
        }
    }

    fun availableCount(): Int = poolSize.get()
}
