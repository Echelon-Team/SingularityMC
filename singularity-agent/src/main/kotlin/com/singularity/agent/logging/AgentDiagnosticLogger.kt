package com.singularity.agent.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Konfiguracja file appender dla logów agenta.
 *
 * Pisze do <instanceDir>/logs/agent/singularity-agent.log.
 * Format czytelny dla człowieka i AI crash analyzer.
 * Brak rotacji, brak limitu rozmiaru (jak vanilla MC).
 * Nowy plik per sesja (isAppend = false).
 *
 * Używane przez:
 * - AgentMain.premain(): setupFileLogging() po parsowaniu args
 * - CrashAnalyzer (launcher side): readLastLines() czyta plik z dysku
 */
object AgentDiagnosticLogger {

    private val logger = LoggerFactory.getLogger(AgentDiagnosticLogger::class.java)
    private var appender: FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>? = null

    private const val LOG_PATTERN = "[%d{yyyy-MM-dd HH:mm:ss.SSS}] [%thread] [%level] %logger{30} - %msg%n"
    private const val LOG_SUBPATH = "logs/agent/singularity-agent.log"

    fun setupFileLogging(instanceDir: Path) {
        val logFile = instanceDir.resolve(LOG_SUBPATH)
        Files.createDirectories(logFile.parent)

        val context = LoggerFactory.getILoggerFactory() as LoggerContext

        val encoder = PatternLayoutEncoder().apply {
            this.context = context
            pattern = LOG_PATTERN
            start()
        }

        val fileAppender = FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            this.context = context
            name = "SINGULARITY_FILE"
            file = logFile.toString()
            this.encoder = encoder
            isAppend = false // clean log per session
            start()
        }

        val rootLogger = LoggerFactory.getLogger(LogbackLogger.ROOT_LOGGER_NAME) as LogbackLogger
        rootLogger.addAppender(fileAppender)
        appender = fileAppender

        logger.info("Agent diagnostic logger initialized — writing to {}", logFile)
    }

    fun teardown() {
        appender?.stop()
        val rootLogger = LoggerFactory.getLogger(LogbackLogger.ROOT_LOGGER_NAME) as LogbackLogger
        if (appender != null) {
            rootLogger.detachAppender(appender)
        }
        appender = null
    }

    fun getLogFilePath(instanceDir: Path): Path =
        instanceDir.resolve(LOG_SUBPATH)

    /**
     * Czyta ostatnie N linii z pliku logu agenta.
     * Używane przez CrashAnalyzer w launcherze (czyta plik z dysku, bez importu tej klasy).
     */
    fun readLastLines(instanceDir: Path, count: Int = 200): List<String> {
        val logFile = instanceDir.resolve(LOG_SUBPATH)
        if (!Files.exists(logFile)) return emptyList()
        return Files.readAllLines(logFile).takeLast(count)
    }
}
