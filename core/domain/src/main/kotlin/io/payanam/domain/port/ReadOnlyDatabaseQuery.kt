//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * Runs ONE model-authored SQL statement against the user's own database and answers rows.
 *
 * ## What this port is for
 *
 * The assistant answers questions by running SQL the model wrote. That string is UNTRUSTED INPUT: it
 * arrives from a remote model, so the only safe assumption about it is that it will eventually contain
 * something hostile. This port is the seam that holds the database on the far side of that boundary, and
 * its entire reason to exist is that the answer must be structurally incapable of being a write.
 *
 * WHY A PORT AND NOT A SHARED HELPER: the handle is the platform's. Android answers it through a
 * `SQLiteDatabase` opened read-only, the desktop through a JDBC connection the SQLCipher driver has
 * already refused to open writable. A shared helper would have to hold one of those handles as a
 * dependency and every other raw-SQL caller in the program would inherit that handle.
 *
 * ## WHY READ-ONLY IS STRUCTURAL HERE AND NOT A PROPERTY OF THE CALLER
 *
 * The port exposes no write, no transaction and no second statement, so a caller reading this signature
 * has no write to make. That is necessary and NOT sufficient: a caller can also pass a write as the
 * [sql] ARGUMENT. So an implementation must open its own handle in a mode where the ENGINE refuses the
 * write, must verify that refusal capability is actually in force before answering anything, and must
 * report its absence as [ReadOnlyRefusal.READ_ONLY_NOT_ENFORCED] rather than reading anyway. Text
 * inspection of [sql] is a courtesy that produces a cleaner error; it is never the thing standing
 * between the model and the user's rows.
 *
 * ## What an implementation may not do
 *
 * Read through a handle it did not verify, and report a refusal as a row set. A caller that cannot tell
 * "the model asked for something I will not do" from "there is nothing here" will render an empty table
 * to the user and call it an answer.
 */
fun interface ReadOnlyDatabaseQuery {
    /**
     * Runs [sql] and returns at most [maxRows] rows.
     *
     * @param sql one statement, authored by the model. Never a batch.
     * @param maxRows the row cap; the caller owns it because the cap is about the model's context
     *   budget, not about the database.
     * @throws IllegalArgumentException when [maxRows] is not positive — a caller bug, not a data
     *   outcome, and never reported as one.
     */
    fun query(sql: String, maxRows: Int): ReadOnlyQueryOutcome
}

/** Why a read did not happen. Chosen by the implementation, READ by the caller, never logged as detail-free text. */
enum class ReadOnlyRefusal {
    /** No usable handle: nothing is open, or the read-only open was refused outright. */
    DATABASE_NOT_OPEN,

    /** [sql] does not begin with a statement that can only read. Reported for a clean refusal, not as enforcement. */
    NOT_A_READ_ONLY_STATEMENT,

    /** A handle was produced but it cannot be proven unable to write, so nothing was read through it. */
    READ_ONLY_NOT_ENFORCED,

    /** The engine refused or failed the statement; [ReadOnlyQueryOutcome.Refused.detail] carries its own message. */
    EXECUTION_FAILED,
}

/**
 * The answer to one read: either the rows, or the reason there are none.
 *
 * A sealed type rather than a nullable rows field because "the database had no matching rows" and "I
 * would not run that" are different facts, and a caller that flattens them renders a refusal to the user
 * as an empty table.
 */
sealed interface ReadOnlyQueryOutcome {
    /** A statement ran and produced these columns, these rows, and possibly more rows than were kept. */
    data class Rows(
        val columns: List<String>,
        val rows: List<List<String?>>,
        /** True when the statement produced at least one row beyond [rows]. */
        val truncated: Boolean,
    ) : ReadOnlyQueryOutcome

    /** No rows were read, and [detail] says why. */
    data class Refused(
        val reason: ReadOnlyRefusal,
        val detail: String,
    ) : ReadOnlyQueryOutcome
}
