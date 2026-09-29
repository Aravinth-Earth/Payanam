//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.transfer

import io.payanam.shared.transfer.DatabaseTransferGateway
import io.payanam.shared.transfer.TransferOutcome
import io.payanam.shared.transfer.TransferRefusal
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The Android side of [DatabaseTransferGateway], over SAF.
 *
 * The Android-specific difference is that a picked source usually arrives as an **`InputStream` from a
 * content URI, not a file**: it cannot be opened in place, so it must be copied into app storage before it
 * can be checked. That reshapes the order — copy to a temp file, verify the temp file, then swap it in —
 * while preserving the rule that matters: the LIVE database is not touched until the passphrase has been
 * proven against the incoming bytes. A refusal still leaves the live database byte-identical.
 *
 * Every platform touch is injected (stream openers, the file verifier, the checkpoint), which keeps this
 * class free of Android API calls and therefore unit-testable as plain Kotlin. The verifier is
 * `DatabaseEncryptionMigrationSupport.canOpenWithSqlCipher` in production, which already opens the
 * candidate read-only.
 *
 * Unlike the desktop this side CANNOT tell "wrong passphrase" from "not one of our databases": the
 * SQLCipher open check answers one question. It therefore reports the passphrase verdict and lets the
 * caller's health check produce the rest, rather than claiming a distinction it did not measure.
 */
class AndroidDatabaseTransferGateway(
    private val liveDatabaseFile: () -> File,
    private val openSourceStream: (String) -> InputStream?,
    private val openDestinationStream: (String) -> OutputStream?,
    /** True only if [passphrase] opens [file]. Production: `canOpenWithSqlCipher`. */
    private val verifyFile: (File, String) -> Boolean,
    /**
     * Folds the write-ahead log and answers whether the log is now EMPTY.
     *
     * This was `() -> Unit` until the shared rule existed, which meant an Android export could not tell a
     * fold that emptied the log from one that was blocked or left frames behind, and copied either way.
     * Production satisfies it with `WalCheckpointRule.walIsEmpty(...)` over the open session's report, so
     * this side and the desktop refuse for the same measured reason. The Boolean is the rule's answer, not
     * a second opinion formed here.
     */
    private val checkpoint: () -> Boolean,
) : DatabaseTransferGateway {
    override suspend fun exportTo(destinationToken: String): TransferOutcome {
        val source = liveDatabaseFile()
        if (!source.isFile || !source.canRead()) return refuse(TransferRefusal.SOURCE_UNREADABLE)

        // Before the copy, not after: folding the log in afterwards would fold it into a file already
        // copied and export the pre-write snapshot this exists to avoid. A log that is still full stops
        // the export here, before anything is written to the destination.
        if (!checkpoint()) return refuse(TransferRefusal.CHECKPOINT_INCOMPLETE)

        val destination = openDestinationStream(destinationToken)
            ?: return refuse(TransferRefusal.DESTINATION_UNWRITABLE)
        return try {
            destination.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
            TransferOutcome.Succeeded(source.length())
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
        val live = liveDatabaseFile()
        val incoming = File(live.parentFile, "${live.name}.incoming")

        val source = openSourceStream(sourceToken) ?: return refuse(TransferRefusal.SOURCE_UNREADABLE)

        // Stage first: a content URI cannot be verified where it lives, so the bytes come into app storage
        // and are checked there. The live database is still untouched at this point.
        val staged =
            try {
                source.use { input -> incoming.outputStream().use { output -> input.copyTo(output) } }
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        if (!staged) {
            cleanup(incoming)
            return refuse(TransferRefusal.SOURCE_UNREADABLE)
        }

        // Verify the staged copy, read-only, before anything is replaced.
        if (!verifyFile(incoming, passphrase.concatToString())) {
            cleanup(incoming)
            return refuse(TransferRefusal.PASSPHRASE_REJECTED)
        }

        // Both files are in app storage, so the swap is a rename on one filesystem.
        return try {
            val bytes = incoming.length()
            live.delete()
            if (incoming.renameTo(live)) {
                TransferOutcome.Succeeded(bytes)
            } else {
                cleanup(incoming)
                refuse(TransferRefusal.REPLACE_FAILED)
            }
        } catch (_: SecurityException) {
            cleanup(incoming)
            refuse(TransferRefusal.REPLACE_FAILED)
        }
    }

    private fun cleanup(path: File) {
        try {
            path.delete()
        } catch (_: SecurityException) {
            // A leftover staged file is not worth failing a transfer over: the live database is untouched,
            // which is the property that matters.
        }
    }

    private fun refuse(reason: TransferRefusal) = TransferOutcome.Refused(reason)
}
