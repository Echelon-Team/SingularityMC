// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.loadingscreen

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LoadingScreenRendererTest {

    @Test
    fun `renderer starts and stops in console fallback mode`() {
        // LWJGL not on test classpath → console fallback
        val state = LoadingScreenState()
        val renderer = LoadingScreenRenderer(state)

        renderer.start()
        Thread.sleep(200) // let console fallback start

        state.setProgress(50)
        state.setCurrentStage("Loading mods...")
        Thread.sleep(600) // let it log

        state.markFinished()
        renderer.stop()
        // No exception = success
    }

    @Test
    fun `renderer handles immediate finish`() {
        val state = LoadingScreenState()
        state.markFinished() // finished before start
        val renderer = LoadingScreenRenderer(state)
        renderer.start()
        renderer.stop()
        // Should exit immediately, no hang
    }

    @Test
    fun `stop is safe without start`() {
        val state = LoadingScreenState()
        val renderer = LoadingScreenRenderer(state)
        assertDoesNotThrow { renderer.stop() }
    }

    @Test
    fun `stop is safe to call multiple times`() {
        val state = LoadingScreenState()
        val renderer = LoadingScreenRenderer(state)
        renderer.start()
        Thread.sleep(100)
        state.markFinished()
        assertDoesNotThrow {
            renderer.stop()
            renderer.stop()
        }
    }
}
