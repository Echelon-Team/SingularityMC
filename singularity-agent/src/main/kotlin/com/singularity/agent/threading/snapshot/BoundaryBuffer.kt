// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.snapshot

import com.singularity.agent.threading.region.RegionId
import java.util.concurrent.ConcurrentHashMap

/**
 * Boundary buffer — zbiór snapshotów sąsiadujących regionów.
 *
 * Tickujący region czyta sąsiadów przez buffer (zero locków, snapshoty immutable).
 *
 * Lifecycle: populate (addNeighborSnapshot) → tick reads → releaseAll to pool.
 */
class BoundaryBuffer(val centerRegion: RegionId) {
    private val snapshots = ConcurrentHashMap<RegionId, ImmutableSnapshot>()

    fun addNeighborSnapshot(snapshot: ImmutableSnapshot) {
        require(centerRegion.isAdjacent(snapshot.regionId)) {
            "Snapshot region ${snapshot.regionId} is not adjacent to center $centerRegion"
        }
        require(!snapshots.containsKey(snapshot.regionId)) {
            "Duplicate snapshot for neighbor ${snapshot.regionId} — release previous first"
        }
        snapshots[snapshot.regionId] = snapshot
    }

    fun getNeighborSnapshot(regionId: RegionId): ImmutableSnapshot? = snapshots[regionId]

    fun snapshotCount(): Int = snapshots.size

    fun releaseAll(pool: SnapshotPool) {
        for (snapshot in snapshots.values) {
            pool.release(snapshot)
        }
        snapshots.clear()
    }
}
