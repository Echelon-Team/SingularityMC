package com.singularity.agent.registry

import com.singularity.common.contracts.ModRegistryContract
import com.singularity.common.contracts.VisibilityContract
import com.singularity.common.model.LoaderType

/**
 * Filtruje widoczność modów per loader.
 *
 * Zasada (design spec sekcja 5A.7):
 * - FabricLoader.getAllMods() → TYLKO mody Fabric + Multi
 * - ModList.getMods() → TYLKO mody Forge + NeoForge + Multi
 * - Żaden loader "nie widzi" modów z drugiego
 * - Cross-loader interakcja WYŁĄCZNIE przez bridges (events, capabilities, registry)
 * - Library i Unknown nigdy nie widoczne
 *
 * Implementuje VisibilityContract — moduły compat używają kontraktu,
 * nie tego obiektu bezpośrednio.
 *
 * Shimy w module compat (plan 2d) wywołują te filtry.
 */
object VisibilityRules : VisibilityContract {

    /**
     * Mody widoczne dla Fabric loadera: Fabric + Multi-loader.
     */
    override fun filterForFabric(
        allMods: List<ModRegistryContract.ModEntry>
    ): List<ModRegistryContract.ModEntry> =
        allMods.filter { it.loaderType == LoaderType.FABRIC || it.loaderType == LoaderType.MULTI }

    /**
     * Mody widoczne dla Forge loadera: Forge + NeoForge + Multi-loader.
     */
    override fun filterForForge(
        allMods: List<ModRegistryContract.ModEntry>
    ): List<ModRegistryContract.ModEntry> =
        allMods.filter {
            it.loaderType == LoaderType.FORGE ||
                it.loaderType == LoaderType.NEOFORGE ||
                it.loaderType == LoaderType.MULTI
        }

    /**
     * Mody widoczne dla NeoForge loadera.
     * Na MC 1.20.1: identycznie z Forge (NeoForge ≈ Forge).
     * Na MC 1.20.2+: może wymagać osobnej logiki (Sub 6+ TODO).
     */
    override fun filterForNeoForge(
        allMods: List<ModRegistryContract.ModEntry>
    ): List<ModRegistryContract.ModEntry> =
        filterForForge(allMods)
}
