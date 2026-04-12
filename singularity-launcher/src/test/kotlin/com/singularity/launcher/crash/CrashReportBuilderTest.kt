package com.singularity.launcher.crash

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CrashReportBuilderTest {

    private val builder = CrashReportBuilder(
        launcherVersion = "1.0.0",
        agentVersion = "1.0.0"
    )

    private val sampleParsed = CrashLogParser.ParsedCrash(
        exceptionType = "java.lang.NullPointerException",
        errorMessage = "level is null",
        stackTrace = listOf("com.example.Mod.tick(Mod.java:1)", "net.minecraft.Main.run(Main.java:20)"),
        topStackFrame = "com.example.Mod.tick(Mod.java:1)",
        time = "2026-04-07 14:32:15",
        description = "Ticking entity"
    )

    @Test
    fun `report contains parsed exception info`() {
        val report = builder.build(
            parsed = sampleParsed,
            category = CrashPatternMatcher.CrashCategory.MOD_BUG,
            description = "Mod bug: NPE in tick",
            suggestedActions = listOf("Update the mod"),
            vanillaCrashLog = "... vanilla log ...",
            agentLogs = listOf("INFO: loaded 10 mods")
        )

        assertTrue(report.contains("java.lang.NullPointerException"))
        assertTrue(report.contains("level is null"))
        assertTrue(report.contains("Mod bug: NPE in tick"))
        assertTrue(report.contains("com.example.Mod.tick(Mod.java:1)"))
    }

    @Test
    fun `report contains launcher and agent versions`() {
        val report = builder.build(
            parsed = sampleParsed,
            category = CrashPatternMatcher.CrashCategory.UNKNOWN,
            description = "",
            suggestedActions = emptyList(),
            vanillaCrashLog = "",
            agentLogs = emptyList()
        )
        assertTrue(report.contains("1.0.0"))
    }

    @Test
    fun `report contains suggested actions`() {
        val report = builder.build(
            parsed = sampleParsed,
            category = CrashPatternMatcher.CrashCategory.OUT_OF_MEMORY,
            description = "OOM",
            suggestedActions = listOf("Zwiększ RAM", "Usuń mody"),
            vanillaCrashLog = "",
            agentLogs = emptyList()
        )
        assertTrue(report.contains("Zwiększ RAM"))
        assertTrue(report.contains("Usuń mody"))
    }

    @Test
    fun `report contains system info`() {
        val report = builder.build(
            parsed = sampleParsed,
            category = CrashPatternMatcher.CrashCategory.UNKNOWN,
            description = "",
            suggestedActions = emptyList(),
            vanillaCrashLog = "",
            agentLogs = emptyList()
        )
        assertTrue(report.contains("OS:"))
        assertTrue(report.contains("Java:"))
        assertTrue(report.contains("CPU cores:"))
    }

    @Test
    fun `report includes agent logs tail`() {
        val report = builder.build(
            parsed = sampleParsed,
            category = CrashPatternMatcher.CrashCategory.UNKNOWN,
            description = "",
            suggestedActions = emptyList(),
            vanillaCrashLog = "",
            agentLogs = listOf("line1", "line2", "line3")
        )
        assertTrue(report.contains("line1"))
        assertTrue(report.contains("line3"))
        assertTrue(report.contains("Agent logs"))
    }

    @Test
    fun `report omits agent logs section when empty`() {
        val report = builder.build(
            parsed = sampleParsed,
            category = CrashPatternMatcher.CrashCategory.UNKNOWN,
            description = "",
            suggestedActions = emptyList(),
            vanillaCrashLog = "",
            agentLogs = emptyList()
        )
        assertFalse(report.contains("Agent logs"))
    }

    @Test
    fun `report truncates long vanilla crash log`() {
        val longLog = "A".repeat(6000)
        val report = builder.build(
            parsed = sampleParsed,
            category = CrashPatternMatcher.CrashCategory.UNKNOWN,
            description = "",
            suggestedActions = emptyList(),
            vanillaCrashLog = longLog,
            agentLogs = emptyList()
        )
        assertTrue(report.contains("truncated"))
    }

    @Test
    fun `report handles exception without message`() {
        val noMsg = CrashLogParser.ParsedCrash(
            exceptionType = "java.lang.StackOverflowError",
            errorMessage = "",
            stackTrace = listOf("com.mod.R.call(R.java:5)"),
            topStackFrame = "com.mod.R.call(R.java:5)",
            time = null,
            description = null
        )
        val report = builder.build(
            parsed = noMsg,
            category = CrashPatternMatcher.CrashCategory.INFINITE_RECURSION,
            description = "Recursion",
            suggestedActions = emptyList(),
            vanillaCrashLog = "",
            agentLogs = emptyList()
        )
        assertTrue(report.contains("java.lang.StackOverflowError"))
        // Should not have ": " after exception type when message is empty
        assertFalse(report.contains("StackOverflowError: \n"))
    }
}
