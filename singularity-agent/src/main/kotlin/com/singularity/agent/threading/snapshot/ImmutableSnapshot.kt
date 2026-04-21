// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.snapshot

import com.singularity.agent.threading.region.RegionId

/**
 * Immutable snapshot of region state for cross-region reads.
 *
 * ADDENDUM v2 errata:
 * - freeze/unfreeze: setBlock/setBlockEntity internal, freeze() after population
 * - BlockEntity NBT via defensive copy
 * - Y encoding fixed for negative values (-64..319 in MC 1.20.1)
 *
 * Lifecycle: acquire from pool → populate (setBlock/setBlockEntity) → freeze → read-only → release → clear
 */
class ImmutableSnapshot internal constructor() {
    @Volatile var regionId: RegionId = RegionId(0, 0)
        internal set
    @Volatile var tickNumber: Long = -1
        internal set

    // HashMap (not ConcurrentHashMap) — written single-threaded during populate,
    // then frozen (@Volatile write provides happens-before for safe publication),
    // then read multi-threaded. No concurrent mutation → HashMap is safe and faster.
    private val blocks = HashMap<Long, Int>()
    private val blockEntities = HashMap<Long, Map<String, Any>>()

    @Volatile private var frozen = false

    fun getBlock(blockX: Int, blockY: Int, blockZ: Int): Int =
        blocks[encodePosition(blockX, blockY, blockZ)] ?: 0

    fun getBlockEntity(blockX: Int, blockY: Int, blockZ: Int): Map<String, Any>? =
        blockEntities[encodePosition(blockX, blockY, blockZ)]

    internal fun setBlock(blockX: Int, blockY: Int, blockZ: Int, blockStateId: Int) {
        check(!frozen) { "Cannot modify frozen snapshot" }
        blocks[encodePosition(blockX, blockY, blockZ)] = blockStateId
    }

    internal fun setBlockEntity(blockX: Int, blockY: Int, blockZ: Int, nbtData: Map<String, Any>) {
        check(!frozen) { "Cannot modify frozen snapshot" }
        blockEntities[encodePosition(blockX, blockY, blockZ)] = nbtData.toMap()
    }

    internal fun freeze() { frozen = true }

    fun isFrozen(): Boolean = frozen

    internal fun clear() {
        frozen = false
        blocks.clear()
        blockEntities.clear()
    }

    fun size(): Int = blocks.size

    private fun encodePosition(x: Int, y: Int, z: Int): Long {
        // Y shifted by +64 for negative support (-64..319 → 0..383)
        val adjustedY = (y + 64).toLong() and 0x1FF
        return ((x.toLong() and 0x3FFFFFF) shl 35) or
               (adjustedY shl 26) or
               (z.toLong() and 0x3FFFFFF)
    }
}
