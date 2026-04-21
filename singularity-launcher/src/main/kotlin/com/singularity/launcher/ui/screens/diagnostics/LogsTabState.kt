// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.diagnostics

/**
 * LogsTab state logic — separated dla testowalności bez Compose UI.
 */

enum class LogLevel { INFO, WARN, ERROR }

enum class LogSource { ALL, MINECRAFT, SINGULARITY, MOD }

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val source: LogSource,
    val message: String,
    val sourceName: String = ""  // e.g. mod name
)

data class LogsTabStateData(
    val entries: List<LogEntry> = emptyList(),
    val levelFilter: LogLevel? = null,   // null = all levels
    val sourceFilter: LogSource = LogSource.ALL,
    val searchQuery: String = "",
    val autoScroll: Boolean = true
) {
    val filteredEntries: List<LogEntry>
        get() {
            var result = entries

            if (levelFilter != null) {
                result = result.filter { it.level == levelFilter }
            }

            if (sourceFilter != LogSource.ALL) {
                result = result.filter { it.source == sourceFilter }
            }

            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                result = result.filter {
                    it.message.lowercase().contains(q) ||
                    it.sourceName.lowercase().contains(q)
                }
            }

            return result
        }
}

/**
 * Pure helpers dla LogsTab state — testowalne bez Compose.
 */
object LogsTabLogic {

    /**
     * Parse linia logu MC — format "[HH:mm:ss] [Thread/LEVEL] [Source]: message"
     * Returns null jeśli nie parse'uje (plan może zignorować, wywalić lub traktować jako
     * INFO z unknown source). Tu: zwraca null, caller decyduje.
     */
    fun parseLine(line: String, now: Long = System.currentTimeMillis()): LogEntry? {
        // Simplified parser — real MC logs mają różne formaty w różnych wersjach.
        // Pattern: [HH:mm:ss] [Thread/LEVEL] [Source]: message
        val regex = Regex("""\[(\d{2}):(\d{2}):(\d{2})]\s+\[([^/]+)/(\w+)]\s+\[([^]]+)]:\s*(.*)""")
        val match = regex.matchEntire(line) ?: return null

        val level = when (match.groupValues[5].uppercase()) {
            "INFO" -> LogLevel.INFO
            "WARN", "WARNING" -> LogLevel.WARN
            "ERROR", "SEVERE", "FATAL" -> LogLevel.ERROR
            else -> LogLevel.INFO
        }

        val sourceName = match.groupValues[6]
        val source = when {
            sourceName.startsWith("minecraft", ignoreCase = true) -> LogSource.MINECRAFT
            sourceName.startsWith("singularity", ignoreCase = true) -> LogSource.SINGULARITY
            else -> LogSource.MOD
        }

        return LogEntry(
            timestamp = now,
            level = level,
            source = source,
            message = match.groupValues[7],
            sourceName = sourceName
        )
    }
}
