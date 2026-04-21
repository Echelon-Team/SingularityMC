// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.service

import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Interface serwisu zarządzania serwerami Minecraft. Implementacja w Task 17
 * (`ServerManagerImpl` z ProcessBuilder + vanilla server.jar download z Mojang piston-meta).
 *
 * **Forward reference:** Task 16 (ServersScreen) używa tego interface. Task 17 dodaje
 * real impl. Task 32 wire'uje DI.
 */
interface ServerManager {

    data class Server(
        val id: String,
        val rootDir: Path,
        val config: ServerConfig,
        val status: ServerStatus,
        val statusChangedAt: Long,  // timestamp dla timeout detection
        val liveMetrics: ServerMetrics? = null  // null gdy OFFLINE
    )

    suspend fun list(): List<Server>
    suspend fun getById(id: String): Server?
    suspend fun create(config: ServerConfig): Server
    suspend fun delete(id: String)
    suspend fun start(id: String)
    suspend fun stop(id: String)
    suspend fun forceStop(id: String)
    suspend fun restart(id: String)
}

/**
 * Runtime status serwera. Transitions: OFFLINE → STARTING → RUNNING → STOPPING → OFFLINE.
 * CRASHED pojawia się gdy process exit z error code lub STARTING timeout > 60s.
 */
@Serializable
enum class ServerStatus {
    OFFLINE,
    STARTING,
    RUNNING,
    STOPPING,
    CRASHED
}

/**
 * Persisted config serwera — zapisane w `<server>/server.json`.
 *
 * **Sub 4 vanilla only:** Enhanced option disabled w NewServerWizard banner (Task 17).
 * `parentInstanceId` linkuje serwer do instancji (Task 12) dla "Mody server-side kopiowane"
 * feature (Sub 5).
 */
@Serializable
data class ServerConfig(
    val name: String,
    val minecraftVersion: String,
    val parentInstanceId: String?,  // null gdy standalone server, non-null gdy związany z instancją
    val port: Int = 25565,
    val ramMb: Int = 4096,
    val threads: Int = 4,
    val motd: String = "A Minecraft Server",
    val maxPlayers: Int = 20
)

/**
 * Real-time metrics serwera — null gdy OFFLINE, populated gdy RUNNING. Mock w Sub 4,
 * real w Sub 5 (IpcClient real connection do agent JVM).
 */
@Serializable
data class ServerMetrics(
    val tps: Double,
    val playerCount: Int,
    val maxPlayers: Int,
    val ramUsedMb: Int,
    val ramTotalMb: Int,
    val cpuPercent: Double
)
