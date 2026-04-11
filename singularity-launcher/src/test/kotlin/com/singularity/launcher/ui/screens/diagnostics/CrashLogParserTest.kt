package com.singularity.launcher.ui.screens.diagnostics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CrashLogParserTest {

    @Test
    fun `parse valid crash log extracts time and description`() {
        val raw = """
            ---- Minecraft Crash Report ----
            // It's not a bug, it's a feature.

            Time: 2026-04-11 14:23:45
            Description: Rendering overlay

            java.lang.NullPointerException
                at net.minecraft.client.Minecraft.run(Minecraft.java:123)
                at net.minecraft.client.main.Main.main(Main.java:45)
        """.trimIndent()

        val report = CrashLogParser.parse(rawContent = raw, fileName = "crash-2026-04-11_14.23.45.txt")
        assertNotNull(report)
        assertEquals("crash-2026-04-11_14.23.45.txt", report.fileName)
        assertTrue(report.rawContent.contains("NullPointerException"))
        assertEquals("2026-04-11 14:23:45", report.time)
        assertEquals("Rendering overlay", report.description)
    }

    @Test
    fun `parse malformed crash log returns fallback`() {
        val raw = "garbage content not a real crash log"
        val report = CrashLogParser.parse(raw, "bad.txt")
        assertNotNull(report, "Fallback should not return null (#33 edge-case)")
        assertEquals("bad.txt", report.fileName)
        assertEquals(raw, report.rawContent)
        assertEquals("Unknown", report.description)
    }

    @Test
    fun `parse empty crash log returns fallback with empty content`() {
        val report = CrashLogParser.parse("", "empty.txt")
        assertNotNull(report)
        assertEquals("", report.rawContent)
        assertEquals("Unknown", report.time)
    }

    @Test
    fun `extractHeadline pulls Description line`() {
        val raw = """
            ---- Minecraft Crash Report ----
            Time: 2026-04-11
            Description: Rendering overlay
            java.lang.NullPointerException
        """.trimIndent()
        val report = CrashLogParser.parse(raw, "test.txt")
        assertEquals("Rendering overlay", report.description)
    }

    @Test
    fun `extractHeadline returns fallback for missing Description`() {
        val raw = "no description here"
        val report = CrashLogParser.parse(raw, "test.txt")
        assertEquals("Unknown", report.description)
    }

    @Test
    fun `formatToMarkdown produces non-blank string`() {
        val report = CrashLogParser.parse(
            "---- Minecraft Crash Report ----\nTime: 2026-04-11\nDescription: Test\njava.lang.RuntimeException",
            "crash.txt"
        )
        val md = CrashLogParser.formatToMarkdown(report)
        assertTrue(md.isNotBlank())
        assertTrue(md.contains("crash.txt"))
    }
}
