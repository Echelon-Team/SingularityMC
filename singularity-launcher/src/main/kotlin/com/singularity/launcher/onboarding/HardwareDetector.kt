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
        val cpuThreads = Runtime.getRuntime().availableProcessors()
        // availableProcessors() returns logical processors — on non-HT CPUs this equals
        // physical cores, on HT/SMT it's 2x. We can't reliably distinguish without
        // platform-specific tools, so we report logical count for both fields.
        val cpuCores = cpuThreads

        val totalRamBytes = try {
            (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize
                ?: (Runtime.getRuntime().maxMemory() * 2)
        } catch (_: Exception) {
            Runtime.getRuntime().maxMemory() * 2
        }

        HardwareInfo(
            cpuCores = cpuCores,
            cpuThreads = cpuThreads,
            ramMb = totalRamBytes / (1024 * 1024),
            gpuName = detectGpu(),
            osName = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        )
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
            val output = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                logger.warn("GPU detection timed out after 5s")
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
