package com.singularity.agent.threading.commit

import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.pool.DimensionPool
import com.singularity.agent.threading.region.RegionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class CommitPhaseExecutorTest {

    private lateinit var pool: DimensionPool
    private lateinit var executor: CommitPhaseExecutor

    @BeforeEach
    fun setup() {
        pool = DimensionPool("overworld", 4)
        executor = CommitPhaseExecutor(pool, ThreadingConfig())
    }

    @AfterEach
    fun cleanup() {
        pool.shutdown()
    }

    @Test
    fun `executes empty commit gracefully`() {
        executor.executeCommit(emptyList()) { _ -> }
    }

    @Test
    fun `runs all submitted operations`() {
        val processed = ConcurrentLinkedQueue<String>()
        val ops = listOf(
            CommitOperation.Explosion(0.0, 64.0, 0.0, 4f, setOf(RegionId(0, 0))),
            CommitOperation.BulkBlockChange(emptyList(), setOf(RegionId(1, 0))),
            CommitOperation.BlockMove(0L, 1L, 1, regionsAffected = setOf(RegionId(0, 0)))
        )

        executor.executeCommit(ops) { op ->
            processed.add(op.operationType)
        }

        assertEquals(3, processed.size)
    }

    @Test
    fun `DEPENDENT operations run BEFORE INDEPENDENT`() {
        val executionOrder = ConcurrentLinkedQueue<String>()

        val ops = listOf(
            CommitOperation.Explosion(0.0, 64.0, 0.0, 4f, setOf(RegionId(0, 0))),
            CommitOperation.BlockMove(0L, 1L, 1, regionsAffected = setOf(RegionId(0, 0))),
            CommitOperation.BulkBlockChange(emptyList(), setOf(RegionId(1, 0))),
            CommitOperation.BlockMove(2L, 3L, 1, regionsAffected = setOf(RegionId(2, 0)))
        )

        executor.executeCommit(ops) { op ->
            executionOrder.add(op.operationType)
        }

        val list = executionOrder.toList()
        val firstIndependentIdx = list.indexOfFirst { it == "explosion" || it == "bulk_block_change" }
        val lastDependentIdx = list.indexOfLast { it == "block_move" }
        assertTrue(lastDependentIdx < firstIndependentIdx,
            "DEPENDENT (block_move) must finish before INDEPENDENT (explosion/bulk). Order: $list")
    }

    @Test
    fun `independent operations run in parallel`() {
        val ops = (1..8).map { i ->
            CommitOperation.BulkBlockChange(emptyList(), setOf(RegionId(i, 0)))
        }

        val start = System.currentTimeMillis()
        executor.executeCommit(ops) { _ ->
            Thread.sleep(50)
        }
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed < 350, "Expected parallel execution but took ${elapsed}ms")
    }

    @Test
    fun `exception in one operation does not break others`() {
        val processed = ConcurrentLinkedQueue<String>()
        val ops = listOf(
            CommitOperation.Explosion(0.0, 64.0, 0.0, 4f, setOf(RegionId(0, 0))),
            CommitOperation.Explosion(0.0, 64.0, 0.0, 4f, setOf(RegionId(1, 0))),
            CommitOperation.Explosion(0.0, 64.0, 0.0, 4f, setOf(RegionId(2, 0)))
        )

        executor.executeCommit(ops) { op ->
            if ((op as CommitOperation.Explosion).regionsAffected.contains(RegionId(1, 0))) {
                throw RuntimeException("simulated failure")
            }
            processed.add(op.operationType)
        }

        assertEquals(2, processed.size)
    }

    @Test
    fun `DEPENDENT ops sorted by pushGroupId then sequenceNumber descending`() {
        val executionOrder = ConcurrentLinkedQueue<Int>()
        val groupA = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val groupB = UUID.fromString("00000000-0000-0000-0000-000000000002")

        val ops = listOf(
            // Ungrouped (null pushGroupId) — should come last among dependent
            CommitOperation.BlockMove(10L, 11L, 1, null, 0, setOf(RegionId(0, 0))),
            // Group B seq 0
            CommitOperation.BlockMove(20L, 21L, 1, groupB, 0, setOf(RegionId(0, 0))),
            // Group A seq 1
            CommitOperation.BlockMove(30L, 31L, 1, groupA, 1, setOf(RegionId(0, 0))),
            // Group A seq 3 (should come first in group A — descending)
            CommitOperation.BlockMove(40L, 41L, 1, groupA, 3, setOf(RegionId(0, 0))),
            // Group A seq 2
            CommitOperation.BlockMove(50L, 51L, 1, groupA, 2, setOf(RegionId(0, 0))),
            // Group B seq 2
            CommitOperation.BlockMove(60L, 61L, 1, groupB, 2, setOf(RegionId(0, 0)))
        )

        executor.executeCommit(ops) { op ->
            executionOrder.add((op as CommitOperation.BlockMove).sequenceNumber * 100 +
                (op.pushGroupId?.leastSignificantBits?.toInt() ?: -1))
        }

        val list = executionOrder.toList()
        assertEquals(6, list.size)

        // Group A (UUID ...001) comes before Group B (UUID ...002) by UUID natural order
        // Within Group A: seq 3, 2, 1 (descending)
        // Within Group B: seq 2, 0 (descending)
        // Ungrouped: last
        val sourcePositions = mutableListOf<Long>()
        val ops2 = listOf(
            CommitOperation.BlockMove(10L, 11L, 1, null, 0, setOf(RegionId(0, 0))),
            CommitOperation.BlockMove(20L, 21L, 1, groupB, 0, setOf(RegionId(0, 0))),
            CommitOperation.BlockMove(30L, 31L, 1, groupA, 1, setOf(RegionId(0, 0))),
            CommitOperation.BlockMove(40L, 41L, 1, groupA, 3, setOf(RegionId(0, 0))),
            CommitOperation.BlockMove(50L, 51L, 1, groupA, 2, setOf(RegionId(0, 0))),
            CommitOperation.BlockMove(60L, 61L, 1, groupB, 2, setOf(RegionId(0, 0)))
        )

        val sorted = ops2.sortedWith(CommitPhaseExecutor.dependentComparator)
        val sortedSources = sorted.map { it.sourcePos }

        // Group A (seq desc): 40, 50, 30 then Group B (seq desc): 60, 20 then ungrouped: 10
        assertEquals(listOf(40L, 50L, 30L, 60L, 20L, 10L), sortedSources)
    }

    @Test
    fun `BlockMove with pushGroupId non-null sorts before null`() {
        val group = UUID.randomUUID()
        val ops = listOf(
            CommitOperation.BlockMove(1L, 2L, 1, null, 0, setOf(RegionId(0, 0))),
            CommitOperation.BlockMove(3L, 4L, 1, group, 0, setOf(RegionId(0, 0)))
        )

        val sorted = ops.sortedWith(CommitPhaseExecutor.dependentComparator)
        assertNotNull(sorted[0].pushGroupId)
        assertNull(sorted[1].pushGroupId)
    }
}
