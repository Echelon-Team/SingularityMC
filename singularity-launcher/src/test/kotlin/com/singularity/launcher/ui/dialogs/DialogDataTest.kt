// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.dialogs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DialogDataTest {

    @Test
    fun `ConflictSeverity has 3 levels`() {
        assertEquals(3, ConflictSeverity.entries.size)
        assertTrue(ConflictSeverity.entries.contains(ConflictSeverity.WARNING))
        assertTrue(ConflictSeverity.entries.contains(ConflictSeverity.ERROR))
        assertTrue(ConflictSeverity.entries.contains(ConflictSeverity.CRITICAL))
    }

    @Test
    fun `ModConflict stores all fields`() {
        val c = ModConflict(
            modA = "Sodium",
            modB = "OptiFine",
            severity = ConflictSeverity.CRITICAL,
            description = "Incompatible renderer"
        )
        assertEquals("Sodium", c.modA)
        assertEquals("OptiFine", c.modB)
        assertEquals(ConflictSeverity.CRITICAL, c.severity)
    }

    @Test
    fun `UpdateInfo stores versions and URL`() {
        val info = UpdateInfo(
            newVersion = "1.2.0",
            currentVersion = "1.1.0",
            changelog = "Bug fixes",
            downloadUrl = "https://example.com/update"
        )
        assertEquals("1.2.0", info.newVersion)
        assertEquals("https://example.com/update", info.downloadUrl)
    }

    @Test
    fun `ImportScanResult detects fractureiser`() {
        val result = ImportScanResult(
            totalJars = 50,
            fractureiserDetected = true,
            suspiciousJars = listOf("evil.jar", "suspicious.jar")
        )
        assertTrue(result.fractureiserDetected)
        assertEquals(2, result.suspiciousJars.size)
    }

    @Test
    fun `ImportScanResult clean scan`() {
        val result = ImportScanResult(
            totalJars = 30,
            fractureiserDetected = false,
            suspiciousJars = emptyList()
        )
        assertFalse(result.fractureiserDetected)
        assertTrue(result.suspiciousJars.isEmpty())
    }

    @Test
    fun `ModConflict equality`() {
        val a = ModConflict("A", "B", ConflictSeverity.WARNING, "desc")
        val b = ModConflict("A", "B", ConflictSeverity.WARNING, "desc")
        assertEquals(a, b)
    }
}
