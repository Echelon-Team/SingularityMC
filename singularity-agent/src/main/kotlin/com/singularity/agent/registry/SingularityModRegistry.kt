package com.singularity.agent.registry

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralny rejestr WSZYSTKICH załadowanych modów.
 *
 * Wie o modach z każdego loadera (Fabric, Forge, NeoForge).
 * Loader shimy (w module compat) filtrują widoczność per loader
 * przez VisibilityRules — FabricLoader.getAllMods() widzi TYLKO mody Fabric + Multi.
 *
 * Thread-safe: ConcurrentHashMap, bezpieczny dla concurrent reads z wielu wątków.
 *
 * Referencja: design spec sekcja 5A.7 (cross-loader visibility rules).
 */
class SingularityModRegistry {

    private val logger = LoggerFactory.getLogger(SingularityModRegistry::class.java)
    private val mods = ConcurrentHashMap<String, RegisteredMod>()

    data class RegisteredMod(
        val modId: String,
        val version: String,
        val name: String,
        val loaderType: LoaderType,
        val side: ModSide
    )

    val size: Int get() = mods.size

    fun register(mod: RegisteredMod) {
        mods[mod.modId] = mod
        logger.debug("Registered mod: {} v{} ({})", mod.modId, mod.version, mod.loaderType)
    }

    fun getById(modId: String): RegisteredMod? = mods[modId]

    fun isLoaded(modId: String): Boolean = mods.containsKey(modId)

    fun getAll(): List<RegisteredMod> = mods.values.toList()

    fun getByLoader(loaderType: LoaderType): List<RegisteredMod> =
        mods.values.filter { it.loaderType == loaderType }
}
