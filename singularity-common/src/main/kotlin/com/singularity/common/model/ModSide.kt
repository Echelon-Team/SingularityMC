package com.singularity.common.model

import kotlinx.serialization.Serializable

/**
 * Strona na której mod działa — klient, serwer, lub oba.
 *
 * Fabric: pole "environment" w fabric.mod.json ("client", "server", "*")
 * Forge: pole "side" w dependencies ("CLIENT", "SERVER", "BOTH")
 *
 * Używane przy filtrowaniu modów:
 * - server-side only mody nie potrzebne na kliencie
 * - client-side only mody nie kopiowane na serwer
 *
 * Referencja: design spec sekcja 6A.4 (blokowanie nadmiarowych modów).
 */
@Serializable
enum class ModSide {
    CLIENT,
    SERVER,
    BOTH;

    val isClientSide: Boolean get() = this == CLIENT || this == BOTH
    val isServerSide: Boolean get() = this == SERVER || this == BOTH

    companion object {
        /**
         * Mapuje Fabric "environment" pole na ModSide.
         * Fabric: "client", "server", "*" (lub brak pola = BOTH)
         */
        fun fromFabricEnvironment(environment: String?): ModSide = when (environment?.lowercase()) {
            "client" -> CLIENT
            "server" -> SERVER
            "*", "", null -> BOTH
            else -> BOTH
        }

        /**
         * Mapuje Forge/NeoForge "side" pole na ModSide.
         * Forge: "CLIENT", "SERVER", "BOTH" (lub "NONE" = BOTH)
         */
        fun fromForgeSide(side: String?): ModSide = when (side?.uppercase()) {
            "CLIENT" -> CLIENT
            "SERVER" -> SERVER
            "BOTH", "NONE", null -> BOTH
            else -> BOTH
        }
    }
}
