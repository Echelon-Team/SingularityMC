package com.singularity.launcher.service

import com.singularity.common.model.InstanceConfig
import java.nio.file.Path

/**
 * Interface dla serwisu zarządzania instancjami. Implementacja w Task 26.
 *
 * **Forward reference:** Plik utworzony w Task 10 żeby HomeViewModel mógł go
 * importować. Implementacja `InstanceManagerImpl` przychodzi w Task 26.
 */
interface InstanceManager {

    data class Instance(
        val id: String,
        val rootDir: Path,
        val config: InstanceConfig,
        val lastPlayedAt: Long?,
        val modCount: Int
    )

    suspend fun getAll(): List<Instance>
    suspend fun getById(id: String): Instance?
    suspend fun getLastPlayed(): Instance?
    suspend fun create(config: InstanceConfig): Instance
    suspend fun update(instance: Instance)
    suspend fun delete(id: String)
}
