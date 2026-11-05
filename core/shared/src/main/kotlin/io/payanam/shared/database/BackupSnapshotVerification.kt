//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import java.io.File

/**
 * Snapshot verification logic extracted from the Android `DatabaseBackupCoordinator`.
 *
 * Per the backup-coordinator extraction plan (Q5 ruling): common LOGIC is promoted to
 * `core/shared` while paths remain platform-specific (injected). This object owns the
 * pure-JVM half of that ruling — the three functions that inspect a snapshot file's
 * bytes without any Android/Room dependency. The Android coordinator delegates here;
 * the desktop backup path can do the same without a look-alike copy.
 *
 * All three functions operate on [File] and read only the SQLite header bytes, so
 * they are safe to call from any platform that can supply a file path.
 */
object BackupSnapshotVerification {
    /**
     * Verifies that [snapshot] is a non-empty file and, if it carries the standard
     * SQLite header, that its in-header `user version` is positive.
     *
     * A non-SQLite file (no standard header) passes silently — the caller is
     * expected to have already classified the file before calling this.
     *
     * @throws IllegalStateException if the file is empty or the user_version is <= 0.
     */
    fun verifySnapshot(snapshot: File) {
        if (!snapshot.exists() || snapshot.length() == 0L) {
            throw IllegalStateException("Snapshot file was empty")
        }
        if (hasStandardSqliteHeader(snapshot)) {
            val userVersion = readSnapshotUserVersion(snapshot)
            if (userVersion <= 0) {
                throw IllegalStateException("Snapshot verification failed to read schema version")
            }
        }
    }

    /**
     * Reads the SQLite in-header `user version` directly from the snapshot bytes.
     *
     * A snapshot check needs only the version number, so it reads the header field
     * rather than opening the file through any platform API. The `user version` is a
     * big-endian int32 at byte offset 60 (0x3C) in the SQLite file header.
     *
     * @throws IllegalStateException if the file is shorter than 64 bytes.
     */
    @Suppress("MagicNumber") // SQLite header layout constants (see the KDoc above), not arbitrary values.
    fun readSnapshotUserVersion(snapshot: File): Int {
        val header = ByteArray(64)
        val bytesRead = snapshot.inputStream().use { it.read(header) }
        if (bytesRead < 64) {
            throw IllegalStateException("Snapshot verification failed to read schema version")
        }
        return ((header[60].toInt() and 0xFF) shl 24) or
            ((header[61].toInt() and 0xFF) shl 16) or
            ((header[62].toInt() and 0xFF) shl 8) or
            (header[63].toInt() and 0xFF)
    }

    /**
     * Returns `true` if [databaseFile] begins with the standard SQLite plaintext
     * magic bytes, `false` otherwise (including any I/O error).
     */
    fun hasStandardSqliteHeader(databaseFile: File): Boolean =
        try {
            val header = ByteArray(16)
            val bytesRead = databaseFile.inputStream().use { it.read(header) }
            // The shared declaration's bytes, not a restated literal: two copies of the magic is how two
            // files come to disagree about what counts as plaintext.
            val sqliteMagic = EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC
            bytesRead >= sqliteMagic.size && header.copyOf(sqliteMagic.size).contentEquals(sqliteMagic)
        } catch (_: Exception) {
            false
        }
}
