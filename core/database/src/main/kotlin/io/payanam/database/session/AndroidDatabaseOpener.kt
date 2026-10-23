//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.session

import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseOpenOutcome
import io.payanam.shared.database.PayanamDatabaseHandle
import io.payanam.shared.database.PayanamDatabaseOpener
import io.payanam.shared.database.PayanamDatabaseSchema

/**
 * The Android side of the shared database-opener contract, over [DatabaseSessionManager].
 *
 * Two Android-specific facts shape this adapter, and both are stated rather than smoothed over:
 *
 * **The path is not consulted.** Android has exactly one database, at a path its own storage code owns, and
 * its verifier is a hash comparison against the stored credential rather than an attempt to decrypt a given
 * file. The parameter exists because the contract is shared with the desktop, where verifying a file the
 * user just picked is the whole point of the import flow. Ignoring it on Android is correct — but a caller
 * must not read a `true` here as "that file is good", because Android never looked at the file.
 *
 * **Read-only is refused, not approximated.** Room opens the app's database read-write, so there is no way
 * to honour `readOnly = true`. The contract requires a refusal in that case rather than opening read-write
 * behind the caller's back, because the caller is relying on it not to mutate what it is inspecting.
 */
class AndroidDatabaseOpener(
    private val sessionManager: DatabaseSessionManager,
    context: android.content.Context,
) : PayanamDatabaseOpener {
    private val encryptionManager = DatabaseEncryptionManager(context)

    override suspend fun verifyPassphrase(
        databasePath: String,
        passphrase: CharArray,
    ): Boolean = encryptionManager.verifyPassphrase(passphrase.concatToString())

    override suspend fun open(
        databasePath: String,
        passphrase: CharArray,
        readOnly: Boolean,
    ): DatabaseOpenOutcome {
        if (readOnly) {
            return DatabaseOpenOutcome.Refused(
                DatabaseBootIssue(
                    type = DatabaseBootIssueType.OPEN_FAILED,
                    detailMessage = "read_only_unsupported",
                ),
            )
        }
        val result = sessionManager.openDatabase(passphrase.concatToString())
        return result.fold(
            onSuccess = { DatabaseOpenOutcome.Opened(AndroidDatabaseHandle(sessionManager)) },
            onFailure = { error ->
                // The exception MESSAGE can carry a filesystem path or user content, so only the class name
                // travels: enough to tell a disk error from bad state, nothing that leaks.
                DatabaseOpenOutcome.Refused(
                    DatabaseBootIssue(
                        type = DatabaseBootIssueType.OPEN_FAILED,
                        detailMessage = error.javaClass.simpleName,
                    ),
                )
            },
        )
    }

    /**
     * The manager keeps no session object to hand out, so the handle delegates back to it. The schema
     * version comes from the shared constant: after Room has opened and migrated, the open database IS at
     * this build's version.
     */
    private class AndroidDatabaseHandle(
        private val sessionManager: DatabaseSessionManager,
    ) : PayanamDatabaseHandle {
        override val schemaVersion: Int = PayanamDatabaseSchema.VERSION

        override val isOpen: Boolean get() = sessionManager.isDbOpen()

        override fun close() = sessionManager.closeDatabase()
    }
}
