package com.singularity.launcher.crash

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Orkiestrator analizy crashów.
 *
 * 1. Parsuje crash log i stack trace (CrashLogParser)
 * 2. Kategoryzuje crash (CrashPatternMatcher)
 * 3. Tłumaczy błąd na zrozumiały opis
 * 4. Proponuje rozwiązania
 * 5. Dołącza logi agenta + vanilla crash log
 *
 * Logi agenta czytane bezpośrednio z dysku (logs/agent/singularity-agent.log)
 * — launcher i agent to osobne procesy, brak cross-module dependency.
 */
class CrashAnalyzer(
    private val instanceDir: Path,
    private val patternMatcher: CrashPatternMatcher,
    private val reportBuilder: CrashReportBuilder
) {
    private val logger = LoggerFactory.getLogger(CrashAnalyzer::class.java)

    data class AnalysisResult(
        val parsed: CrashLogParser.ParsedCrash,
        val category: CrashPatternMatcher.CrashCategory,
        val humanReadableDescription: String,
        val suggestedActions: List<String>,
        val fullReport: String
    )

    fun analyze(crashReportFile: Path): AnalysisResult {
        logger.info("Analyzing crash report: {}", crashReportFile)

        val content = Files.readString(crashReportFile)
        val parsed = CrashLogParser.parse(content)
        val category = patternMatcher.categorize(parsed)
        val description = patternMatcher.describe(parsed, category)
        val actions = patternMatcher.suggestActions(category)

        // Read last 200 lines of agent logs from disk (no cross-module import).
        // Uses BufferedReader from end to avoid loading entire file for large logs.
        val agentLogFile = instanceDir.resolve("logs/agent/singularity-agent.log")
        val agentLogs = readTail(agentLogFile, 200)

        val fullReport = reportBuilder.build(
            parsed = parsed,
            category = category,
            description = description,
            suggestedActions = actions,
            vanillaCrashLog = content,
            agentLogs = agentLogs
        )

        return AnalysisResult(
            parsed = parsed,
            category = category,
            humanReadableDescription = description,
            suggestedActions = actions,
            fullReport = fullReport
        )
    }

    private fun readTail(file: Path, count: Int): List<String> {
        if (!Files.exists(file)) return emptyList()
        val size = Files.size(file)
        if (size == 0L) return emptyList()
        // For small files, just read all
        if (size < 1_048_576L) {
            return Files.readAllLines(file).takeLast(count)
        }
        // For large files, read backwards from end
        java.io.RandomAccessFile(file.toFile(), "r").use { raf ->
            val lines = mutableListOf<String>()
            var pos = raf.length() - 1
            if (pos >= 0) { raf.seek(pos); if (raf.readByte().toInt().toChar() == '\n') pos-- }
            val buf = StringBuilder()
            while (pos >= 0 && lines.size < count) {
                raf.seek(pos)
                val ch = raf.readByte().toInt().toChar()
                if (ch == '\n') { lines.add(buf.reverse().toString()); buf.clear() } else buf.append(ch)
                pos--
            }
            if (buf.isNotEmpty() && lines.size < count) lines.add(buf.reverse().toString())
            lines.reverse()
            return lines
        }
    }

    fun findRecentCrashes(limit: Int = 10): List<Path> {
        val crashDir = instanceDir.resolve("minecraft/crash-reports")
        if (!Files.exists(crashDir)) return emptyList()

        return Files.list(crashDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".txt") }
                .sorted(Comparator.comparingLong<Path> { Files.getLastModifiedTime(it).toMillis() }.reversed())
                .limit(limit.toLong())
                .toList()
        }
    }
}
