// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.commit

import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.pool.DimensionPool
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wykonuje commit phase ticka.
 *
 * Design spec 4.5 warstwa 2A:
 * - DEPENDENT operations wykonywane SEKWENCYJNIE (block movers, pistony)
 * - INDEPENDENT operations wykonywane PARALLEL per region (eksplozje, /fill, WorldEdit)
 * - DEPENDENT przed INDEPENDENT
 *
 * ERRATA: DEPENDENT ops sorted by pushGroupId (null last), then sequenceNumber
 * descending (back-to-front, like vanilla piston ordering for slime block contraptions).
 *
 * handler — callback wykonywany dla każdej operacji. To jest miejsce gdzie
 * compat module wstawia faktyczną logikę MC (np. piston move, explosion damage).
 */
class CommitPhaseExecutor(
    private val pool: DimensionPool,
    private val config: ThreadingConfig
) {
    private val logger = LoggerFactory.getLogger(CommitPhaseExecutor::class.java)

    fun executeCommit(operations: List<CommitOperation>, handler: (CommitOperation) -> Unit) {
        if (operations.isEmpty()) return

        val (dependent, independent) = operations.partition { !it.isIndependent }

        // Faza 1: DEPENDENT sekwencyjnie, sorted by pushGroupId then sequenceNumber desc
        val sortedDependent = dependent.sortedWith(dependentComparator)
        for (op in sortedDependent) {
            try {
                handler(op)
            } catch (e: Exception) {
                logger.error("DEPENDENT commit operation '{}' failed: {}", op.operationType, e.message, e)
            }
        }

        // Faza 2: INDEPENDENT parallel
        if (independent.isNotEmpty()) {
            val latch = CountDownLatch(independent.size)
            for (op in independent) {
                pool.submit {
                    try {
                        handler(op)
                    } catch (e: Exception) {
                        logger.error("INDEPENDENT commit operation '{}' failed: {}", op.operationType, e.message, e)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            val completed = latch.await(config.barrierTimeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                logger.warn("Commit phase timeout — {} INDEPENDENT operations did not complete", latch.count)
            }
        }
    }

    companion object {
        /**
         * Sorting for DEPENDENT operations:
         * 1. pushGroupId non-null before null (grouped piston moves first)
         * 2. Within same pushGroupId: sequenceNumber descending (back-to-front)
         * 3. Non-BlockMove dependent ops: after all BlockMoves with groups, before ungrouped
         */
        internal val dependentComparator = Comparator<CommitOperation> { a, b ->
            val aMove = a as? CommitOperation.BlockMove
            val bMove = b as? CommitOperation.BlockMove

            val aGroupId = aMove?.pushGroupId
            val bGroupId = bMove?.pushGroupId

            // Non-BlockMove ops have null groupId, treat same as ungrouped BlockMove
            when {
                aGroupId != null && bGroupId == null -> -1
                aGroupId == null && bGroupId != null -> 1
                aGroupId != null && bGroupId != null -> {
                    val groupCmp = aGroupId.compareTo(bGroupId)
                    if (groupCmp != 0) groupCmp
                    else (bMove.sequenceNumber - aMove.sequenceNumber) // descending
                }
                else -> 0 // both null — preserve insertion order
            }
        }
    }
}
