package com.singularity.agent.threading.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ThreadingConfigTest {

    @Test
    fun `defaults match design spec`() {
        val config = ThreadingConfig()
        assertEquals(8, config.regionSizeChunks)
        assertEquals(8, config.totalThreads)
        assertEquals(3000L, config.barrierTimeoutMs)
        assertEquals(0.80, config.heapAggressiveUnloadThreshold)
        assertEquals(0.90, config.heapPauseGenerationThreshold)
        assertEquals(20, config.stuckFallbackTicks)
        assertEquals(600, config.stuckFallbackMaxTicks)
    }

    @Test
    fun `regionSizeBlocks calculated from chunks`() {
        val config = ThreadingConfig(regionSizeChunks = 8)
        assertEquals(128, config.regionSizeBlocks)
    }

    @Test
    fun `custom region size 4 chunks = 64 blocks`() {
        val config = ThreadingConfig(regionSizeChunks = 4)
        assertEquals(64, config.regionSizeBlocks)
    }

    @Test
    fun `custom region size 16 chunks = 256 blocks`() {
        val config = ThreadingConfig(regionSizeChunks = 16)
        assertEquals(256, config.regionSizeBlocks)
    }

    @Test
    fun `validates region size is in allowed range`() {
        assertDoesNotThrow { ThreadingConfig(regionSizeChunks = 4) }
        assertDoesNotThrow { ThreadingConfig(regionSizeChunks = 8) }
        assertDoesNotThrow { ThreadingConfig(regionSizeChunks = 16) }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(regionSizeChunks = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(regionSizeChunks = 32)
        }
    }

    @Test
    fun `validates thread count minimum 4`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(totalThreads = 3)
        }
        assertDoesNotThrow { ThreadingConfig(totalThreads = 4) }
    }

    @Test
    fun `validates heap thresholds are ordered`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(
                heapAggressiveUnloadThreshold = 0.95,
                heapPauseGenerationThreshold = 0.90
            )
        }
    }

    @Test
    fun `splitMerge hysteresis thresholds`() {
        val config = ThreadingConfig()
        assertTrue(config.splitLoadThreshold > config.mergeLoadThreshold)
    }

    @Test
    fun `regionShift correct for power-of-2 sizes`() {
        assertEquals(6, ThreadingConfig(regionSizeChunks = 4).regionShift)  // 64 = 2^6
        assertEquals(7, ThreadingConfig(regionSizeChunks = 8).regionShift)  // 128 = 2^7
        assertEquals(8, ThreadingConfig(regionSizeChunks = 16).regionShift) // 256 = 2^8
    }

    @Test
    fun `barrierTimeoutMs must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(barrierTimeoutMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(barrierTimeoutMs = -1)
        }
    }

    @Test
    fun `splitLoadThreshold must be greater than mergeLoadThreshold`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(splitLoadThreshold = 100, mergeLoadThreshold = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(splitLoadThreshold = 50, mergeLoadThreshold = 100)
        }
    }

    @Test
    fun `heap thresholds equal throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(
                heapAggressiveUnloadThreshold = 0.90,
                heapPauseGenerationThreshold = 0.90
            )
        }
    }

    @Test
    fun `non-power-of-2 region sizes rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(regionSizeChunks = 6)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadingConfig(regionSizeChunks = 12)
        }
    }
}
