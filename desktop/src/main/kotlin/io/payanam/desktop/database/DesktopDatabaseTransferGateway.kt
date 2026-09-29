//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseOpenOutcome
import io.payanam.shared.database.PayanamDatabaseOpener
import io.payanam.shared.transfer.DatabaseTransferGateway
import io.payanam.shared.transfer.TransferOutcome
import io.payanam.shared.transfer.TransferRefusal
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The desktop's [DatabaseTransferGateway], over filesystem paths.
 *
 * Two rules are the whole point of this class, and the tests are written to fail if either is broken:
 *
 * **Nothing is replaced before the passphrase is verified.** The import path opens the source READ-ONLY
 * first, which both proves the passphrase and runs the schema guard. Only then does it touch the live
 * database. An import that copies first and fails the passphrase afterwards has already destroyed the data
 * it was meant to preserve.
 *
 * **The replacement is atomic.** The source is copied to a sibling temp file and then MOVED into place, so
 * a failure mid-copy leaves the previous database intact. A half-written database is unrecoverable; a stale
 * one is merely out of date.
 *
 * Read-only is used for verification even though a transfer is a copy: the check must not be able to change
 * the file it is checking, and the driver has no other mechanism (see the measured driver facts — the
 * `?mode=ro` URL parameter is silently ignored).
 */
internal class DesktopDatabaseTransferGateway(
    private val liveDatabasePath: () -> Path,
    private val opener: PayanamDatabaseOpener,
    /**
     * Folds any write-ahead log into the main database file, answering whether the log is now EMPTY.
     *
     * Injected rather than reached for, so the gateway has no opinion about how the database is held open —
     * and so a test can prove the fold runs BEFORE the copy without a real session. The answer is returned
     * rather than dropped because an export must not proceed on a log it could not fold: the copy would be
     * a snapshot missing the user's most recent writes. Production satisfies it with
     * `WalCheckpointRule.walIsEmpty(sessionController.checkpoint())`, so the emptiness decision here is the
     * shared rule's, not this class's.
     */
    private val checkpoint: () -> Boolean,
) : DatabaseTransferGateway {
    override suspend fun exportTo(destinationToken: String): TransferOutcome {
        val source = liveDatabasePath()
        val destination = pathOf(destinationToken) ?: return refuse(TransferRefusal.DESTINATION_UNWRITABLE)

        if (isSameFile(source, destination)) return refuse(TransferRefusal.SAME_FILE)
        if (!isReadableFile(source)) return refuse(TransferRefusal.SOURCE_UNREADABLE)

        // The log is folded in first: copying the main file while a -wal holds recent writes would export a
        // snapshot missing whatever the user just did, which reads as data loss. A fold that did not leave
        // the log empty stops the export here, before anything is written to the destination.
        if (!checkpoint()) return refuse(TransferRefusal.CHECKPOINT_INCOMPLETE)

        return try {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
            // Files.copy returns the destination Path, so the size is read back: the byte count is the only
            // cheap evidence the user gets that a backup contains anything.
            TransferOutcome.Succeeded(Files.size(destination))
        } catch (_: IOException) {
            refuse(TransferRefusal.DESTINATION_UNWRITABLE)
        } catch (_: SecurityException) {
            refuse(TransferRefusal.DESTINATION_UNWRITABLE)
        }
    }

    override suspend fun importFrom(
        sourceToken: String,
        passphrase: CharArray,
    ): TransferOutcome {
        val source = pathOf(sourceToken) ?: return refuse(TransferRefusal.SOURCE_UNREADABLE)
        val live = liveDatabasePath()

        if (isSameFile(source, live)) return refuse(TransferRefusal.SAME_FILE)
        if (!isReadableFile(source)) return refuse(TransferRefusal.SOURCE_UNREADABLE)

        // Verify BEFORE copying anything. A read-only open both proves the key and runs the schema guard, so
        // a wrong passphrase and a foreign file are distinguishable instead of both reporting "refused".
        verificationRefusal(source, passphrase)?.let { return it }

        val incoming = live.resolveSibling("${live.fileName}.incoming")
        return try {
            Files.copy(source, incoming, StandardCopyOption.REPLACE_EXISTING)
            Files.move(
                incoming,
                live,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            TransferOutcome.Succeeded(Files.size(live))
        } catch (_: AtomicMoveNotSupportedException) {
            // The filesystem cannot promise atomicity, and a non-atomic replacement is exactly the outcome
            // this design exists to avoid. Leave the live database alone.
            cleanup(incoming)
            refuse(TransferRefusal.REPLACE_FAILED)
        } catch (_: IOException) {
            cleanup(incoming)
            refuse(TransferRefusal.REPLACE_FAILED)
        } catch (_: SecurityException) {
            cleanup(incoming)
            refuse(TransferRefusal.REPLACE_FAILED)
        }
    }

    /**
     * The refusal for a source that cannot be imported, or null when it can.
     *
     * The mapping is the useful part: an `OPEN_FAILED` means the file did not decrypt, which is a passphrase
     * verdict; a version refusal means it IS a Payanam database this build cannot read; anything else means
     * it is not one of ours.
     */
    private suspend fun verificationRefusal(
        source: Path,
        passphrase: CharArray,
    ): TransferOutcome.Refused? =
        when (val outcome = opener.open(source.toString(), passphrase, readOnly = true)) {
            is DatabaseOpenOutcome.Opened -> {
                outcome.handle.close()
                null
            }

            is DatabaseOpenOutcome.Refused -> {
                when (outcome.issue.type) {
                    DatabaseBootIssueType.OPEN_FAILED -> refuse(TransferRefusal.PASSPHRASE_REJECTED)

                    DatabaseBootIssueType.DB_TOO_OLD,
                    DatabaseBootIssueType.DB_TOO_NEW,
                    -> refuse(TransferRefusal.INCOMPATIBLE_SCHEMA)

                    else -> refuse(TransferRefusal.NOT_A_PAYANAM_DATABASE)
                }
            }
        }

    private fun pathOf(token: String): Path? =
        try {
            if (token.isBlank()) null else Path.of(token)
        } catch (_: java.nio.file.InvalidPathException) {
            null
        }

    private fun isReadableFile(path: Path): Boolean = Files.isReadable(path) && Files.isRegularFile(path)

    /**
     * Whether the two paths name the same file.
     *
     * Falls back to a normalised comparison when the target does not exist yet, because the export case
     * checks a destination that may be new — and copying a database onto itself is never intended.
     */
    private fun isSameFile(
        first: Path,
        second: Path,
    ): Boolean =
        try {
            if (Files.exists(first) && Files.exists(second)) {
                Files.isSameFile(first, second)
            } else {
                first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize()
            }
        } catch (_: IOException) {
            first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize()
        }

    private fun cleanup(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // A leftover temp file is not worth failing a transfer over; the live database is untouched,
            // which is the property that matters.
        }
    }

    private fun refuse(reason: TransferRefusal) = TransferOutcome.Refused(reason)
}
