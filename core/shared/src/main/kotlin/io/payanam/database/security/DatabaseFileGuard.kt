//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import java.io.File

/**
 * Guards deletion of primary database files. Every in-app delete of the production
 * DB file (or its WAL/SHM sidecars) must go through [safeDelete] with an explicit
 * [DeleteIntent] so the operation is traceable in logs.
 *
 * Logger is bridged via [AppLogger.current] (installed on Android through
 * [AppLogger.install] at PayanamApp.kt:91) so this module stays platform-neutral.
 */
object DatabaseFileGuard {
    /**
     * Read per call rather than captured once at construction, so the guard follows the app's installed
     * logger. Capturing it once made the guard's own contract untestable: whichever test touched the object
     * first froze the logger for the whole JVM, so a test could not install a recording one and assert the
     * traceability this file exists for. In production the app installs its logger before any read, so the
     * two are the same object either way.
     */
    private val logger: UnifiedLogger get() = AppLogger.current

    /** Declares the reason for a primary DB file deletion. */
    enum class DeleteIntent {
        /** User explicitly chose "Delete All Data" from Settings. */
        USER_DELETE_ALL,

        /** Import-replace flow is overwriting the DB with imported data. */
        IMPORT_REPLACE,

        /** Admin artifact cleanup from the database stats screen. */
        ADMIN_ARTIFACT_CLEANUP,

        /** Janitor cleanup of stale/temp artifacts (never the primary DB). */
        JANITOR_CLEANUP,

        /**
         * The unlock gate's "I forgot my passphrase" reset, which removes the whole artifact family.
         *
         * Its own value rather than [USER_DELETE_ALL] because the two are not the same event: the user
         * asked the app to forget a credential, and this is what the app did about it. A log that called
         * this a user delete-all would misattribute a recovery the user did not choose to a wipe they did.
         */
        FORGOT_PASSPHRASE_RESET,
    }

    private fun primaryDbNames(): Set<String> {
        val baseName = PayanamDatabaseSchema.DATABASE_NAME
        return DatabaseArtifacts.artifactSuffixes.mapTo(mutableSetOf()) { "$baseName$it" }
    }

    /**
     * Deletes [file] only if [intent] is provided. Logs the intent for forensic
     * traceability. Returns true if the file was deleted (or did not exist).
     */
    fun safeDelete(
        file: File,
        intent: DeleteIntent,
        caller: String,
    ): Boolean {
        val isPrimary = primaryDbNames().contains(file.name)
        if (isPrimary) {
            logger.i(
                "DatabaseFileGuard.safeDelete",
                "Primary DB file deletion authorized",
                mapOf(
                    "file" to file.name,
                    "intent" to intent.name,
                    "caller" to caller,
                    "existed" to file.exists(),
                    "sizeBytes" to if (file.exists()) file.length() else 0L,
                ),
            )
        }
        return if (file.exists()) {
            val deleted = file.delete()
            if (!deleted) {
                logger.w(
                    "DatabaseFileGuard.safeDelete",
                    "Failed to delete file",
                    mapOf(
                        "file" to file.name,
                        "intent" to intent.name,
                        "caller" to caller,
                    ),
                )
            }
            deleted
        } else {
            true
        }
    }

    /**
     * Recursively deletes [dir]. logs intent for traceability.
     */
    fun safeDeleteDir(
        dir: File,
        intent: DeleteIntent,
        caller: String,
    ): Boolean {
        logger.i(
            "DatabaseFileGuard.safeDeleteDir",
            "Directory deletion authorized",
            mapOf(
                "dir" to dir.name,
                "intent" to intent.name,
                "caller" to caller,
                "existed" to dir.exists(),
            ),
        )
        return if (dir.exists()) dir.deleteRecursively() else true
    }
}
