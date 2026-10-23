//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import java.io.File

/**
 * Can this file be read as an encrypted Payanam database, and can it be made so?
 *
 * The import path asks two questions of every file a user picks, and the ANSWERS are platform-neutral — the
 * same file format on both sides. What is not shareable is the handle: Android inspects through Room and a
 * `Context`, the desktop through JDBC. So each platform answers for its own file and this contract carries
 * only the questions.
 *
 * The re-encryption half is here for the CREATE path, not the import path: a first run builds its database
 * with the passphrase it was given, and both platforms need that same capability. Import asks the opposite
 * question and is answered elsewhere — a plaintext file offered to import is REFUSED on both platforms,
 * because a Payanam export IS the encrypted file and no version of it can produce a plaintext one.
 */
interface DatabaseEncryptionProbe {
    /**
     * Whether [databaseFile] looks like an encrypted Payanam database.
     *
     * False means either "plaintext" or "not a database" — the same answer Android gives, because one open
     * attempt cannot separate them. A caller that needs the distinction must ask the open itself.
     */
    fun isDetectablyEncrypted(
        databaseFile: File,
        logTag: String = "DatabaseEncryptionProbe",
    ): Boolean

    /** Whether [passphrase] opens [databaseFile]. The read-only check, and the one import verifies with. */
    fun canOpen(
        databaseFile: File,
        passphrase: String,
        logTag: String = "DatabaseEncryptionProbe",
    ): Boolean

    /**
     * Re-encrypts [databaseFile] in place with [passphrase].
     *
     * Android's implementation checkpoints the log, rekeys a copy, verifies the copy opens, then swaps it.
     * A platform that has no plaintext path may refuse — and must say so rather than report success.
     */
    fun ensureEncrypted(
        databaseFile: File,
        passphrase: String,
        logTag: String = "DatabaseEncryptionProbe",
    )

    /**
     * Row counts per table, read from [databaseFile] with [passphrase] (null/blank for a plaintext file).
     *
     * This is the import integrity check: the same counts are read before and after a conversion, and a
     * difference means the conversion lost data. An unreadable table reports 0 rather than throwing, because
     * a file that cannot be counted is a refusal the caller reports, not a crash mid-import.
     */
    fun readTableCounts(
        databaseFile: File,
        passphrase: String?,
        tableNames: List<String>,
    ): Map<String, Int>
}
