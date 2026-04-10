package com.singularity.agent.threading.chunk

import com.singularity.agent.threading.memory.HeapMonitor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.TimeUnit

class ChunkLoadingPoolTest {
    @Test
    fun `pool loads chunk and returns result`() {
        val pool = ChunkLoadingPool(2)
        val future = pool.submitLoad(0, 0) { x, z -> "chunk-$x-$z" }
        assertEquals("chunk-0-0", future.get(2, TimeUnit.SECONDS))
        pool.shutdown()
    }

    @Test
    fun `aggressive unload returns 0 for NORMAL`() {
        val strategy = AggressiveUnloadStrategy()
        assertEquals(0, strategy.computeUnloadBatch(HeapMonitor.HeapState.NORMAL, 100))
    }

    @Test
    fun `aggressive unload returns 10 percent for AGGRESSIVE_UNLOAD`() {
        val strategy = AggressiveUnloadStrategy()
        assertEquals(10, strategy.computeUnloadBatch(HeapMonitor.HeapState.AGGRESSIVE_UNLOAD, 100))
    }

    @Test
    fun `aggressive unload returns 25 percent for PAUSE_PREGEN`() {
        val strategy = AggressiveUnloadStrategy()
        assertEquals(25, strategy.computeUnloadBatch(HeapMonitor.HeapState.PAUSE_PREGEN, 100))
    }

    @Test
    fun `shouldPauseGeneration true only for PAUSE_PREGEN`() {
        val strategy = AggressiveUnloadStrategy()
        assertFalse(strategy.shouldPauseGeneration(HeapMonitor.HeapState.NORMAL))
        assertFalse(strategy.shouldPauseGeneration(HeapMonitor.HeapState.AGGRESSIVE_UNLOAD))
        assertTrue(strategy.shouldPauseGeneration(HeapMonitor.HeapState.PAUSE_PREGEN))
    }
}
