package com.singularity.common.model

import kotlinx.serialization.Serializable

/**
 * Priorytet handlerów eventów w unified pipeline.
 *
 * Forge: HIGHEST → HIGH → NORMAL → LOW → LOWEST (natywne priorytety).
 * Fabric: wstawiane na NORMAL (Fabric nie ma systemu priorytetów).
 *
 * Referencja: design spec sekcja 5.2B.
 */
@Serializable
enum class EventPriority(val order: Int) {
    HIGHEST(0),
    HIGH(1),
    NORMAL(2),
    LOW(3),
    LOWEST(4);

    companion object {
        fun fromForge(forgePriority: String): EventPriority = when (forgePriority.uppercase()) {
            "HIGHEST" -> HIGHEST
            "HIGH" -> HIGH
            "NORMAL" -> NORMAL
            "LOW" -> LOW
            "LOWEST" -> LOWEST
            else -> NORMAL
        }
    }
}
