// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.pool

import org.slf4j.LoggerFactory
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DimensionPool(
    val dimensionId: String,
    val threadCount: Int
) {
    private val logger = LoggerFactory.getLogger(DimensionPool::class.java)
    private val threadIdCounter = AtomicInteger(0)

    private val pool: ForkJoinPool = ForkJoinPool(
        threadCount,
        { pool ->
            val worker = object : ForkJoinWorkerThread(pool) {}
            worker.name = "singularity-$dimensionId-${threadIdCounter.incrementAndGet()}"
            worker.isDaemon = true
            worker
        },
        { thread, exception ->
            logger.error("Uncaught exception in pool {} thread {}: {}",
                dimensionId, thread.name, exception.message, exception)
        },
        true // async mode (FIFO)
    )

    val isShutdown: Boolean get() = pool.isShutdown

    fun submit(task: Runnable) { pool.submit(task) }

    fun invokeAll(tasks: List<Runnable>) {
        val futures = tasks.map { pool.submit(it) }
        futures.forEach { it.get() }
    }

    fun awaitQuiescence(timeout: Long, unit: TimeUnit): Boolean =
        pool.awaitQuiescence(timeout, unit)

    fun shutdown() {
        pool.shutdown()
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow()
                logger.warn("Pool {} did not terminate gracefully, forced shutdown", dimensionId)
            }
        } catch (e: InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
