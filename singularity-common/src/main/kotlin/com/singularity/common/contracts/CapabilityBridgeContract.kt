// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.common.contracts

/**
 * Kontrakt capability bridge — IItemHandler (Forge) ↔ Transfer API (Fabric).
 *
 * Direction-aware bridging (design spec sekcja 5.2A-1):
 * - Direction to klasa vanilla MC, współdzielona przez oba loadery
 * - null Direction = undirected access
 *
 * Implementacja operuje na Any (runtime typy MC) — agent nie zna typów Forge/Fabric.
 * Moduł compat zna konkretne typy i castuje.
 */
interface CapabilityBridgeContract {

    /**
     * Opakowuje Forge IItemHandler jako Fabric Storage<ItemVariant>.
     * @param forgeHandler instancja IItemHandler z Forge bloku
     * @param direction kierunek (vanilla Direction) lub null
     * @return obiekt implementujący Fabric Storage interface
     */
    fun wrapForgeAsStorage(forgeHandler: Any, direction: Any?): Any

    /**
     * Opakowuje Fabric Storage<ItemVariant> jako Forge IItemHandler.
     * @param fabricStorage instancja Storage z Fabric bloku
     * @param direction kierunek lub null
     * @return obiekt implementujący Forge IItemHandler interface
     */
    fun wrapFabricAsHandler(fabricStorage: Any, direction: Any?): Any

    /** Sprawdza czy block entity ma Forge capability na danym kierunku. */
    fun isForgeCapabilityAvailable(blockEntity: Any, direction: Any?): Boolean

    /** Sprawdza czy Fabric storage jest dostępny na danej pozycji. */
    fun isFabricStorageAvailable(world: Any, pos: Any, direction: Any?): Boolean
}
