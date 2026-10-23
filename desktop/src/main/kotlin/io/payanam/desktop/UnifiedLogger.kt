//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.logging.ErrorPayload
import io.payanam.shared.logging.LogSanitizer
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Env var that overrides the build's debug-logging default; `true` turns DEBUG entries on, any other
 * value (including `false`) turns them off for the session.
 */
private const val DEBUG_LOGGING_ENV_VAR = "PAYANAM_DEBUG_LOGGING"

/** Stable name for the copy written by [UnifiedLogger.exportLatestLog]. */
private const val LATEST_EXPORT_FILE_NAME = "payanam_desktop_latest_export.log"

/**
 * Default for [UnifiedLogger.Companion.initialize]'s debug gate. Read once here rather than inside
 * the logger so the value is fixed for the session and recorded in the header.
 */
private fun defaultDebugLogging(): Boolean =
    resolveDefaultDebugLogging(
        envValue = System.getenv(DEBUG_LOGGING_ENV_VAR),
        debugBuild = DesktopBuildInfo.IS_DEBUG_BUILD,
    )

/**
 * Which debug gate a session starts with: the environment variable when it is set, the build type
 * otherwise.
 *
 * The same shape as Android's `DebugLoggingPrefs.resolve()`, which falls back to `BuildConfig.DEBUG`
 * when the user has never touched the toggle - a debug build logs DEBUG entries from process start, a
 * release build does not. The environment variable wins because it is the escape hatch: debugging a
 * release build with `PAYANAM_DEBUG_LOGGING=true`, or quietening a debug build with `=false`, must not
 * need a rebuild. Extracted from [defaultDebugLogging] so the precedence is testable without touching
 * the environment.
 */
internal fun resolveDefaultDebugLogging(
    envValue: String?,
    debugBuild: Boolean,
): Boolean = envValue?.equals("true", ignoreCase = true) ?: debugBuild

/**
 * Append-only desktop session logger that writes structured entries to a file
 * and implements [AutoCloseable] so the underlying writer can be released.
 */
class UnifiedLogger private constructor(
    private val logFilePath: Path,
    private val openedAt: Instant,
    private val clock: () -> Instant,
    /** Gates [d]; supplied by the factory so tests can set it without touching the environment. */
    @Volatile
    private var debugEnabled: Boolean,
) : io.payanam.shared.logging.UnifiedLogger,
    AutoCloseable {
    private val sequenceCounter = AtomicLong(0)

    init {
        writeHeader()
    }

    /**
     * DEBUG-level structured entry. Desktop has no logcat, so debug entries are written
     * to the session file with a DEBUG level marker (mirrors Android's gated d()).
     */
    override fun d(
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) {
        // Gated like Android's logger: DEBUG is off unless explicitly enabled, because the desktop
        // previously wrote it unconditionally and nothing bounded the volume.
        if (!debugEnabled) {
            return
        }
        appendEntry(level = "DEBUG", source = source, message = message, data = data)
    }

    /**
     * INFO-level structured entry.
     */
    override fun i(
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) {
        appendEntry(level = "INFO", source = source, message = message, data = data)
    }

    /**
     * WARN-level structured entry.
     */
    override fun w(
        source: String,
        message: String,
        data: Map<String, Any?>,
    ) {
        appendEntry(level = "WARN", source = source, message = message, data = data)
    }

    /**
     * ERROR-level structured entry; [error]'s class, message, stack, and cause chain
     * are folded into the data payload.
     */
    override fun e(
        source: String,
        message: String,
        error: Throwable?,
        data: Map<String, Any?>,
    ) {
        appendEntry(
            level = "ERROR",
            source = source,
            message = message,
            data =
                ErrorPayload.build(
                    error,
                    data,
                    ErrorPayload.MAX_STACK_FRAMES,
                ),
        )
    }

    override fun eSync(
        source: String,
        message: String,
        error: Throwable?,
        data: Map<String, Any?>,
    ) {
        // Desktop appendEntry is already @Synchronized and synchronous, so this is not
        // buffered like Android's eSync — the only difference from e() is the wider
        // stack budget used for crash-imminent reports.
        appendEntry(
            level = "ERROR",
            source = source,
            message = message,
            data =
                ErrorPayload.build(
                    error,
                    data,
                    ErrorPayload.MAX_CRASH_STACK_FRAMES,
                ),
        )
    }

    /**
     * Path of this session's log file.
     */
    fun getLogPath(): Path = logFilePath

    /**
     * Copies this session's log file to a stable sibling export path and returns it.
     *
     * The desktop writes plain files into a plain directory, so export is a copy - there is no SAF
     * picker and no R class to route a content URI through. The original log is never moved or
     * deleted, so exporting mid-session does not disturb the append-only writer.
     *
     * Returns null if the log file does not exist yet or the copy fails; export must never take down
     * the caller, so every failure is swallowed.
     */
    suspend fun exportLatestLog(): File? {
        val source = getLogPath()
        return runCatching {
            if (!Files.isRegularFile(source)) {
                return@runCatching null
            }
            val target = source.resolveSibling(LATEST_EXPORT_FILE_NAME)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            target.toFile()
        }.getOrNull()
    }

    /**
     * Zips every regular file in the log directory into a timestamped sibling archive and returns it.
     *
     * Same desktop-side contract as [exportLatestLog]: the originals are only read, the archive is a
     * copy, and any failure returns null instead of propagating.
     */
    suspend fun exportAllLogs(): File? {
        val logsDirectory = getLogPath().parent ?: return null
        return runCatching {
            val stamp = fileTimestampFormatter().format(clock().atZone(ZoneId.systemDefault()))
            val target = logsDirectory.resolve("payanam_desktop_all_logs_$stamp.zip")
            ZipOutputStream(Files.newOutputStream(target)).use { zip ->
                Files.list(logsDirectory).use { entries ->
                    entries
                        .filter { path -> Files.isRegularFile(path) }
                        .sorted()
                        .forEach { path ->
                            // A previous export archive in the log directory would otherwise swallow
                            // this one, so the zip only ever takes the plain log files.
                            if (path.fileName.toString().endsWith(".zip")) {
                                return@forEach
                            }
                            zip.putNextEntry(ZipEntry(path.fileName.toString()))
                            Files.copy(path, zip)
                            zip.closeEntry()
                        }
                }
            }
            target.toFile()
        }.getOrNull()
    }

    /**
     * Writes the session-end marker and releases the singleton.
     */
    override fun close() {
        appendEntry(
            level = "INFO",
            source = "UnifiedLogger.close",
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
                    append(" | Debug logging: ")
                    append(if (debugEnabled) "on" else "off")
                },
                "Device: Desktop | OS: ${System.getProperty("os.name")}",
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
        // Sanitize on the way in, mirroring the Android logger: the desktop path previously only
        // collapsed newlines and wrote the data map verbatim.
        val safeMessage = LogSanitizer.sanitizeMessage(message)
        val metadata = "$sequence | thread=${Thread.currentThread().name}"
        val payload = buildPayload(LogSanitizer.sanitizeData(data))
        val line =
            "${entryFormatter.format(
                now.atZone(ZoneId.systemDefault()),
            )} | ${level.padEnd(LOG_LEVEL_WIDTH)} | $metadata | $source | $safeMessage$payload\n"
        try {
            Files.writeString(
                logFilePath,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
            )
        } catch (io: IOException) {
            // Logging must never take down the caller. Surface the failure once on stderr
            // (the log file itself is the thing that failed) and keep going.
            System.err.println("UnifiedLogger: failed to append log entry: ${io.message}")
        }
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
            is Collection<*> -> "[${value.size} items]"
            is Map<*, *> -> "{${value.size} entries}"
            else -> value.toString()
        }

    companion object {
        private const val HEADER_LINE_LENGTH = 120
        private const val LOG_LEVEL_WIDTH = 5

        /**
         * Session logs kept in the log directory. 20 on the desktop against Android's 9: these are plain
         * files in a directory the user browses, ships and deletes themselves, while Android's sit inside
         * app storage where nothing else can reach them. A deliberate difference, not a drift.
         */
        private const val RETAINED_LOG_COUNT = 20

        @Volatile
        private var instance: UnifiedLogger? = null
        private val companionLock = Any()

        /**
         * Creates the singleton session logger (pruning stale logs first);
         * safe to call repeatedly.
         */
        fun initialize(
            logsDirectory: Path = DesktopAppPaths.resolveLogsDirectory(),
            clock: () -> Instant = { Instant.now() },
            debugEnabled: Boolean = defaultDebugLogging(),
        ): UnifiedLogger =
            instance ?: synchronized(companionLock) {
                instance ?: createLogger(
                    logsDirectory = logsDirectory,
                    clock = clock,
                    debugEnabled = debugEnabled,
                ).also {
                    instance = it
                }
            }

        /**
         * The active session logger; errors if [initialize] was not called.
         */
        fun getInstance(): UnifiedLogger = instance ?: error("UnifiedLogger not initialized. Call initialize() from desktop main().")

        /**
         * Moves the session logger's debug gate and returns the value in force.
         *
         * The desktop has no build flag to follow, so this is how the shared `debug_logging_enabled` row
         * reaches the logger: `DesktopDebugFlags` applies the row here, the same shape Android's flags use.
         * Before [initialize] there is no logger to gate, so the call records nothing and reports the
         * requested value - the shell initializes the logger before it can read any row, so that window is a
         * start-up detail rather than a behaviour anyone can observe.
         */
        fun setDebugLoggingEnabled(enabled: Boolean): Boolean {
            instance?.debugEnabled = enabled
            return enabled
        }

        /**
         * Test-only: drops the memoized singleton so the next [initialize] starts a fresh
         * session. Production code must not call this — [close] already releases the singleton.
         */
        internal fun resetForTests() {
            synchronized(companionLock) {
                instance = null
            }
        }

        private fun createLogger(
            logsDirectory: Path,
            clock: () -> Instant,
            debugEnabled: Boolean,
        ): UnifiedLogger {
            Files.createDirectories(logsDirectory)
            pruneOldLogs(logsDirectory)
            val now = clock()
            val fileName = "payanam_desktop_${DesktopBuildInfo.PLATFORM_BUILD_NUMBER}_${fileTimestampFormatter().format(
                now.atZone(ZoneId.systemDefault()),
            )}.log"
            val logFile = logsDirectory.resolve(fileName)
            return UnifiedLogger(
                logFilePath = logFile,
                openedAt = now,
                clock = clock,
                debugEnabled = debugEnabled,
            )
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

        /**
         * Memoised: [appendEntry] formats a timestamp on every write, and `ofPattern` compiles the
         * pattern each call. [DateTimeFormatter] is immutable and every call site is serialized
         * (`@Synchronized` appendEntry, or the `companionLock` during construction), so one shared
         * instance is safe.
         */
        private val entryFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
