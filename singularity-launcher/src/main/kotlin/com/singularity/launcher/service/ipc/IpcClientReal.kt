package com.singularity.launcher.service.ipc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Real IPC client — łączy się z agent TCP socket, odbiera metryki.
 *
 * Protocol (from singularity-agent IpcProtocol):
 *   Packet: [4 bytes length BE][payload]
 *   Payload type 0x01 METRICS: tps/fps/ram/gpu/regions/entities/chunks/cpuPerThread
 *
 * Connection lifecycle:
 * - Agent writes port to <instanceDir>/.singularity/agent-port
 * - Launcher polls for port file (max 30s during game startup)
 * - Socket connect + length-prefixed read loop
 * - Reconnect with exponential backoff on disconnect
 *
 * Implements IpcClient interface — drop-in replacement for IpcClientMock.
 */
class IpcClientReal(
    private val instancesDir: Path,
    private val parentScope: CoroutineScope
) : IpcClient {

    private val logger = LoggerFactory.getLogger(IpcClientReal::class.java)

    private val _metrics = MutableStateFlow<GameMetrics?>(null)
    override val metrics: StateFlow<GameMetrics?> = _metrics.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var connectionJob: Job? = null
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + Dispatchers.IO)

    override fun connect(instanceId: String) {
        if (connectionJob?.isActive == true) return

        val instanceDir = instancesDir.resolve(instanceId)
        connectionJob = scope.launch {
            var backoffMs = 1000L
            while (isActive) {
                try {
                    val port = waitForPortFile(instanceDir) ?: run {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
                        return@launch // no port file after 30s — game may not have started with agent
                    }
                    connectAndRead(port)
                    backoffMs = 1000L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("IPC connection failed: {}, retrying in {}ms", e.message, backoffMs)
                    _isConnected.value = false
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
                }
            }
        }
    }

    private suspend fun waitForPortFile(instanceDir: Path): Int? {
        val portFile = instanceDir.resolve(".singularity/agent-port")
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(portFile)) {
                return try {
                    Files.readString(portFile).trim().toInt()
                } catch (e: Exception) {
                    logger.warn("Failed to read port file: {}", e.message)
                    null
                }
            }
            delay(500)
        }
        logger.info("Port file not found after 30s — agent may not be running")
        return null
    }

    private suspend fun connectAndRead(port: Int) = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            _isConnected.value = true
            logger.info("IPC connected to agent on port {}", port)

            val input = DataInputStream(socket.getInputStream())
            while (coroutineContext.isActive) {
                val length = try {
                    input.readInt()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (length <= 0 || length > 1_048_576) {
                    logger.warn("Invalid IPC packet length: {}", length)
                    break
                }
                val payload = ByteArray(length)
                input.readFully(payload)

                // Decode metrics — inline protocol parsing (no cross-module import to agent)
                val metricsData = DataInputStream(payload.inputStream())
                try {
                    val type = metricsData.readByte()
                    if (type != 0x01.toByte()) continue // only METRICS packets

                    val tps = metricsData.readFloat()
                    val fps = metricsData.readInt()
                    val ramUsed = metricsData.readLong()
                    val ramMax = metricsData.readLong()
                    val gpuPercent = metricsData.readFloat()
                    val activeRegions = metricsData.readInt()
                    val entityCount = metricsData.readInt()
                    val loadedChunks = metricsData.readInt()
                    val pendingChunks = metricsData.readInt()
                    val threadCount = metricsData.readShort().toInt().and(0xFFFF)
                    val cpuPerThread = FloatArray(threadCount) { metricsData.readFloat() }
                    val avgCpu = if (cpuPerThread.isNotEmpty()) cpuPerThread.average() else 0.0

                    _metrics.value = GameMetrics(
                        tps = tps.toDouble(),
                        fps = fps.toDouble(),
                        ramUsedMb = (ramUsed / (1024 * 1024)).toInt(),
                        ramTotalMb = (ramMax / (1024 * 1024)).toInt(),
                        cpuPercent = avgCpu,
                        cpuPercentPerThread = cpuPerThread,
                        gpuPercent = gpuPercent.toDouble(),
                        activeRegions = activeRegions,
                        entityCount = entityCount,
                        chunkCount = loadedChunks,
                        suggestions = emptyList()
                    )
                } catch (e: Exception) {
                    logger.debug("Failed to decode metrics packet: {}", e.message)
                }
            }
            _isConnected.value = false
        }
    }

    override fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        _isConnected.value = false
        _metrics.value = null
        logger.info("IPC disconnected")
    }
}
