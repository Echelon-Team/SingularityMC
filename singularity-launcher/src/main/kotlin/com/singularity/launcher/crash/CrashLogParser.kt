package com.singularity.launcher.crash

import org.slf4j.LoggerFactory

/**
 * Parser vanilla MC crash log.
 *
 * Format:
 *   ---- Minecraft Crash Report ----
 *   Time: ...
 *   Description: ...
 *
 *   java.lang.ExceptionType: message
 *       at class.method(File.java:line)
 *       ...
 *
 * Obsługuje też exception bez message (np. StackOverflowError, ConcurrentModificationException).
 */
object CrashLogParser {

    private val logger = LoggerFactory.getLogger(CrashLogParser::class.java)

    data class ParsedCrash(
        val exceptionType: String,
        val errorMessage: String,
        val stackTrace: List<String>,
        val topStackFrame: String?,
        val time: String?,
        val description: String?
    )

    // Matches: "java.lang.NullPointerException: some message" or "java.lang.NullPointerException"
    private val exceptionWithMessageRegex = Regex("""^([a-zA-Z0-9_$.]+(?:Exception|Error)): (.+)$""")
    private val exceptionNoMessageRegex = Regex("""^([a-zA-Z0-9_$.]+(?:Exception|Error))$""")
    private val stackFrameRegex = Regex("""^\s+at (.+)$""")
    private val timeRegex = Regex("""^Time: (.+)$""")
    private val descriptionRegex = Regex("""^Description: (.+)$""")

    fun parse(logContent: String): ParsedCrash {
        if (logContent.isBlank()) {
            return ParsedCrash("unknown", "", emptyList(), null, null, null)
        }

        var exceptionType = "unknown"
        var errorMessage = ""
        val stackTrace = mutableListOf<String>()
        var time: String? = null
        var description: String? = null
        var foundException = false

        for (line in logContent.lines()) {
            val trimmed = line.trim()
            when {
                !foundException -> {
                    timeRegex.matchEntire(trimmed)?.let {
                        time = it.groupValues[1].trim()
                    }
                    descriptionRegex.matchEntire(trimmed)?.let {
                        description = it.groupValues[1].trim()
                    }
                    exceptionWithMessageRegex.matchEntire(trimmed)?.let {
                        exceptionType = it.groupValues[1]
                        errorMessage = it.groupValues[2]
                        foundException = true
                    }
                    if (!foundException) {
                        exceptionNoMessageRegex.matchEntire(trimmed)?.let {
                            exceptionType = it.groupValues[1]
                            errorMessage = ""
                            foundException = true
                        }
                    }
                }
                foundException -> {
                    stackFrameRegex.matchEntire(line)?.let {
                        stackTrace.add(it.groupValues[1].trim())
                    }
                }
            }
        }

        return ParsedCrash(
            exceptionType = exceptionType,
            errorMessage = errorMessage,
            stackTrace = stackTrace,
            topStackFrame = stackTrace.firstOrNull(),
            time = time,
            description = description
        )
    }
}
