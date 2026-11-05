//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import io.payanam.domain.port.DatabaseForgotPassphraseReset
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.Report
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop's [DatabaseForgotPassphraseReset].
 *
 * This is a REAL implementation, not one of `DesktopUnsupportedPorts`' refusals, and the reason is that the
 * desktop genuinely holds a local database file that a user can lose the passphrase to. The file is passed
 * in rather than resolved here so the caller supplies the same path the desktop OPENS the database at — the
 * reset then cannot delete a file the app never used, which is the failure a hardcoded second copy of the
 * path would risk.
 *
 * The order matches Android's adapter — artifacts first, credential second — and for the same reason: a reset
 * that cleared the credential first and then failed to delete would leave the user with a working unlock and a
 * database they cannot open.
 */
internal class DesktopForgottenPassphraseReset(
    private val vault: PassphraseVault,
    private val databaseFile: Path,
) : DatabaseForgotPassphraseReset {
    private val logger = AppLogger.current

    override fun reset(): Report {
        // The family is enumerated ONCE, before anything is removed, so the report can say how many members
        // this platform tried to delete rather than only how many went. Without the second count a caller
        // cannot tell an absent member from one this process failed to remove, so `Report.succeeded` would
        // be a claim about the credential alone — and a user told their data is gone while a `-wal` they
        // cannot see is still readable beside the wiped main file is exactly the failure this port exists to
        // prevent.
        //
        // "Tried" means the members that EXISTED, not the four the family always enumerates: a fresh install
        // has none, and counting the ones never there would make zero deletions look like a partial failure
        // on every first run.
        val present = DatabaseArtifacts.filesFor(databaseFile.toFile()).filter { Files.exists(it.toPath()) }
        val attempted = present.size
        var deleted = 0
        present.forEach { file ->
            val path = file.toPath()
            val wasDeleted =
                runCatching { Files.deleteIfExists(path) }.getOrElse { error ->
                    // Logged, not swallowed: "we tried to delete your data and could not" is the single most
                    // important thing this method can report, and a count that quietly omits it is a lie.
                    logger.e(
                        "DesktopForgottenPassphraseReset.reset",
                        "Failed to delete a database artifact during the forgot-passphrase reset",
                        error,
                        mapOf("artifact" to path.fileName.toString()),
                    )
                    false
                }
            if (wasDeleted) {
                deleted++
            }
        }
        val credentialCleared =
            runCatching {
                vault.clearPassphrase()
                true
            }.getOrElse { error ->
                logger.e(
                    "DesktopForgottenPassphraseReset.reset",
                    "Deleted the database but failed to clear the passphrase credential",
                    error,
                    mapOf("artifactsDeleted" to deleted),
                )
                false
            }
        logger.w(
            "DesktopForgottenPassphraseReset.reset",
            "Forgot-passphrase reset executed",
            mapOf(
                "artifactsDeleted" to deleted,
                "artifactsAttempted" to attempted,
                "credentialCleared" to credentialCleared,
            ),
        )
        return Report(
            artifactsDeleted = deleted,
            artifactsAttempted = attempted,
            credentialCleared = credentialCleared,
        )
    }
}
