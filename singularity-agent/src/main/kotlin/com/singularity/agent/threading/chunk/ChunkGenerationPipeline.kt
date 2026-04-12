package com.singularity.agent.threading.chunk

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChunkGenerationPipeline<C>(threadCount: Int) {
    private val logger = LoggerFactory.getLogger(ChunkGenerationPipeline::class.java)
    private val threadCounter = AtomicInteger(0)
    private val pendingCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)
    private val executor = Executors.newFixedThreadPool(threadCount) { runnable ->
        Thread(runnable, "singularity-chunk-gen-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    fun submitChunk(chunk: C, onComplete: () -> Unit, stageHandler: (C, ChunkStage) -> Unit) {
        pendingCount.incrementAndGet()
        executor.submit {
            try {
                for (stage in ChunkStage.entries) { stageHandler(chunk, stage) }
                completedCount.incrementAndGet()
                onComplete()
            } catch (e: Exception) {
                logger.error("Chunk generation failed: {}", e.message, e)
                onComplete()
            } finally {
                pendingCount.decrementAndGet()
            }
        }
    }

    fun getPendingCount(): Int = pendingCount.get()
    fun getCompletedCount(): Int = completedCount.get()

    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) { executor.shutdownNow() }
    }
}
