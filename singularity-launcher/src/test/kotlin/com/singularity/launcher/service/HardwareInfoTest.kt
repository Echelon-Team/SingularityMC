package com.singularity.launcher.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HardwareInfoTest {

    @Test
    fun `totalCores returns positive value`() {
        // JVM runtime ma availableProcessors() — >= 1 zawsze
        assertTrue(HardwareInfo.totalCores >= 1, "totalCores must be >= 1")
    }

    @Test
    fun `totalCores matches availableProcessors`() {
        assertEquals(Runtime.getRuntime().availableProcessors(), HardwareInfo.totalCores)
    }

    @Test
    fun `totalRamMB returns positive value`() {
        // Każdy system ma > 0 RAM
        assertTrue(HardwareInfo.totalRamMB > 0, "totalRamMB must be > 0")
    }

    @Test
    fun `totalRamMB is reasonable for modern systems`() {
        // Test CI environment ma co najmniej 512 MB. Jeśli ten test failuje, system jest
        // absurdalnie mały albo OSMXBean nie działa (potencjalna regresja JVM API).
        assertTrue(HardwareInfo.totalRamMB >= 512, "totalRamMB unreasonably low: ${HardwareInfo.totalRamMB}")
    }

    @Test
    fun `totalCores is stable across calls (lazy property)`() {
        val first = HardwareInfo.totalCores
        val second = HardwareInfo.totalCores
        assertEquals(first, second, "Lazy property should return same value")
    }

    @Test
    fun `totalRamMB is stable across calls (lazy property)`() {
        val first = HardwareInfo.totalRamMB
        val second = HardwareInfo.totalRamMB
        assertEquals(first, second)
    }
}
