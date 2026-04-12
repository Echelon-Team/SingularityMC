package com.singularity.launcher.integration

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.time.Instant

/**
 * Discord Rich Presence via IPC named pipe (Windows) / Unix socket (Linux/macOS).
 *
 * Protocol: length-prefixed JSON frames over Discord IPC pipe.
 * Frame format: [4 bytes opcode LE][4 bytes length LE][JSON payload]
 *
 * Opcodes:
 *   0 = HANDSHAKE
 *   1 = FRAME (activity update)
 *   2 = CLOSE
 *   3 = PING
 *   4 = PONG
 *
 * Discord pipe: \\.\pipe\discord-ipc-{0-9} (Windows) or
 * {XDG_RUNTIME_DIR,/tmp,...}/discord-ipc-{0-9} (Unix)
 *
 * Pure Kotlin implementation — no external deps (no JNI, no native DLL).
 */
class DiscordRpcManager(
    private val clientId: String
) {
    private val logger = LoggerFactory.getLogger(DiscordRpcManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    data class PresenceConfig(
        val enabled: Boolean = true,
        val showInstance: Boolean = true,
        val showVersion: Boolean = true,
        val showServer: Boolean = false
    )

    data class PresenceState(
        val instanceName: String? = null,
        val minecraftVersion: String? = null,
        val serverAddress: String? = null,
        val isPlaying: Boolean = false,
        val startedAt: Instant? = null
    )

    @Volatile private var config = PresenceConfig()
    @Volatile private var currentState = PresenceState()
    @Volatile private var connected = false

    // Platform-specific pipe handle — guarded by pipeLock
    private var windowsPipe: RandomAccessFile? = null
    private var unixChannel: SocketChannel? = null
    private val pipeLock = Any()

    fun initialize() {
        if (!config.enabled) return
        logger.info("Initializing Discord RPC (client_id: {})", clientId)

        try {
            if (connectToPipe()) {
                sendHandshake()
                connected = true
                logger.info("Discord RPC connected")
            } else {
                logger.info("Discord not running or pipe not found — RPC disabled")
            }
        } catch (e: Exception) {
            logger.warn("Discord RPC initialization failed: {}", e.message)
        }
    }

    private fun connectToPipe(): Boolean {
        val osName = System.getProperty("os.name").lowercase()
        return if (osName.contains("win")) {
            connectWindowsPipe()
        } else {
            connectUnixSocket()
        }
    }

    private fun connectWindowsPipe(): Boolean {
        for (i in 0..9) {
            try {
                val pipe = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw")
                windowsPipe = pipe
                logger.debug("Connected to discord-ipc-{}", i)
                return true
            } catch (_: Exception) {
                continue
            }
        }
        return false
    }

    private fun connectUnixSocket(): Boolean {
        val tmpDirs = listOfNotNull(
            System.getenv("XDG_RUNTIME_DIR"),
            System.getenv("TMPDIR"),
            System.getenv("TMP"),
            "/tmp"
        )

        for (dir in tmpDirs) {
            for (i in 0..9) {
                try {
                    val socketPath = Path.of(dir, "discord-ipc-$i")
                    if (!java.nio.file.Files.exists(socketPath)) continue
                    val address = UnixDomainSocketAddress.of(socketPath)
                    val channel = SocketChannel.open(address)
                    unixChannel = channel
                    logger.debug("Connected to {}/discord-ipc-{}", dir, i)
                    return true
                } catch (_: Exception) {
                    continue
                }
            }
        }
        return false
    }

    private fun sendHandshake() {
        val handshake = buildJsonObject {
            put("v", 1)
            put("client_id", clientId)
        }
        writeFrame(0, handshake.toString()) // opcode 0 = HANDSHAKE

        // Read handshake response
        try {
            readFrame()
        } catch (e: Exception) {
            logger.debug("Handshake response read failed: {}", e.message)
        }
    }

    fun updateConfig(newConfig: PresenceConfig) {
        config = newConfig
        if (!newConfig.enabled && connected) {
            shutdown()
        } else if (newConfig.enabled && !connected) {
            initialize()
        }
    }

    fun updatePresence(state: PresenceState) {
        currentState = state
        if (!connected || !config.enabled) return

        val details = buildString {
            if (config.showInstance && state.instanceName != null) {
                append(state.instanceName)
            } else {
                append("SingularityMC")
            }
        }

        val status = buildString {
            when {
                state.isPlaying && config.showVersion && state.minecraftVersion != null ->
                    append("Gra w ${state.minecraftVersion}")
                state.isPlaying -> append("Gra w Minecraft")
                else -> append("W launcherze")
            }
            if (config.showServer && state.serverAddress != null) {
                append(" na ${state.serverAddress}")
            }
        }

        val activity = buildJsonObject {
            put("details", details)
            put("state", status)
            if (state.startedAt != null) {
                putJsonObject("timestamps") {
                    put("start", state.startedAt.epochSecond)
                }
            }
            putJsonObject("assets") {
                put("large_image", "singularitymc_logo")
                put("large_text", "SingularityMC")
            }
        }

        val payload = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            putJsonObject("args") {
                put("pid", ProcessHandle.current().pid())
                put("activity", activity)
            }
            put("nonce", System.nanoTime().toString())
        }

        synchronized(pipeLock) {
            try {
                writeFrame(1, payload.toString()) // opcode 1 = FRAME
            } catch (e: Exception) {
                logger.debug("Failed to update presence: {}", e.message)
                connected = false
            }
        }
    }

    private fun writeFrame(opcode: Int, payload: String) {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(opcode)
        header.putInt(payloadBytes.size)
        header.flip()

        val headerArray = ByteArray(8)
        header.get(headerArray)

        // Discord pipe requires single write for the entire frame
        val frame = headerArray + payloadBytes

        when {
            windowsPipe != null -> windowsPipe!!.write(frame)
            unixChannel != null -> {
                val buf = ByteBuffer.wrap(frame)
                while (buf.hasRemaining()) {
                    unixChannel!!.write(buf)
                }
            }
        }
    }

    private fun readFrame(): String? {
        val headerBuf = ByteArray(8)

        when {
            windowsPipe != null -> windowsPipe!!.readFully(headerBuf)
            unixChannel != null -> {
                val buf = ByteBuffer.wrap(headerBuf)
                while (buf.hasRemaining()) {
                    if (unixChannel!!.read(buf) == -1) return null
                }
            }
            else -> return null
        }

        val header = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
        val opcode = header.getInt()
        val length = header.getInt()

        if (length <= 0 || length > 65536) return null

        val payload = ByteArray(length)
        when {
            windowsPipe != null -> windowsPipe!!.readFully(payload)
            unixChannel != null -> {
                val buf = ByteBuffer.wrap(payload)
                while (buf.hasRemaining()) {
                    if (unixChannel!!.read(buf) == -1) return null
                }
            }
        }

        return String(payload, Charsets.UTF_8)
    }

    fun shutdown() {
        synchronized(pipeLock) {
            if (!connected) return
            logger.info("Shutting down Discord RPC")
            try {
                writeFrame(2, buildJsonObject { put("v", 1) }.toString()) // opcode 2 = CLOSE
            } catch (_: Exception) {}

            try { windowsPipe?.close() } catch (_: Exception) {}
            try { unixChannel?.close() } catch (_: Exception) {}
            windowsPipe = null
            unixChannel = null
            connected = false
        }
    }

    fun isConnected(): Boolean = connected
}
