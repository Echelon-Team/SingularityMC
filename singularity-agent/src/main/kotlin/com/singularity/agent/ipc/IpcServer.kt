package com.singularity.agent.ipc

import org.slf4j.LoggerFactory
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IPC TCP server na localhost.
 *
 * Agent otwiera losowy port, zapisuje go do <instanceDir>/.singularity/agent-port.
 * Launcher łączy się i odbiera strumień metryk (length-prefixed binary packets).
 * Obsługuje wielu klientów jednocześnie (launcher, narzędzia diagnostyczne, testy).
 */
class IpcServer(private val instanceDir: Path) {
    private val logger = LoggerFactory.getLogger(IpcServer::class.java)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<Socket>()

    fun start() {
        if (running.get()) return

        serverSocket = ServerSocket(0) // 0 = random free port
        val port = serverSocket!!.localPort
        logger.info("IPC server started on localhost:{}", port)

        val portFile = instanceDir.resolve(".singularity/agent-port")
        Files.createDirectories(portFile.parent)
        Files.writeString(portFile, port.toString())

        running.set(true)
        acceptThread = Thread {
            while (running.get()) {
                try {
                    val client = serverSocket!!.accept()
                    logger.info("IPC client connected: {}", client.remoteSocketAddress)
                    clients.add(client)
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
        for (client in clients) {
            try {
                val out = DataOutputStream(client.getOutputStream())
                out.write(lengthPrefix)
                out.write(payload)
                out.flush()
            } catch (e: Exception) {
                logger.debug("Client disconnected: {}", e.message)
                deadClients.add(client)
            }
        }
        clients.removeAll(deadClients.toSet())
    }

    fun stop() {
        running.set(false)
        for (client in clients) {
            try { client.close() } catch (_: Exception) {}
        }
        clients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        acceptThread?.interrupt()

        // Clean up port file
        try {
            Files.deleteIfExists(instanceDir.resolve(".singularity/agent-port"))
        } catch (_: Exception) {}

        logger.info("IPC server stopped")
    }

    fun connectedClientCount(): Int = clients.size
}
