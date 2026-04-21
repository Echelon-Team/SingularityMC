// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.transfer

import com.singularity.agent.threading.region.RegionId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

data class CrossDimensionTransfer(
    val entityUuid: UUID,
    val sourceDimension: String,
    val sourceRegionId: RegionId,
    val targetDimension: String,
    val targetPosition: Triple<Double, Double, Double>,
    val entityPayload: Any,
    val groupId: UUID? = null
)

class CrossDimensionTransferQueue {
    private val pending = ConcurrentLinkedQueue<CrossDimensionTransfer>()

    fun enqueue(transfer: CrossDimensionTransfer) { pending.offer(transfer) }

    fun drainAll(): List<CrossDimensionTransfer> {
        val result = mutableListOf<CrossDimensionTransfer>()
        var t = pending.poll()
        while (t != null) { result.add(t); t = pending.poll() }
        return result
    }

    fun size(): Int = pending.size
}
