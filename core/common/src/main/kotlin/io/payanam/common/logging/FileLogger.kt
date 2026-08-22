//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class for log entry parameters
 */
private data class LogEntry(
    val level: String,
    val source: String,
    val file: String,
    val function: String,
    val message: String,
    val data: Map<String, Any?>?,
)

/**
 * FileLogger - Kotlin implementation matching v0.0.2 UnifiedLoggerAndroid
 *
 * Features:
 * - Auto-creates log files per session: payanam-log-android_{version}_{buildNum}_{timestamp}.log
 * - Writes to Documents/payanam/logs/
 * - Persistent, viewable, exportable logs
 * - No dependency on adb logcat
 * - Thread-safe with coroutine mutex
 * - Auto-flush buffer every 5 seconds
 */
class FileLogger private constructor( // detekt:ignore:TooManyFunctions
    private val context: Context,
    private val versionName: String,
    private val buildNumber: Int,
) {
    private val logDir: File
    private val logFile: File
    private val sessionStart = System.currentTimeMillis()
    private val logBuffer = mutableListOf<String>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stepCounter = 1

    init {
        // Create log directory: Documents/payanam/logs/
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        logDir = File(documentsDir, "payanam/logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        // Create log file: payanam-log-android_{version}_{buildNum}_{timestamp}.log
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(Date())
        val fileName = "payanam-log-android_${versionName}_${buildNumber}_$timestamp.log"
        logFile = File(logDir, fileName)

        // Write session header
        val header =
            buildString {
                appendLine("=".repeat(HEADER_LINE_LENGTH))
                appendLine("Payanam Android Log Session")
                appendLine("Version: $versionName")
                appendLine("Build: $buildNumber")
                appendLine("Session Start: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())}")
                appendLine("Device: ${android.os.Build.MODEL} (${android.os.Build.VERSION.RELEASE})")
                appendLine("Log File: ${logFile.absolutePath}")
                appendLine("=".repeat(HEADER_LINE_LENGTH))
                appendLine()
            }

        logFile.writeText(header)

        // Start auto-flush every 5 seconds
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(AUTO_FLUSH_INTERVAL_MS)
                flush()
            }
        }

        // Log initialization
        info(
            "FileLogger",
            "FileLogger.kt",
            "init",
            "Logger initialized successfully",
            mapOf(
                "logFile" to logFile.absolutePath,
                "size" to "${logFile.length()} bytes",
            ),
        )
    }

    /**
     * Log an info message
     */
    fun info(
        source: String,
        file: String,
        function: String,
        message: String,
        data: Map<String, Any?>? = null,
    ) {
        log(LogEntry("INFO", source, file, function, message, data))
    }

    /**
     * Log a debug message
     */
    fun debug(
        source: String,
        file: String,
        function: String,
        message: String,
        data: Map<String, Any?>? = null,
    ) {
        log(LogEntry("DEBUG", source, file, function, message, data))
    }

    /**
     * Log a warning message
     */
    fun warn(
        source: String,
        file: String,
        function: String,
        message: String,
        data: Map<String, Any?>? = null,
    ) {
        log(LogEntry("WARN", source, file, function, message, data))
    }

    // detekt:ignore:LongParameterList

    /**
     * Log an error message
     */
    fun error(
        source: String,
        file: String,
        function: String,
        message: String,
        error: Throwable? = null,
        data: Map<String, Any?>? = null,
    ) {
        val errorData =
            if (error != null) {
                (data ?: emptyMap()) +
                    mapOf(
                        "error" to error.message,
                        "stackTrace" to error.stackTraceToString(),
                    )
            } else {
                data
            }
        log(LogEntry("ERROR", source, file, function, message, errorData))
    }

    /**
     * Core logging function
     */
    private fun log(entry: LogEntry) {
        scope.launch {
            mutex.withLock {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val step = stepCounter++
                val elapsed = System.currentTimeMillis() - sessionStart

                val logEntry =
                    buildString {
                        appendLine("[$timestamp] [${entry.level}] Step $step (+${elapsed}ms)")
                        appendLine("  Source: ${entry.source}")
                        appendLine("  File: ${entry.file}")
                        appendLine("  Function: ${entry.function}")
                        appendLine("  Message: ${entry.message}")
                        if (!entry.data.isNullOrEmpty()) {
                            appendLine("  Data:")
                            entry.data.forEach { (key, value) ->
                                appendLine("    $key: $value")
                            }
                        }
                        appendLine()
                    }

                logBuffer.add(logEntry)

                // Auto-flush if buffer exceeds threshold
                if (logBuffer.size >= BUFFER_FLUSH_THRESHOLD) {
                    flushInternal()
                }
            }
        }
    }

    /**
     * Flush buffer to file
     */
    fun flush() {
        scope.launch {
            mutex.withLock {
                flushInternal()
            }
        }
    }

    private fun flushInternal() {
        if (logBuffer.isEmpty()) return

        try {
            FileWriter(logFile, true).use { writer ->
                logBuffer.forEach { entry ->
                    writer.write(entry)
                }
            }
            logBuffer.clear()
        } catch (e: IOException) {
            // Fallback to console if file write fails
            System.err.println(
                "FileLogger: Failed to flush buffer: ${e.message}",
            )
        }
    }

    /**
     * Get all log files
     */
    fun getLogFiles(): List<File> =
        logDir
            .listFiles()
            ?.filter { it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Get current log file
     */
    fun getCurrentLogFile(): File = logFile

    /**
     * Read last n lines from current log
     */
    fun getRecentLogs(lines: Int = 100): String =
        try {
            logFile.readLines().takeLast(lines).joinToString("\n")
        } catch (e: IOException) {
            "Error reading logs: ${e.message}"
        }

    /**
     * Clear old log files (keep last n)
     */
    fun clearOldLogs(keepLast: Int = 10) {
        val files = getLogFiles()
        if (files.size > keepLast) {
            files.drop(keepLast).forEach { it.delete() }
        }
    }

    companion object {
        /** Format constants for the legacy FileLogger session header/files. */
        private const val HEADER_LINE_LENGTH = 80
        private const val AUTO_FLUSH_INTERVAL_MS = 5000L
        private const val BUFFER_FLUSH_THRESHOLD = 50

        @Volatile
        private var instance: FileLogger? = null

        /**
         * Initialize the logger (call from application.onCreate)
         */
        fun initialize(
            context: Context,
            versionName: String,
            buildNumber: Int,
        ): FileLogger =
            instance ?: synchronized(this) {
                instance ?: FileLogger(context, versionName, buildNumber).also {
                    instance = it
                }
            }

        /**
         * Get the logger instance
         */
        fun getInstance(): FileLogger = instance ?: error("FileLogger not initialized. Call initialize() first.")
    }
}
