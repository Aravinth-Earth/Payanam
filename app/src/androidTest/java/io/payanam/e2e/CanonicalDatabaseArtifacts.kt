//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import android.content.Context
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import java.io.File

/**
 * Deletes the canonical database file and its SQLite companion files.
 * Shared by e2e seeds and import tests, which both need a pristine canonical path.
 *
 * The delete RESULTS are checked rather than discarded. A `delete()` that fails leaves the previous
 * database in place, and the caller then measures the very state it believed it had removed — a green
 * assertion about a database nobody created. An artifact that was never there is NOT a failure, so
 * only a file that still exists after the attempt counts against the check.
 */
fun deleteCanonicalDatabaseArtifacts(context: Context) {
    val basePath = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).absolutePath
    val artifacts = DatabaseArtifacts.sidecarSuffixes.map { suffix -> File(basePath + suffix) } + File(basePath)
    val undeleted = artifacts.filter { file -> file.exists() && !file.delete() }
    check(undeleted.isEmpty()) {
        "could not delete the canonical database artifacts, so the following test would measure a stale " +
            "database: " + undeleted.joinToString { it.absolutePath }
    }
}
