// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.logging

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import ch.qos.logback.classic.Logger as LogbackLogger

class AgentDiagnosticLoggerTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun cleanup() {
        AgentDiagnosticLogger.teardown()
    }

    @Test
    fun `setupFileLogging creates log file and writes`() {
        AgentDiagnosticLogger.setupFileLogging(tempDir)

        // Force a log event to flush
        val testLogger = LoggerFactory.getLogger("test.setup")
        testLogger.info("setup test message")

        val logFile = tempDir.resolve("logs/agent/singularity-agent.log")
        assertTrue(Files.exists(logFile), "Log file should be created after logging")
        val content = Files.readString(logFile)
        assertTrue(content.contains("Agent diagnostic logger initialized"), "Should contain init message")
    }

    @Test
    fun `teardown removes appender from root logger`() {
        AgentDiagnosticLogger.setupFileLogging(tempDir)

        val rootLogger = LoggerFactory.getLogger(LogbackLogger.ROOT_LOGGER_NAME) as LogbackLogger
        assertNotNull(rootLogger.getAppender("SINGULARITY_FILE"), "Appender should exist after setup")

        AgentDiagnosticLogger.teardown()
        assertNull(rootLogger.getAppender("SINGULARITY_FILE"), "Appender should be removed after teardown")
    }

    @Test
    fun `setupFileLogging is idempotent — second call replaces first`() {
        AgentDiagnosticLogger.setupFileLogging(tempDir)
        AgentDiagnosticLogger.setupFileLogging(tempDir) // should not leak first appender

        val rootLogger = LoggerFactory.getLogger(LogbackLogger.ROOT_LOGGER_NAME) as LogbackLogger
        // Count SINGULARITY_FILE appenders — should be exactly 1
        var count = 0
        val iter = rootLogger.iteratorForAppenders()
        while (iter.hasNext()) {
            if (iter.next().name == "SINGULARITY_FILE") count++
        }
        assertEquals(1, count, "Should have exactly 1 SINGULARITY_FILE appender after double setup")
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

    @Test
    fun `teardown is safe to call without setup`() {
        assertDoesNotThrow { AgentDiagnosticLogger.teardown() }
    }

    @Test
    fun `teardown is safe to call multiple times`() {
        AgentDiagnosticLogger.setupFileLogging(tempDir)
        assertDoesNotThrow {
            AgentDiagnosticLogger.teardown()
            AgentDiagnosticLogger.teardown()
        }
    }
}
