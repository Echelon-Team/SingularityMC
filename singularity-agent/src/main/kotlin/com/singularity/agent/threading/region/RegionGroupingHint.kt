// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.threading.region

/**
 * Interface do grupowania regionów które muszą tickować razem (sekwencyjnie).
 *
 * Domyślnie: NONE (każdy region niezależny, 1-tick delay na granicy).
 * Przyszłość: RedstoneCircuitTracker implementuje ten interface.
 * Server engine (roadmap): WYMAGA pełnej implementacji.
 */
interface RegionGroupingHint {
    fun getLinkedRegions(regionId: RegionId): Set<RegionId>

    companion object {
        val NONE = object : RegionGroupingHint {
            override fun getLinkedRegions(regionId: RegionId): Set<RegionId> = emptySet()
        }
    }
}
