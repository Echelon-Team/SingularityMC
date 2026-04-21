// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.transfer

import com.singularity.agent.threading.region.RegionId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Entity transfer queue with grouped transfers (ADDENDUM v2).
 *
 * Design spec 4.5B: entity crosses boundary → pending → commit phase atomic transfer.
 * TransferGroup: passengers/vehicle bundled as one group (all-or-nothing).
 */
class EntityTransferQueue {

    data class PendingTransfer(
        val entityUuid: UUID,
        val entityPayload: Any,
        val sourceRegion: RegionId,
        val targetRegion: RegionId,
        val groupId: UUID? = null
    )

    data class TransferGroup(
        val groupId: UUID = UUID.randomUUID(),
        val transfers: List<PendingTransfer>
    ) {
        val primaryTransfer: PendingTransfer get() = transfers.first()
    }

    private val pendingGroups = ConcurrentLinkedQueue<TransferGroup>()

    fun enqueueTransfer(transfer: PendingTransfer) {
        pendingGroups.offer(TransferGroup(transfers = listOf(transfer)))
    }

    /**
     * Enqueue a group of transfers (e.g., vehicle + passengers).
     * All transfers in a group MUST target the same region — passengers share vehicle position.
     */
    fun enqueueGroupTransfer(group: TransferGroup) {
        require(group.transfers.map { it.targetRegion }.distinct().size <= 1) {
            "All transfers in a group must target the same region"
        }
        pendingGroups.offer(group)
    }

    /** Snapshot of pending transfers targeting a specific region (without removing). */
    fun pendingForTarget(target: RegionId): List<PendingTransfer> {
        return pendingGroups.flatMap { it.transfers }.filter { it.targetRegion == target }
    }

    /**
     * Commit all transfer groups targeting the given region. Atomic per group.
     * THREAD SAFETY: Single consumer per target in commit phase. Do not call
     * concurrently for the same target.
     */
    fun commitTransfersFor(target: RegionId): List<TransferGroup> {
        val result = mutableListOf<TransferGroup>()
        val iter = pendingGroups.iterator()
        while (iter.hasNext()) {
            val group = iter.next()
            if (group.transfers.any { it.targetRegion == target }) {
                iter.remove()
                result.add(group)
            }
        }
        return result
    }

    fun totalPending(): Int = pendingGroups.sumOf { it.transfers.size }

    fun clear() { pendingGroups.clear() }
}
