//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import java.sql.ResultSet

/**
 * The two JDBC shapes every repository implementation in this layer needs.
 *
 * Extracted the moment a second repository needed them: with fourteen still to port, a private copy in
 * each would be fourteen chances for the parameter-binding or resource-closing behaviour to drift.
 *
 * Both run on the session's single long-lived connection. Nothing here opens or closes a connection,
 * and nothing here commits — see [PayanamDatabaseSession] for who owns the connection lifecycle.
 */
internal object JdbcQueries {
    /**
     * Runs a query whose parameters are all text and maps each row with [map].
     *
     * Both the statement and the result set are closed here, so a caller cannot leak either by
     * forgetting a `use`.
     *
     * Parameters are nullable because the columns they bind to are: a note's `details` and `dimension_id`
     * are both optional, and binding them as the four-character string "null" would be a different value
     * from SQL NULL. `setString(i, null)` writes a real NULL.
     */
    fun <T> read(
        session: PayanamDatabaseSession,
        sql: String,
        parameters: Array<out String?>,
        map: (ResultSet) -> T,
    ): T =
        session.requireConnection().prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { map(it) }
        }

    /**
     * Runs a single-statement write whose parameters are all text, returning the affected row count.
     *
     * `autoCommit` is `true` on this driver's connections (measured), so the write is durable when this
     * returns. Callers that need several statements to land together must open an explicit transaction
     * rather than assume one here — this helper deliberately does not begin one, because a transaction
     * spanning a caller's whole method would be a different contract, not a convenience.
     */
    fun write(
        session: PayanamDatabaseSession,
        sql: String,
        parameters: Array<out String?>,
    ): Int =
        session.requireConnection().prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeUpdate()
        }

    /**
     * Runs [block] as one transaction on the session's connection, committing on success and rolling
     * back on any throw.
     *
     * Needed by every write that touches more than one table — the note write is the first: the Android
     * implementation writes the journal note and its legacy `notes` shadow inside one Room transaction,
     * and a JDBC port that wrote them separately could leave a note whose tag foreign keys point at a row
     * that was never inserted.
     *
     * `autoCommit` is restored to what it was, never assumed to be false, so a nested call cannot silently
     * turn autocommit back on for its caller.
     *
     * **A nested call joins the open transaction instead of starting a second one.** SQLite has no nested
     * transactions, so a second `BEGIN`/`COMMIT` pair would COMMIT THE OUTER TRANSACTION'S WORK EARLY —
     * after which the outer rollback can no longer undo it, and a failure halfway through a multi-table
     * write would leave it half applied. The callers nest for real: `clearDayPlan` wraps its delete and its
     * mode change in one transaction, and the mode change marks the day lens-dirty, which is itself a
     * read-modify-write that wants to be atomic. Room's own `withTransaction` joins for the same reason.
     *
     * The catch is deliberately `Throwable` rather than `Exception`: a rollback is required on ANY failure,
     * `Error` included, because a half-applied multi-table write is worse than propagating whatever went
     * wrong.
     */
    fun <T> transaction(
        session: PayanamDatabaseSession,
        block: () -> T,
    ): T {
        val connection = session.requireConnection()
        if (!connection.autoCommit) return block()
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        return try {
            val result = block()
            connection.commit()
            result
        } catch (
            @Suppress("TooGenericExceptionCaught")
            error: Throwable,
        ) {
            runCatching { connection.rollback() }
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    /**
     * Runs a query and returns the first column of the first row, or null.
     *
     * An aggregate such as `MIN(dayKey)` over an empty table still returns one row, holding SQL NULL —
     * so "no rows" and "null value" are different cases and this returns null for both, which is what
     * the callers want. Written once so that stays true everywhere.
     */
    fun readScalar(
        session: PayanamDatabaseSession,
        sql: String,
        parameters: Array<out String?>,
    ): String? =
        read(session, sql, parameters) { resultSet ->
            if (resultSet.next()) resultSet.getString(1) else null
        }
}
