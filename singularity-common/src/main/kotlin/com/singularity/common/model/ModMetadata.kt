package com.singularity.common.model

import kotlinx.serialization.Serializable

/**
 * Uproszczone metadane moda — serializowalne, przesyłane z agenta do launchera
 * przez IPC i wyświetlane w UI.
 *
 * To jest "lekka" wersja ModInfo z agenta — bez ścieżek do JAR, zależności, entrypointów.
 * Zawiera tylko to co UI potrzebuje.
 */
@Serializable
data class ModMetadata(
    val modId: String,
    val version: String,
    val name: String,
    val loader: LoaderType,
    val side: ModSide,
    val authors: List<String> = emptyList(),
    val description: String = ""
)
