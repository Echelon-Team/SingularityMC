// Copyright (c) 2026 Echelon Team. All rights reserved.

package com.singularity.agent.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
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
    @Volatile private var appender: FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>? = null

    private const val LOG_PATTERN = "[%d{yyyy-MM-dd HH:mm:ss.SSS}] [%thread] [%level] %logger{30} - %msg%n"
    private const val LOG_SUBPATH = "logs/agent/singularity-agent.log"
    private const val APPENDER_NAME = "SINGULARITY_FILE"

    @Synchronized
    fun setupFileLogging(instanceDir: Path) {
        // Idempotent: detach old appender if exists
        teardownInternal()

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
            name = APPENDER_NAME
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

    @Synchronized
    fun teardown() {
        teardownInternal()
    }

    private fun teardownInternal() {
        val current = appender ?: return
        val rootLogger = LoggerFactory.getLogger(LogbackLogger.ROOT_LOGGER_NAME) as LogbackLogger
        // Detach first, then stop — prevents concurrent log calls hitting stopped appender
        rootLogger.detachAppender(current)
        current.stop()
        appender = null
    }

    fun getLogFilePath(instanceDir: Path): Path =
        instanceDir.resolve(LOG_SUBPATH)

    /**
     * Czyta ostatnie N linii z pliku logu agenta.
     * Używa RandomAccessFile z szukaniem od końca — nie ładuje całego pliku do pamięci.
     * Używane przez CrashAnalyzer w launcherze (czyta plik z dysku, bez importu tej klasy).
     */
    fun readLastLines(instanceDir: Path, count: Int = 200): List<String> {
        val logFile = instanceDir.resolve(LOG_SUBPATH)
        if (!Files.exists(logFile)) return emptyList()

        val file = logFile.toFile()
        if (file.length() == 0L) return emptyList()

        // For small files (< 1MB), just read all — simpler and fast enough
        if (file.length() < 1_048_576L) {
            return Files.readAllLines(logFile).takeLast(count)
        }

        // For large files: read backwards from end
        return tailFromEnd(file, count)
    }

    private fun tailFromEnd(file: java.io.File, count: Int): List<String> {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            val lines = mutableListOf<String>()
            var pos = fileLength - 1

            // Skip trailing newline
            if (pos >= 0) {
                raf.seek(pos)
                if (raf.readByte().toInt().toChar() == '\n') pos--
            }

            val buf = StringBuilder()
            while (pos >= 0 && lines.size < count) {
                raf.seek(pos)
                val ch = raf.readByte().toInt().toChar()
                if (ch == '\n') {
                    lines.add(buf.reverse().toString())
                    buf.clear()
                } else {
                    buf.append(ch)
                }
                pos--
            }
            // First line (no leading newline)
            if (buf.isNotEmpty() && lines.size < count) {
                lines.add(buf.reverse().toString())
            }

            lines.reverse()
            return lines
        }
    }
}
