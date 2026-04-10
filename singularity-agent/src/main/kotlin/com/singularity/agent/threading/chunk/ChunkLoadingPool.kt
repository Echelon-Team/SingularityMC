package com.singularity.agent.threading.chunk

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChunkLoadingPool(threadCount: Int = 2) {
    private val logger = LoggerFactory.getLogger(ChunkLoadingPool::class.java)
    private val threadCounter = AtomicInteger(0)
    private val executor = Executors.newFixedThreadPool(threadCount) { runnable ->
        Thread(runnable, "singularity-chunk-io-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    fun submitLoad(chunkX: Int, chunkZ: Int, loader: (Int, Int) -> Any?): java.util.concurrent.Future<Any?> {
        return executor.submit<Any?> { loader(chunkX, chunkZ) }
    }

    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) { executor.shutdownNow() }
    }
}
