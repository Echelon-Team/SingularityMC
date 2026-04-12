package com.singularity.launcher.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory

/**
 * Wykrywa sprzęt systemu: CPU cores/threads, RAM, GPU, OS.
 *
 * GPU detection uses platform-specific CLI tools:
 * - Windows: wmic path win32_VideoController get name
 * - Linux: lspci | grep VGA
 * - macOS: system_profiler SPDisplaysDataType
 *
 * Fallback: "unknown" — działamy bez GPU info.
 */
class HardwareDetector {
    private val logger = LoggerFactory.getLogger(HardwareDetector::class.java)

    data class HardwareInfo(
        val cpuCores: Int,
        val cpuThreads: Int,
        val ramMb: Long,
        val gpuName: String?,
        val osName: String
    )

    suspend fun detect(): HardwareInfo = withContext(Dispatchers.IO) {
        val quick = detectQuick()
        quick.copy(gpuName = detectGpu())
    }

    /** CPU + RAM only — instant, no subprocess. */
    fun detectQuick(): HardwareInfo {
        val cpuThreads = Runtime.getRuntime().availableProcessors()
        val totalRamBytes = try {
            (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize
                ?: (Runtime.getRuntime().maxMemory() * 2)
        } catch (_: Exception) {
            Runtime.getRuntime().maxMemory() * 2
        }
        return HardwareInfo(
            cpuCores = cpuThreads,
            cpuThreads = cpuThreads,
            ramMb = totalRamBytes / (1024 * 1024),
            gpuName = null,
            osName = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        )
    }

    /** GPU detection — may block up to 7s. Call from coroutine. */
    suspend fun detectGpuAsync(): String? = withContext(Dispatchers.IO) {
        detectGpu()
    }

    private fun detectGpu(): String? {
        val osName = System.getProperty("os.name").lowercase()
        return try {
            val command = when {
                osName.contains("win") -> arrayOf("wmic", "path", "win32_VideoController", "get", "name")
                osName.contains("linux") -> arrayOf("sh", "-c", "lspci | grep -i vga")
                osName.contains("mac") -> arrayOf("system_profiler", "SPDisplaysDataType")
                else -> return null
            }
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            // Read output async — readText() blocks until EOF, must not block the timeout
            val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().readText()
            }
            val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                logger.warn("GPU detection timed out after 5s")
                return null
            }
            val output = try {
                outputFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null

            parseGpuFromOutput(output, osName)
        } catch (e: Exception) {
            logger.warn("GPU detection failed: {}", e.message)
            null
        }
    }

    private fun parseGpuFromOutput(output: String, osName: String): String? {
        return when {
            osName.contains("win") -> {
                output.lines()
                    .drop(1)
                    .firstOrNull { it.isNotBlank() && !it.trim().equals("Name", ignoreCase = true) }
                    ?.trim()
            }
            osName.contains("linux") -> {
                output.lines()
                    .firstOrNull { it.contains("VGA", ignoreCase = true) }
                    ?.substringAfter(": ")
                    ?.trim()
            }
            osName.contains("mac") -> {
                output.lines()
                    .firstOrNull { it.trim().startsWith("Chipset Model:") }
                    ?.substringAfter(":")
                    ?.trim()
            }
            else -> null
        }
    }

    /**
     * Rekomenduje preset wydajności na podstawie sprzętu.
     *
     * LOW: minimum (<=4 threads, <=6 GB RAM)
     * MEDIUM: typical gaming (6-12 threads, 6-12 GB RAM)
     * HIGH: enthusiast (12-24 threads, 12-24 GB RAM)
     * ULTRA: high-end (24+ threads, 24+ GB RAM)
     */
    fun recommendPreset(info: HardwareInfo): String {
        val ramGb = info.ramMb / 1024
        return when {
            info.cpuThreads >= 24 && ramGb >= 24 -> "ULTRA"
            info.cpuThreads >= 12 && ramGb >= 12 -> "HIGH"
            info.cpuThreads >= 6 && ramGb >= 6 -> "MEDIUM"
            else -> "LOW"
        }
    }
}
