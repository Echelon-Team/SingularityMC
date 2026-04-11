package com.singularity.launcher.service

import com.singularity.launcher.service.java.JavaManager
import com.singularity.launcher.service.mojang.MojangVersionClient
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Persistent disk-backed implementation `ServerManager`.
 *
 * **Co robi:**
 * - list/getById: reads z <serversRoot>/{id}/server.json per serwer (atomic writes)
 * - create: generuje UUID, writes config do JSON, mkdir rootDir
 * - delete: usuwa config file + rootDir
 * - start: ServerRunner.start() → trzyma handle w activeProcesses
 * - stop: graceful stop via stdin "stop\n" + 30s timeout → fallback forceStop
 * - forceStop: Process.destroyForcibly() + 5s wait
 * - restart: stop + start
 *
 * **Current scope:**
 * - Vanilla server only (Enhanced agent attach wymaga Sub 5)
 * - Log parsing → consoleLines wire przez ServerRunnerHandle.logFlow (ServerPanel consumes)
 */
class ServerManagerImpl(
    private val serversRoot: Path,
    private val mojangClient: MojangVersionClient,
    private val downloadHttpClient: HttpClient,
    private val javaManager: JavaManager
) : ServerManager {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        Files.createDirectories(serversRoot)
    }

    // Active server processes — thread-safe
    private val activeProcesses = ConcurrentHashMap<String, ServerRunnerHandle>()

    override suspend fun list(): List<ServerManager.Server> {
        if (!Files.exists(serversRoot)) return emptyList()
        val servers = mutableListOf<ServerManager.Server>()
        Files.list(serversRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                val configFile = dir.resolve("server.json")
                if (Files.exists(configFile)) {
                    try {
                        val content = Files.readString(configFile)
                        val config = json.decodeFromString(ServerConfig.serializer(), content)
                        val status = if (activeProcesses.containsKey(dir.fileName.toString())) {
                            ServerStatus.RUNNING
                        } else ServerStatus.OFFLINE
                        servers.add(ServerManager.Server(
                            id = dir.fileName.toString(),
                            rootDir = dir,
                            config = config,
                            status = status,
                            statusChangedAt = 0L
                        ))
                    } catch (e: Exception) {
                        // Skip corrupted server.json
                        System.err.println("Warning: skipping corrupted server: ${dir.fileName}: ${e.message}")
                    }
                }
            }
        }
        return servers
    }

    override suspend fun getById(id: String): ServerManager.Server? =
        list().find { it.id == id }

    override suspend fun create(config: ServerConfig): ServerManager.Server {
        val id = UUID.randomUUID().toString()
        val dir = serversRoot.resolve(id)
        Files.createDirectories(dir)
        val configFile = dir.resolve("server.json")
        val tmp = dir.resolve("server.json.tmp")
        val content = json.encodeToString(ServerConfig.serializer(), config)
        Files.writeString(tmp, content)
        Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return ServerManager.Server(
            id = id,
            rootDir = dir,
            config = config,
            status = ServerStatus.OFFLINE,
            statusChangedAt = System.currentTimeMillis()
        )
    }

    override suspend fun delete(id: String) {
        val dir = serversRoot.resolve(id)
        if (Files.exists(dir)) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    override suspend fun start(id: String) {
        // Prevent double-start
        if (activeProcesses.containsKey(id)) return

        val server = getById(id) ?: throw IllegalStateException("Server not found: $id")
        val runner = ServerRunner(
            mojangClient = mojangClient,
            httpClient = downloadHttpClient,
            javaManager = javaManager
        )
        val handle = runner.start(server)
        activeProcesses[id] = handle
    }

    override suspend fun stop(id: String) {
        val handle = activeProcesses[id] ?: return  // already stopped
        // Graceful stop: wysłanie "stop\n" do server stdin
        try {
            handle.process.outputStream.write("stop\n".toByteArray())
            handle.process.outputStream.flush()
            // Wait do 30s na graceful exit
            val exited = handle.process.waitFor(30, TimeUnit.SECONDS)
            if (!exited) {
                // Graceful stop timeout — escalate do forceStop
                handle.process.destroyForcibly()
            }
        } catch (e: Exception) {
            // Jeśli stdin closed, process już kończy — destroyForcibly jako fallback
            handle.process.destroyForcibly()
        }
        activeProcesses.remove(id)
    }

    override suspend fun forceStop(id: String) {
        val handle = activeProcesses[id] ?: return
        handle.process.destroyForcibly()
        handle.process.waitFor(5, TimeUnit.SECONDS)
        activeProcesses.remove(id)
    }

    override suspend fun restart(id: String) {
        stop(id)
        start(id)
    }

    /**
     * Zwraca handle do aktywnego process dla danego server id — null jeśli serwer offline.
     * Używane przez ServerPanel (ConsoleTab) dla log streaming + stdin commands.
     */
    fun getActiveHandle(id: String): ServerRunnerHandle? = activeProcesses[id]
}
