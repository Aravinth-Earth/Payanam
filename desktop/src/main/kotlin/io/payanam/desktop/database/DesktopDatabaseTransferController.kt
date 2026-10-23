//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.transfer.DatabaseTransferGateway
import io.payanam.shared.transfer.TransferOutcome

/**
 * What a transfer attempt produced.
 *
 * Distinct from [TransferOutcome] because two of these are the APP's preconditions, not facts about the
 * files: "there is no open database to export" and "the log was not folded" say nothing about which file is
 * good or bad. Keeping them out of the shared refusal vocabulary keeps that vocabulary about file-level
 * facts, so the Android side is not made to answer a question only the desktop's shape asks.
 */
internal sealed interface DesktopTransferOutcome {
    /** The transfer ran and the shared gateway answered. */
    data class Gateway(
        val outcome: TransferOutcome,
    ) : DesktopTransferOutcome

    /** Nothing is open, so there is no current database to export. */
    data object NothingToExport : DesktopTransferOutcome

    /**
     * The file was imported, but the session could not be established on it.
     *
     * A separate case because the transfer SUCCEEDED: the live database IS the imported one, and what
     * failed is opening it. Reporting it as a transfer refusal would send the user to pick a different file
     * when the file is already in place.
     */
    data class ImportedButUnopenable(
        val reason: String,
    ) : DesktopTransferOutcome
}

/**
 * Export and import for the desktop's encrypted database, in the order each one needs.
 *
 * The gateway already knows how to move the file; this class owns the two things around it that are not
 * file mechanics:
 *
 * - **Export requires an OPEN database.** The fold and the copy belong to the gateway; what the controller
 *   adds is the precondition, because "nothing is open" is app state and the gateway cannot see it. Without
 *   this the gateway would report an unreadable source, which sends the user to look at a file instead of at
 *   the session.
 * - **Import releases the live database before replacing it.** The verification step refuses to open a file
 *   with a `-wal`/`-shm` beside it — which is exactly what an open session leaves behind — and the
 *   replacement needs the file free. So the session is closed and the layer uninstalled first, and after a
 *   successful import the session is established on the imported file, so the repositories read the data
 *   the user just brought in rather than the one they replaced.
 */
internal class DesktopDatabaseTransferController(
    private val sessionController: DesktopDatabaseSessionController,
    private val gateway: DatabaseTransferGateway,
    private val logger: UnifiedLogger,
) {
    /**
     * Copies the live database to [destinationToken].
     *
     * The fold happens inside the gateway, immediately before the copy, so there is exactly one fold and it
     * cannot be separated from the copy it protects.
     */
    suspend fun exportTo(destinationToken: String): DesktopTransferOutcome {
        if (!sessionController.isOpen) {
            logger.w(
                "DesktopDatabaseTransferController.export",
                "Export refused: no database is open",
            )
            return DesktopTransferOutcome.NothingToExport
        }

        return DesktopTransferOutcome.Gateway(gateway.exportTo(destinationToken))
    }

    /** Replaces the live database with [sourceToken], then opens the result. */
    suspend fun importFrom(
        sourceToken: String,
        passphrase: CharArray,
    ): DesktopTransferOutcome {
        // Released BEFORE the transfer, for two reasons that both matter: the verification refuses to open
        // beside the sidecars an open session leaves, and the replacement wants the file free. A refusal
        // from here on therefore leaves the live database exactly as it was.
        sessionController.closeAndUninstall()

        val outcome = gateway.importFrom(sourceToken, passphrase)
        if (outcome !is TransferOutcome.Succeeded) {
            // Nothing was replaced. The database the user had is still closed, which is a state they can
            // recover from by unlocking again; leaving a half-open session would be worse.
            logger.w(
                "DesktopDatabaseTransferController.import",
                "Import refused; the live database was not replaced",
                mapOf("reason" to (outcome as? TransferOutcome.Refused)?.reason?.name),
            )
            return DesktopTransferOutcome.Gateway(outcome)
        }

        val reopened = sessionController.openAndInstall(passphrase)
        return when (reopened) {
            is DatabaseOpenResult.Opened -> {
                logger.i(
                    "DesktopDatabaseTransferController.import",
                    "Database imported and re-opened",
                    mapOf("byteCount" to outcome.byteCount),
                )
                DesktopTransferOutcome.Gateway(outcome)
            }

            is DatabaseOpenResult.Refused -> {
                // Named honestly: the file IS in place, so the user must not be told to pick another one.
                DesktopTransferOutcome.ImportedButUnopenable(reopened.issue.type.name)
            }
        }
    }
}
