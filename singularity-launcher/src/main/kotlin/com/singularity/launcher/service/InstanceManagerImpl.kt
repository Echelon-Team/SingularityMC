// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service

import com.singularity.common.model.InstanceConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Persistent metadata stored alongside instance.json — lastPlayedAt, modCount.
 * Nie jest częścią `InstanceConfig` bo to runtime metadata, nie config settings.
 */
@Serializable
private data class InstanceMeta(
    val lastPlayedAt: Long? = null,
    val modCount: Int = 0
)

/**
 * Implementation `InstanceManager` z filesystem persistence.
 *
 * **Storage layout:**
 * ```
 * <instancesRoot>/
 *   <uuid>/
 *     instance.json         — InstanceConfig (nazwa, wersja, type, loader, ramMb, threads, jvmArgs)
 *     meta.json             — InstanceMeta (lastPlayedAt, modCount)
 *     settings.json         — InstanceRuntimeSettings (Task 12) — optional
 *     minecraft/            — MC gameDir
 *     .singularity/
 *       modules/            — compat modules (Sub 2)
 *       cache/              — agent cache (Sub 3)
 * ```
 *
 * **Atomic writes:** wszystkie save operations używają temp file + ATOMIC_MOVE żeby
 * zapobiec corruption w przypadku crash w trakcie zapisu.
 *
 * **Corrupted JSON tolerance:** `getAll` łapie JsonException per-instance i skipuje
 * corrupted instances (log warning do stderr). Pozwala user'owi otworzyć launcher nawet
 * jeśli jedna z instancji ma popsuty `instance.json`.
 */
class InstanceManagerImpl(
    private val instancesRoot: Path
) : InstanceManager {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        Files.createDirectories(instancesRoot)
    }

    override suspend fun getAll(): List<InstanceManager.Instance> {
        if (!Files.exists(instancesRoot)) return emptyList()
        val result = mutableListOf<InstanceManager.Instance>()

        Files.list(instancesRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                val configFile = dir.resolve("instance.json")
                if (!Files.exists(configFile)) return@forEach

                try {
                    val configContent = Files.readString(configFile)
                    val config = json.decodeFromString(InstanceConfig.serializer(), configContent)

                    val metaFile = dir.resolve("meta.json")
                    val meta = if (Files.exists(metaFile)) {
                        try {
                            json.decodeFromString(
                                InstanceMeta.serializer(),
                                Files.readString(metaFile)
                            )
                        } catch (e: Exception) {
                            InstanceMeta()
                        }
                    } else {
                        InstanceMeta()
                    }

                    result.add(InstanceManager.Instance(
                        id = dir.fileName.toString(),
                        rootDir = dir,
                        config = config,
                        lastPlayedAt = meta.lastPlayedAt,
                        modCount = meta.modCount
                    ))
                } catch (e: Exception) {
                    System.err.println("Warning: skipping corrupted instance ${dir.fileName}: ${e.message}")
                }
            }
        }

        return result
    }

    override suspend fun getById(id: String): InstanceManager.Instance? =
        getAll().find { it.id == id }

    override suspend fun getLastPlayed(): InstanceManager.Instance? =
        getAll().filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt!! }

    override suspend fun create(config: InstanceConfig): InstanceManager.Instance {
        // Generate unique UUID (retry jeśli collision)
        var id: String
        var dir: Path
        do {
            id = UUID.randomUUID().toString()
            dir = instancesRoot.resolve(id)
        } while (Files.exists(dir))

        // Create directory structure
        Files.createDirectories(dir.resolve("minecraft"))
        Files.createDirectories(dir.resolve(".singularity").resolve("modules"))
        Files.createDirectories(dir.resolve(".singularity").resolve("cache"))

        // Atomic write instance.json
        val configFile = dir.resolve("instance.json")
        val tmpConfig = dir.resolve("instance.json.tmp")
        Files.writeString(tmpConfig, json.encodeToString(InstanceConfig.serializer(), config))
        Files.move(tmpConfig, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        // Empty meta
        val metaFile = dir.resolve("meta.json")
        val tmpMeta = dir.resolve("meta.json.tmp")
        Files.writeString(tmpMeta, json.encodeToString(InstanceMeta.serializer(), InstanceMeta()))
        Files.move(tmpMeta, metaFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        return InstanceManager.Instance(
            id = id,
            rootDir = dir,
            config = config,
            lastPlayedAt = null,
            modCount = 0
        )
    }

    override suspend fun update(instance: InstanceManager.Instance) {
        if (!Files.exists(instance.rootDir)) {
            throw IllegalStateException("Cannot update non-existent instance: ${instance.id}")
        }
        val configFile = instance.rootDir.resolve("instance.json")
        if (!Files.exists(configFile)) {
            throw IllegalStateException("Instance config missing: ${instance.rootDir}")
        }

        // Atomic write updated config
        val tmpConfig = instance.rootDir.resolve("instance.json.tmp")
        Files.writeString(tmpConfig, json.encodeToString(InstanceConfig.serializer(), instance.config))
        Files.move(tmpConfig, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        // Atomic write updated meta
        val metaFile = instance.rootDir.resolve("meta.json")
        val tmpMeta = instance.rootDir.resolve("meta.json.tmp")
        val meta = InstanceMeta(
            lastPlayedAt = instance.lastPlayedAt,
            modCount = instance.modCount
        )
        Files.writeString(tmpMeta, json.encodeToString(InstanceMeta.serializer(), meta))
        Files.move(tmpMeta, metaFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override suspend fun delete(id: String) {
        val dir = instancesRoot.resolve(id)
        if (!Files.exists(dir)) return  // no-op

        // Recursive delete w reverse order (files before parents)
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { path ->
            try {
                Files.delete(path)
            } catch (e: Exception) {
                System.err.println("Warning: failed to delete $path: ${e.message}")
            }
        }
    }
}
