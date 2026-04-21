// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModMetadata
import com.singularity.common.model.ModSide
import java.nio.file.Path

/**
 * Zunifikowany model metadanych moda — wynik parsowania fabric.mod.json lub mods.toml.
 *
 * Zawiera WSZYSTKIE dane potrzebne agentowi: identyfikacja, zależności, entrypointy,
 * mixiny, ścieżka do JAR. To jest "ciężka" wersja — do wewnętrznego użytku agenta.
 * Lekka wersja (ModMetadata) przesyłana do launchera przez IPC.
 *
 * Referencja: design spec sekcja 5A.2.
 */
data class ModInfo(
    /** Unikalny identyfikator moda (np. "sodium", "create") */
    val modId: String,

    /** Wersja moda (np. "0.5.8+mc1.20.1") */
    val version: String,

    /** Wyświetlana nazwa moda (np. "Sodium", "Create") */
    val name: String,

    /** Typ loadera */
    val loaderType: LoaderType,

    /** Lista zależności (required + optional) */
    val dependencies: List<ModDependency>,

    /** Lista Fabric entrypointów — puste dla Forge */
    val entryPoints: List<String>,

    /** Nazwy plików mixin config (np. ["sodium.mixins.json"]) */
    val mixinConfigs: List<String>,

    /** Autorzy moda */
    val authors: List<String>,

    /** Opis moda */
    val description: String,

    /** Strona na której mod działa */
    val side: ModSide,

    /** Ścieżka do JAR moda na dysku */
    val jarPath: Path
) {
    /**
     * Konwertuje do lekkiego ModMetadata (do przesłania do launchera).
     */
    fun toMetadata(): ModMetadata = ModMetadata(
        modId = modId,
        version = version,
        name = name,
        loader = loaderType,
        side = side,
        authors = authors,
        description = description
    )
}
