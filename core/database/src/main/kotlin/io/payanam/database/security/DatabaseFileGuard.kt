//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import java.io.File

/**
 * Guards deletion of primary database files. Every in-app delete of the production
 * DB file (or its WAL/SHM sidecars) must go through [safeDelete] with an explicit
 * [DeleteIntent] so the operation is traceable in logs.
 */
object DatabaseFileGuard {
    private val logger = UnifiedLogger.getInstance()

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
    }

    private fun primaryDbNames(): Set<String> {
        val baseName = PayanamDatabase.DATABASE_NAME
        return setOf(baseName, "$baseName-wal", "$baseName-shm", "$baseName-journal")
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
     * Recursively deletes [dir]. Logs intent for traceability.
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
