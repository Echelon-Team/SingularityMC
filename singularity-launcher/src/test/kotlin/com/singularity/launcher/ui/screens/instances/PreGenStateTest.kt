// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.instances

import com.singularity.launcher.config.PreGenPreset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PreGenStateTest {

    @Test
    fun `detectPresetFromRadius returns POTATO for 32`() {
        assertEquals(PreGenPreset.POTATO, PreGenStateLogic.detectPresetFromRadius(32))
    }

    @Test
    fun `detectPresetFromRadius returns MEDIUM for 64`() {
        assertEquals(PreGenPreset.MEDIUM, PreGenStateLogic.detectPresetFromRadius(64))
    }

    @Test
    fun `detectPresetFromRadius returns HIGH for 128`() {
        assertEquals(PreGenPreset.HIGH, PreGenStateLogic.detectPresetFromRadius(128))
    }

    @Test
    fun `detectPresetFromRadius returns FIREPLACE for 256`() {
        assertEquals(PreGenPreset.FIREPLACE, PreGenStateLogic.detectPresetFromRadius(256))
    }

    @Test
    fun `detectPresetFromRadius returns null for non-preset values`() {
        assertNull(PreGenStateLogic.detectPresetFromRadius(50), "Custom value not a preset")
        assertNull(PreGenStateLogic.detectPresetFromRadius(100))
        assertNull(PreGenStateLogic.detectPresetFromRadius(200))
    }

    @Test
    fun `calculateEta estimates minutes from progress`() {
        // 500/1000 chunks in 60s → 1000 chunks/min → 500 remaining → 0.5 min
        // Formula: chunksPerSec = 500/60 = 8.33; remaining = 500; remSec = 60; remMin = 1.0
        val eta = PreGenStateLogic.calculateEtaMinutes(currentChunks = 500, totalChunks = 1000, elapsedSec = 60L)
        assertNotNull(eta)
        assertEquals(1.0, eta!!, 0.1)
    }

    @Test
    fun `calculateEta returns null for zero elapsed`() {
        assertNull(PreGenStateLogic.calculateEtaMinutes(100, 1000, 0L))
    }

    @Test
    fun `calculateEta returns null when current is zero`() {
        assertNull(PreGenStateLogic.calculateEtaMinutes(0, 1000, 60L))
    }
}
