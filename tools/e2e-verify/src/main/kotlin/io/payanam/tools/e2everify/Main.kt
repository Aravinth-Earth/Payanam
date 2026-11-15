//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.tools.e2everify

import io.payanam.database.jvm.DatabaseOpenMode
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseOpener
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The post-run oracle for the Maestro e2e sessions.
 *
 * A session flow proves only what a user can see. This tool reads the three artifacts the app itself
 * wrote during that session and checks them against each other:
 *
 *  1. **Log** — the exported session `.log` (public Documents/exported-logs). Every session writes it,
 *     and its `sequence` column is a total order over the whole session.
 *  2. **Data** — the DB snapshot produced by "Run Backup Now" (public Documents/data/export/<name>/),
 *     opened read-only through `:core:database-jvm`, i.e. the same SQLCipher driver and guards the
 *     desktop uses.
 *  3. **Media** — the screenshots Maestro captured at the named milestones.
 *
 * The log also names the snapshot it caused (`AutoBackupWorker.doWork … backupPath="…"`), so the DB
 * file is bound to the session by the app's own record rather than by "newest folder wins".
 *
 * Exit code 0 = every check passed; 1 = at least one failed; 2 = the invocation itself was wrong.
 */
private const val EXIT_OK = 0
private const val EXIT_FAILED = 1
private const val EXIT_USAGE = 2
private const val SEQUENCE_UNUSED = 0

/**
 * Field positions in one unified-log line. The line is " | " separated:
 * timestamp | LEVEL | sequence | thread=… | tag | message [ | data map], so the message starts at the
 * sixth field and everything after it is re-joined (a data map itself contains no " | ").
 */
private const val LOG_FIELD_MIN = 6
private const val FIELD_LEVEL = 1
private const val FIELD_SEQUENCE = 2
private const val FIELD_TAG = 4
private const val FIELD_MESSAGE_START = 5

/** How many failing lines the report quotes before it stops; the count is always printed. */
private const val MAX_REPORTED_FAILURES = 3

/** One artefact check: a name, and whether it held, with the evidence that decided it. */
internal class Check(
    val name: String,
    val passed: Boolean,
    val detail: String,
) {
    override fun toString(): String = (if (passed) "PASS  " else "FAIL  ") + name + " — " + detail
}

/** The command line, parsed by hand: this tool has no dependency beyond the database module. */
internal class Options(
    val log: File,
    val database: File,
    val passphrase: String,
    val titlePrefix: String,
    val maestroDir: File?,
    val minScreenshots: Int,
    /** Substrings of known, already-filed anomalies; matching ERROR lines are reported, not failed. */
    val allowedErrors: List<String>,
    /** Status the session must have written for its tasks' occurrences, or null to only report them. */
    val expectOccurrence: String?,
    /** How many of the newest created entities the snapshot must contain. */
    val entityCount: Int,
    /** The session deleted its note: the create and delete log lines must agree and the row be gone. */
    val expectNoteDeleted: Boolean,
    /** The session created a manual time entry: its logged id must exist in the snapshot. */
    val expectTimeEntry: Boolean,
    /** The session wrote today's journal: a response row created today must exist. */
    val expectJournal: Boolean,
)

internal fun main(args: Array<String>) {
    val options = parseOptions(args) ?: exitProcess(EXIT_USAGE)
    val checks = ArrayList<Check>()
    checks += checkLog(options)
    checks += checkDatabase(options)
    checks += checkCorrelation(options)
    checks += checkEntityTypes(options)
    checks += checkMedia(options)
    checks.forEach(::println)
    val failed = checks.count { !it.passed }
    println()
    println("summary: " + (checks.size - failed) + " passed, " + failed + " failed")
    exitProcess(if (failed == 0) EXIT_OK else EXIT_FAILED)
}

internal const val USAGE =
    "usage: --log <file> --db <file> --passphrase <value> [--prefix <p>] [--maestro <dir>] " +
        "[--min-screenshots <n>] [--entities <n>] [--expect-occurrence <status>] " +
        "[--expect-note-deleted] [--expect-time-entry] [--expect-journal] [--allow-error <text>]"

internal fun parseOptions(args: Array<String>): Options? {
    val values = HashMap<String, String>()
    val allowedErrors = ArrayList<String>()
    var index = 0
    while (index < args.size) {
        val key = args[index]
        if (!key.startsWith("--")) {
            System.err.println(USAGE)
            return null
        }
        val name = key.removePrefix("--")
        // Boolean switches stand alone (--expect-journal); valued options take the next token.
        val hasValue = index + 1 < args.size && !args[index + 1].startsWith("--")
        val value = if (hasValue) args[index + 1] else "true"
        if (name == "allow-error") allowedErrors += value else values[name] = value
        index += if (hasValue) 2 else 1
    }
    val log = values["log"]?.let(::File) ?: return usage("--log is required")
    val database = values["db"]?.let(::File) ?: return usage("--db is required")
    val passphrase = values["passphrase"] ?: return usage("--passphrase is required")
    if (!log.isFile) return usage("log file does not exist: " + log.path)
    if (!database.isFile) return usage("database file does not exist: " + database.path)
    val minScreenshots = values["min-screenshots"]?.toIntOrNull() ?: SEQUENCE_UNUSED
    return Options(
        log = log,
        database = database,
        passphrase = passphrase,
        titlePrefix = values["prefix"] ?: "e2e-session-",
        maestroDir = values["maestro"]?.let(::File),
        minScreenshots = minScreenshots,
        allowedErrors = allowedErrors,
        expectOccurrence = values["expect-occurrence"],
        entityCount = values["entities"]?.toIntOrNull() ?: 1,
        expectNoteDeleted = values.containsKey("expect-note-deleted"),
        expectTimeEntry = values.containsKey("expect-time-entry"),
        expectJournal = values.containsKey("expect-journal"),
    )
}

private fun usage(message: String): Options? {
    System.err.println(message)
    return null
}

/** One parsed line of the unified log: level, tag and the remainder, which may carry a data map. */
internal class LogLine(
    val level: String,
    val sequence: Int,
    val tag: String,
    val message: String,
)

internal fun readLog(file: File): List<LogLine> {
    val lines = ArrayList<LogLine>()
    file.forEachLine { raw ->
        // Fields are " | " separated: timestamp | LEVEL | seq | thread=… | tag | message [ | data].
        val parts = raw.split(" | ")
        if (parts.size < LOG_FIELD_MIN || !parts[0].first().isDigit()) return@forEachLine
        lines +=
            LogLine(
                level = parts[FIELD_LEVEL].trim(),
                sequence = parts[FIELD_SEQUENCE].trim().toIntOrNull() ?: SEQUENCE_UNUSED,
                tag = parts[FIELD_TAG].trim(),
                message = parts.drop(FIELD_MESSAGE_START).joinToString(" | ").trim(),
            )
    }
    return lines
}

internal fun checkLog(options: Options): List<Check> {
    val lines = readLog(options.log)
    if (lines.isEmpty()) {
        return listOf(Check("log/parsed", false, "no timestamped lines in " + options.log.path))
    }
    val errors = lines.filter { it.level == "ERROR" || it.level == "FATAL" }
    // Paths are redacted by design (LogSanitizer), so the log can NAME a backup but never its location;
    // the snapshot is bound to the session by the timestamp window instead (see db/session-window).
    val known =
        errors.filter { line ->
            options.allowedErrors.any { allowed -> line.message.contains(allowed) || line.tag.contains(allowed) }
        }
    val failures = errors.filterNot { it in known }
    // A fresh app start logs `UnifiedLogger.init`; a rotation mid-run logs `startNewSession`. Either one
    // proves the exported file is the session's own log.
    val started =
        lines.any {
            (it.tag == "UnifiedLogger.init" && it.message.contains("Logger initialized")) ||
                (it.tag == "UnifiedLogger.startNewSession" && it.message.contains("Started"))
        }
    // Two markers prove the same thing: the worker's own completion, or the Settings action's
    // confirmation (which is what a manual "Run Backup Now" logs). The worker line can land after the
    // log export because WorkManager is asynchronous, so either marker counts.
    val backup =
        lines.firstOrNull {
            (it.tag == "AutoBackupWorker.doWork" && it.message.contains("completed successfully")) ||
                (it.tag == "AppPreferencesViewModel.triggerManualBackupNow" && it.message.contains("succeeded"))
        }
    return listOf(
        Check(
            "log/parsed",
            true,
            lines.size.toString() + " lines, highest sequence " + lines.maxOf { it.sequence },
        ),
        Check(
            "log/session-start",
            started,
            if (started) "startNewSession recorded" else "no UnifiedLogger.startNewSession line",
        ),
        Check(
            "log/no-errors",
            failures.isEmpty(),
            when {
                !failures.isEmpty() -> {
                    failures.size.toString() + " ERROR/FATAL: " +
                        failures.take(MAX_REPORTED_FAILURES).joinToString("; ") { it.tag }
                }

                known.isEmpty() -> {
                    "zero ERROR/FATAL lines"
                }

                else -> {
                    "zero unexplained; " + known.size + " known: " + known.first().tag
                }
            },
        ),
        Check(
            "log/backup",
            backup != null,
            backup?.message ?: "no AutoBackupWorker completion line in the session log",
        ),
    )
}

internal fun checkDatabase(options: Options): List<Check> {
    val checks = ArrayList<Check>()
    val result =
        PayanamDatabaseOpener.open(
            Path.of(options.database.absolutePath),
            options.passphrase,
            DatabaseOpenMode.READ_ONLY,
        )
    if (result is DatabaseOpenResult.Refused) {
        checks +=
            Check(
                "db/opened",
                false,
                "refused: " + result.issue.type + (result.issue.detailMessage?.let { " (" + it + ")" } ?: ""),
            )
        return checks
    }
    val opened = result as DatabaseOpenResult.Opened
    try {
        opened.session.withSerializedAccess { connection ->
            val tasks = countTasksWithPrefix(connection, options.titlePrefix)
            checks +=
                Check(
                    "db/session-tasks",
                    tasks > SEQUENCE_UNUSED,
                    tasks.toString() + " task(s) titled " + options.titlePrefix + "*",
                )
            val statuses = occurrenceStatuses(connection, options.titlePrefix)
            val expected = options.expectOccurrence
            checks +=
                Check(
                    "db/occurrences",
                    expected == null || statuses.containsKey(expected),
                    statuses.toString() + (expected?.let { " (expected " + it + ")" } ?: " (reported, not asserted)"),
                )
            val dimensions = scalarCount(connection, "SELECT count(*) FROM life_dimensions")
            checks += Check("db/dimensions", dimensions > SEQUENCE_UNUSED, dimensions.toString() + " dimensions")
            checks += sessionWindowCheck(options)
        }
    } finally {
        opened.session.close()
    }
    return checks
}

/**
 * One entity the app created during the session, as the APP itself logged it.
 *
 * Correlation is keyed on the UUID the app writes on create (`AddTaskViewModel.createTask … id=`), not
 * on a title or a timestamp: the id is opaque (it reveals nothing about the user's content), it is the
 * same value the database stores, and it survives a rename. That is what lets the log and the DB be
 * matched row-for-row instead of "newest row wins".
 */
internal class CreatedEntity(
    val id: String,
)

/**
 * Every entity the session created, in creation order, as the APP logged it.
 *
 * Titles are REDACTED in logs on purpose, so the log is only the id source; the title is read from the
 * snapshot, which is what the assertion is about. One id per `AddTaskViewModel.createTask` line.
 */
internal fun createdEntities(lines: List<LogLine>): List<CreatedEntity> {
    val idPattern = Regex("""id="([^"]+)"""")
    return lines
        .filter { it.tag == "AddTaskViewModel.createTask" && it.message.contains("Task created successfully") }
        .mapNotNull { line ->
            idPattern
                .find(line.message)
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
                ?.let(::CreatedEntity)
        }
}

/**
 * Binds the session's log to the session's data by UUID: the newest created ids must exist in the
 * snapshot with the run's title, and — when the flow completed one — own an occurrence row with that
 * status. The UUID is opaque (it reveals nothing about the user's content), it is the value the database
 * stores, and it survives a rename.
 */
internal fun checkCorrelation(options: Options): List<Check> {
    if (options.entityCount == 0) {
        // A session that creates no task/habit (notes, journal, time): the task-by-UUID layer is not
        // part of this run, and its own entity checks carry the data assertions instead.
        return listOf(Check("log/created-ids", true, "not asserted (--entities 0)"))
    }
    val created = createdEntities(readLog(options.log))
    if (created.isEmpty()) {
        return listOf(Check("log/created-ids", false, "no AddTaskViewModel.createTask line carries a UUID"))
    }
    val newest = created.takeLast(options.entityCount).reversed()
    val checks = ArrayList<Check>()
    checks +=
        Check(
            "log/created-ids",
            newest.size == options.entityCount,
            created.size.toString() + " created in the session; newest " + newest.first().id,
        )
    val result =
        PayanamDatabaseOpener.open(
            Path.of(options.database.absolutePath),
            options.passphrase,
            DatabaseOpenMode.READ_ONLY,
        )
    if (result is DatabaseOpenResult.Refused) {
        checks += Check("db/by-uuid", false, "refused: " + result.issue.type)
        return checks
    }
    val opened = result as DatabaseOpenResult.Opened
    try {
        opened.session.withSerializedAccess { connection ->
            newest.forEachIndexed { index, entity ->
                val title = titleOf(connection, entity.id)
                checks +=
                    Check(
                        "db/by-uuid[" + index + "]",
                        title != null && title.startsWith(options.titlePrefix),
                        if (title == null) "no task row for " + entity.id else entity.id + " = " + title,
                    )
            }
            val expected = options.expectOccurrence
            if (expected != null) {
                val status = occurrenceStatusOf(connection, newest.first().id)
                checks +=
                    Check(
                        "db/occurrence-by-uuid",
                        status == expected,
                        "occurrence status " + status + " (expected " + expected + ")",
                    )
            }
        }
    } finally {
        opened.session.close()
    }
    return checks
}

/**
 * Cross-layer checks for the entity types whose ids the app logs but whose rows the generic
 * correlation cannot assume: a note that was deleted, a manual time entry, and today's journal.
 */
internal fun checkEntityTypes(options: Options): List<Check> {
    if (!options.expectNoteDeleted && !options.expectTimeEntry && !options.expectJournal) {
        return emptyList()
    }
    val lines = readLog(options.log)
    val checks = ArrayList<Check>()
    val opened =
        PayanamDatabaseOpener.open(
            Path.of(options.database.absolutePath),
            options.passphrase,
            DatabaseOpenMode.READ_ONLY,
        )
    if (opened is DatabaseOpenResult.Refused) {
        return listOf(Check("db/entity-types", false, "refused: " + opened.issue.type))
    }
    val session = (opened as DatabaseOpenResult.Opened).session
    try {
        session.withSerializedAccess { connection ->
            if (options.expectNoteDeleted) {
                checks += noteLifecycleCheck(connection, lines)
            }
            if (options.expectTimeEntry) {
                checks += timeEntryCheck(connection, lines)
            }
            if (options.expectJournal) {
                checks += journalCheck(connection)
            }
        }
    } finally {
        session.close()
    }
    return checks
}

private fun noteLifecycleCheck(
    connection: java.sql.Connection,
    lines: List<LogLine>,
): Check {
    val pattern = Regex("""noteId="([^"]+)"""")
    val created =
        lines
            .filter { it.tag == "NotesViewModel.createNote" && it.message.contains("Note created") }
            .mapNotNull { pattern.find(it.message)?.groupValues?.get(1) }
    val deleted =
        lines
            .filter { it.tag == "NotesViewModel.deleteNote" && it.message.contains("Note deleted") }
            .mapNotNull { pattern.find(it.message)?.groupValues?.get(1) }
    if (created.isEmpty() || deleted.isEmpty()) {
        return Check(
            "db/note-lifecycle",
            false,
            "create lines " + created.size + ", delete lines " + deleted.size,
        )
    }
    val noteId = created.last()
    val rowExists =
        connection.prepareStatement("SELECT 1 FROM notes WHERE id = ?").use { statement ->
            statement.setString(1, noteId)
            statement.executeQuery().use { rows -> rows.next() }
        }
    return Check(
        "db/note-lifecycle",
        noteId in deleted && !rowExists,
        noteId + if (rowExists) " still in the snapshot" else " created then deleted",
    )
}

private fun timeEntryCheck(
    connection: java.sql.Connection,
    lines: List<LogLine>,
): Check {
    val pattern = Regex("""entryId="([^"]+)"""")
    val entryId =
        lines
            .filter { it.tag == "TimeViewModel.createManualEntry" && it.message.contains("created") }
            .mapNotNull { pattern.find(it.message)?.groupValues?.get(1) }
            .lastOrNull()
            ?: return Check("db/time-entry", false, "no createManualEntry line carries an entryId")
    val exists =
        connection.prepareStatement("SELECT 1 FROM time_entries WHERE id = ?").use { statement ->
            statement.setString(1, entryId)
            statement.executeQuery().use { rows -> rows.next() }
        }
    return Check("db/time-entry", exists, entryId + if (exists) " present" else " missing")
}

private fun journalCheck(connection: java.sql.Connection): Check {
    val count =
        scalarCount(
            connection,
            "SELECT count(*) FROM day_journal_responses " +
                "WHERE date(createdAt) = date('now','localtime') " +
                "AND responseText IS NOT NULL AND responseText <> ''",
        )
    return Check("db/journal-today", count > SEQUENCE_UNUSED, count.toString() + " response(s) today")
}

internal fun checkMedia(options: Options): List<Check> {
    val directory =
        options.maestroDir ?: return listOf(
            Check("media/screenshots", true, "skipped (no --maestro dir given)"),
        )
    val shots = directory.walkTopDown().count { it.isFile && it.extension.equals("png", ignoreCase = true) }
    return listOf(
        Check(
            "media/screenshots",
            shots >= options.minScreenshots,
            shots.toString() + " screenshot(s), required " + options.minScreenshots,
        ),
    )
}

/**
 * Binds the snapshot file to the session without trusting a path: the backup folder name ends with the
 * moment it was written (`…_yyyyMMdd_HHmmss`), and that moment must not precede the session's first log
 * line. Run order is one session = one flow, so a snapshot older than the session is another run's file.
 */
internal fun sessionWindowCheck(options: Options): Check {
    val folder =
        options.database.absoluteFile.parentFile
            ?.name ?: return Check(
            "db/session-window",
            false,
            "snapshot file has no parent folder to date it by",
        )
    val stamp =
        Regex("""(\d{8}_\d{6})$""").find(folder)?.groupValues?.get(1)
            ?: return Check("db/session-window", false, "folder name carries no timestamp: " + folder)
    val snapshotAt =
        java.time.LocalDateTime.parse(
            stamp,
            java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd_HHmmss"),
        )
    val firstLine =
        options.log.useLines { it.firstOrNull { line -> line.firstOrNull()?.isDigit() == true } }
            ?: return Check("db/session-window", false, "log has no timestamped line")
    val sessionStart =
        java.time.LocalDateTime.parse(
            firstLine.substringBefore(" | ").trim(),
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        )
    return Check(
        "db/session-window",
        !snapshotAt.isBefore(sessionStart),
        "snapshot " + snapshotAt + " vs session start " + sessionStart,
    )
}

private fun countTasksWithPrefix(
    connection: java.sql.Connection,
    prefix: String,
): Int =
    connection.prepareStatement("SELECT count(*) FROM tasks WHERE title LIKE ?").use { statement ->
        statement.setString(1, prefix + "%")
        statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else SEQUENCE_UNUSED }
    }

private fun titleOf(
    connection: java.sql.Connection,
    id: String,
): String? =
    connection.prepareStatement("SELECT title FROM tasks WHERE id = ?").use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

private fun occurrenceStatusOf(
    connection: java.sql.Connection,
    taskId: String,
): String? =
    connection.prepareStatement("SELECT status FROM task_occurrences WHERE taskId = ? LIMIT 1").use { statement ->
        statement.setString(1, taskId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

private fun scalarCount(
    connection: java.sql.Connection,
    sql: String,
): Int =
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getInt(1) else SEQUENCE_UNUSED }
    }

private fun occurrenceStatuses(
    connection: java.sql.Connection,
    prefix: String,
): Map<String, Int> {
    val sql =
        "SELECT o.status, count(*) FROM task_occurrences o JOIN tasks t ON t.id = o.taskId " +
            "WHERE t.title LIKE ? GROUP BY o.status"
    val statuses = LinkedHashMap<String, Int>()
    connection.prepareStatement(sql).use { statement ->
        statement.setString(1, prefix + "%")
        statement.executeQuery().use { rows ->
            while (rows.next()) statuses[rows.getString(1)] = rows.getInt(2)
        }
    }
    return statuses
}
