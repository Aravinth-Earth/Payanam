//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Append-only desktop session logger that writes structured entries to a file
 * and implements [AutoCloseable] so the underlying writer can be released.
 */
class DesktopSessionLogger private constructor(
    private val logFilePath: Path,
    private val openedAt: Instant,
    private val clock: () -> Instant,
) : AutoCloseable {
    private val sequenceCounter = AtomicLong(0)

    init {
        writeHeader()
    }
    /**
     * Performs the i.
     */
    fun i(
        source: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        appendEntry(level = "INFO", source = source, message = message, data = data)
    }
    /**
     * Performs the w.
     */
    fun w(
        source: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        appendEntry(level = "WARN", source = source, message = message, data = data)
    }
    /**
     * Performs the e.
     */
    fun e(
        source: String,
        message: String,
        error: Throwable? = null,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val errorData =
            if (error == null) {
                data
            } else {
                data +
                    mapOf(
                        "exception" to error.javaClass.simpleName,
                        "error" to (error.message ?: "no-message"),
                        "stack" to
                            error.stackTrace.take(MAX_STACK_FRAMES).joinToString(" > ") {
                                "${it.fileName}:${it.lineNumber}:${it.methodName}"
                            },
                    )
            }
        appendEntry(level = "ERROR", source = source, message = message, data = errorData)
    }
    /**
     * Returns the log path.
     */
    fun getLogPath(): Path = logFilePath

    /**
     * Performs the close.
     */
    override fun close() {
        appendEntry(
            level = "INFO",
            source = "DesktopSessionLogger.close",
            message = "Desktop session finished",
            data = emptyMap(),
        )
        synchronized(companionLock) {
            if (instance === this) {
                instance = null
            }
        }
    }

    private fun writeHeader() {
        val lines =
            listOf(
                "=".repeat(HEADER_LINE_LENGTH),
                buildString {
                    append("Payanam Unified Log | Platform: ")
                    append(DesktopBuildInfo.PLATFORM)
                    append(" | Build: ")
                    append(DesktopBuildInfo.PLATFORM_BUILD_NUMBER)
                    append(" | Session: ")
                    append(sessionFormatter().format(openedAt.atZone(ZoneId.systemDefault())))
                },
                "Device: Desktop | OS: Windows",
                "Format: timestamp | level | n | thread=<name> | source | message | [data]",
                "=".repeat(HEADER_LINE_LENGTH),
                "",
            )
        Files.writeString(
            logFilePath,
            lines.joinToString(separator = "\n"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    @Synchronized
    private fun appendEntry(
        level: String,
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) {
        val now = clock()
        val sequence = sequenceCounter.incrementAndGet()
        val safeMessage = message.replace('\n', ' ').trim()
        val metadata = "$sequence | thread=${Thread.currentThread().name}"
        val payload = buildPayload(data)
        val line =
            "${entryFormatter().format(
                now.atZone(ZoneId.systemDefault()),
            )} | ${level.padEnd(LOG_LEVEL_WIDTH)} | $metadata | $source | $safeMessage$payload\n"
        Files.writeString(
            logFilePath,
            line,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE,
        )
    }

    private fun buildPayload(data: Map<String, Any?>): String {
        if (data.isEmpty()) {
            return ""
        }
        return " | [" +
            data.entries.joinToString(", ") { (key, value) ->
                "$key=${formatValue(value)}"
            } + "]"
    }

    private fun formatValue(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"${value.replace('\n', ' ')}\""
            is Iterable<*> -> "[${value.count()} items]"
            is Map<*, *> -> "{${value.size} entries}"
            else -> value.toString()
        }

    companion object {
        private const val HEADER_LINE_LENGTH = 120
        private const val LOG_LEVEL_WIDTH = 5
        private const val MAX_STACK_FRAMES = 6
        private const val RETAINED_LOG_COUNT = 9

        @Volatile
        private var instance: DesktopSessionLogger? = null
        private val companionLock = Any()
        /**
         * Performs the initialize.
         */
        fun initialize(
            logsDirectory: Path = DesktopAppPaths.resolveLogsDirectory(),
            clock: () -> Instant = { Instant.now() },
        ): DesktopSessionLogger =
            instance ?: synchronized(companionLock) {
                instance ?: createLogger(logsDirectory = logsDirectory, clock = clock).also {
                    instance = it
                }
            }
        /**
         * Returns the instance.
         */
        fun getInstance(): DesktopSessionLogger =
            instance ?: error("DesktopSessionLogger not initialized. Call initialize() from desktop main().")

        private fun createLogger(
            logsDirectory: Path,
            clock: () -> Instant,
        ): DesktopSessionLogger {
            Files.createDirectories(logsDirectory)
            pruneOldLogs(logsDirectory)
            val now = clock()
            val fileName = "payanam_desktop_${DesktopBuildInfo.PLATFORM_BUILD_NUMBER}_${fileTimestampFormatter().format(
                now.atZone(ZoneId.systemDefault()),
            )}.log"
            val logFile = logsDirectory.resolve(fileName)
            return DesktopSessionLogger(logFilePath = logFile, openedAt = now, clock = clock)
        }

        private fun pruneOldLogs(logsDirectory: Path) {
            Files.list(logsDirectory).use { entries ->
                entries
                    .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".log") }
                    .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() })
                    .skip(RETAINED_LOG_COUNT.toLong())
                    .forEach { stalePath -> Files.deleteIfExists(stalePath) }
            }
        }

        private fun fileTimestampFormatter(): DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        private fun sessionFormatter(): DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        private fun entryFormatter(): DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
