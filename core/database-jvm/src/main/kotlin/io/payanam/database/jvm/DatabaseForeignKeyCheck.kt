//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import java.sql.Connection

/**
 * One orphaned row: a child row whose parent row is not in the file.
 *
 * The three strings are the columns `PRAGMA foreign_key_check` returns, read as strings so a value the
 * driver types differently cannot raise out of a read-only check.
 */
data class ForeignKeyViolation(
    val table: String,
    val rowId: String,
    val parent: String,
)

/**
 * Reads the orphan rows a database accumulates when nothing enforces its foreign keys.
 *
 * **Why this is here.** A delete that should cascade leaves the child row behind whenever the connection
 * did not enforce the declared action, and a user who inspects the file afterwards finds rows nothing will
 * ever read and nothing will ever clean up. Enforcing the declared actions would make those rows a
 * different problem rather than removing them: a later write to one of the orphans would then fail with a
 * constraint error, on a row the user never touched. So the check has to be able to answer "does this file
 * already hold any?" *before* enforcement is turned on - and, afterwards, on a database imported from a
 * platform that enforces it differently.
 *
 * **`PRAGMA foreign_key_check` is the right instrument and is deliberately read-only.** It reports
 * violations whether or not enforcement is currently on, it modifies nothing, and it is a QUERY, so it
 * runs on the drained-query path this driver requires - the shape the class KDoc in
 * [PayanamDatabaseOpener] documents for every pragma that must actually execute.
 *
 * This does NOT enforce anything, repair anything, or decide whether a file is acceptable. It counts and
 * names what is already there.
 */
object DatabaseForeignKeyCheck {
    /**
     * Every orphan row currently stored, in the order the pragma returns them.
     *
     * Ordered by the pragma rather than sorted, because a caller reporting the first few wants the file's
     * own first few, and a stable order keeps two runs over one file comparable.
     */
    fun violations(connection: Connection): List<ForeignKeyViolation> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { rows ->
                val found = mutableListOf<ForeignKeyViolation>()
                while (rows.next()) {
                    found.add(
                        ForeignKeyViolation(
                            table = rows.getString(1).orEmpty(),
                            rowId = rows.getString(2).orEmpty(),
                            parent = rows.getString(PARENT_COLUMN).orEmpty(),
                        ),
                    )
                }
                found
            }
        }

    /**
     * Names [violations] for a person, most numerous first, capped at [limit] relationships.
     *
     * Grouped by the child/parent pair rather than listed per row: one deleted task can leave a dozen
     * orphans in one table, and twelve repetitions of the same relationship teaches nothing a count and a
     * name do not. The remainder is stated as a count, so a capped message is never read as a complete
     * one.
     */
    fun message(
        violations: List<ForeignKeyViolation>,
        limit: Int = DEFAULT_RELATIONSHIP_LIMIT,
    ): String {
        val grouped = violations.groupBy { "${it.table} -> ${it.parent}" }
        val shown = grouped.entries.sortedByDescending { it.value.size }.take(limit)
        val named = shown.joinToString("; ") { "${it.key} (${it.value.size})" }
        val omitted = grouped.size - shown.size
        return if (omitted > 0) "$named, and $omitted more" else named
    }

    private const val DEFAULT_RELATIONSHIP_LIMIT = 5

    /**
     * The pragma's fourth column, the parent table a row points at.
     *
     * `PRAGMA foreign_key_check` returns one row per orphan as (table, rowid, parent, fkid); the first
     * two columns are this child's own identity, so the parent is the first column that names something
     * the orphan is missing, and the only one a reader needs beyond the count.
     */
    private const val PARENT_COLUMN = 3
}
