package com.singularity.common.model

import kotlinx.serialization.Serializable

/**
 * Typ instancji Minecraft.
 * ENHANCED — wersja wspierana przez SingularityMC (wielowątkowość, compat layer, C2ME).
 * VANILLA — czysty Minecraft bez modyfikacji SingularityMC.
 */
@Serializable
enum class InstanceType {
    ENHANCED,
    VANILLA
}
