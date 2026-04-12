package com.singularity.agent.ipc

import org.slf4j.LoggerFactory
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IPC TCP server na localhost.
 *
 * Agent otwiera losowy port, zapisuje go do <instanceDir>/.singularity/agent-port.
 * Launcher łączy się i odbiera strumień metryk (length-prefixed binary packets).
 * Obsługuje wielu klientów jednocześnie (launcher, narzędzia diagnostyczne, testy).
 *
 * Thread safety:
 * - Per-client synchronized writes (prevents packet interleaving)
 * - CAS-guarded start() (prevents double ServerSocket)
 * - stop() closes serverSocket first (prevents new accepts after shutdown begins)
 */
class IpcServer(private val instanceDir: Path) {
    private val logger = LoggerFactory.getLogger(IpcServer::class.java)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)

    // Per-client: socket + cached DataOutputStream + write lock
    private data class ClientConnection(
        val socket: Socket,
        val output: DataOutputStream,
        val writeLock: Any = Any()
    )

    private val clients = ConcurrentHashMap<Socket, ClientConnection>()

    fun start() {
        if (!running.compareAndSet(false, true)) return

        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort
        logger.info("IPC server started on localhost:{}", port)

        val portFile = instanceDir.resolve(".singularity/agent-port")
        Files.createDirectories(portFile.parent)
        Files.writeString(portFile, port.toString())

        acceptThread = Thread {
            while (running.get()) {
                try {
                    val socket = serverSocket!!.accept()
                    logger.info("IPC client connected: {}", socket.remoteSocketAddress)
                    clients[socket] = ClientConnection(
                        socket = socket,
                        output = DataOutputStream(socket.getOutputStream())
                    )
                } catch (e: Exception) {
                    if (running.get()) {
                        logger.warn("Accept failed: {}", e.message)
                    }
                }
            }
        }.apply {
            name = "singularity-ipc-accept"
            isDaemon = true
            start()
        }
    }

    fun broadcastMetrics(metrics: IpcProtocol.MetricsPayload) {
        val payload = IpcProtocol.encodeMetrics(metrics)
        val lengthPrefix = IpcProtocol.encodeLengthPrefix(payload.size)

        val deadClients = mutableListOf<Socket>()
        for ((socket, conn) in clients) {
            try {
                synchronized(conn.writeLock) {
                    conn.output.write(lengthPrefix)
                    conn.output.write(payload)
                    conn.output.flush()
                }
            } catch (e: Exception) {
                logger.debug("Client disconnected: {}", e.message)
                deadClients.add(socket)
            }
        }
        for (dead in deadClients) {
            val conn = clients.remove(dead)
            if (conn != null) {
                try { conn.socket.close() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        running.set(false)

        // Close server socket FIRST — prevents new accepts
        try { serverSocket?.close() } catch (_: Exception) {}
        acceptThread?.interrupt()

        // Then close all connected clients
        for ((_, conn) in clients) {
            try { conn.socket.close() } catch (_: Exception) {}
        }
        clients.clear()

        // Clean up port file
        try {
            Files.deleteIfExists(instanceDir.resolve(".singularity/agent-port"))
        } catch (_: Exception) {}

        logger.info("IPC server stopped")
    }

    fun connectedClientCount(): Int = clients.size
}
