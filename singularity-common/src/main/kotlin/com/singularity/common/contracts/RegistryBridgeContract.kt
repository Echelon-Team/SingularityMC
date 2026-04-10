package com.singularity.common.contracts

/**
 * Kontrakt unified registry — zunifikowany rejestr bloków, itemów, encji.
 *
 * Forge: ForgeRegistries.BLOCKS → widzi bloki ze WSZYSTKICH loaderów
 * Fabric: Registries.BLOCK → widzi bloki ze WSZYSTKICH loaderów
 * Oba operują na tym samym stanie.
 *
 * Mod Forge rejestruje blok → widoczny przez Fabric Registries (i odwrotnie).
 *
 * Referencja: design spec sekcja 5.1 (filozofia centralny hub).
 */
interface RegistryBridgeContract {

    /**
     * Rejestruje blok w zunifikowanym rejestrze.
     * @param namespace mod namespace (np. "create")
     * @param path identyfikator bloku (np. "shaft")
     * @param block instancja bloku MC
     */
    fun registerBlock(namespace: String, path: String, block: Any)

    /** Rejestruje item. */
    fun registerItem(namespace: String, path: String, item: Any)

    /**
     * Rejestruje entity type.
     * @param namespace mod namespace
     * @param path identyfikator entity type
     * @param entityType instancja EntityType MC
     */
    fun registerEntityType(namespace: String, path: String, entityType: Any)

    /** Pobiera blok po ResourceLocation string (np. "create:shaft"). */
    fun getBlock(resourceLocation: String): Any?

    /** Pobiera item. */
    fun getItem(resourceLocation: String): Any?

    /** Pobiera entity type po ResourceLocation string. */
    fun getEntityType(resourceLocation: String): Any?

    /** Zwraca wszystkie zarejestrowane bloki jako mapa ResourceLocation → Block. */
    fun getAllBlocks(): Map<String, Any>

    /** Zwraca wszystkie zarejestrowane itemy. */
    fun getAllItems(): Map<String, Any>

    /** Zwraca wszystkie zarejestrowane entity types. */
    fun getAllEntityTypes(): Map<String, Any>
}
