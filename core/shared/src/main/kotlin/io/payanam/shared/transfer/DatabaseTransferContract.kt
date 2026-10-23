//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.transfer

/**
 * Moving the encrypted database in and out of the app.
 *
 * There is exactly ONE artifact: the SQLCipher-encrypted database file. Export copies it out, import copies
 * it in. Nothing structured is produced, because a JSON or zip export would be a second data format with a
 * second set of bugs, and the project's data rule is that no plaintext form of the data exists anywhere —
 * in the app, on export, or on import.
 *
 * **A source or destination is a TOKEN, not a path.** The two platforms genuinely differ here: Android
 * receives a SAF `content://` URI the user picked, and the desktop receives a filesystem path. A shared
 * contract cannot say `Path` without excluding Android, or `Uri` without excluding the desktop, so it says
 * "the platform's own handle, opaque to this layer" and each implementation interprets it. That is the
 * difference the register lists under file in/out; it is legitimate and it is contained here.
 *
 * **Verification happens BEFORE anything is replaced.** This is the whole reason the two entry points exist
 * separately on the opener: an import that copies first and discovers the passphrase is wrong afterwards has
 * already destroyed the data it was supposed to preserve. Every implementation must answer "is this
 * passphrase correct for this file" first, and a refusal must leave the live database byte-identical.
 */
interface DatabaseTransferGateway {
    /**
     * Copies the live database to [destinationToken].
     *
     * A write-ahead log, if one exists, must be folded into the main file first: copying the database
     * without it would export a snapshot missing the most recent writes, which looks like data loss to the
     * user who just made them.
     */
    suspend fun exportTo(destinationToken: String): TransferOutcome

    /**
     * Copies the database at [sourceToken] in, replacing the live one — but only after
     * [passphrase] has been verified against that exact file.
     *
     * The replacement must be atomic: a failure part-way through must leave the previous database intact
     * rather than a half-written one, because a half-written database is unrecoverable while a stale one is
     * merely out of date.
     */
    suspend fun importFrom(
        sourceToken: String,
        passphrase: CharArray,
    ): TransferOutcome
}

/** The result of a transfer. */
sealed interface TransferOutcome {
    /**
     * The transfer completed.
     *
     * [byteCount] is the size of the database that was moved — reported because it is the only cheap,
     * user-meaningful evidence that a backup contains anything.
     */
    data class Succeeded(
        val byteCount: Long,
    ) : TransferOutcome

    /** Nothing was moved, for the reason in [reason]. */
    data class Refused(
        val reason: TransferRefusal,
    ) : TransferOutcome
}

/**
 * Why a transfer did not happen.
 *
 * Every case is a state the user can be told about in their own words, which is why the contract names them
 * instead of returning a boolean or an exception: "your passphrase is wrong" and "that file is not a
 * Payanam database" call for different copy, and conflating them sends the user to fix the wrong thing.
 */
enum class TransferRefusal {
    /** The file could not be read at all. */
    SOURCE_UNREADABLE,

    /** The destination could not be written at all. */
    DESTINATION_UNWRITABLE,

    /** The passphrase does not open this file. Nothing was replaced. */
    PASSPHRASE_REJECTED,

    /** Readable, but not a Payanam database — wrong file entirely. */
    NOT_A_PAYANAM_DATABASE,

    /**
     * A Payanam database, opened by the passphrase, but at a schema version this build does not implement.
     *
     * Kept separate from [NOT_A_PAYANAM_DATABASE] because the user's fix is different and so is the copy:
     * "this backup came from a newer version of the app" is a wait-or-update problem, while "this is not a
     * Payanam backup" is a wrong-file problem. Reporting one as the other sends them to fix the wrong thing.
     */
    INCOMPATIBLE_SCHEMA,

    /** The source and the destination are the same file; copying a file onto itself is never intended. */
    SAME_FILE,

    /** The file moved, but could not be put into place. The previous database is still the live one. */
    REPLACE_FAILED,

    /**
     * The write-ahead log did not fold, so the main file does not hold every committed write.
     *
     * Its own case because the user's fix is different from every other refusal: the file they chose may be
     * perfectly good — the log simply could not be folded before it was copied, which is a "try again"
     * rather than a "pick something else".
     */
    CHECKPOINT_INCOMPLETE,
}
