// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.region

import com.singularity.agent.threading.config.ThreadingConfig

class RegionScheduler(
    private val grid: RegionGrid,
    private val config: ThreadingConfig = ThreadingConfig()
) {
    companion object {
        private const val MEDIUM_RANGE = 2
    }

    fun priorityFor(region: Region, players: List<PlayerPosition>): RegionPriority {
        if (!region.isActive()) return RegionPriority.SKIP
        if (players.isEmpty()) return RegionPriority.LOW

        val minDistance = players.minOf { player ->
            val playerRegionId = RegionId.fromBlockCoords(
                player.blockX, player.blockZ, config.regionSizeBlocks
            )
            region.id.manhattanDistance(playerRegionId)
        }

        return when {
            minDistance == 0 -> RegionPriority.HIGH
            minDistance <= MEDIUM_RANGE -> RegionPriority.MEDIUM
            else -> RegionPriority.LOW
        }
    }

    fun buildSchedule(playerPositions: List<PlayerPosition>): List<Region> {
        val active = grid.getActiveRegions()
        return active
            .map { region -> region to priorityFor(region, playerPositions) }
            .filter { (_, priority) -> priority != RegionPriority.SKIP }
            .sortedBy { (_, priority) -> priority.order }
            .map { (region, _) -> region }
    }
}
