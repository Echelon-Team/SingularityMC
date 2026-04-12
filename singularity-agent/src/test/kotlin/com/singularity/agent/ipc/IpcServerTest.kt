package com.singularity.agent.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

class IpcServerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `server starts and writes port file`() {
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(200)
            val portFile = tempDir.resolve(".singularity/agent-port")
            assertTrue(Files.exists(portFile), "Port file should be created")

            val port = Files.readString(portFile).trim().toInt()
            assertTrue(port in 1024..65535, "Port $port should be in valid range")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `client can connect and receive metrics`() {
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(200)
            val port = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

            Socket("127.0.0.1", port).use { socket ->
                val metrics = IpcProtocol.MetricsPayload(
                    tps = 20f, fps = 60,
                    ramUsed = 1024, ramMax = 2048,
                    gpuPercent = 50f,
                    activeRegions = 10, entityCount = 100,
                    loadedChunks = 50, pendingChunks = 2,
                    cpuPerThread = listOf(45f, 55f)
                )
                // Give accept thread time to register client
                Thread.sleep(100)
                server.broadcastMetrics(metrics)

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                assertTrue(length > 0, "Packet length should be positive")

                val payload = ByteArray(length)
                input.readFully(payload)
                val decoded = IpcProtocol.decodeMetrics(
                    DataInputStream(payload.inputStream())
                )
                assertEquals(20f, decoded.tps)
                assertEquals(60, decoded.fps)
                assertEquals(50f, decoded.gpuPercent)
                assertEquals(10, decoded.activeRegions)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop closes server socket`() {
        val server = IpcServer(tempDir)
        server.start()
        Thread.sleep(200)
        val port = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

        server.stop()
        Thread.sleep(200)

        assertThrows(java.net.ConnectException::class.java) {
            Socket("127.0.0.1", port).use { }
        }
    }

    @Test
    fun `multiple clients receive same broadcast`() {
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(200)
            val port = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

            val socket1 = Socket("127.0.0.1", port)
            val socket2 = Socket("127.0.0.1", port)
            Thread.sleep(100) // let accept thread register both

            val metrics = IpcProtocol.MetricsPayload(
                tps = 19.5f, fps = 120,
                ramUsed = 0, ramMax = 0, gpuPercent = 0f,
                activeRegions = 0, entityCount = 0, loadedChunks = 0, pendingChunks = 0,
                cpuPerThread = emptyList()
            )
            server.broadcastMetrics(metrics)

            for (socket in listOf(socket1, socket2)) {
                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                val payload = ByteArray(length)
                input.readFully(payload)
                val decoded = IpcProtocol.decodeMetrics(DataInputStream(payload.inputStream()))
                assertEquals(19.5f, decoded.tps)
                assertEquals(120, decoded.fps)
            }

            assertEquals(2, server.connectedClientCount())
            socket1.close()
            socket2.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `dead client is cleaned up on next broadcast`() {
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(200)
            val port = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

            val socket = Socket("127.0.0.1", port)
            Thread.sleep(100)
            assertEquals(1, server.connectedClientCount())

            socket.close()
            Thread.sleep(50)

            // Broadcast to trigger dead client cleanup
            val metrics = IpcProtocol.MetricsPayload(
                tps = 20f, fps = 60,
                ramUsed = 0, ramMax = 0, gpuPercent = 0f,
                activeRegions = 0, entityCount = 0, loadedChunks = 0, pendingChunks = 0,
                cpuPerThread = emptyList()
            )
            server.broadcastMetrics(metrics)
            assertEquals(0, server.connectedClientCount())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `connectedClientCount returns zero before any connections`() {
        val server = IpcServer(tempDir)
        server.start()
        try {
            assertEquals(0, server.connectedClientCount())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `start is idempotent`() {
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(100)
            val port1 = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()
            server.start() // second call should be no-op
            val port2 = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()
            assertEquals(port1, port2, "Port should not change on duplicate start")
        } finally {
            server.stop()
        }
    }
}
