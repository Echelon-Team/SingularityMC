// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.ipc

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * IPC binary protocol między agentem a launcherem.
 *
 * Packet format: [4 bytes length BE][payload]
 *
 * Payload type 0x01 METRICS (agent → launcher):
 *   byte  type = 0x01
 *   float tps
 *   int   fps
 *   long  ramUsed
 *   long  ramMax
 *   float gpuPercent
 *   int   activeRegions
 *   int   entityCount
 *   int   loadedChunks
 *   int   pendingChunks
 *   short threadCount
 *   float[threadCount] cpuPerThread
 *
 * Payload type 0x02 COMMAND (launcher → agent):
 *   byte  type = 0x02
 *   short commandLength
 *   byte[commandLength] commandUtf8
 *   int   argsCount
 *   (short keyLen, byte[keyLen] key, short valLen, byte[valLen] val)[argsCount]
 */
object IpcProtocol {

    const val PROTOCOL_VERSION: Byte = 0x01
    const val TYPE_METRICS: Byte = 0x01
    const val TYPE_COMMAND: Byte = 0x02
    const val MAX_THREADS: Int = Short.MAX_VALUE.toInt()

    data class MetricsPayload(
        val tps: Float,
        val fps: Int,
        val ramUsed: Long,
        val ramMax: Long,
        val gpuPercent: Float,
        val activeRegions: Int,
        val entityCount: Int,
        val loadedChunks: Int,
        val pendingChunks: Int,
        val cpuPerThread: List<Float>
    )

    // ByteBuffer defaults to BIG_ENDIAN — matches DataOutputStream used in encodeMetrics
    fun encodeLengthPrefix(length: Int): ByteArray {
        val buf = ByteBuffer.allocate(4)
        buf.putInt(length)
        return buf.array()
    }

    /**
     * Reads one length-prefixed frame from the stream.
     * Returns the payload bytes (without the 4-byte length prefix).
     */
    fun readFrame(input: DataInputStream): ByteArray {
        val length = input.readInt()
        require(length in 1..1_048_576) { "Invalid frame length: $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return payload
    }

    fun encodeMetrics(metrics: MetricsPayload): ByteArray {
        require(metrics.cpuPerThread.size <= MAX_THREADS) {
            "cpuPerThread size ${metrics.cpuPerThread.size} exceeds max $MAX_THREADS"
        }

        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)

        out.writeByte(TYPE_METRICS.toInt())
        out.writeFloat(metrics.tps)
        out.writeInt(metrics.fps)
        out.writeLong(metrics.ramUsed)
        out.writeLong(metrics.ramMax)
        out.writeFloat(metrics.gpuPercent)
        out.writeInt(metrics.activeRegions)
        out.writeInt(metrics.entityCount)
        out.writeInt(metrics.loadedChunks)
        out.writeInt(metrics.pendingChunks)
        out.writeShort(metrics.cpuPerThread.size)
        metrics.cpuPerThread.forEach { out.writeFloat(it) }

        return baos.toByteArray()
    }

    fun decodeMetrics(input: DataInputStream): MetricsPayload {
        val type = input.readByte()
        require(type == TYPE_METRICS) { "Expected METRICS (0x01), got 0x${type.toString(16)}" }

        val tps = input.readFloat()
        val fps = input.readInt()
        val ramUsed = input.readLong()
        val ramMax = input.readLong()
        val gpuPercent = input.readFloat()
        val activeRegions = input.readInt()
        val entityCount = input.readInt()
        val loadedChunks = input.readInt()
        val pendingChunks = input.readInt()
        val threadCount = input.readShort().toInt().and(0xFFFF) // unsigned short
        val cpuPerThread = List(threadCount) { input.readFloat() }

        return MetricsPayload(
            tps = tps,
            fps = fps,
            ramUsed = ramUsed,
            ramMax = ramMax,
            gpuPercent = gpuPercent,
            activeRegions = activeRegions,
            entityCount = entityCount,
            loadedChunks = loadedChunks,
            pendingChunks = pendingChunks,
            cpuPerThread = cpuPerThread
        )
    }
}
