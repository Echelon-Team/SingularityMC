package com.singularity.common.model

import kotlinx.serialization.Serializable

/**
 * Typ loadera moda — identyfikowany przy discovery na podstawie plików metadanych w JAR.
 *
 * Używany przez agent (discovery, classloading) i launcher (wyświetlanie info o modach).
 *
 * Referencja: design spec sekcja 5A.1.
 */
@Serializable
enum class LoaderType {
    /** Vanilla instance — brak loadera. Użyte dla instancji bez modów. Dodane w Sub 4 Task 11. */
    NONE,

    /** Mod z fabric.mod.json */
    FABRIC,

    /** Mod z META-INF/mods.toml (Forge) */
    FORGE,

    /** Mod z META-INF/mods.toml (NeoForge — na 1.20.1 identyczny z Forge) */
    NEOFORGE,

    /** Mod z fabric.mod.json ORAZ META-INF/mods.toml */
    MULTI,

    /** JAR rozpoznany jako biblioteka (znana zależność lub Maven metadata) */
    LIBRARY,

    /** JAR bez rozpoznanych metadanych */
    UNKNOWN;

    /** Czy ten typ reprezentuje prawdziwy mod (nie bibliotekę/nieznany/vanilla) */
    val isMod: Boolean get() = this != LIBRARY && this != UNKNOWN && this != NONE
}
