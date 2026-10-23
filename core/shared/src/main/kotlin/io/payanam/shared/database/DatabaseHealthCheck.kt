//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import java.io.File

/**
 * What a health check found, in the terms the DB-init screens render.
 *
 * These four facts are what a caller actually branches on, so they are the whole contract. The Android
 * type carries two more (`currentVersion`, `targetVersion`) that only its own migration path reads, which
 * is why promoting that class wholesale would drag migration vocabulary into shared code.
 */
data class DatabaseHealthReport(
    /** The file is present and every critical table is readable. */
    val isHealthy: Boolean,
    /** A human-readable reason, already localised by the implementation that produced it. */
    val errorMessage: String? = null,
    /** The file is there but unusable — a repair or a re-import is the user's next step. */
    val needsRepair: Boolean = false,
    /** The schema is outside the range this build can serve. */
    val needsMigration: Boolean = false,
    /**
     * The schema version actually found in the file, or null when no file was opened.
     *
     * Carried because a boot issue records what was DETECTED beside what this build expects - a user
     * reading "found 22, this build serves 21" is why the message is actionable at all. It is null rather
     * than 0 when nothing was read, because 0 is a real (too old) version and would be a claim nobody
     * measured.
     */
    val detectedVersion: Int? = null,
)

/**
 * "Is the database on disk safe to open?", asked the same way on both platforms.
 *
 * The RULES are platform-neutral and already shared: the critical-table list lives in
 * [PayanamDatabaseSchema.CRITICAL_TABLES], and both platforms diff their own handle against it. What is NOT
 * shareable is the plumbing — Android reads through a Room `SQLiteDatabase` obtained from a `Context`,
 * while the desktop holds a JDBC `Connection` — so each platform answers with its own handle and the same
 * question. That is the pattern this whole migration runs on: platform-specific plumbing, shared rules,
 * reuse whatever is already shared.
 *
 * A platform that cannot answer must still ANSWER, with a reason. Returning "healthy" because a check was
 * skipped would let a corrupt file open; that is the failure the honesty rule exists to prevent.
 */
interface DatabaseHealthCheck {
    /**
     * Reports on the database file, which is a [File] on both platforms — the desktop already has one and
     * Android's `Context.getDatabasePath` produces the same path.
     */
    suspend fun check(
        databaseFile: File,
        passphrase: String?,
    ): DatabaseHealthReport

    /** Whether a database file exists at [databaseFile] at all. */
    fun hasArtifacts(databaseFile: File): Boolean
}
