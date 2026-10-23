//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.security.ForgottenPassphraseReset
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.Report

/**
 * Android's [ForgottenPassphraseReset], over the platform's own database path.
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
) : ForgottenPassphraseReset {
    private val appContext = context.applicationContext

    override fun reset(report: (Report) -> Unit) {
        val deleted = deleteAllDatabaseFiles()
        // Deliberately NOT a roll-up with `deleted`: the two halves fail independently and the caller is
        // owed the difference. A single boolean hides "your data is gone but the app still thinks it is
        // locked", which is a support ticket.
        val credentialCleared =
            runCatching {
                vault.clearPassphrase()
                true
            }.getOrElse { false }
        report(Report(artifactsDeleted = deleted, credentialCleared = credentialCleared))
    }

    private fun deleteAllDatabaseFiles(): Int {
        val databaseFile = appContext.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
        var deleted = 0
        DatabaseArtifacts.filesFor(databaseFile).forEach { file ->
            if (file.exists() && file.delete()) {
                deleted++
            }
        }
        return deleted
    }
}
