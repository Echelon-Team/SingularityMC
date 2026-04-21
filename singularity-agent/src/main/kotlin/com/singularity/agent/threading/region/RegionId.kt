// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.region

import kotlin.math.abs

/**
 * Identyfikator regionu w wymiarze — koordynaty na siatce regionów (x, z).
 *
 * Region pokrywa obszar regionSizeBlocks × regionSizeBlocks bloków.
 * Y nie wchodzi do identyfikacji — regiony są 2D (pełny słup Y należy do jednego regionu).
 *
 * Referencja: design spec sekcja 4.3 (region-based entity ticking).
 */
data class RegionId(val x: Int, val z: Int) {

    /** Zwraca 8 sąsiadujących regionów (Moore neighborhood). */
    fun neighbors(): List<RegionId> = listOf(
        RegionId(x - 1, z - 1), RegionId(x, z - 1), RegionId(x + 1, z - 1),
        RegionId(x - 1, z),                           RegionId(x + 1, z),
        RegionId(x - 1, z + 1), RegionId(x, z + 1), RegionId(x + 1, z + 1)
    )

    /** Czy dany region jest sąsiadem (Moore — diagonalnie też)? */
    fun isAdjacent(other: RegionId): Boolean {
        if (this == other) return false
        val dx = abs(x - other.x)
        val dz = abs(z - other.z)
        return dx <= 1 && dz <= 1
    }

    /** Dystans Manhattan między regionami (dla priorytetyzacji). */
    fun manhattanDistance(other: RegionId): Int =
        abs(x - other.x) + abs(z - other.z)

    companion object {
        /**
         * Oblicza RegionId z koordynatów bloku.
         * Używa floorDiv aby poprawnie obsługiwać wartości ujemne.
         */
        fun fromBlockCoords(blockX: Int, blockZ: Int, regionSizeBlocks: Int): RegionId {
            return RegionId(
                x = Math.floorDiv(blockX, regionSizeBlocks),
                z = Math.floorDiv(blockZ, regionSizeBlocks)
            )
        }
    }
}
