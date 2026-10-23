//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

/**
 * Returns true when [fileName] names one of the live SQLite files for [dbName]
 * — the main database plus its `-wal`, `-shm`, and `-journal` companion files.
 * Anything else (`.bak`, temp import files, orphaned companions) is stale.
 *
 * THE SET IS [DatabaseArtifacts.artifactSuffixes], not a copy: this predicate decides which files a
 * user-initiated delete is allowed to remove, so a family that gained or lost a member here and not
 * there is a file that survives every cleanup or is deleted while in use.
 */
fun isActiveArtifact(
    fileName: String,
    dbName: String = PayanamDatabaseSchema.DATABASE_NAME,
): Boolean =
    DatabaseArtifacts.artifactSuffixes.any {
        fileName ==
            "$dbName$it"
    }
