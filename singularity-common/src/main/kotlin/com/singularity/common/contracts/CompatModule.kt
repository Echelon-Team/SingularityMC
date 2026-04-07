package com.singularity.common.contracts

/**
 * Kontrakt między singularity-agent (core) a modułami kompatybilności (per wersja MC).
 *
 * Agent definiuje ten interfejs. Moduł go implementuje.
 * Agent jest uniwersalny — obsługuje KAŻDY moduł niezależnie od wersji MC.
 *
 * Kontrakty są additive-only: agent nigdy nie zmienia ani nie usuwa
 * istniejących metod, tylko dodaje nowe. Backward compatibility by design.
 *
 * Moduł deklaruje w singularity-module.json pole requiredContracts —
 * listę kontraktów których wymaga. Agent przy ładowaniu modułu sprawdza
 * czy oferuje WSZYSTKIE kontrakty z requiredContracts (agent ⊇ moduł).
 *
 * Referencja: design spec sekcja 3 (singularity-compat), implementation design sekcja 4.
 *
 * 5 kategorii kontraktów:
 * 1. Metadata & Discovery — moduł identyfikuje się
 * 2. Remapping — core ma RemappingEngine, moduł dostarcza mapping tables
 * 3. Loader Emulation — moduł dostarcza shimy udające loadery
 * 4. Cross-Loader Bridges — tłumaczenie events/capabilities/registries
 * 5. MC Version Hooks — integracja z kodem konkretnej wersji MC
 */
interface CompatModule {

    /** Unikalny identyfikator modułu, np. "compat-1.20.1" */
    val moduleId: String

    /** Wersja modułu (SemVer), np. "1.0.0" */
    val moduleVersion: String

    /** Wersja Minecraft którą ten moduł obsługuje, np. "1.20.1" */
    val minecraftVersion: String

    /** Zbiór wspieranych loaderów: "fabric", "forge", "neoforge" */
    val supportedLoaders: Set<String>

    /**
     * Zbiór kontraktów wymaganych od agenta.
     * Agent przy ładowaniu modułu sprawdza: agent ⊇ requiredContracts.
     * Przykład: setOf("metadata", "remapping", "loader_emulation", "bridges", "hooks")
     */
    val requiredContracts: Set<String>

    /**
     * Inicjalizacja modułu. Wywoływane przez agenta po weryfikacji kontraktów.
     * Moduł ładuje mapping tables do pamięci, rejestruje shimy, inicjalizuje bridges.
     */
    fun initialize()

    /**
     * Zwraca mapping tables jako Map<namespace, Map<sourceNazwa, targetNazwa>>.
     * Klucze namespace: "obf-to-mojmap", "srg-to-mojmap", "intermediary-to-mojmap".
     * Wartości: lookup table (derivative works, nie oryginalne pliki Mojanga).
     *
     * Referencja: design spec sekcja 15 (Mappingi), mapping-reference.md.
     */
    fun getMappingTables(): Map<String, Map<String, String>>
}
