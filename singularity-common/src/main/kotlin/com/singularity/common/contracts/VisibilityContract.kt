// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.common.contracts

/**
 * Kontrakt filtrowania widoczności modów per loader.
 *
 * Zasada (design spec sekcja 5A.7):
 * - FabricLoader.getAllMods() → TYLKO mody Fabric + Multi
 * - ModList.getMods() → TYLKO mody Forge + NeoForge + Multi
 * - Żaden loader "nie widzi" modów z drugiego
 *
 * Agent-side: VisibilityRules implementuje ten interfejs.
 * Module-side: shimy przyjmują VisibilityContract w konstruktorze.
 */
interface VisibilityContract {

    fun filterForFabric(allMods: List<ModRegistryContract.ModEntry>): List<ModRegistryContract.ModEntry>

    fun filterForForge(allMods: List<ModRegistryContract.ModEntry>): List<ModRegistryContract.ModEntry>

    fun filterForNeoForge(allMods: List<ModRegistryContract.ModEntry>): List<ModRegistryContract.ModEntry>
}
