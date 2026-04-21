// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading

import com.singularity.agent.registry.SingularityModRegistry
import com.singularity.agent.threading.config.ThreadingConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach

class ThreadingEngineTest {

    private val emptyRegistry = SingularityModRegistry()

    private var engine: ThreadingEngine? = null

    @AfterEach
    fun cleanup() { engine?.shutdown() }

    @Test
    fun `initialize creates grids and executors for all dimensions`() {
        engine = ThreadingEngine(ThreadingConfig(), emptyRegistry)
        engine!!.initialize(listOf("overworld", "the_nether", "the_end"))
        assertNotNull(engine!!.getGrid("overworld"))
        assertNotNull(engine!!.getGrid("the_nether"))
        assertNotNull(engine!!.getGrid("the_end"))
        assertNotNull(engine!!.getTickExecutor("overworld"))
        assertTrue(engine!!.isInitialized())
    }

    @Test
    fun `double initialize throws`() {
        engine = ThreadingEngine(ThreadingConfig(), emptyRegistry)
        engine!!.initialize(listOf("overworld"))
        assertThrows(IllegalStateException::class.java) {
            engine!!.initialize(listOf("overworld"))
        }
    }

    @Test
    fun `shutdown marks engine as not initialized`() {
        engine = ThreadingEngine(ThreadingConfig(), emptyRegistry)
        engine!!.initialize(listOf("overworld"))
        engine!!.shutdown()
        assertFalse(engine!!.isInitialized())
    }

    @Test
    fun `crossDimensionTransfers queue available`() {
        engine = ThreadingEngine(ThreadingConfig(), emptyRegistry)
        engine!!.initialize(listOf("overworld"))
        assertNotNull(engine!!.getCrossDimensionTransfers())
    }
}
