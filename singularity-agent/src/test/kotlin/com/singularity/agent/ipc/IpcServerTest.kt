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

    private fun readPort(): Int =
        Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

    private fun awaitClientCount(server: IpcServer, expected: Int, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (server.connectedClientCount() == expected) return
            Thread.sleep(20)
        }
        assertEquals(expected, server.connectedClientCount(),
            "Expected $expected clients within ${timeoutMs}ms")
    }

    private fun sampleMetrics() = IpcProtocol.MetricsPayload(
        tps = 20f, fps = 60,
        ramUsed = 1024, ramMax = 2048,
        gpuPercent = 50f,
        activeRegions = 10, entityCount = 100,
        loadedChunks = 50, pendingChunks = 2,
        cpuPerThread = listOf(45f, 55f)
    )

    @Test
    fun `server starts and writes port file`() {
        val server = IpcServer(tempDir)
        server.start()
        try {
            // Port file is written synchronously in start()
            val portFile = tempDir.resolve(".singularity/agent-port")
            assertTrue(Files.exists(portFile), "Port file should be created")

            val port = readPort()
            assertTrue(port in 1024..65535, "Port $port should be in valid range")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `client can connect and receive all metrics fields`() {
        val server = IpcServer(tempDir)
        server.start()
        try {
            val port = readPort()
            Socket("127.0.0.1", port).use { socket ->
                awaitClientCount(server, 1)

                val metrics = sampleMetrics()
                server.broadcastMetrics(metrics)

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                assertTrue(length > 0, "Packet length should be positive")

                val payload = ByteArray(length)
                input.readFully(payload)
                val decoded = IpcProtocol.decodeMetrics(DataInputStream(payload.inputStream()))

                // Assert ALL fields
                assertEquals(20f, decoded.tps)
                assertEquals(60, decoded.fps)
                assertEquals(1024L, decoded.ramUsed)
                assertEquals(2048L, decoded.ramMax)
                assertEquals(50f, decoded.gpuPercent)
                assertEquals(10, decoded.activeRegions)
                assertEquals(100, decoded.entityCount)
                assertEquals(50, decoded.loadedChunks)
                assertEquals(2, decoded.pendingChunks)
                assertEquals(listOf(45f, 55f), decoded.cpuPerThread)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop closes server socket`() {
        val server = IpcServer(tempDir)
        server.start()
        val port = readPort()
        server.stop()

        // Port file should be cleaned up
        assertFalse(Files.exists(tempDir.resolve(".singularity/agent-port")))

        assertThrows(java.net.ConnectException::class.java) {
            Socket("127.0.0.1", port).use { }
        }
    }

    @Test
    fun `multiple clients receive same broadcast`() {
        val server = IpcServer(tempDir)
        server.start()
        val socket1 = Socket("127.0.0.1", readPort())
        val socket2 = Socket("127.0.0.1", readPort())
        try {
            awaitClientCount(server, 2)

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
        } finally {
            socket1.close()
            socket2.close()
            server.stop()
        }
    }

    @Test
    fun `dead client is cleaned up on next broadcast`() {
        val server = IpcServer(tempDir)
        server.start()
        try {
            val socket = Socket("127.0.0.1", readPort())
            awaitClientCount(server, 1)

            socket.close()

            // Poll: broadcast until dead client is detected and removed
            val metrics = sampleMetrics()
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                server.broadcastMetrics(metrics)
                if (server.connectedClientCount() == 0) break
                Thread.sleep(50)
            }
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
            val port1 = readPort()
            server.start() // second call should be no-op (CAS guard)
            val port2 = readPort()
            assertEquals(port1, port2, "Port should not change on duplicate start")
        } finally {
            server.stop()
        }
    }
}
