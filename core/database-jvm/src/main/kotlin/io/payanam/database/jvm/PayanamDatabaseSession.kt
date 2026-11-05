//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.shared.database.PayanamDatabaseHandle
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.database.WalCheckpointRule.FoldReport
import io.payanam.shared.logging.AppLogger
import java.sql.Connection
import java.sql.SQLException

/** The one message a closed-session failure carries, so the type and the text cannot drift apart. */
private const val SESSION_CLOSED_MESSAGE = "PayanamDatabaseSession is closed"

/**
 * An open handle to the encrypted Payanam database.
 *
 * Holds **one long-lived connection**, not open-per-query. SQLCipher derives the key from the
 * passphrase on every open (PBKDF2-HMAC-SHA512, 256 000 iterations), which costs roughly 100–300 ms;
 * opening per query would pay that on every read for no benefit. Access is serialised by [synchronized]
 * because a single JDBC connection is not safe for concurrent use.
 *
 * [mode] is the contract this handle was opened under. In [DatabaseOpenMode.READ_ONLY] the connection
 * carries `PRAGMA query_only = ON`, which was read back as `1` at open time and is the only mechanism
 * this driver actually enforces.
 */
class PayanamDatabaseSession internal constructor(
    private val connection: Connection,
    /** The `PRAGMA user_version` found in the file when it was opened. */
    override val schemaVersion: Int,
    /** Whether this handle is read-only or read-write. */
    val mode: DatabaseOpenMode,
) : PayanamDatabaseHandle,
    AutoCloseable {
    @Volatile
    private var closed = false

    companion object {
        /**
         * Wraps an ALREADY-OPEN connection for a generated fixture.
         *
         * Named for what it is: it bypasses the schema guard the opener applies, because the driver cannot
         * write `PRAGMA user_version` (a measured driver fact), so every generated fixture is version 0 and
         * the opener would correctly refuse it. Tests that need a fixture reach the repositories through
         * here; the guard itself is proven against a real Android export instead.
         *
         * Production code must go through [PayanamDatabaseOpener] — a session built here can be pointed at a
         * database whose schema this build does not implement, with no warning.
         */
        fun forGeneratedFixture(
            connection: Connection,
            schemaVersion: Int,
        ): PayanamDatabaseSession = PayanamDatabaseSession(connection, schemaVersion, DatabaseOpenMode.READ_WRITE)

        /** Log-source tag. Private: the companion is public only so the factory above can be reached. */
        private const val SOURCE = "PayanamDatabaseSession"
    }

    /**
     * The underlying connection. Callers must not close it — close the [PayanamDatabaseSession].
     *
     * @throws IllegalStateException if this session has already been closed.
     */
    @Synchronized
    fun requireConnection(): Connection {
        if (closed) throw PayanamDatabaseSessionClosedException()
        return connection
    }

    /**
     * Runs [block] while holding this session's lock, so the single connection is USED serially, not
     * merely handed out serially.
     *
     * WHY THIS EXISTS, MEASURED 2026-10-05: [requireConnection] alone only serialises the accessor.
     * A caller that takes the connection and then prepares a statement on its own thread races every
     * other caller that did the same - and the driver's native layer is not safe under that race. A
     * JVM crash log shows the exact death: a SIGSEGV in `libsqlcipher_jni.so`'s `findElementWithHash`
     * under `JdbcQueries.read -> prepareStatement`, on a `DefaultDispatcher` worker, while the test
     * JVM ran many repository flows at once (`desktop/hs_err_pid2245066.log`). Every statement use in
     * this module now runs through here, which is what the type's own KDoc always claimed.
     *
     * Reentrant: JDBC transactions and helpers that nest reads inside a write re-enter it safely,
     * because Java's monitor is reentrant and the same thread taking it twice is exactly the nesting
     * those callers need.
     */
    @Synchronized
    fun <T> withSerializedAccess(block: (Connection) -> T): T {
        if (closed) throw PayanamDatabaseSessionClosedException()
        return block(connection)
    }

    /** True once [close] has run. */
    val isClosed: Boolean get() = closed

    /** The shared contract's view of the same fact, phrased positively. */
    override val isOpen: Boolean get() = !closed

    /**
     * Closes the connection. Idempotent: a second call is a no-op, so a `use { }` block and an explicit
     * close in a finally cannot double-close.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.close()
        } catch (error: SQLException) {
            // Closing a broken connection is not actionable, but it must not be silent.
            AppLogger.current.w(
                SOURCE,
                "Failed to close the database connection cleanly",
                mapOf("error" to error.javaClass.simpleName),
            )
        }
    }

    /**
     * Folds the write-ahead log into the main database file, and reports whether it completed.
     *
     * WHY AN EXPORT NEEDS THIS: a copy of the main file taken while a `-wal` holds recent writes is a
     * snapshot missing whatever the user just did, which the user reads as data loss. Folding first is what
     * makes "copy the encrypted database" export the CURRENT database.
     *
     * WHY IT RETURNS A RESULT RATHER THAN THROWING: `PRAGMA wal_checkpoint` answers `busy`, `log` and
     * `checkpointed` in one row, and `busy = 1` means another reader held the database and the fold did NOT
     * complete. That is not an exception — it is a normal outcome under concurrent access — but a caller
     * about to copy the file must not treat it as success.
     *
     * Run as a QUERY: this driver classifies PRAGMA as query SQL, so `executeUpdate("PRAGMA …")` throws
     * "cannot be used for query SQL" (a measured driver fact, alongside `?mode=ro` being ignored).
     */
    @Synchronized
    fun checkpoint(): FoldReport {
        if (closed) throw PayanamDatabaseSessionClosedException()
        val row =
            JdbcQueries.read(this, WalCheckpointRule.FOLD_STATEMENT, emptyArray()) { resultSet ->
                if (resultSet.next()) {
                    WalCheckpointRule.reportOf(
                        intArrayOf(resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3)),
                    )
                } else {
                    // No row at all: the pragma did not run, so nothing was folded and nothing may be
                    // assumed. WalCheckpointRule.NO_ROW is already non-empty, so it can never pass the gate.
                    WalCheckpointRule.NO_ROW
                }
            }
        if (!row.walIsEmpty) {
            AppLogger.current.w(
                SOURCE,
                "WAL checkpoint did not leave the log empty; the main file may not hold every write",
                mapOf(
                    "busy" to row.busy,
                    "logFrames" to row.logFrames,
                    "checkpointedFrames" to row.checkpointedFrames,
                ),
            )
        }
        return row
    }
}

/**
 * Thrown when a session's connection is used after [PayanamDatabaseSession.close].
 *
 * An [IllegalStateException], which is what this condition has always been reported as, so every existing
 * caller keeps its contract. The distinct TYPE is what lets the polling observation stream tell a session
 * that ended under it — a normal end of subscription — from any other state failure, which must still reach
 * its caller. Constructed only by [PayanamDatabaseSession].
 */
class PayanamDatabaseSessionClosedException internal constructor() : IllegalStateException(SESSION_CLOSED_MESSAGE)
