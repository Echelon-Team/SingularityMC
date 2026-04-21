// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.launcher.ui.screens.diagnostics

import java.nio.file.Files
import java.nio.file.Path

/**
 * Parser plików crash-report Minecrafta.
 *
 * **Format crash report:**
 * ```
 * ---- Minecraft Crash Report ----
 * // Witty comment
 *
 * Time: 2026-04-11 14:23:45
 * Description: Rendering overlay
 *
 * java.lang.NullPointerException
 *     at net.minecraft.client.Minecraft.run(Minecraft.java:123)
 *     ...
 * ```
 *
 * **Malformed handling (#33 edge-case):** Jeśli pattern nie matcha → fallback raw content,
 * zwraca CrashReport z description = "Unknown" zamiast rzucać exception.
 */
object CrashLogParser {

    data class CrashReport(
        val fileName: String,
        val rawContent: String,
        val time: String,
        val description: String,
        val lastModifiedMs: Long = 0L,
        val sizeBytes: Long = 0L
    )

    fun parse(rawContent: String, fileName: String, lastModifiedMs: Long = 0L, sizeBytes: Long = 0L): CrashReport {
        val time = extractLine(rawContent, "Time:") ?: "Unknown"
        val description = extractLine(rawContent, "Description:") ?: "Unknown"
        return CrashReport(
            fileName = fileName,
            rawContent = rawContent,
            time = time,
            description = description,
            lastModifiedMs = lastModifiedMs,
            sizeBytes = sizeBytes
        )
    }

    /**
     * Extract linia po prefix (np. "Time: 2026-04-11" → "2026-04-11").
     */
    private fun extractLine(content: String, prefix: String): String? {
        content.lineSequence().forEach { line ->
            if (line.trimStart().startsWith(prefix)) {
                return line.substringAfter(prefix).trim()
            }
        }
        return null
    }

    /**
     * Format CrashReport do markdown dla display.
     */
    fun formatToMarkdown(report: CrashReport): String = buildString {
        appendLine("# ${report.fileName}")
        appendLine()
        appendLine("**Time:** ${report.time}")
        appendLine("**Description:** ${report.description}")
        appendLine()
        appendLine("## Full crash log")
        appendLine()
        appendLine("```")
        appendLine(report.rawContent)
        appendLine("```")
    }

    /**
     * Scan directory for crash-report files and parse each (graceful fallback dla malformed).
     */
    fun scanDirectory(crashReportsDir: Path): List<CrashReport> {
        if (!Files.exists(crashReportsDir) || !Files.isDirectory(crashReportsDir)) return emptyList()

        val reports = mutableListOf<CrashReport>()
        try {
            Files.list(crashReportsDir).use { stream ->
                stream.filter { it.fileName.toString().lowercase().endsWith(".txt") }.forEach { path ->
                    try {
                        val content = Files.readString(path)
                        val lastMod = Files.getLastModifiedTime(path).toMillis()
                        val size = Files.size(path)
                        reports.add(parse(content, path.fileName.toString(), lastMod, size))
                    } catch (e: Exception) {
                        // Include corrupted files as unknown — better than skipping silently
                        reports.add(CrashReport(
                            fileName = path.fileName.toString(),
                            rawContent = "Failed to read: ${e.message}",
                            time = "Unknown",
                            description = "Unknown"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            // Directory listing fail — return empty
        }

        return reports.sortedByDescending { it.lastModifiedMs }
    }
}
