package com.singularity.common.model

import kotlinx.serialization.Serializable

/**
 * Konfiguracja instancji Minecraft.
 * Serializowana do instance.json w katalogu instancji.
 *
 * Referencja: design spec sekcja 7 (System instancji).
 */
@Serializable
data class InstanceConfig(
    val name: String,
    val minecraftVersion: String,
    val type: InstanceType,
    val loader: LoaderType = LoaderType.NONE,
    val ramMb: Int = 4096,
    val threads: Int = 4,
    val jvmArgs: String = ""
)
