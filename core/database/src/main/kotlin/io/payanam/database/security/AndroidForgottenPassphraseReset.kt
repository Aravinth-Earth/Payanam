//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.port.DatabaseForgotPassphraseReset
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.Report

/**
 * Android's [DatabaseForgotPassphraseReset], over the platform's own database path.
 *
 * WHY THE PATH IS TAKEN FROM `Context` RATHER THAN INJECTED: `context.getDatabasePath` is the platform's
 * authority for where a database lives, and restating it here would create a second answer that can drift
 * from the one the database is actually opened at. A reset that deletes a path the app never opens is a
 * reset that reports success and destroys nothing.
 *
 * ORDER, AND WHY IT IS THE ONLY ORDER: the artifacts go first, the credential second. A reset that cleared
 * the credential first and then failed to delete would leave the user with a working unlock and a database
 * they cannot open — locked out of data that is still there. Deleting first and failing after leaves them an
 * empty app they can set up, which is the state the reset exists to produce.
 *
 * The artifact family comes from [DatabaseArtifacts], so this path deletes exactly the set the rest of the
 * app means by "a Payanam database" — including the sidecars whose omission is how a surviving `-wal` turns
 * a wiped main file back into recovered rows.
 */
class AndroidForgottenPassphraseReset(
    context: Context,
    private val vault: PassphraseVault,
) : DatabaseForgotPassphraseReset {
    private val appContext = context.applicationContext
    private val logger = UnifiedLogger.getInstance()

    override fun reset(): Report {
        val attempted = deleteAllDatabaseFiles()
        // Deliberately NOT a roll-up with `deleted`: the two halves fail independently and the caller is
        // owed the difference. A single boolean hides "your data is gone but the app still thinks it is
        // locked", which is a support ticket.
        val credentialCleared =
            runCatching {
                vault.clearPassphrase()
                true
            }.getOrElse { false }
        return Report(
            artifactsDeleted = attempted.deleted,
            artifactsAttempted = attempted.attempted,
            credentialCleared = credentialCleared,
        )
    }

    /**
     * Removes the artifact family through [DatabaseFileGuard], which is what makes the deletion
     * traceable after the fact.
     *
     * It is the guard rather than `File.delete()` because this is the one path that destroys a user's
     * data on purpose: a delete that fails here is silent in every other sense, and a surviving `-wal`
     * beside a wiped main file is how a database the user believes is gone hands its rows back. The
     * guard logs the intent and the outcome, so "we tried to delete your data and could not" is a line
     * in the session log rather than a count that quietly omits one member.
     *
     * WHY IT REPORTS BOTH COUNTS and counts the family BEFORE deleting: the caller decides success from
     * the pair, and a survivors-only count cannot tell a caller that a member it could not remove is still
     * on disk. Counting the family up front means an absent member is never mistaken for a failed delete,
     * and a failed delete is never mistaken for an absent member — the two directions of the same lie.
     */
    private fun deleteAllDatabaseFiles(): AttemptedDeletes {
        val databaseFile = appContext.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
        // Counted as the members that EXIST, not as the family the path authority enumerates: a fresh
        // install has no artifacts at all, and counting the four it never had would report zero of four as
        // a failed reset on every first run. The attempts that count are the ones this process made.
        val present = DatabaseArtifacts.filesFor(databaseFile).filter { it.exists() }
        val attempted = present.size
        var deleted = 0
        present.forEach { file ->
            val wasDeleted =
                DatabaseFileGuard.safeDelete(
                    file,
                    DatabaseFileGuard.DeleteIntent.FORGOT_PASSPHRASE_RESET,
                    "AndroidForgottenPassphraseReset.deleteAllDatabaseFiles",
                )
            if (wasDeleted) {
                deleted++
            } else {
                logger.e(
                    "AndroidForgottenPassphraseReset.deleteAllDatabaseFiles",
                    "Failed to delete a database artifact during the forgot-passphrase reset",
                    null,
                    mapOf("artifact" to file.name),
                )
            }
        }
        return AttemptedDeletes(deleted = deleted, attempted = attempted)
    }

    /**
     * The two counts the caller needs to tell a complete delete from a partial one.
     *
     * A local pair rather than a constructor result because the counts are produced by two separate loops'
     * worth of state; naming them here keeps the report construction readable and gives [Report.succeeded]
     * the shape it derives from.
     */
    private data class AttemptedDeletes(
        val deleted: Int,
        val attempted: Int,
    )
}
