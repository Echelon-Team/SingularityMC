// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.common.contracts

import com.singularity.common.model.LoaderType
import com.singularity.common.model.ModSide

/**
 * Kontrakt dostępu do rejestru modów — eksponowany modułom compat.
 *
 * Moduły compat (shimy) używają tego interfejsu zamiast bezpośredniego
 * SingularityModRegistry z agent. Odsprzęga moduł od implementacji agenta.
 *
 * Agent-side: SingularityModRegistry implementuje ten interfejs.
 * Module-side: shimy przyjmują ModRegistryContract w konstruktorze.
 */
interface ModRegistryContract {

    /**
     * Wpis moda w rejestrze — implementowany przez RegisteredMod w agent.
     */
    interface ModEntry {
        val modId: String
        val version: String
        val name: String
        val loaderType: LoaderType
        val side: ModSide
    }

    val size: Int

    fun getAll(): List<ModEntry>

    fun getById(modId: String): ModEntry?

    fun isLoaded(modId: String): Boolean

    fun getByLoader(loaderType: LoaderType): List<ModEntry>
}
