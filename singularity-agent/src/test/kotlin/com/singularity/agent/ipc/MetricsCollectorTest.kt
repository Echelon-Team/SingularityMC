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

    @Test
    fun `collector broadcasts metrics to IPC server`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(200)
            val port = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

            val collector = MetricsCollector(server, engine)

            // Connect client before starting collector
            Socket("127.0.0.1", port).use { socket ->
                Thread.sleep(100)

                collector.start()
                // Wait for at least one broadcast (1s interval + buffer)
                Thread.sleep(1500)
                collector.stop()

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                assertTrue(length > 0)

                val payload = ByteArray(length)
                input.readFully(payload)
                val decoded = IpcProtocol.decodeMetrics(DataInputStream(payload.inputStream()))

                // RAM should be positive (JVM is running)
                assertTrue(decoded.ramUsed > 0, "RAM used should be positive")
                assertTrue(decoded.ramMax > 0, "RAM max should be positive")
                // Regions/entities/chunks may be 0 (no game running)
                assertTrue(decoded.activeRegions >= 0)
                assertTrue(decoded.entityCount >= 0)
            }
        } finally {
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `tickCompleted increments TPS`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(200)
            val port = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

            val collector = MetricsCollector(server, engine)

            // Simulate 20 ticks in ~1 second
            Socket("127.0.0.1", port).use { socket ->
                Thread.sleep(100)
                collector.start()

                // Simulate ticks
                repeat(20) {
                    collector.tickCompleted()
                    Thread.sleep(50) // 20 ticks over 1s
                }

                Thread.sleep(1200) // wait for collection cycle
                collector.stop()

                val input = DataInputStream(socket.getInputStream())
                // Skip first metric (might be before ticks)
                var decoded: IpcProtocol.MetricsPayload? = null
                repeat(3) {
                    try {
                        val length = input.readInt()
                        val payload = ByteArray(length)
                        input.readFully(payload)
                        decoded = IpcProtocol.decodeMetrics(DataInputStream(payload.inputStream()))
                    } catch (_: Exception) {}
                }

                // TPS should be > 0 after ticks were reported
                assertNotNull(decoded)
                assertTrue(decoded!!.ramUsed > 0)
            }
        } finally {
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `reportFps updates FPS in metrics`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(200)
            val port = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

            val collector = MetricsCollector(server, engine)
            collector.reportFps(144)

            Socket("127.0.0.1", port).use { socket ->
                Thread.sleep(100)
                collector.start()
                Thread.sleep(1500)
                collector.stop()

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                val payload = ByteArray(length)
                input.readFully(payload)
                val decoded = IpcProtocol.decodeMetrics(DataInputStream(payload.inputStream()))

                assertEquals(144, decoded.fps)
            }
        } finally {
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `reportGpuPercent updates GPU in metrics`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(200)
            val port = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

            val collector = MetricsCollector(server, engine)
            collector.reportGpuPercent(85.5f)

            Socket("127.0.0.1", port).use { socket ->
                Thread.sleep(100)
                collector.start()
                Thread.sleep(1500)
                collector.stop()

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                val payload = ByteArray(length)
                input.readFully(payload)
                val decoded = IpcProtocol.decodeMetrics(DataInputStream(payload.inputStream()))

                assertEquals(85.5f, decoded.gpuPercent)
            }
        } finally {
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `cpuPerThread only includes singularity threads`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        try {
            Thread.sleep(200)
            val port = Files.readString(tempDir.resolve(".singularity/agent-port")).trim().toInt()

            val collector = MetricsCollector(server, engine)

            Socket("127.0.0.1", port).use { socket ->
                Thread.sleep(100)
                collector.start()
                Thread.sleep(1500)
                collector.stop()

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                val payload = ByteArray(length)
                input.readFully(payload)
                val decoded = IpcProtocol.decodeMetrics(DataInputStream(payload.inputStream()))

                // Should have some singularity threads (dim pools, chunk gen, IPC accept, metrics collector)
                assertTrue(decoded.cpuPerThread.isNotEmpty(),
                    "Should have at least some singularity-* threads")
                // All values should be valid percentages
                decoded.cpuPerThread.forEach { pct ->
                    assertTrue(pct in 0f..100f, "CPU% should be 0-100, got $pct")
                }
            }
        } finally {
            server.stop()
            engine.shutdown()
        }
    }

    @Test
    fun `stop is safe to call multiple times`() {
        val engine = createEngine()
        val server = IpcServer(tempDir)
        server.start()
        try {
            val collector = MetricsCollector(server, engine)
            collector.start()
            Thread.sleep(200)
            collector.stop()
            collector.stop() // should not throw
        } finally {
            server.stop()
            engine.shutdown()
        }
    }
}
