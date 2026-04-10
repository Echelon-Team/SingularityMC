package com.singularity.agent.threading.chunk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChunkGenerationPipelineTest {
    data class TestChunk(val x: Int, val z: Int, var stage: ChunkStage = ChunkStage.NOISE)

    @Test
    fun `pipeline executes all 6 stages in order`() {
        val pipeline = ChunkGenerationPipeline<TestChunk>(threadCount = 2)
        val stagesSeen = mutableListOf<ChunkStage>()
        val latch = CountDownLatch(1)
        pipeline.submitChunk(TestChunk(0, 0), onComplete = { latch.countDown() }) { chunk, stage ->
            synchronized(stagesSeen) { stagesSeen.add(stage) }; chunk.stage = stage
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(6, stagesSeen.size)
        assertEquals(ChunkStage.NOISE, stagesSeen[0])
        assertEquals(ChunkStage.FULL, stagesSeen[5])
        pipeline.shutdown()
    }

    @Test
    fun `multiple chunks processed in parallel`() {
        val pipeline = ChunkGenerationPipeline<TestChunk>(threadCount = 4)
        val processed = AtomicInteger(0)
        val latch = CountDownLatch(8)
        repeat(8) { i ->
            pipeline.submitChunk(TestChunk(i, 0), onComplete = { latch.countDown() }) { _, _ -> processed.incrementAndGet() }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(48, processed.get())
        pipeline.shutdown()
    }

    @Test
    fun `exception in one chunk does not break pipeline`() {
        val pipeline = ChunkGenerationPipeline<TestChunk>(threadCount = 2)
        val survived = AtomicInteger(0)
        val latch = CountDownLatch(2)
        pipeline.submitChunk(TestChunk(0, 0), onComplete = { latch.countDown() }) { _, stage ->
            if (stage == ChunkStage.FEATURES) throw RuntimeException("boom")
        }
        pipeline.submitChunk(TestChunk(1, 0), onComplete = { latch.countDown() }) { _, _ -> survived.incrementAndGet() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(6, survived.get())
        pipeline.shutdown()
    }
}
