//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

/**
 * The ONE rule that decides whether a file looks like an encrypted Payanam database.
 *
 * ## Why this exists
 *
 * Android and the desktop each grew their own copy of "is this file encrypted?", and they drifted apart:
 * Android accepted any non-empty file that lacked the plaintext magic (`length > 0`), the desktop required
 * more than a header's worth of bytes (`length > 16`). The two answers disagreed on a 1-byte file — Android
 * called it encrypted and asked the user for a passphrase, the desktop called it not-a-database.
 *
 * A shared *constant* would have fixed today's number and left the drift one edit away. So the shared thing
 * is the DERIVATION, not the value: this object decides the verdict, and both platforms only *read bytes and
 * ask*. Neither platform holds a length threshold of its own any more, so they cannot disagree.
 *
 * ## The rule
 *
 * A file is detectably encrypted when it is long enough to have carried the plaintext header in the first
 * place, and its header is not that header. The threshold is derived from the magic's own length — the one
 * number here is a property of the SQLite file format, not a tuning knob either platform may re-pick.
 *
 * ## What each platform still owns
 *
 * Only the *handle* is platform-specific, and it deliberately stays that way: whether a file actually OPENS
 * (Room on Android, JDBC on the desktop) is measured by the platform and composed with the verdict below.
 * What crosses platforms is the format question, which is the same question on both sides.
 *
 * ## Invariant this classification assumes
 *
 * **Full-file encryption.** No `cipher_plaintext_header_size` (or equivalent partial-header setting) is
 * configured anywhere in Payanam, so an encrypted file cannot carry the plaintext magic. If that setting is
 * ever introduced, an encrypted file could match the magic and be misclassified — revisit this with it.
 */
object EncryptedFileClassifier {
    /**
     * The first 16 bytes of every unencrypted SQLite database: `"SQLite format 3\0"`.
     *
     * Its length is also the shortest file that could have carried it, which is why the minimum probe length
     * below is derived from it instead of being written out as a second number.
     */
    @JvmStatic
    val SQLITE_PLAINTEXT_MAGIC: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    /**
     * The shortest file worth classifying, in bytes: anything shorter cannot hold a header, so it is not a
     * database at all — encrypted or otherwise.
     */
    @JvmStatic
    val MINIMUM_PROBE_BYTES: Int = SQLITE_PLAINTEXT_MAGIC.size

    /**
     * Why a file got its verdict, so a non-encrypted answer is attributable in the logs instead of a
     * silent `false`.
     */
    enum class Reason {
        /** The file does not exist, or reported no length at all. */
        ABSENT,

        /** The file is shorter than a header, so it cannot be a database of either kind. */
        TOO_SHORT,

        /** The header could not be read. Treated as "not encrypted": asking for a passphrase we cannot use is worse. */
        READ_FAILED,

        /** The header was read and is the plaintext magic. */
        PLAINTEXT_MAGIC,

        /** The header was read and is not the plaintext magic — on a long enough file, that means encrypted. */
        NO_MAGIC,
    }

    /**
     * The verdict for one file, plus the reason it was reached.
     *
     * [isEncrypted] is the only field a caller branches on; [reason] exists so a log can explain a `false`.
     */
    data class Verdict(
        val isEncrypted: Boolean,
        val hasPlaintextMagic: Boolean,
        val reason: Reason,
    )

    /**
     * Classifies a file from the bytes a platform managed to read.
     *
     * @param header the first bytes of the file — as many as were read, possibly fewer than
     *   [SQLITE_PLAINTEXT_MAGIC] if the file is short, or `null` if the file is absent or the read failed.
     * @param totalLength the file's length in bytes.
     * @param readSucceeded whether [header] was obtained by an actual read. `false` downgrades a would-be
     *   encrypted answer to [Reason.READ_FAILED] — an unreadable file must not send the user to a passphrase
     *   prompt, because a wrong guess cannot be verified and the file is deleted on a failed attempt.
     */
    fun classify(
        header: ByteArray?,
        totalLength: Long,
        readSucceeded: Boolean = header != null,
    ): Verdict {
        if (header == null && !readSucceeded) {
            return Verdict(isEncrypted = false, hasPlaintextMagic = false, reason = Reason.ABSENT)
        }
        if (header == null) {
            return Verdict(isEncrypted = false, hasPlaintextMagic = false, reason = Reason.READ_FAILED)
        }
        // Derived, never restated: the same bytes that define the magic also define the shortest file that
        // could carry one. A file below that length is not a truncated database, it is not a database.
        if (totalLength < MINIMUM_PROBE_BYTES) {
            return Verdict(isEncrypted = false, hasPlaintextMagic = false, reason = Reason.TOO_SHORT)
        }
        if (header.contentEquals(SQLITE_PLAINTEXT_MAGIC)) {
            return Verdict(isEncrypted = false, hasPlaintextMagic = true, reason = Reason.PLAINTEXT_MAGIC)
        }
        return Verdict(isEncrypted = true, hasPlaintextMagic = false, reason = Reason.NO_MAGIC)
    }
}
