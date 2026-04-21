// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.message

import com.singularity.agent.threading.region.RegionId

sealed class RegionMessage {
    abstract val sourceRegion: RegionId
    abstract val targetRegion: RegionId

    data class SetBlock(
        override val sourceRegion: RegionId,
        override val targetRegion: RegionId,
        val blockX: Int, val blockY: Int, val blockZ: Int,
        val blockStateId: Int, val flags: Int = 3
    ) : RegionMessage()

    data class DamageEntity(
        override val sourceRegion: RegionId,
        override val targetRegion: RegionId,
        val entityUuid: java.util.UUID,
        val damage: Float, val sourceDescription: String
    ) : RegionMessage()

    data class InteractBlock(
        override val sourceRegion: RegionId,
        override val targetRegion: RegionId,
        val blockX: Int, val blockY: Int, val blockZ: Int,
        val interactionType: String, val payload: Any?
    ) : RegionMessage()

    data class CustomData(
        override val sourceRegion: RegionId,
        override val targetRegion: RegionId,
        val key: String, val payload: Any?
    ) : RegionMessage()
}
