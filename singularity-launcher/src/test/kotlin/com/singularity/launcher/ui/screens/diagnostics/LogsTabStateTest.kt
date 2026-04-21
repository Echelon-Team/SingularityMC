// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.diagnostics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LogsTabStateTest {

    private fun entry(
        level: LogLevel = LogLevel.INFO,
        source: LogSource = LogSource.MINECRAFT,
        message: String = "test",
        sourceName: String = "Minecraft"
    ) = LogEntry(
        timestamp = System.currentTimeMillis(),
        level = level,
        source = source,
        message = message,
        sourceName = sourceName
    )

    @Test
    fun `empty state has empty filtered list`() {
        val state = LogsTabStateData()
        assertTrue(state.filteredEntries.isEmpty())
    }

    @Test
    fun `level filter returns only matching level`() {
        val state = LogsTabStateData(
            entries = listOf(
                entry(level = LogLevel.INFO),
                entry(level = LogLevel.WARN),
                entry(level = LogLevel.ERROR),
                entry(level = LogLevel.WARN)
            ),
            levelFilter = LogLevel.WARN
        )
        assertEquals(2, state.filteredEntries.size)
        assertTrue(state.filteredEntries.all { it.level == LogLevel.WARN })
    }

    @Test
    fun `source filter ALL returns all sources`() {
        val state = LogsTabStateData(
            entries = listOf(
                entry(source = LogSource.MINECRAFT),
                entry(source = LogSource.SINGULARITY),
                entry(source = LogSource.MOD)
            ),
            sourceFilter = LogSource.ALL
        )
        assertEquals(3, state.filteredEntries.size)
    }

    @Test
    fun `source filter MOD returns only mod entries`() {
        val state = LogsTabStateData(
            entries = listOf(
                entry(source = LogSource.MINECRAFT),
                entry(source = LogSource.MOD),
                entry(source = LogSource.MOD)
            ),
            sourceFilter = LogSource.MOD
        )
        assertEquals(2, state.filteredEntries.size)
    }

    @Test
    fun `searchQuery filters by message case insensitive`() {
        val state = LogsTabStateData(
            entries = listOf(
                entry(message = "Loading world chunks"),
                entry(message = "Rendering failed"),
                entry(message = "Chunk saved")
            ),
            searchQuery = "CHUNK"
        )
        assertEquals(2, state.filteredEntries.size)
    }

    @Test
    fun `parseLine extracts valid MC log line`() {
        val line = "[14:23:45] [Client thread/INFO] [minecraft]: Loading world"
        val entry = LogsTabLogic.parseLine(line)
        assertNotNull(entry)
        assertEquals(LogLevel.INFO, entry!!.level)
        assertEquals(LogSource.MINECRAFT, entry.source)
        assertEquals("Loading world", entry.message)
    }
}
