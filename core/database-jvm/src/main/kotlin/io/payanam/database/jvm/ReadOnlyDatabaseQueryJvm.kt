//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")

package io.payanam.database.jvm

import io.payanam.domain.port.DatabaseSchemaReader
import io.payanam.domain.port.DatabaseSchemaResult
import io.payanam.domain.port.DatabaseTableSchema
import io.payanam.domain.port.ReadOnlyDatabaseQuery
import io.payanam.domain.port.ReadOnlyQueryOutcome
import io.payanam.domain.port.ReadOnlyQueryRules
import io.payanam.domain.port.ReadOnlyRefusal
import io.payanam.shared.logging.AppLogger
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/**
 * The JDBC answer to [ReadOnlyDatabaseQuery] and [DatabaseSchemaReader], over the SQLCipher driver this
 * module already opens databases with.
 *
 * ## WHERE THE READ-ONLY GUARANTEE ACTUALLY COMES FROM
 *
 * From a SECOND HANDLE, not from this class's own checks, and not from the caller's cooperation:
 *
 *  1. [access] asks [open] — [PayanamDatabaseOpener.open] in production — for a handle under
 *     [DatabaseOpenMode.READ_ONLY]. That opener is the one place in the program that sets
 *     `PRAGMA query_only = ON`, reads it back, and REFUSES the open when it did not take, so the
 *     engine, not this class, is what rejects a write.
 *  2. This class then re-reads `PRAGMA query_only` on the handle it was actually handed and compares it
 *     with [READ_ONLY_FLAG], so an injected or future opener that returned a writable handle is caught
 *     HERE rather than trusted. Both this read-back and the handle's own [DatabaseOpenMode] must hold;
 *     either one failing yields [ReadOnlyRefusal.READ_ONLY_NOT_ENFORCED] and no rows.
 *  3. Only then does a statement run, and it runs through [Connection.prepareStatement] +
 *     [java.sql.Statement.executeQuery], which cannot issue a statement that returns no result set.
 *
 * A caller cannot bypass any of it: there is no constructor argument that supplies a connection, no
 * member that returns one, and the cache holds only handles this class opened. The two constructor
 * lambdas answer WHERE the database is and WHICH passphrase it is under, and never hand out a handle.
 *
 * WHY A SEPARATE HANDLE RATHER THAN THE LIVE SESSION'S CONNECTION: the live session is read-write by
 * design — it is the app's own working database — so pinning `query_only` on it would break the app. The
 * cost is one extra open (SQLCipher derives the key per open, ~100-300 ms) paid once, lazily, on the
 * first read rather than per statement.
 *
 * WHAT THIS COSTS, STATED PLAINLY: [PayanamDatabaseOpener.open] refuses a file with a `-wal`/`-shm`/
 * `-journal` beside it, because a read-only handle applies `query_only` after connecting. So while the
 * live session holds a hot journal, a schema or query read reports [ReadOnlyRefusal.DATABASE_NOT_OPEN]
 * instead of reading a file that might not be self-contained. That is a refusal, not a wrong answer,
 * and it is the same refusal the boot path already makes.
 */
class ReadOnlyDatabaseQueryJvm(
    private val databaseFile: () -> Path,
    private val passphrase: () -> CharArray?,
    /** The open, injected: a test can hand this class a writable handle and must be refused. */
    private val open: (Path, String, DatabaseOpenMode) -> DatabaseOpenResult = PayanamDatabaseOpener::open,
) : ReadOnlyDatabaseQuery,
    DatabaseSchemaReader,
    AutoCloseable {
    private val logger = AppLogger.current
    private val lock = Any()

    /** The verified read-only handle, or null when none has been opened yet. */
    @Volatile
    private var handle: PayanamDatabaseSession? = null

    override fun query(
        sql: String,
        maxRows: Int,
    ): ReadOnlyQueryOutcome {
        require(maxRows > 0) { "maxRows must be positive but was $maxRows" }
        if (!isReadOnlyStatement(sql)) {
            return refuse(ReadOnlyRefusal.NOT_A_READ_ONLY_STATEMENT, "leading keyword is not a read-only statement")
        }
        return when (val access = access()) {
            is ReadOnlyHandle.Denied -> refuse(access.reason, access.detail)
            is ReadOnlyHandle.Granted -> runStatement(sql, maxRows, access.connection)
        }
    }

    override fun readSchema(): DatabaseSchemaResult =
        when (val outcome = query(ReadOnlyQueryRules.SCHEMA_QUERY, ReadOnlyQueryRules.SCHEMA_MAX_ROWS)) {
            is ReadOnlyQueryOutcome.Rows -> {
                DatabaseSchemaResult.Tables(
                    outcome.rows.mapNotNull { row ->
                        val name = row.getOrNull(0) ?: return@mapNotNull null
                        val ddl = row.getOrNull(1) ?: return@mapNotNull null
                        DatabaseTableSchema(name, ddl)
                    },
                )
            }

            is ReadOnlyQueryOutcome.Refused -> {
                DatabaseSchemaResult.Refused(outcome.reason, outcome.detail)
            }
        }

    /**
     * Releases the verified handle. Idempotent, and safe to call with nothing open.
     *
     * A caller that closes this has not closed the app's session — the live session belongs to
     * [io.payanam.domain.port.DatabaseSessionManager] and is untouched by anything here.
     */
    override fun close() {
        synchronized(lock) {
            handle?.close()
            handle = null
        }
    }

    /**
     * A verified read-only connection, or the reason there is none.
     *
     * The cache is the whole reason the key derivation is paid once, and it holds ONLY a handle this
     * class verified, so a second caller cannot inherit an unverified one.
     */
    private fun access(): ReadOnlyHandle =
        synchronized(lock) {
            handle?.takeIf { !it.isClosed }?.let { return ReadOnlyHandle.Granted(it.requireConnection()) }
            val secret =
                passphrase()?.takeIf { it.isNotEmpty() }
                    ?: return ReadOnlyHandle.Denied(ReadOnlyRefusal.DATABASE_NOT_OPEN, "no passphrase for an open session")
            // `when` rather than an `if (x !is Opened)`: the branch arms each carry one case, so no
            // member of `DatabaseOpenResult` is named outside the arm that proved which one it is.
            val session =
                when (
                    val opened =
                        try {
                            open(databaseFile(), secret.concatToString(), DatabaseOpenMode.READ_ONLY)
                        } catch (
                            @Suppress("TooGenericExceptionCaught") error: Exception,
                        ) {
                            logger.w(SOURCE, "The read-only open threw", mapOf("error" to error.javaClass.simpleName))
                            return ReadOnlyHandle.Denied(ReadOnlyRefusal.DATABASE_NOT_OPEN, "read-only open threw")
                        }
                ) {
                    is DatabaseOpenResult.Opened -> {
                        opened.session
                    }

                    is DatabaseOpenResult.Refused -> {
                        logger.w(SOURCE, "The read-only open was refused", mapOf("issue" to opened.issue.type.name))
                        return ReadOnlyHandle.Denied(
                            ReadOnlyRefusal.DATABASE_NOT_OPEN,
                            "read-only open refused: " + opened.issue.type.name,
                        )
                    }
                }
            val enforced = session.mode == DatabaseOpenMode.READ_ONLY && readQueryOnlyFlag(session.requireConnection()) == 1
            if (!enforced) {
                // Closed here, deliberately: an unverified handle is a WRITABLE handle, and leaving it
                // open behind a refusal would leak exactly what this class exists to not have open.
                session.close()
                logger.w(SOURCE, "A handle was produced that cannot be proven read-only; refusing to read", emptyMap())
                return ReadOnlyHandle.Denied(
                    ReadOnlyRefusal.READ_ONLY_NOT_ENFORCED,
                    "the handle offered is not proven read-only",
                )
            }
            handle = session
            ReadOnlyHandle.Granted(session.requireConnection())
        }

    /** Runs one statement through [executeQuery], whose only possible failure is the engine's own. */
    private fun runStatement(
        sql: String,
        maxRows: Int,
        connection: Connection,
    ): ReadOnlyQueryOutcome {
        val started = System.currentTimeMillis()
        try {
            return connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rows -> collect(rows, maxRows) }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            logger.w(
                SOURCE,
                "A statement was refused or failed",
                mapOf("error" to error.javaClass.simpleName),
            )
            return ReadOnlyQueryOutcome.Refused(
                ReadOnlyRefusal.EXECUTION_FAILED,
                error.javaClass.simpleName + ": " + error.message,
            )
        } finally {
            // The statement text and the row values are deliberately absent: this line can reach a log
            // the user pastes into a public issue, and both carry personal data.
            logger.i(
                SOURCE,
                "A read-only statement completed",
                mapOf("duration_ms" to (System.currentTimeMillis() - started)),
            )
        }
    }

    private fun collect(
        rows: ResultSet,
        maxRows: Int,
    ): ReadOnlyQueryOutcome.Rows {
        val columnCount = rows.metaData.columnCount
        val columns = (1..columnCount).map { rows.metaData.getColumnLabel(it) }
        val collected = mutableListOf<List<String?>>()
        var truncated = false
        while (rows.next()) {
            if (collected.size == maxRows) {
                truncated = true
                break
            }
            collected +=
                columns.indices.map { index ->
                    // getString, not getObject: the caller renders cells as text, and a NULL must stay a
                    // real null rather than becoming the four-character string "null".
                    rows.getString(index + 1)
                }
        }
        return ReadOnlyQueryOutcome.Rows(columns, collected, truncated)
    }

    /**
     * Whether [sql] STARTS with something that can only read.
     *
     * A courtesy, not an enforcement: it turns a mutation into a clean refusal instead of an engine
     * error string, and it is why `WITH` is NOT on the list — SQLite permits `WITH ... INSERT`, so
     * allowing it would approve a statement whose shape says nothing about what it writes. The guarantee
     * itself is the verified handle in [access].
     */
    private fun isReadOnlyStatement(sql: String): Boolean =
        sql.trimStart().takeWhile { it.isLetter() }.uppercase() in READ_ONLY_LEADING_KEYWORDS

    /** Reads `PRAGMA query_only` back off [connection]; a value other than `1` is not a read-only handle. */
    private fun readQueryOnlyFlag(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(QUERY_ONLY_STATEMENT).use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }

    private fun refuse(
        reason: ReadOnlyRefusal,
        detail: String,
    ): ReadOnlyQueryOutcome.Refused =
        ReadOnlyQueryOutcome.Refused(reason, detail).also {
            logger.w(SOURCE, "A read was refused", mapOf("reason" to reason.name))
        }

    private companion object {
        private const val SOURCE = "ReadOnlyDatabaseQueryJvm"

        /** What `PRAGMA query_only` must read back as before a handle is trusted. */
        private const val READ_ONLY_FLAG = 1

        private const val QUERY_ONLY_STATEMENT = "PRAGMA query_only"

        private val READ_ONLY_LEADING_KEYWORDS = setOf("SELECT", "VALUES", "EXPLAIN")
    }
}

/** A verified read-only connection, or the reason this class will not produce one. */
private sealed interface ReadOnlyHandle {
    class Granted(
        val connection: Connection,
    ) : ReadOnlyHandle

    class Denied(
        val reason: ReadOnlyRefusal,
        val detail: String,
    ) : ReadOnlyHandle
}
