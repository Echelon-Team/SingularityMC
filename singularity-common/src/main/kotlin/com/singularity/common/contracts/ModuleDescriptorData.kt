// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.common.contracts

import kotlinx.serialization.Serializable

/**
 * Dane z singularity-module.json wewnątrz JAR modułu compat.
 *
 * Data class — parsowana z JSON przy ładowaniu modułu. Różni się od
 * interfejsu [CompatModule]: ModuleDescriptorData to DANE (serializacja),
 * CompatModule to KONTRAKT RUNTIME (initialize, getMappingTables).
 * Moduł ma OBA: deskryptor w JAR + klasę implementującą interface.
 *
 * Referencja: design spec sekcja 3 (singularity-compat), implementation
 * design sekcja 4.1 (Agent ładowanie modułu).
 */
@Serializable
data class ModuleDescriptorData(
    /** Unikalny identyfikator modułu, np. "compat-1.20.1" */
    val moduleId: String,

    /** Wersja modułu (SemVer), np. "1.0.0" */
    val moduleVersion: String,

    /** Wersja Minecraft obsługiwana przez ten moduł, np. "1.20.1" */
    val minecraftVersion: String,

    /** Wspierane loadery: ["fabric", "forge", "neoforge"] */
    val supportedLoaders: Set<String>,

    /**
     * Kontrakty wymagane od agenta. Agent musi oferować WSZYSTKIE.
     * Przykład: ["metadata", "remapping", "loader_emulation", "bridges", "hooks"]
     */
    val requiredContracts: Set<String>,

    /**
     * Minimalna wersja core API wymagana przez moduł.
     * Agent sprawdza: swojaWersja >= coreApiVersion.
     */
    val coreApiVersion: String = "1.0.0",

    /**
     * Pełna nazwa klasy entrypoint modułu (implementuje CompatModule).
     * Agent ładuje tę klasę przez SingularityClassLoader i wywołuje initialize().
     */
    val entrypoint: String,

    /** Ścieżki do mapping tables wewnątrz JAR (relative) */
    val mappingFiles: MappingFiles = MappingFiles()
)

@Serializable
data class MappingFiles(
    val obfToMojmap: String = "mappings/obf-to-mojmap.tiny",
    val srgToMojmap: String = "mappings/srg-to-mojmap.tiny",
    val intermediaryToMojmap: String = "mappings/intermediary-to-mojmap.tiny"
)
