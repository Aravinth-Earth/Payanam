//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupSnapshotVerificationTest {
    private fun createSqliteFile(
        file: File,
        userVersion: Int,
    ) {
        val header = ByteArray(100)
        val magic = EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC
        magic.copyInto(header, 0)
        header[60] = ((userVersion shr 24) and 0xFF).toByte()
        header[61] = ((userVersion shr 16) and 0xFF).toByte()
        header[62] = ((userVersion shr 8) and 0xFF).toByte()
        header[63] = (userVersion and 0xFF).toByte()
        file.writeBytes(header)
    }

    @Test
    fun `readSnapshotUserVersion returns the user_version from a real SQLite file`() {
        val dbFile = File.createTempFile("test_snapshot", ".db")
        dbFile.deleteOnExit()
        createSqliteFile(dbFile, 42)

        assertThat(BackupSnapshotVerification.readSnapshotUserVersion(dbFile)).isEqualTo(42)
    }

    @Test
    fun `readSnapshotUserVersion throws for a file shorter than 64 bytes`() {
        val dbFile = File.createTempFile("test_short", ".db")
        dbFile.deleteOnExit()
        dbFile.writeBytes(ByteArray(32))

        try {
            BackupSnapshotVerification.readSnapshotUserVersion(dbFile)
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e.message).isEqualTo("Snapshot verification failed to read schema version")
        }
    }

    @Test
    fun `verifySnapshot passes for a valid SQLite file with positive user_version`() {
        val dbFile = File.createTempFile("test_valid", ".db")
        dbFile.deleteOnExit()
        createSqliteFile(dbFile, 1)

        BackupSnapshotVerification.verifySnapshot(dbFile)
    }

    @Test
    fun `verifySnapshot throws for a valid SQLite file with zero user_version`() {
        val dbFile = File.createTempFile("test_zero_version", ".db")
        dbFile.deleteOnExit()
        createSqliteFile(dbFile, 0)

        try {
            BackupSnapshotVerification.verifySnapshot(dbFile)
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e.message).isEqualTo("Snapshot verification failed to read schema version")
        }
    }

    @Test
    fun `verifySnapshot skips verification for a non-sqlite file`() {
        val dbFile = File.createTempFile("test_not_sqlite", ".db")
        dbFile.deleteOnExit()
        dbFile.writeBytes("This is not a SQLite database".toByteArray())

        BackupSnapshotVerification.verifySnapshot(dbFile)
    }

    @Test
    fun `verifySnapshot throws when the snapshot file is missing`() {
        // A missing snapshot is the failure the caller cannot distinguish from a good one downstream: the
        // backup step would report success over a file that is not there. The empty/missing guard is the
        // first thing the function checks, so it is pinned here rather than trusted to a caller.
        val missing = File(createTempDir(), "absent.db")

        val failure = runCatching { BackupSnapshotVerification.verifySnapshot(missing) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("Snapshot file was empty")
    }

    @Test
    fun `verifySnapshot throws when the snapshot file exists but is empty`() {
        // Length zero is the other half of the guard: the file is there, so a caller that only checked
        // existence would read a header from nothing. Both halves produce the same refusal.
        val empty = File.createTempFile("test_empty_snapshot", ".db")
        empty.deleteOnExit()
        assertThat(empty.exists()).isTrue()
        assertThat(empty.length()).isEqualTo(0L)

        val failure = runCatching { BackupSnapshotVerification.verifySnapshot(empty) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("Snapshot file was empty")
    }

    @Test
    fun `hasStandardSqliteHeader returns false when the file cannot be opened for reading`() {
        // The I/O-error branch. A file that throws on read must answer "not a plaintext SQLite file"
        // rather than propagating, so a caller classifying a half-written or unreadable file gets a
        // verdict instead of an exception. A non-existent path is the simplest guaranteed I/O failure.
        val unreadable = File(createTempDir(), "does-not-exist.db")

        assertThat(BackupSnapshotVerification.hasStandardSqliteHeader(unreadable)).isFalse()
    }

    @Test
    fun `readSnapshotUserVersion accepts a snapshot that is exactly 64 bytes`() {
        // The boundary of the length check: 64 bytes is enough to hold the user_version field, so it must
        // read the version rather than refuse. One byte fewer is the short-file case already covered
        // above; this pins the inclusive edge of `bytesRead < 64`.
        val dbFile = File.createTempFile("test_exact64", ".db")
        dbFile.deleteOnExit()
        createSqliteFile(dbFile, 7)
        // The helper writes a 100-byte header; trim the file to exactly the 64 bytes the reader needs.
        dbFile.writeBytes(dbFile.readBytes().copyOf(64))

        assertThat(BackupSnapshotVerification.readSnapshotUserVersion(dbFile)).isEqualTo(7)
    }

    private fun createTempDir(): File =
        Files.createTempDirectory("snapshot-verify").toFile().apply { deleteOnExit() }
}
