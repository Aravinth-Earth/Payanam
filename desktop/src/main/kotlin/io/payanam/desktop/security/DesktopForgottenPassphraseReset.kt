//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import io.payanam.desktop.DesktopPassphraseActionResult
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.ForgottenPassphraseReset
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.Report
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop's [ForgottenPassphraseReset].
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
) : ForgottenPassphraseReset {
    private val logger = AppLogger.current

    override fun reset(report: (Report) -> Unit) {
        var deleted = 0
        DatabaseArtifacts.filesFor(databaseFile.toFile()).forEach { file ->
            val path = file.toPath()
            if (!Files.exists(path)) {
                return@forEach
            }
            val wasDeleted = runCatching { Files.deleteIfExists(path) }.getOrElse { error ->
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
            mapOf("artifactsDeleted" to deleted, "credentialCleared" to credentialCleared),
        )
        report(Report(artifactsDeleted = deleted, credentialCleared = credentialCleared))
    }
}
