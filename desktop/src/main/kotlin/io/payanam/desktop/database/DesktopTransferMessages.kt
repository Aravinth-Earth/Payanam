//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.shared.transfer.TransferOutcome
import io.payanam.shared.transfer.TransferRefusal

/**
 * What the user is told about a transfer.
 *
 * Separated from the controllers because this is the one place where a refusal can be turned into a wrong
 * instruction: the refusals are distinct precisely because the user's next step differs, and a message that
 * collapses two of them ("import failed") sends them to fix the wrong thing. The rule enforced here is that
 * **every refusal says whether the live database was replaced**, because that is the fact a user needs to
 * decide what to do next.
 *
 * The screen that renders these lands with V5, which sources its copy from the shared string table (EN/TA).
 * These are the English strings that move into it.
 */
internal object DesktopTransferMessages {
    /** A finished export. */
    fun exportSucceeded(
        byteCount: Long,
        destination: String,
    ): String = "Exported ${formatBytes(byteCount)} to $destination"

    /** A finished import. */
    fun importSucceeded(byteCount: Long): String = "Imported ${formatBytes(byteCount)}. This is your database now."

    /** Which way a transfer was going, so a success can be phrased without guessing from an empty path. */
    enum class TransferDirection { EXPORT, IMPORT }

    /**
     * The outcome, phrased as the user's next step.
     *
     * [destination] is read only for a successful export, where the path IS the useful part of the answer.
     */
    fun describe(
        outcome: DesktopTransferOutcome,
        direction: TransferDirection,
        destination: String? = null,
    ): String =
        when (outcome) {
            is DesktopTransferOutcome.Gateway -> {
                when (val gatewayOutcome = outcome.outcome) {
                    is TransferOutcome.Succeeded -> {
                        when (direction) {
                            TransferDirection.EXPORT -> {
                                exportSucceeded(gatewayOutcome.byteCount, destination.orEmpty())
                            }

                            TransferDirection.IMPORT -> {
                                importSucceeded(gatewayOutcome.byteCount)
                            }
                        }
                    }

                    is TransferOutcome.Refused -> {
                        refusal(gatewayOutcome.reason)
                    }
                }
            }

            DesktopTransferOutcome.NothingToExport -> {
                when (direction) {
                    TransferDirection.EXPORT -> "No database is open, so there is nothing to export. Unlock first."

                    // Not reachable from the import path, which never requires an open database - the message
                    // says so plainly rather than inventing a user-facing cause.
                    TransferDirection.IMPORT -> "Nothing was imported."
                }
            }

            is DesktopTransferOutcome.ImportedButUnopenable -> {
                "The file was imported, but it could not be opened (${openFailure(outcome.reason)}). " +
                    "It is already your database file, so unlocking again will use it."
            }
        }

    private fun refusal(reason: TransferRefusal): String =
        when (reason) {
            TransferRefusal.PASSPHRASE_REJECTED -> {
                "That file did not open with your passphrase. Nothing was replaced."
            }

            TransferRefusal.NOT_A_PAYANAM_DATABASE -> {
                "That file is not a Payanam database. Nothing was replaced."
            }

            TransferRefusal.INCOMPATIBLE_SCHEMA -> {
                "That backup comes from a version of the app this build cannot read. Nothing was replaced."
            }

            TransferRefusal.SOURCE_UNREADABLE -> {
                "That file could not be read. Nothing was replaced."
            }

            TransferRefusal.DESTINATION_UNWRITABLE -> {
                "That location could not be written to. Nothing was replaced."
            }

            TransferRefusal.SAME_FILE -> {
                "The source and the destination are the same file. Nothing was replaced."
            }

            TransferRefusal.REPLACE_FAILED -> {
                "The imported database could not be put into place. Your previous database is still the live one."
            }

            TransferRefusal.CHECKPOINT_INCOMPLETE -> {
                // Deliberately NOT "nothing was replaced": this refusal is about the file we were about to
                // WRITE, and the user's fix is to try again rather than to choose a different file.
                "Recent changes could not be folded into the database file, so nothing was written. Try again."
            }
        }

    /**
     * The open failure, in words rather than an enum name.
     *
     * An unknown type keeps a neutral sentence rather than leaking an internal name into the UI; adding a type
     * without deciding what the user is told is the mistake this avoids.
     */
    private fun openFailure(reason: String): String =
        when (reason) {
            "DB_TOO_NEW" -> "it comes from a newer version of the app"
            "DB_TOO_OLD" -> "it comes from a version this build can no longer migrate"
            "NOT_FOUND" -> "the file is no longer there"
            else -> "this build could not read it"
        }

    private fun formatBytes(byteCount: Long): String =
        when {
            byteCount < BYTES_PER_KB -> "$byteCount bytes"
            byteCount < BYTES_PER_MB -> "${byteCount / BYTES_PER_KB} KB"
            else -> "${byteCount / BYTES_PER_MB} MB"
        }

    private const val BYTES_PER_KB = 1024L
    private const val BYTES_PER_MB = BYTES_PER_KB * 1024
}
