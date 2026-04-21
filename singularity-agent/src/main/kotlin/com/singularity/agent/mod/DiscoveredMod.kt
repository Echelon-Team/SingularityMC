// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.mod

import com.singularity.common.model.LoaderType
import java.nio.file.Path

/**
 * Wynik skanowania pojedynczego JAR z folderu mods/.
 *
 * Zawiera surowe dane z discovery — jeszcze przed parsowaniem metadanych.
 * rawFabricJson i rawModsToml to surowa zawartość plików metadanych (null jeśli brak).
 *
 * Referencja: design spec sekcja 5A.1.
 */
data class DiscoveredMod(
    /** Ścieżka do pliku JAR */
    val jarPath: Path,

    /** Zidentyfikowany typ loadera */
    val loaderType: LoaderType,

    /** Surowa zawartość fabric.mod.json (null jeśli brak) */
    val rawFabricJson: String?,

    /** Surowa zawartość META-INF/mods.toml (null jeśli brak) */
    val rawModsToml: String?,

    /** Czy JAR rozpoznany jako biblioteka (znana zależność lub Maven metadata) */
    val isLibrary: Boolean,

    /** Group ID z Maven metadata (jeśli znaleziony w MANIFEST.MF lub pom.properties) */
    val mavenGroupId: String?
)
