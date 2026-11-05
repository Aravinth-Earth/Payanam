//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

/**
 * The one rule for folding a write-ahead log, shared.
 *
 * ## Why the rule and not a copy
 *
 * Every destructive transition in this app has the same precondition: the main database file must already
 * hold every write, because the transition is about to take the file away — copy it to a backup, replace
 * it with an import, delete it, or close it. A database in WAL mode breaks that assumption by default: a
 * `-wal` sidecar holds the most recent commits, and the main file is a snapshot from whenever the last fold
 * happened. So each of those transitions had to grow its own `PRAGMA wal_checkpoint(TRUNCATE)`, and they
 * did not agree on what the answer MEANT.
 *
 * What is shared here is the STATEMENT, the READING of its row, and the DECISION about whether the caller
 * may proceed. What is not shared is the handle: a caller supplies [fold] and owns whatever it touches — on
 * Android a `SQLiteDatabase` cursor, on the desktop a JDBC `ResultSet`.
 *
 * ## The rule, in one sentence
 *
 * A fold is EMPTY only when the pragma ran, reported `busy = 0`, and left no frames behind; anything else —
 * including a row that never arrived — is a NON-EMPTY answer, and the caller does not proceed.
 *
 * ## Why `busy = 0` alone was not enough
 *
 * `busy = 0` means nothing HELD the database. It does not, by itself, mean the log is empty, and the
 * desktop's result type said `completed = (busy == 0)` for a long time. A log that still reports frames
 * after a fold that was not blocked is a snapshot waiting to be mistaken for the current database, which is
 * the exact failure the rule exists to prevent. So emptiness is `busy == 0` AND no frames left.
 *
 * `logFrames = -1` is the one non-zero-looking value that still means empty: the pragma returned no row, or
 * the database is not in WAL mode at all, so there was nothing to fold and the main file is already
 * complete. It is named [NO_LOG] rather than left as a magic number.
 *
 * ## Why there are TWO gates, and they are named
 *
 * A lifecycle flush must not fail hard: `onStop` throwing is a crash, and a fold that did not complete on
 * the way out is not worth one. An export must fail hard, because the alternative is writing a backup that
 * looks complete and is not. Those are genuinely different requirements, so they are two NAMED entry points
 * ([walIsEmpty] for a caller that reports, [requireWalEmpty] for a caller that refuses) rather than a
 * boolean flag threaded through one function, and both are the same decision underneath.
 */
object WalCheckpointRule {
    /** The frame count that means "there is no log here", not "the log is empty but huge". */
    const val NO_LOG: Int = -1

    /** The arity of the pragma's row: `(busy, log, checkpointed)`. Anything shorter is not a report. */
    private const val ROW_WIDTH: Int = 3

    /** The statement every caller runs. Shared so both platforms fold the same way. */
    const val FOLD_STATEMENT: String = "PRAGMA wal_checkpoint(TRUNCATE)"

    /**
     * What a fold reported, as the pragma's own row: `(busy, log, checkpointed)`.
     *
     * Carried rather than collapsed to a boolean so a caller that refuses can say WHY, and so the frame
     * counts survive into a log line instead of being read and dropped.
     *
     * @param busy `1` when another connection held the database and the fold did not complete.
     * @param logFrames frames the log held when the pragma ran, or [NO_LOG] when there was no log.
     * @param checkpointedFrames frames folded into the main file, or [NO_LOG] when there was no log.
     */
    data class FoldReport(
        val busy: Int,
        val logFrames: Int,
        val checkpointedFrames: Int,
    ) {
        /**
         * True only when the main file now holds every write.
         *
         * Both halves are required, as described on [WalCheckpointRule]: a `busy = 1` fold did not run at
         * all, and a fold that left frames behind has not finished emptying the log.
         */
        val walIsEmpty: Boolean
            get() = busy == 0 && (logFrames == 0 || logFrames == NO_LOG)
    }

    /** The report used when the pragma never answered, so an unread fold cannot read as an empty one. */
    val NO_ROW: FoldReport = FoldReport(busy = 1, logFrames = NO_LOG, checkpointedFrames = NO_LOG)

    /**
     * Reads the pragma's row into a [FoldReport], or [NO_ROW] when there was no row to read.
     *
     * The row is `[busy, log, checkpointed]` in that order, and a caller that guessed the order wrong
     * would report a completed fold as a blocked one — or worse, the reverse. So the read lives here, once.
     */
    fun reportOf(row: IntArray?): FoldReport =
        if (row == null || row.size < ROW_WIDTH) {
            NO_ROW
        } else {
            FoldReport(busy = row[0], logFrames = row[1], checkpointedFrames = row[2])
        }

    /**
     * The decision, for a caller that REPORTS rather than refuses.
     *
     * A null report means nothing was folded — no session, no cursor, no answer — and is a NON-EMPTY
     * answer. A caller cannot claim an empty log out of silence.
     */
    fun walIsEmpty(report: FoldReport?): Boolean = report != null && report.walIsEmpty

    /**
     * The same decision, for a caller that REFUSES: returns [report] when the log is empty, and throws
     * [IllegalStateException] when it is not, naming the counts so the refusal is diagnosable.
     *
     * @param what the transition being attempted, phrased for a log line ("export", "backup snapshot").
     */
    fun requireWalEmpty(
        report: FoldReport?,
        what: String,
    ): FoldReport {
        if (!walIsEmpty(report)) {
            val held =
                report?.let { "busy=${it.busy} logFrames=${it.logFrames} checkpointedFrames=${it.checkpointedFrames}" }
                    ?: "no fold was reported"
            throw IllegalStateException(
                "Refusing $what: the write-ahead log is not empty, so the main database file may not hold " +
                    "every write ($held).",
            )
        }
        return checkNotNull(report)
    }
}
