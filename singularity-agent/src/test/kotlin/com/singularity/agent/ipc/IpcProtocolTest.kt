package com.singularity.agent.ipc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class IpcProtocolTest {

    @Test
    fun `encode and decode round-trip`() {
        val metrics = IpcProtocol.MetricsPayload(
            tps = 19.8f,
            fps = 144,
            ramUsed = 4_200_000_000L,
            ramMax = 8_000_000_000L,
            gpuPercent = 78.5f,
            activeRegions = 42,
            entityCount = 1234,
            loadedChunks = 289,
            pendingChunks = 5,
            cpuPerThread = listOf(45.2f, 62.1f, 38.7f, 51.9f)
        )

        val encoded = IpcProtocol.encodeMetrics(metrics)
        val decoded = IpcProtocol.decodeMetrics(DataInputStream(ByteArrayInputStream(encoded)))

        assertEquals(metrics.tps, decoded.tps)
        assertEquals(metrics.fps, decoded.fps)
        assertEquals(metrics.ramUsed, decoded.ramUsed)
        assertEquals(metrics.ramMax, decoded.ramMax)
        assertEquals(metrics.gpuPercent, decoded.gpuPercent)
        assertEquals(metrics.activeRegions, decoded.activeRegions)
        assertEquals(metrics.entityCount, decoded.entityCount)
        assertEquals(metrics.loadedChunks, decoded.loadedChunks)
        assertEquals(metrics.pendingChunks, decoded.pendingChunks)
        assertEquals(metrics.cpuPerThread, decoded.cpuPerThread)
    }

    @Test
    fun `encoded size matches expected bytes`() {
        val metrics = IpcProtocol.MetricsPayload(
            tps = 20f, fps = 60,
            ramUsed = 0, ramMax = 0,
            gpuPercent = 0f,
            activeRegions = 0, entityCount = 0, loadedChunks = 0, pendingChunks = 0,
            cpuPerThread = listOf(50f, 60f, 70f, 80f)
        )

        val encoded = IpcProtocol.encodeMetrics(metrics)
        // type(1) + tps(4) + fps(4) + ramUsed(8) + ramMax(8) + gpu(4)
        // + regions(4) + entities(4) + chunks(4) + pending(4) + threadCount(2) + 4×float(16) = 63
        assertEquals(63, encoded.size)
    }

    @Test
    fun `type byte is 0x01 for METRICS`() {
        val metrics = IpcProtocol.MetricsPayload(
            tps = 20f, fps = 60,
            ramUsed = 0, ramMax = 0,
            gpuPercent = 0f,
            activeRegions = 0, entityCount = 0, loadedChunks = 0, pendingChunks = 0,
            cpuPerThread = emptyList()
        )
        val encoded = IpcProtocol.encodeMetrics(metrics)
        assertEquals(0x01.toByte(), encoded[0])
    }

    @Test
    fun `encodeLengthPrefix returns 4 bytes big-endian`() {
        val result = IpcProtocol.encodeLengthPrefix(100)
        assertEquals(4, result.size)
        assertEquals(0, result[0])
        assertEquals(0, result[1])
        assertEquals(0, result[2])
        assertEquals(100.toByte(), result[3])
    }

    @Test
    fun `round-trip with zero threads`() {
        val metrics = IpcProtocol.MetricsPayload(
            tps = 0f, fps = 0,
            ramUsed = 0, ramMax = 0,
            gpuPercent = 0f,
            activeRegions = 0, entityCount = 0, loadedChunks = 0, pendingChunks = 0,
            cpuPerThread = emptyList()
        )
        val encoded = IpcProtocol.encodeMetrics(metrics)
        val decoded = IpcProtocol.decodeMetrics(DataInputStream(ByteArrayInputStream(encoded)))
        assertEquals(0, decoded.cpuPerThread.size)
        // type(1) + 9 fields(44) + threadCount(2) = 47
        assertEquals(47, encoded.size)
    }

    @Test
    fun `round-trip with max 32 threads`() {
        val cpuValues = List(32) { it.toFloat() }
        val metrics = IpcProtocol.MetricsPayload(
            tps = 20f, fps = 240,
            ramUsed = Long.MAX_VALUE, ramMax = Long.MAX_VALUE,
            gpuPercent = 100f,
            activeRegions = Int.MAX_VALUE, entityCount = Int.MAX_VALUE,
            loadedChunks = Int.MAX_VALUE, pendingChunks = Int.MAX_VALUE,
            cpuPerThread = cpuValues
        )
        val encoded = IpcProtocol.encodeMetrics(metrics)
        val decoded = IpcProtocol.decodeMetrics(DataInputStream(ByteArrayInputStream(encoded)))
        assertEquals(32, decoded.cpuPerThread.size)
        assertEquals(cpuValues, decoded.cpuPerThread)
    }

    @Test
    fun `decode rejects wrong type byte`() {
        val badPayload = byteArrayOf(0x99.toByte(), 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) {
            IpcProtocol.decodeMetrics(DataInputStream(ByteArrayInputStream(badPayload)))
        }
    }

    @Test
    fun `encodeLengthPrefix handles large values`() {
        val result = IpcProtocol.encodeLengthPrefix(65536)
        // 65536 = 0x00010000
        assertEquals(0.toByte(), result[0])
        assertEquals(1.toByte(), result[1])
        assertEquals(0.toByte(), result[2])
        assertEquals(0.toByte(), result[3])
    }
}
