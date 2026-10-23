//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

/**
 * The category of problem detected when an existing database cannot be opened.
 *
 * This model is SHARED deliberately. It was written for the Android boot screen
 * (`DatabaseInitViewModel`), and the desktop needs to describe exactly the same set of problems — so a
 * second, desktop-only enum would be a second vocabulary for one concept, and the two would drift the
 * moment either added a case. Android and desktop must refuse the same databases for the same reasons
 * and say the same thing about it.
 *
 * Android's user-facing copy for each case lives in its string resources
 * (`db_init_issue_*`); each platform supplies its own strings, but the CASE comes from here.
 */
enum class DatabaseBootIssueType {
    /**
     * Sidecar files (`-wal`/`-shm`/`-journal`) exist but the main database file does not.
     *
     * The desktop extends this to the case where the main file exists but a sidecar also does: it
     * refuses rather than opening, because `PRAGMA query_only` is applied after the connection opens
     * and SQLite may replay the log into the main file before any guard can act.
     */
    SIDECAR_PRIMARY_MISSING,

    /**
     * Older than [PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION].
     *
     * Android can migrate in place; the desktop cannot yet, so it refuses with the same category and
     * its own message. **Desktop migration is planned** — until it exists, refusing is correct, because
     * opening a database whose schema this build does not implement would mean writing v21 semantics
     * over v16 tables.
     */
    DB_TOO_OLD,

    /** Newer than [PayanamDatabaseSchema.VERSION]; interpreting it risks misreading columns. */
    DB_TOO_NEW,

    /** The version looked plausible but the database is missing tables the schema requires. */
    SCHEMA_INVALID,

    /** The database could not be opened at all, or failed a guard after opening. */
    OPEN_FAILED,

    /** A generic problem the app believes it can repair. */
    REPAIRABLE_GENERIC,

    /** A generic problem the app believes it cannot repair. */
    NON_REPAIRABLE_GENERIC,
}

/**
 * A specific boot problem: its [type], an optional human-readable [detailMessage], and the
 * [detectedVersion] of the offending database.
 *
 * [detailMessage] is for diagnostics and must never carry a filesystem path or any user content —
 * it can end up in a log the user pastes into a public issue.
 */
data class DatabaseBootIssue(
    val type: DatabaseBootIssueType,
    val detailMessage: String? = null,
    val detectedVersion: Int = 0,
)
