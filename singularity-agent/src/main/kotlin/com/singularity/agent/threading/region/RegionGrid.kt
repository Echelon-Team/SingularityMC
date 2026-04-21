// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.region

import com.singularity.agent.threading.config.ThreadingConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Siatka regionów w wymiarze. Thread-safe mapa RegionId → Region.
 *
 * Regiony tworzone lazy — getOrCreate() tworzy jeśli nie istnieje.
 * Nieużywane regiony mogą być usuwane (remove) aby zwolnić pamięć.
 *
 * Referencja: design spec sekcja 4.3.
 */
class RegionGrid(
    val dimensionId: String,
    private val config: ThreadingConfig
) {
    private val regions = ConcurrentHashMap<RegionId, Region>()

    val size: Int get() = regions.size

    /** Pobiera istniejący region lub tworzy nowy. Thread-safe. */
    fun getOrCreate(id: RegionId): Region =
        regions.computeIfAbsent(id) { Region(id, dimensionId) }

    /** Pobiera region dla danej pozycji bloku. */
    fun regionForBlock(blockX: Int, blockZ: Int): Region {
        val id = RegionId.fromBlockCoords(blockX, blockZ, config.regionSizeBlocks)
        return getOrCreate(id)
    }

    /** Sprawdza czy region istnieje. */
    fun contains(id: RegionId): Boolean = regions.containsKey(id)

    /** Zwraca region jeśli istnieje (bez tworzenia). */
    fun get(id: RegionId): Region? = regions[id]

    /** Usuwa region (np. gdy nieaktywny i daleko od graczy). */
    fun remove(id: RegionId): Region? = regions.remove(id)

    /** Zwraca wszystkie istniejące regiony (snapshot). */
    fun getAllRegions(): List<Region> = regions.values.toList()

    /** Zwraca regiony aktywne (z encjami lub scheduled ticks). */
    fun getActiveRegions(): List<Region> =
        regions.values.filter { it.isActive() }

    /** Oblicza łączne obciążenie wymiaru (suma encji we wszystkich regionach). */
    fun totalEntityCount(): Int =
        regions.values.sumOf { it.getEntityCount() }
}
