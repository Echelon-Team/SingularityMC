package com.singularity.launcher.crash

/**
 * Buduje pełny raport crashowy jako Markdown string.
 * Format przyjazny do wklejenia w GitHub Issues / Discord.
 */
class CrashReportBuilder(
    private val launcherVersion: String,
    private val agentVersion: String
) {

    fun build(
        parsed: CrashLogParser.ParsedCrash,
        category: CrashPatternMatcher.CrashCategory,
        description: String,
        suggestedActions: List<String>,
        vanillaCrashLog: String,
        agentLogs: List<String>
    ): String {
        return buildString {
            appendLine("# SingularityMC Crash Report")
            appendLine()
            appendLine("**Category:** $category")
            appendLine("**Time:** ${parsed.time ?: "unknown"}")
            appendLine("**Launcher version:** $launcherVersion")
            appendLine("**Agent version:** $agentVersion")
            appendLine()

            appendLine("## Description")
            appendLine(description)
            appendLine()

            appendLine("## Exception")
            appendLine("```")
            if (parsed.errorMessage.isNotEmpty()) {
                appendLine("${parsed.exceptionType}: ${parsed.errorMessage}")
            } else {
                appendLine(parsed.exceptionType)
            }
            parsed.stackTrace.take(30).forEach { frame -> appendLine("    at $frame") }
            appendLine("```")
            appendLine()

            appendLine("## Suggested actions")
            suggestedActions.forEachIndexed { i, action ->
                appendLine("${i + 1}. $action")
            }
            appendLine()

            appendLine("## System info")
            appendLine("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("- Arch: ${System.getProperty("os.arch")}")
            appendLine("- Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            appendLine("- RAM: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB max")
            appendLine("- CPU cores: ${Runtime.getRuntime().availableProcessors()}")
            appendLine()

            if (agentLogs.isNotEmpty()) {
                appendLine("## Agent logs (last ${agentLogs.size} lines)")
                appendLine("```")
                agentLogs.takeLast(200).forEach { appendLine(it) }
                appendLine("```")
                appendLine()
            }

            appendLine("## Vanilla crash log")
            appendLine("```")
            appendLine(vanillaCrashLog.take(5000))
            if (vanillaCrashLog.length > 5000) {
                appendLine("... truncated (${vanillaCrashLog.length - 5000} more chars)")
            }
            appendLine("```")
        }
    }
}
