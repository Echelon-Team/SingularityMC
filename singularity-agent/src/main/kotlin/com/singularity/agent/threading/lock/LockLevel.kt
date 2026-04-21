// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.lock

/**
 * Hierarchia locków — strict ordering eliminujący ABBA deadlocki.
 *
 * Design spec 4.5A:
 * Każdy wątek MUSI brać locki WYŁĄCZNIE w kolejności od poziomu 0 w dół.
 * Trzymając lock na poziomie N, można wziąć TYLKO lock na poziomie > N.
 */
enum class LockLevel(val level: Int, val description: String) {
    BARRIER(0, "Tick phase synchronization barriers"),
    REGION_OWNERSHIP(1, "Exclusive tick access to a region"),
    BOUNDARY_BUFFER(2, "Cross-region reads via boundary buffer"),
    MESSAGE_QUEUE(3, "Cross-region message communication"),
    PER_CHUNK(4, "Chunk data access locks"),
    WRAPPER(5, "Shared game state thread-safe wrappers"),
    PER_REGION_FILE(6, "I/O — region file (.mca) locks");

    companion object {
        fun canAcquire(currentLevel: LockLevel?, newLevel: LockLevel): Boolean {
            if (currentLevel == null) return true
            return newLevel.level > currentLevel.level
        }
    }
}
