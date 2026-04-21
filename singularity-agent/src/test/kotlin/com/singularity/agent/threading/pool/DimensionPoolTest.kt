// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.pool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DimensionPoolTest {

    private val pools = mutableListOf<DimensionPool>()

    @AfterEach
    fun cleanup() {
        pools.forEach { it.shutdown() }
        pools.clear()
    }

    private fun newPool(dimensionId: String, threads: Int): DimensionPool {
        val pool = DimensionPool(dimensionId, threads)
        pools.add(pool)
        return pool
    }

    @Test
    fun `pool runs submitted task`() {
        val pool = newPool("overworld", 4)
        val executed = AtomicInteger(0)
        pool.submit { executed.incrementAndGet() }
        pool.awaitQuiescence(1, TimeUnit.SECONDS)
        assertEquals(1, executed.get())
    }

    @Test
    fun `pool runs multiple tasks in parallel`() {
        val pool = newPool("overworld", 4)
        val latch = CountDownLatch(4)
        val counter = AtomicInteger(0)
        repeat(4) {
            pool.submit { counter.incrementAndGet(); latch.countDown() }
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(4, counter.get())
    }

    @Test
    fun `pool uses work-stealing for submitted tasks`() {
        val pool = newPool("overworld", 4)
        val executed = AtomicInteger(0)
        val latch = CountDownLatch(100)
        repeat(100) {
            pool.submit { executed.incrementAndGet(); latch.countDown() }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(100, executed.get())
    }

    @Test
    fun `dimension isolation — separate pools do not share threads`() {
        val pool1 = newPool("overworld", 2)
        val pool2 = newPool("nether", 2)
        val thread1 = java.util.concurrent.atomic.AtomicReference<Thread>()
        val thread2 = java.util.concurrent.atomic.AtomicReference<Thread>()
        val latch = CountDownLatch(2)
        pool1.submit { thread1.set(Thread.currentThread()); latch.countDown() }
        pool2.submit { thread2.set(Thread.currentThread()); latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(thread1.get())
        assertNotNull(thread2.get())
        assertTrue(thread1.get().name.contains("overworld"))
        assertTrue(thread2.get().name.contains("nether"))
    }

    @Test
    fun `threadCount matches constructor argument`() {
        val pool = newPool("overworld", 6)
        assertEquals(6, pool.threadCount)
    }

    @Test
    fun `shutdown stops pool`() {
        val pool = newPool("overworld", 2)
        pool.shutdown()
        assertTrue(pool.isShutdown)
    }

    @Test
    fun `dimensionId is preserved`() {
        val pool = newPool("the_end", 2)
        assertEquals("the_end", pool.dimensionId)
    }

    @Test
    fun `submit after shutdown throws`() {
        val pool = newPool("overworld", 2)
        pool.shutdown()
        assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
            pool.submit { }
        }
    }

    @Test
    fun `invokeAll propagates task exception`() {
        val pool = newPool("overworld", 4)
        val tasks = listOf(Runnable { throw RuntimeException("boom") })
        assertThrows(java.util.concurrent.ExecutionException::class.java) {
            pool.invokeAll(tasks)
        }
    }

    // --- DimensionPoolManager tests ---

    @Test
    fun `manager createPool returns same instance for same id`() {
        val manager = DimensionPoolManager()
        val p1 = manager.createPool("overworld", 4)
        val p2 = manager.createPool("overworld", 4)
        pools.add(p1) // cleanup
        assertSame(p1, p2)
    }

    @Test
    fun `manager getPool returns null for unknown dimension`() {
        val manager = DimensionPoolManager()
        assertNull(manager.getPool("unknown"))
    }

    @Test
    fun `manager getAllPools returns all created`() {
        val manager = DimensionPoolManager()
        val p1 = manager.createPool("overworld", 2)
        val p2 = manager.createPool("nether", 2)
        pools.addAll(listOf(p1, p2))
        assertEquals(2, manager.getAllPools().size)
    }

    @Test
    fun `manager shutdownAll stops all pools`() {
        val manager = DimensionPoolManager()
        val p1 = manager.createPool("overworld", 2)
        val p2 = manager.createPool("nether", 2)
        manager.shutdownAll()
        assertTrue(p1.isShutdown)
        assertTrue(p2.isShutdown)
        // Don't add to pools list — already shut down by manager
    }

    @Test
    fun `invokeAll runs tasks and waits for completion`() {
        val pool = newPool("overworld", 4)
        val counter = AtomicInteger(0)
        val tasks = (1..10).map { i -> Runnable { counter.addAndGet(i) } }
        pool.invokeAll(tasks)
        assertEquals(55, counter.get())
    }
}
