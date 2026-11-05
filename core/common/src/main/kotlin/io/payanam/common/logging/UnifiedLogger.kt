//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import android.os.SystemClock
import io.payanam.shared.concurrent.appCoroutineScope
import io.payanam.shared.logging.LogSanitizer
import io.payanam.shared.logging.logExportName
import io.payanam.shared.logging.sessionLogName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Append-only, async-buffered logger for the Android app.
 *
 * Writes structured entries (level, source, message, sanitized data) to a
 * per-session file under [Context.getFilesDir]/logs and mirrors them to logcat.
 * A background scope flushes the in-memory buffer on a fixed interval; callers
 * may also force a flush. Use the [getInstance] companion accessor after
 * [initialize].
 */
@Suppress("TooManyFunctions")
class UnifiedLogger private constructor(
    private val context: Context,
    private val versionName: String,
    private val buildShortSha: String,
) : io.payanam.shared.logging.UnifiedLogger {
    private val logDir: File
    private var logFile: File
    private val debugBuild: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val logBuffer = mutableListOf<String>()
    private val mutex = Mutex()
    private val scope = appCoroutineScope("UnifiedLogger", Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val sequenceCounter = AtomicLong(0)

    init {
        logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        clearOldLogsSync(keepLast = 9)
        logFile = createSessionLogFile()

        launchSafely {
            while (true) {
                kotlinx.coroutines.delay(AUTO_FLUSH_INTERVAL_MS)
                runIterationSafely("auto-flush") { flush() }
            }
        }

        i(
            "UnifiedLogger.init",
            "Logger initialized",
            mapOf("logFilePath" to logFile.absolutePath),
        )
    }

    /** Logs a DEBUG-level entry. No-op unless debug logging is enabled. */
    override fun d(
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) {
        if (isDebugLoggingEnabled()) {
            log("DEBUG", source, message, data)
        }
    }

    /** Logs an INFO-level entry. */
    override fun i(
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) {
        log("INFO", source, message, data)
    }

    /** Logs a WARN-level entry. */
    override fun w(
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) {
        log("WARN", source, message, data)
    }

    /** Logs an ERROR-level entry, attaching [error] stack/metadata when present. */
    override fun e(
        source: String,
        message: String,
        error: Throwable?,
        data: Map<String, Any?>,
    ) {
        log("ERROR", source, message, buildErrorData(error, data, MAX_STACK_FRAMES))
    }

    /**
     * Synchronously logs an ERROR entry and writes it to logcat immediately,
     * bypassing the async buffer (used where a crash is imminent).
     */
    override fun eSync(
        source: String,
        message: String,
        error: Throwable?,
        data: Map<String, Any?>,
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
        data: Map<String, Any?>,
    ) {
        launchSafely {
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

    /**
     * Runs [block] on this logger's own scope, never letting a failure escape into the process.
     *
     * The logger is the last place a failure should be reported FROM, so it must not be the place a
     * failure is raised FROM: an unexpected throw inside a log write is reported to stderr and
     * logcat and then dropped. Cancellation is the one exception - it is rethrown, so structured
     * concurrency still works and a cancelled scope still unwinds quietly.
     *
     * Without this, the scope's `CoroutineExceptionHandler` would report the failure back through
     * `AppLogger`, which is this logger, whose write would fail the same way and start the loop
     * again. Guarding the writes is what breaks that cycle.
     */
    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                System.err.println("UnifiedLogger: Background log write failed: ${failure.message}")
                android.util.Log.e(LOGCAT_TAG, "UnifiedLogger background log write failed", failure)
            }
        }
    }

    /**
     * Runs ONE iteration of a repeating background task, keeping the repeat alive when that
     * iteration fails.
     *
     * [launchSafely] guards a whole block, so a `while (true)` wrapped in it dies with the FIRST
     * failure: the try/catch sits OUTSIDE the loop, so a throw leaves the `while`, the catch
     * swallows it, and the loop never runs again. For the auto-flush that failure is SILENT -
     * entries pile up in the buffer and nothing reaches the log file again, so anything reading
     * the file (an export, a support bundle, a test waiting on a terminal marker) sees stale
     * content forever. Guarding each iteration separately keeps one failed flush from ending the
     * repeat: the failure is reported and the next iteration still runs.
     *
     * Cancellation is the only thing that ends a repeat: a [CancellationException] is rethrown, so
     * a cancelled scope still unwinds the loop quietly. `runCatching` is deliberately NOT used
     * here - it catches `Throwable`, which would swallow that cancellation as well.
     */
    private suspend fun runIterationSafely(
        description: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            System.err.println("UnifiedLogger: Background $description failed: ${failure.message}")
            android.util.Log.e(LOGCAT_TAG, "UnifiedLogger background $description failed", failure)
        }
    }

    private fun buildErrorData(
        error: Throwable?,
        data: Map<String, Any?>,
        stackFrameLimit: Int,
    ): Map<String, Any?> =
        io.payanam.shared.logging.ErrorPayload
            .build(error, data, stackFrameLimit)

    private fun prepareLog(
        level: String,
        source: String,
        message: String,
        data: Map<String, Any?>,
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

    /** Requests an async flush of the buffered entries to disk. */
    fun flush() {
        launchSafely {
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
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: IOException) {
            System.err.println("UnifiedLogger: Failed to flush buffer: ${e.message}")
            android.util.Log.e(LOGCAT_TAG, "UnifiedLogger flush failed", e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A logger must never be the thing that kills the process. An unexpected throw while
            // writing the buffer is reported and dropped, never rethrown into the caller - and
            // `eSync` calls this inside a `runBlocking`, where a rethrow would crash the caller.
            System.err.println("UnifiedLogger: Failed to flush buffer: ${e.message}")
            android.util.Log.e(LOGCAT_TAG, "UnifiedLogger flush failed", e)
        }
    }

    /** Returns the session log files on disk, newest first. */
    fun getLogFiles(): List<File> =
        logDir
            .listFiles()
            ?.filter {
                it.extension == "log" && it.name.startsWith("payanam")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Returns the absolute path of the active session log file. */
    fun getCurrentLogPath(): String = logFile.absolutePath

    /**
     * Closes the current session file and opens a new one, returning the new
     * session's absolute path. Logs a breadcrumb recording [reason].
     */
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

    /** Returns the last [lines] of the current session file as a single string. */
    fun getRecentLogs(lines: Int = 100): String =
        try {
            logFile.readLines().takeLast(lines).joinToString("\n")
        } catch (e: IOException) {
            "Error reading logs: ${e.message}"
        }

    /**
     * Export the current session log file. Runs on the IO dispatcher; atomic
     * publish via .tmp + rename so the final name never holds a partial copy.
     */
    suspend fun exportLatestLog(): File? =
        withContext(Dispatchers.IO) {
            val startedAtMs = SystemClock.elapsedRealtime()
            try {
                val snapshot = snapshotLogState()
                val externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val suffix = if (context.packageName.endsWith(".debug")) "-debug" else ""
                val exportDir = File(externalDir, "Payanam$suffix/exported-logs")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }

                val exportFile = File(exportDir, logFile.name)
                val tmpFile = File(exportDir, "${logFile.name}.tmp")
                try {
                    snapshot.file.copyTo(tmpFile, overwrite = true)
                    if (tmpFile.length() != snapshot.file.length()) {
                        throw IOException("Copy verification failed: ${snapshot.file.length()} != ${tmpFile.length()}")
                    }
                    if (!tmpFile.renameTo(exportFile)) {
                        throw IOException("Atomic rename failed: ${tmpFile.name} → ${exportFile.name}")
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    tmpFile.delete()
                    throw e
                }

                val durationMs = SystemClock.elapsedRealtime() - startedAtMs
                i(
                    "UnifiedLogger.exportLatestLog",
                    "Current session log exported",
                    mapOf(
                        "fileName" to exportFile.name,
                        "sizeBytes" to exportFile.length(),
                        "durationMs" to durationMs,
                    ),
                )
                exportFile
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                e("UnifiedLogger.exportLatestLog", "Failed to export current session log", e)
                null
            }
        }

    /**
     * Export ALL log files as one zip. Runs on the IO dispatcher; atomic
     * publish via .tmp + rename; the in-memory buffer snapshot is included as
     * a session-live.log entry so the zip always carries the newest lines;
     * old exports are pruned to [EXPORT_RETENTION_KEEP].
     */
    suspend fun exportAllLogs(): File? =
        withContext(Dispatchers.IO) {
            val startedAtMs = SystemClock.elapsedRealtime()
            try {
                val snapshot = snapshotLogState()
                val externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val suffix = if (context.packageName.endsWith(".debug")) "-debug" else ""
                val exportDir = File(externalDir, "Payanam$suffix/exported-logs")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val zipFile =
                    File(exportDir, logExportName("android", versionName, buildShortSha, debugBuild, timestamp))
                val tmpZip = File(exportDir, "${zipFile.name}.tmp")

                try {
                    createZipFileAtomic(tmpZip, snapshot)
                    if (!tmpZip.renameTo(zipFile)) {
                        throw IOException("Atomic rename failed: ${tmpZip.name} → ${zipFile.name}")
                    }
                    cleanupOldExports(exportDir, keepLast = EXPORT_RETENTION_KEEP)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    tmpZip.delete()
                    throw e
                }

                val durationMs = SystemClock.elapsedRealtime() - startedAtMs
                i(
                    "UnifiedLogger.exportAllLogs",
                    "All logs exported",
                    mapOf(
                        "fileName" to zipFile.name,
                        "fileCount" to snapshot.files.size,
                        "bufferedDuringExport" to snapshot.bufferSnapshot.size,
                        "sizeBytes" to zipFile.length(),
                        "durationMs" to durationMs,
                    ),
                )
                zipFile
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                e("UnifiedLogger.exportAllLogs", "Failed to export all logs", e)
                null
            }
        }

    /** Capture the buffer snapshot FIRST, then flush — so session-live.log
     *  carries the not-yet-flushed lines (flush would empty the buffer). */
    private suspend fun snapshotLogState(): LogSnapshot =
        mutex.withLock {
            val bufferSnapshot = logBuffer.toList()
            flushInternal()
            LogSnapshot(
                file = logFile,
                files = getLogFiles(),
                bufferSnapshot = bufferSnapshot,
            )
        }

    private data class LogSnapshot(
        val file: File,
        val files: List<File>,
        val bufferSnapshot: List<String>,
    )

    /**
     * Zip [snapshot.files] + a session-live.log entry with the buffered lines.
     * Writes to [tmpZip] so the caller can rename atomically afterwards.
     */
    private fun createZipFileAtomic(
        tmpZip: File,
        snapshot: LogSnapshot,
    ) {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(tmpZip)).use { zos ->
            snapshot.files.forEach { file ->
                addFileToZip(zos, file)
            }
            if (snapshot.bufferSnapshot.isNotEmpty()) {
                zos.putNextEntry(java.util.zip.ZipEntry("session-live.log"))
                zos.write(snapshot.bufferSnapshot.joinToString(separator = "").toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        verifyZipIntegrity(tmpZip, expectedEntries = snapshot.files.size + if (snapshot.bufferSnapshot.isNotEmpty()) 1 else 0)
    }

    /** Re-open the zip and confirm entry count + EOCD magic before it is renamed into place. */
    private fun verifyZipIntegrity(
        zipFile: File,
        expectedEntries: Int,
    ) {
        java.util.zip.ZipFile(zipFile).use { zip ->
            val actual = zip.size()
            if (actual != expectedEntries) {
                throw IOException("Zip verification failed: expected $expectedEntries entries, found $actual")
            }
        }
        // EOCD magic (PK\x05\x06) must be the last bytes of a complete zip.
        val tail = zipFile.readBytes().takeLast(EOCD_RECORD_SIZE).toByteArray()
        val eocdMagic = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        if (tail.size < EOCD_RECORD_SIZE || !tail.copyOfRange(0, EOCD_MAGIC_SIZE).contentEquals(eocdMagic)) {
            throw IOException("Zip verification failed: EOCD record missing")
        }
    }

    /** Prune the exported-logs dir to the newest [keepLast] payanam artifacts. */
    private fun cleanupOldExports(
        exportDir: File,
        keepLast: Int,
    ) {
        val artifacts =
            exportDir
                .listFiles()
                ?.filter { it.name.startsWith("payanam_") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
        artifacts.drop(keepLast).forEach { file ->
            if (file.delete()) {
                i(
                    "UnifiedLogger.cleanupOldExports",
                    "Deleted old export artifact",
                    mapOf("fileName" to file.name),
                )
            }
        }
    }

    private fun addFileToZip(
        zos: java.util.zip.ZipOutputStream,
        file: File,
    ) {
        zos.putNextEntry(java.util.zip.ZipEntry(file.name))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /** Asynchronously prunes old session files, keeping the newest [keepLast]. */
    fun clearOldLogs(keepLast: Int = 10) {
        launchSafely {
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
        val sessionFile = File(logDir, sessionLogName("android", versionName, buildShortSha, debugBuild, timestamp))
        val header =
            buildString {
                appendLine("=".repeat(HEADER_LINE_LENGTH))
                appendLine(
                    "Payanam Unified Log | Platform: Android | Version: $versionName ($buildShortSha) | " +
                        "Session: $timestamp",
                )
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
        private const val MAX_STACK_FRAMES = io.payanam.shared.logging.ErrorPayload.MAX_STACK_FRAMES
        private const val MAX_CRASH_STACK_FRAMES = io.payanam.shared.logging.ErrorPayload.MAX_CRASH_STACK_FRAMES
        private const val LOG_LEVEL_WIDTH = 5
        private const val MAX_BUFFER_SIZE = 100
        private const val EXPORT_RETENTION_KEEP = 20
        private const val LOGCAT_TAG = "Payanam"

        /** Stands in for the sha when a caller has none: the same token the build scripts use without a `.git`. */
        private const val NO_SHORT_SHA = "nogit"

        // ZIP End-Of-Central-Directory record: fixed 22-byte trailer whose first
        // 4 bytes are the PK\x05\x06 magic. Used to confirm a written zip is complete.
        private const val EOCD_RECORD_SIZE = 22
        private const val EOCD_MAGIC_SIZE = 4
        private val sessionFileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        @Volatile
        private var instance: UnifiedLogger? = null

        @Volatile
        private var debugLoggingEnabled: Boolean = false

        /**
         * Creates and memoizes the singleton logger. Safe to call repeatedly;
         * only the first call constructs an instance. Call from Application.onCreate().
         */
        fun initialize(
            context: Context,
            versionName: String,
            buildShortSha: String,
        ): UnifiedLogger =
            instance ?: synchronized(this) {
                instance ?: UnifiedLogger(context, versionName, buildShortSha).also {
                    instance = it
                }
            }

        /**
         * Legacy arity: the build number no longer names anything, so it is accepted and dropped.
         * Production uses the [buildShortSha] overload.
         */
        fun initialize(
            context: Context,
            versionName: String,
            @Suppress("UNUSED_PARAMETER") buildNumber: Int,
        ): UnifiedLogger = initialize(context, versionName, NO_SHORT_SHA)

        /** Returns the initialized singleton, throwing if [initialize] was not called. */
        fun getInstance(): UnifiedLogger =
            instance ?: error(
                "UnifiedLogger not initialized. Call initialize() from Application.onCreate()",
            )

        /** True once [initialize] has constructed the singleton. */
        fun isInitialized(): Boolean = instance != null

        /** Enables/disables DEBUG-level logging at runtime. */
        fun setDebugLoggingEnabled(enabled: Boolean) {
            debugLoggingEnabled = enabled
        }

        /** Returns the current DEBUG-logging toggle state. */
        fun isDebugLoggingEnabled(): Boolean = debugLoggingEnabled
    }
}
