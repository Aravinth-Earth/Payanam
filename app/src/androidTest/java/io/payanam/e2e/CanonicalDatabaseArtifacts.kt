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
 */
fun deleteCanonicalDatabaseArtifacts(context: Context) {
    val basePath = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).absolutePath
    DatabaseArtifacts.sidecarSuffixes.forEach { suffix -> File(basePath + suffix).delete() }
    File(basePath).delete()
}
