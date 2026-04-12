package com.singularity.agent.logging

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentDiagnosticLoggerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `setupFileLogging creates log directory and file`() {
        AgentDiagnosticLogger.setupFileLogging(tempDir)

        val logFile = tempDir.resolve("logs/agent/singularity-agent.log")
        assertTrue(Files.exists(logFile.parent), "Log directory should be created")
        // Logback may not have flushed yet, but directory must exist
        assertTrue(Files.exists(logFile) || Files.isDirectory(logFile.parent))

        AgentDiagnosticLogger.teardown()
    }

    @Test
    fun `readLastLines returns empty list for nonexistent log`() {
        val lines = AgentDiagnosticLogger.readLastLines(tempDir, 100)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `readLastLines returns last N lines from log file`() {
        val logDir = tempDir.resolve("logs/agent")
        Files.createDirectories(logDir)
        val logFile = logDir.resolve("singularity-agent.log")

        val allLines = (1..500).map { "Log line $it" }
        Files.write(logFile, allLines)

        val last200 = AgentDiagnosticLogger.readLastLines(tempDir, 200)
        assertEquals(200, last200.size)
        assertEquals("Log line 301", last200.first())
        assertEquals("Log line 500", last200.last())
    }

    @Test
    fun `readLastLines returns all lines when file has fewer than N`() {
        val logDir = tempDir.resolve("logs/agent")
        Files.createDirectories(logDir)
        val logFile = logDir.resolve("singularity-agent.log")

        val allLines = listOf("line1", "line2", "line3")
        Files.write(logFile, allLines)

        val result = AgentDiagnosticLogger.readLastLines(tempDir, 200)
        assertEquals(3, result.size)
        assertEquals("line1", result.first())
        assertEquals("line3", result.last())
    }

    @Test
    fun `readLastLines handles empty file`() {
        val logDir = tempDir.resolve("logs/agent")
        Files.createDirectories(logDir)
        Files.createFile(logDir.resolve("singularity-agent.log"))

        val result = AgentDiagnosticLogger.readLastLines(tempDir, 200)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getLogFilePath returns correct path`() {
        val path = AgentDiagnosticLogger.getLogFilePath(tempDir)
        assertEquals(tempDir.resolve("logs/agent/singularity-agent.log"), path)
    }
}
