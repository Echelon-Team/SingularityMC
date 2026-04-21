// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.chunk

import com.singularity.agent.threading.memory.HeapMonitor

class AggressiveUnloadStrategy {
    fun computeUnloadBatch(heapState: HeapMonitor.HeapState, loadedChunkCount: Int): Int {
        return when (heapState) {
            HeapMonitor.HeapState.NORMAL -> 0
            HeapMonitor.HeapState.AGGRESSIVE_UNLOAD -> (loadedChunkCount * 0.1).toInt().coerceAtLeast(1)
            HeapMonitor.HeapState.PAUSE_PREGEN -> (loadedChunkCount * 0.25).toInt().coerceAtLeast(1)
        }
    }

    fun shouldPauseGeneration(heapState: HeapMonitor.HeapState): Boolean =
        heapState == HeapMonitor.HeapState.PAUSE_PREGEN
}
