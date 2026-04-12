package com.singularity.launcher.onboarding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HardwareDetectorTest {

    @Test
    fun `detect returns positive values for cpu and ram`() = runBlocking {
        val detector = HardwareDetector()
        val info = detector.detect()

        assertTrue(info.cpuCores > 0, "cpuCores should be > 0")
        assertTrue(info.cpuThreads > 0, "cpuThreads should be > 0")
        assertTrue(info.ramMb > 0, "ramMb should be > 0")
        assertTrue(info.osName.isNotBlank(), "osName should not be blank")
    }

    @Test
    fun `cpuThreads is at least cpuCores`() = runBlocking {
        val detector = HardwareDetector()
        val info = detector.detect()
        assertTrue(info.cpuThreads >= info.cpuCores,
            "cpuThreads (${info.cpuThreads}) should be >= cpuCores (${info.cpuCores})")
    }

    @Test
    fun `recommendPreset returns LOW for low-end hardware`() {
        val detector = HardwareDetector()
        val info = HardwareDetector.HardwareInfo(
            cpuCores = 2, cpuThreads = 4, ramMb = 4096,
            gpuName = "Intel HD Graphics 4000", osName = "Linux"
        )
        assertEquals("LOW", detector.recommendPreset(info))
    }

    @Test
    fun `recommendPreset returns MEDIUM for mid-range`() {
        val detector = HardwareDetector()
        val info = HardwareDetector.HardwareInfo(
            cpuCores = 4, cpuThreads = 8, ramMb = 8192,
            gpuName = "NVIDIA GTX 1060", osName = "Windows 11"
        )
        assertEquals("MEDIUM", detector.recommendPreset(info))
    }

    @Test
    fun `recommendPreset returns HIGH for good hardware`() {
        val detector = HardwareDetector()
        val info = HardwareDetector.HardwareInfo(
            cpuCores = 8, cpuThreads = 16, ramMb = 16384,
            gpuName = "NVIDIA RTX 3070", osName = "Windows 11"
        )
        assertEquals("HIGH", detector.recommendPreset(info))
    }

    @Test
    fun `recommendPreset returns ULTRA for high-end hardware`() {
        val detector = HardwareDetector()
        val info = HardwareDetector.HardwareInfo(
            cpuCores = 16, cpuThreads = 32, ramMb = 32768,
            gpuName = "NVIDIA RTX 4090", osName = "Windows 11"
        )
        assertEquals("ULTRA", detector.recommendPreset(info))
    }

    @Test
    fun `recommendPreset boundary - exactly 6 threads and 6GB`() {
        val detector = HardwareDetector()
        val info = HardwareDetector.HardwareInfo(
            cpuCores = 3, cpuThreads = 6, ramMb = 6144,
            gpuName = null, osName = "Linux"
        )
        assertEquals("MEDIUM", detector.recommendPreset(info))
    }

    @Test
    fun `recommendPreset boundary - 5 threads`() {
        val detector = HardwareDetector()
        val info = HardwareDetector.HardwareInfo(
            cpuCores = 2, cpuThreads = 5, ramMb = 8192,
            gpuName = null, osName = "Linux"
        )
        assertEquals("LOW", detector.recommendPreset(info),
            "5 threads < 6 threshold should be LOW even with enough RAM")
    }
}
