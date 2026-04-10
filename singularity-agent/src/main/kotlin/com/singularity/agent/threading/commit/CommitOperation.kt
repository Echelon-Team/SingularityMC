package com.singularity.agent.threading.commit

import com.singularity.agent.threading.region.RegionId
import java.util.UUID

/**
 * Operacja w commit phase.
 *
 * Design spec 4.5 warstwa 2A:
 * - INDEPENDENT — operacje wykonywane parallel per region (eksplozje, /fill, WorldEdit)
 * - DEPENDENT — operacje wykonywane sekwencyjnie (pistony, block movers)
 *
 * DEPENDENT wykonywane PRZED INDEPENDENT (design spec — dependent są bardziej krytyczne
 * dla spójności i nie skalują się dobrze, więc wykonujemy je najpierw na wolnym systemie).
 */
sealed class CommitOperation {
    abstract val regionsAffected: Set<RegionId>
    abstract val operationType: String
    abstract val isIndependent: Boolean

    /** Eksplozja — INDEPENDENT (każdy region niszczy swoje bloki niezależnie). */
    data class Explosion(
        val centerX: Double,
        val centerY: Double,
        val centerZ: Double,
        val power: Float,
        override val regionsAffected: Set<RegionId>
    ) : CommitOperation() {
        override val operationType = "explosion"
        override val isIndependent = true
    }

    /** /fill, WorldEdit, Digital Miner — INDEPENDENT. */
    data class BulkBlockChange(
        val blockChanges: List<BlockChange>,
        override val regionsAffected: Set<RegionId>
    ) : CommitOperation() {
        override val operationType = "bulk_block_change"
        override val isIndependent = true
    }

    /**
     * Piston / block mover — DEPENDENT (sekwencyjnie).
     *
     * ERRATA: pushGroupId groups slime block contraption moves together.
     * sequenceNumber orders moves within a group (back-to-front, like vanilla).
     * CommitPhaseExecutor sorts by pushGroupId first, then sequenceNumber descending.
     */
    data class BlockMove(
        val sourcePos: Long,
        val targetPos: Long,
        val blockStateId: Int,
        val pushGroupId: UUID? = null,
        val sequenceNumber: Int = 0,
        override val regionsAffected: Set<RegionId>,
        override val isIndependent: Boolean = false
    ) : CommitOperation() {
        override val operationType = "block_move"
    }

    data class BlockChange(
        val x: Int, val y: Int, val z: Int,
        val blockStateId: Int
    )
}
