// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.ipc

import com.singularity.agent.registry.SingularityModRegistry
import com.singularity.agent.threading.ThreadingEngine
import com.singularity.agent.threading.config.ThreadingConfig
import com.singularity.agent.threading.region.RegionGroupingHint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

class MetricsCollectorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createEngine(): ThreadingEngine {
        val config = ThreadingConfig()
        val registry = SingularityModRegistry()
        val engine = ThreadingEngine(config, registry, RegionGroupingHint.NONE)
        engine.initialize(listOf("overworld", "the_nether", "the_end"))
        return engine
    }

    private fun readPort(): Int =
        Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

    private fun awaitClientCount(server: IpcServer, expected: Int, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (server.connectedClientCount() == expected) return
            Thread.sleep(20)
        }
        assertEquals(expected, server.connectedClientCount())
    }

    private fun readMetricsPacket(input: DataInputStream): IpcProtocol.MetricsPayload {
        val length = input.readInt()
        val payload = ByteArray(length)
        input.readFully(payload)
        return IpcProtocol.decodeMetrics(DataInputStream(payload.inputStream()))
    }

    @Test
    fun `collector broadcasts real JVM metrics`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        val collector = MetricsCollector(server, engine)
        try {
            Socket("127.0.0.1", readPort()).use { socket ->
                awaitClientCount(server, 1)
                collector.start()

                // Wait for first broadcast (initialDelay=1s + margin)
                socket.soTimeout = 3000
                val input = DataInputStream(socket.getInputStream())
                val decoded = readMetricsPacket(input)

                assertTrue(decoded.ramUsed > 0, "RAM used should be positive, got ${decoded.ramUsed}")
                assertTrue(decoded.ramMax > 0, "RAM max should be positive, got ${decoded.ramMax}")
                assertTrue(decoded.activeRegions >= 0)
                assertTrue(decoded.entityCount >= 0)
            }
        } finally {
            collector.stop()
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `tickCompleted increments TPS`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        val collector = MetricsCollector(server, engine)
        try {
            Socket("127.0.0.1", readPort()).use { socket ->
                awaitClientCount(server, 1)

                // Simulate ticks before starting collector — fill the window
                repeat(20) {
                    collector.tickCompleted()
                    Thread.sleep(50)
                }

                collector.start()
                socket.soTimeout = 3000
                val input = DataInputStream(socket.getInputStream())
                val decoded = readMetricsPacket(input)

                assertTrue(decoded.tps > 0f, "TPS should be > 0 after 20 ticks, got ${decoded.tps}")
            }
        } finally {
            collector.stop()
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `reportFps updates FPS in metrics`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        val collector = MetricsCollector(server, engine)
        try {
            collector.reportFps(144)

            Socket("127.0.0.1", readPort()).use { socket ->
                awaitClientCount(server, 1)
                collector.start()

                socket.soTimeout = 3000
                val input = DataInputStream(socket.getInputStream())
                val decoded = readMetricsPacket(input)

                assertEquals(144, decoded.fps, "FPS should be 144")
            }
        } finally {
            collector.stop()
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `reportGpuPercent updates GPU in metrics`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        val collector = MetricsCollector(server, engine)
        try {
            collector.reportGpuPercent(85.5f)

            Socket("127.0.0.1", readPort()).use { socket ->
                awaitClientCount(server, 1)
                collector.start()

                socket.soTimeout = 3000
                val input = DataInputStream(socket.getInputStream())
                val decoded = readMetricsPacket(input)

                assertEquals(85.5f, decoded.gpuPercent, "GPU should be 85.5%")
            }
        } finally {
            collector.stop()
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `cpuPerThread contains only singularity threads with valid percentages`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        val collector = MetricsCollector(server, engine)
        try {
            Socket("127.0.0.1", readPort()).use { socket ->
                awaitClientCount(server, 1)
                collector.start()

                socket.soTimeout = 3000
                val input = DataInputStream(socket.getInputStream())
                val decoded = readMetricsPacket(input)

                // All values should be valid percentages
                decoded.cpuPerThread.forEach { pct ->
                    assertTrue(pct in 0f..100f, "CPU% should be 0-100, got $pct")
                }
                // Protocol limit: max 32 entries
                assertTrue(decoded.cpuPerThread.size <= 32,
                    "cpuPerThread should be capped at 32, got ${decoded.cpuPerThread.size}")
            }
        } finally {
            collector.stop()
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `stop is safe to call multiple times`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        val collector = MetricsCollector(server, engine)
        try {
            collector.start()
            Thread.sleep(200)
            collector.stop()
            collector.stop() // should not throw
        } finally {
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `initial TPS is zero before any ticks`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        val collector = MetricsCollector(server, engine)
        try {
            // No tickCompleted() calls — TPS should be 0
            Socket("127.0.0.1", readPort()).use { socket ->
                awaitClientCount(server, 1)
                collector.start()

                socket.soTimeout = 3000
                val input = DataInputStream(socket.getInputStream())
                val decoded = readMetricsPacket(input)

                assertEquals(0f, decoded.tps, "Initial TPS should be 0 before any ticks")
            }
        } finally {
            collector.stop()
            server.stop()
            engine.shutdown()
        }
    }
}
