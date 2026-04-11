package com.singularity.launcher.ui.screens.instances

import com.singularity.launcher.config.PreGenPreset

/**
 * Pure logic helpers dla Pre-gen tab UI state — testowalne bez Compose.
 */
object PreGenStateLogic {

    fun detectPresetFromRadius(radius: Int): PreGenPreset? =
        PreGenPreset.entries.find { it.defaultRadius == radius }

    fun calculateEtaMinutes(currentChunks: Int, totalChunks: Int, elapsedSec: Long): Double? {
        if (elapsedSec <= 0L || currentChunks <= 0) return null
        val chunksPerSec = currentChunks.toDouble() / elapsedSec.toDouble()
        val remaining = (totalChunks - currentChunks).coerceAtLeast(0)
        val remainingSec = remaining.toDouble() / chunksPerSec
        return remainingSec / 60.0
    }
}
