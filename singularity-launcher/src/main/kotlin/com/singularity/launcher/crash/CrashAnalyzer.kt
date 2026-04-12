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

        // Read agent logs from disk (no cross-module import)
        val agentLogFile = instanceDir.resolve("logs/agent/singularity-agent.log")
        val agentLogs = if (Files.exists(agentLogFile)) {
            Files.readAllLines(agentLogFile).takeLast(200)
        } else {
            emptyList()
        }

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
