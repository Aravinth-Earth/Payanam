//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

@Suppress("TooManyFunctions")
class UnifiedLogger private constructor(
    private val context: Context,
    private val buildNumber: Int,
) {
    private val logDir: File
    private var logFile: File
    private val logBuffer = mutableListOf<String>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val sequenceCounter = AtomicLong(0)

    init {
        logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        clearOldLogsSync(keepLast = 9)
        logFile = createSessionLogFile()

        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(AUTO_FLUSH_INTERVAL_MS)
                flush()
            }
        }

        i(
            "UnifiedLogger.init",
            "Logger initialized",
            mapOf("logFilePath" to logFile.absolutePath),
        )
    }

    fun d(
        source: String,
        message: String,
        data: Map<String, Any?>? = null,
    ) {
        if (isDebugLoggingEnabled()) {
            log("DEBUG", source, message, data)
        }
    }

    fun i(
        source: String,
        message: String,
        data: Map<String, Any?>? = null,
    ) {
        log("INFO", source, message, data)
    }

    fun w(
        source: String,
        message: String,
        data: Map<String, Any?>? = null,
    ) {
        log("WARN", source, message, data)
    }

    fun e(
        source: String,
        message: String,
        error: Throwable? = null,
        data: Map<String, Any?>? = null,
    ) {
        log("ERROR", source, message, buildErrorData(error, data, MAX_STACK_FRAMES))
    }

    fun eSync(
        source: String,
        message: String,
        error: Throwable? = null,
        data: Map<String, Any?>? = null,
    ) {
        val preparedLog =
            runBlocking {
                mutex.withLock {
                    val prepared =
                        prepareLog(
                            level = "ERROR",
                            source = source,
                            message = message,
                            data = buildErrorData(error, data, MAX_CRASH_STACK_FRAMES),
                        )
                    logBuffer.add(prepared.entry)
                    flushInternal()
                    prepared
                }
            }
        android.util.Log.e(LOGCAT_TAG, preparedLog.logcatMessage, error)
    }

    private fun log(
        level: String,
        source: String,
        message: String,
        data: Map<String, Any?>?,
    ) {
        scope.launch {
            mutex.withLock {
                val prepared = prepareLog(level, source, message, data)
                logBuffer.add(prepared.entry)
                logToLogcat(level, prepared.logcatMessage)

                if (logBuffer.size >= MAX_BUFFER_SIZE) {
                    flushInternal()
                }
            }
        }
    }

    private fun buildErrorData(
        error: Throwable?,
        data: Map<String, Any?>?,
        stackFrameLimit: Int,
    ): Map<String, Any?>? {
        if (error == null) return data

        val combined = data?.toMutableMap() ?: mutableMapOf()
        combined["error"] = error.message ?: error.javaClass.simpleName
        combined["exception"] = error.javaClass.simpleName
        combined["stack"] =
            error.stackTrace.take(stackFrameLimit).joinToString(" > ") {
                "${it.fileName}:${it.lineNumber}:${it.methodName}"
            }
        val causeTrace =
            buildString {
                var current: Throwable? = error.cause
                var depth = 0
                while (current != null && depth < MAX_CAUSE_DEPTH) {
                    if (isNotEmpty()) append(" || ")
                    append("${current.javaClass.simpleName}:${current.message ?: "no-message"}")
                    current = current.cause
                    depth++
                }
            }
        if (causeTrace.isNotEmpty()) {
            combined["causeChain"] = causeTrace
        }
        return combined
    }

    private fun prepareLog(
        level: String,
        source: String,
        message: String,
        data: Map<String, Any?>?,
    ): PreparedLog {
        val timestamp = dateFormat.format(Date())
        val safeMessage = LogSanitizer.sanitizeMessage(message)
        val safeData = LogSanitizer.sanitizeData(data)
        val sequence = sequenceCounter.incrementAndGet()
        val threadName = Thread.currentThread().name
        val metadata = "$sequence | thread=$threadName"
        val dataStr = buildDataString(safeData)
        val entry = "$timestamp | ${level.padEnd(LOG_LEVEL_WIDTH)} | $metadata | $source | $safeMessage$dataStr\n"
        val logcatMessage = "[$source][$metadata] $safeMessage$dataStr"
        return PreparedLog(entry = entry, logcatMessage = logcatMessage)
    }

    private fun buildDataString(safeData: Map<String, Any?>): String {
        if (safeData.isEmpty()) {
            return ""
        }
        return " | [" +
            safeData.entries.joinToString(", ") { (key, value) ->
                "$key=${formatValue(value)}"
            } + "]"
    }

    private fun logToLogcat(
        level: String,
        message: String,
    ) {
        when (level) {
            "DEBUG" -> android.util.Log.d(LOGCAT_TAG, message)
            "INFO" -> android.util.Log.i(LOGCAT_TAG, message)
            "WARN" -> android.util.Log.w(LOGCAT_TAG, message)
            "ERROR" -> android.util.Log.e(LOGCAT_TAG, message)
        }
    }

    private fun formatValue(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Collection<*> -> "[${value.size} items]"
            is Map<*, *> -> "{${value.size} entries}"
            else -> value.toString()
        }

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
            System.err.println("UnifiedLogger: Failed to flush buffer: ${e.message}")
            android.util.Log.e(LOGCAT_TAG, "UnifiedLogger flush failed", e)
        }
    }

    fun getLogFiles(): List<File> =
        logDir
            .listFiles()
            ?.filter {
                it.extension == "log" && it.name.startsWith("payanam")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun getCurrentLogPath(): String = logFile.absolutePath

    fun startNewSession(reason: String): String {
        val previousLogPath = logFile.absolutePath
        val newLogPath =
            runBlocking {
                mutex.withLock {
                    flushInternal()
                    sequenceCounter.set(0)
                    logFile = createSessionLogFile()
                    logFile.absolutePath
                }
            }

        i(
            "UnifiedLogger.startNewSession",
            "Started new log session",
            mapOf(
                "reason" to reason,
                "previousLogPath" to previousLogPath,
                "newLogPath" to newLogPath,
            ),
        )

        return newLogPath
    }

    fun getRecentLogs(lines: Int = 100): String =
        try {
            logFile.readLines().takeLast(lines).joinToString("\n")
        } catch (e: IOException) {
            "Error reading logs: ${e.message}"
        }

    fun exportLatestLog(): File? =
        try {
            runBlocking {
                mutex.withLock { flushInternal() }
            }
            val externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val exportDir = File(externalDir, "payanam/exported-logs")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val exportFile = File(exportDir, logFile.name)
            logFile.copyTo(exportFile, overwrite = true)

            i(
                "UnifiedLogger.exportLatestLog",
                "Latest log exported",
                mapOf(
                    "fileName" to exportFile.name,
                ),
            )

            exportFile
        } catch (e: IOException) {
            e("UnifiedLogger.exportLatestLog", "Failed to export latest log", e)
            null
        }

    fun exportAllLogs(): File? {
        runBlocking {
            mutex.withLock { flushInternal() }
        }
        val externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val exportDir = File(externalDir, "payanam/exported-logs")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(exportDir, "payanam_${buildNumber}_$timestamp.zip")

        return try {
            createZipFile(zipFile)
        } catch (e: IOException) {
            e("UnifiedLogger.exportAllLogs", "Failed to export all logs", e)
            null
        }
    }

    private fun createZipFile(zipFile: File): File {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
            getLogFiles().forEach { file ->
                addFileToZip(zos, file)
            }
        }

        i(
            "UnifiedLogger.exportAllLogs",
            "All logs exported",
            mapOf(
                "fileName" to zipFile.name,
                "fileCount" to getLogFiles().size,
            ),
        )

        return zipFile
    }

    private fun addFileToZip(
        zos: java.util.zip.ZipOutputStream,
        file: File,
    ) {
        zos.putNextEntry(java.util.zip.ZipEntry(file.name))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    fun clearOldLogs(keepLast: Int = 10) {
        scope.launch {
            try {
                val deletedCount = clearOldLogsSync(keepLast)
                if (deletedCount > 0) {
                    i("UnifiedLogger.clearOldLogs", "Deleted old log files", mapOf("deletedCount" to deletedCount))
                }
            } catch (e: IOException) {
                e("UnifiedLogger.clearOldLogs", "Failed to clear old logs", e)
            }
        }
    }

    private fun clearOldLogsSync(keepLast: Int): Int {
        val files = getLogFiles()
        if (files.size <= keepLast) return 0
        var deletedCount = 0
        files.drop(keepLast).forEach { file ->
            if (file.delete()) {
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun createSessionLogFile(): File {
        val timestamp = sessionFileNameFormat.format(Date())
        val sessionFile = File(logDir, "payanam_${buildNumber}_$timestamp.log")
        val header =
            buildString {
                appendLine("=".repeat(HEADER_LINE_LENGTH))
                appendLine("Payanam Unified Log | Platform: Android | Build: $buildNumber | Session: $timestamp")
                appendLine(
                    "Device: ${android.os.Build.MODEL} | OS: Android ${android.os.Build.VERSION.RELEASE} | " +
                        "SDK: ${android.os.Build.VERSION.SDK_INT}",
                )
                appendLine("Format: timestamp | level | n | thread=<name> | source | message | [data]")
                appendLine("=".repeat(HEADER_LINE_LENGTH))
            }
        sessionFile.writeText(header)
        return sessionFile
    }

    private data class PreparedLog(
        val entry: String,
        val logcatMessage: String,
    )

    companion object {
        private const val HEADER_LINE_LENGTH = 120
        private const val AUTO_FLUSH_INTERVAL_MS = 5000L
        private const val MAX_STACK_FRAMES = 3
        private const val MAX_CRASH_STACK_FRAMES = 64
        private const val MAX_CAUSE_DEPTH = 8
        private const val LOG_LEVEL_WIDTH = 5
        private const val MAX_BUFFER_SIZE = 100
        private const val LOGCAT_TAG = "Payanam"
        private val sessionFileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

        @Volatile
        private var instance: UnifiedLogger? = null

        @Volatile
        private var debugLoggingEnabled: Boolean = false

        fun initialize(
            context: Context,
            @Suppress("UNUSED_PARAMETER") versionName: String,
            buildNumber: Int,
        ): UnifiedLogger =
            instance ?: synchronized(this) {
                instance ?: UnifiedLogger(context, buildNumber).also {
                    instance = it
                }
            }

        fun getInstance(): UnifiedLogger =
            instance ?: error(
                "UnifiedLogger not initialized. Call initialize() from Application.onCreate()",
            )

        fun isInitialized(): Boolean = instance != null

        fun setDebugLoggingEnabled(enabled: Boolean) {
            debugLoggingEnabled = enabled
        }

        fun isDebugLoggingEnabled(): Boolean = debugLoggingEnabled
    }
}
