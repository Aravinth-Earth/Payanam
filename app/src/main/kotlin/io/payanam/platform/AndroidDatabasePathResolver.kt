//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.domain.port.DatabasePathResolver
import io.payanam.shared.database.PayanamDatabaseSchema
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's [DatabasePathResolver].
 *
 * `Context.getDatabasePath` is the platform's OWN answer to where a database lives — it embeds the
 * per-app data directory, which varies with profile and user — so it is asked for the file, and the
 * directory is read back as the parent of that very file. Deriving the directory independently would
 * be a second guess that could drift from the path the platform really opens. The file name comes from
 * [PayanamDatabaseSchema.DATABASE_NAME], the same constant Room is given, so the two can never disagree.
 */
@Singleton
class AndroidDatabasePathResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : DatabasePathResolver {
    /**
     * The platform's absolute path for [PayanamDatabaseSchema.DATABASE_NAME], asked of the `Context` rather
     * than composed, because only the `Context` knows the profile-specific databases directory.
     */
    override fun databaseFilePath(): String = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).absolutePath

    /**
     * The parent of the SAME file [databaseFilePath] reports. Failing loudly beats returning a
     * directory that does not hold the database: a path without a parent cannot be absolute in any
     * meaningful sense, and a silent wrong answer would point lifecycle operations at nothing.
     */
    override fun databaseDirectory(): String = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).parentFile?.absolutePath
        ?: error("AndroidDatabasePathResolver: database path has no parent")
}
