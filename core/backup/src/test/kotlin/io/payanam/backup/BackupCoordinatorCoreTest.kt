//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.backup

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.port.BackupCheckpointPort
import io.payanam.domain.port.BackupRetryPolicy
import io.payanam.domain.port.BackupTrigger
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupCoordinatorCoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createDummyDbFile(): File {
        val dbFile = tempFolder.newFile("test.db")
        // Write a minimal SQLite header: 100 bytes, magic at start, user_version=22 at offset 60
        val header = ByteArray(100)
        // SQLite magic: "SQLite format 3\0"
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        magic.copyInto(header)
        // user_version at offset 60 (big-endian int32)
        header[60] = 0
        header[61] = 0
        header[62] = 0
        header[63] = 22
        dbFile.writeBytes(header)
        return dbFile
    }

    private fun createCheckpointPort(walEmpty: Boolean): BackupCheckpointPort =
        object : BackupCheckpointPort {
            override fun isDbOpen(): Boolean = true

            override fun foldWal(): WalCheckpointRule.FoldReport =
                if (walEmpty) {
                    WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 0)
                } else {
                    WalCheckpointRule.FoldReport(busy = 0, logFrames = 5, checkpointedFrames = 0)
                }
        }

    @Test
    fun `createSnapshot succeeds on first attempt when WAL is empty`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            val checkpointPort = createCheckpointPort(walEmpty = true)
            val retryPolicy = BackupRetryPolicy(maxAttempts = 3, retryDelayMs = 10L)
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            val result = coordinator.createSnapshot(BackupTrigger.MANUAL)

            assertThat(result.attemptsUsed).isEqualTo(1)
            assertThat(result.snapshotFile.exists()).isTrue()
            assertThat(result.snapshotFile.length()).isEqualTo(100L)
        }

    @Test
    fun `createSnapshot retries when WAL is not empty`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            // First call: WAL not empty, second call: WAL empty
            var callCount = 0
            val checkpointPort =
                object : BackupCheckpointPort {
                    override fun isDbOpen(): Boolean = true

                    override fun foldWal(): WalCheckpointRule.FoldReport {
                        callCount++
                        return if (callCount == 1) {
                            WalCheckpointRule.FoldReport(busy = 0, logFrames = 5, checkpointedFrames = 0)
                        } else {
                            WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 0)
                        }
                    }
                }
            val retryPolicy = BackupRetryPolicy(maxAttempts = 3, retryDelayMs = 10L)
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            val result = coordinator.createSnapshot(BackupTrigger.MANUAL)

            assertThat(result.attemptsUsed).isEqualTo(2)
            assertThat(result.snapshotFile.exists()).isTrue()
        }

    @Test
    fun `createSnapshot throws when all attempts fail`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            val checkpointPort = createCheckpointPort(walEmpty = false)
            val retryPolicy = BackupRetryPolicy(maxAttempts = 2, retryDelayMs = 10L)
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            try {
                coordinator.createSnapshot(BackupTrigger.MANUAL)
                assertThat("Expected IllegalStateException").isEmpty()
            } catch (e: IllegalStateException) {
                assertThat(e.message).contains("Refusing backup snapshot")
            }
        }

    @Test
    fun `createSnapshot throws when database file does not exist`() =
        runBlocking {
            val dbFile = File(tempFolder.root, "nonexistent.db")
            val snapshotDir = tempFolder.newFolder("snapshots")
            val checkpointPort = createCheckpointPort(walEmpty = true)
            val retryPolicy = BackupRetryPolicy(maxAttempts = 3, retryDelayMs = 10L)
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            try {
                coordinator.createSnapshot(BackupTrigger.MANUAL)
                assertThat("Expected IllegalStateException").isEmpty()
            } catch (e: IllegalStateException) {
                assertThat(e.message).contains("Database file not found")
            }
        }

    @Test
    fun `createSnapshot throws pending pages when WAL non-empty and db open`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val walFile = File(dbFile.parent, dbFile.name + DatabaseArtifacts.WAL_SUFFIX)
            walFile.writeBytes(ByteArray(16) { 0x42 })
            val snapshotDir = tempFolder.newFolder("snapshots")
            val checkpointPort = createCheckpointPort(walEmpty = true)
            val retryPolicy = BackupRetryPolicy(maxAttempts = 1, retryDelayMs = 10L)
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            try {
                coordinator.createSnapshot(BackupTrigger.MANUAL)
                assertThat("Expected IllegalStateException").isEmpty()
            } catch (e: IllegalStateException) {
                assertThat(e.message).contains("WAL still contains pending pages after checkpoint")
            }
        }

    @Test
    fun `createSnapshot throws session closed when WAL non-empty and db closed`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val walFile = File(dbFile.parent, dbFile.name + DatabaseArtifacts.WAL_SUFFIX)
            walFile.writeBytes(ByteArray(16) { 0x42 })
            val snapshotDir = tempFolder.newFolder("snapshots")
            val checkpointPort =
                object : BackupCheckpointPort {
                    override fun isDbOpen(): Boolean = false

                    override fun foldWal(): WalCheckpointRule.FoldReport =
                        WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 0)
                }
            val retryPolicy = BackupRetryPolicy(maxAttempts = 1, retryDelayMs = 10L)
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            try {
                coordinator.createSnapshot(BackupTrigger.MANUAL)
                assertThat("Expected IllegalStateException").isEmpty()
            } catch (e: IllegalStateException) {
                assertThat(e.message).contains("Database session is closed and WAL still has pending data")
            }
        }

    @Test
    fun `checkpointIfPossible skips fold when db closed`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            var foldCallCount = 0
            val checkpointPort =
                object : BackupCheckpointPort {
                    override fun isDbOpen(): Boolean = false

                    override fun foldWal(): WalCheckpointRule.FoldReport {
                        foldCallCount++
                        return WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 0)
                    }
                }
            val retryPolicy = BackupRetryPolicy(maxAttempts = 1, retryDelayMs = 10L)
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            val result = coordinator.createSnapshot(BackupTrigger.MANUAL)

            assertThat(foldCallCount).isEqualTo(0)
            assertThat(result.attemptsUsed).isEqualTo(1)
        }

    @Test
    fun `createSnapshot rethrows last error on retry exhaustion`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            val checkpointPort =
                object : BackupCheckpointPort {
                    override fun isDbOpen(): Boolean = true

                    override fun foldWal(): WalCheckpointRule.FoldReport = throw RuntimeException("checkpoint exploded")
                }
            val retryPolicy = BackupRetryPolicy(maxAttempts = 3, retryDelayMs = 10L)
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            val result = runCatching { coordinator.createSnapshot(BackupTrigger.MANUAL) }
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).isEqualTo("checkpoint exploded")
        }

    @Test
    fun `backupToAppBackupDirectory success copies snapshot and records success`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            val backupDir = File(tempFolder.root, "backups")
            val checkpointPort = createCheckpointPort(walEmpty = true)
            val retryPolicy = BackupRetryPolicy(maxAttempts = 3, retryDelayMs = 10L)
            var capturedRecordedAt: Long? = null
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = backupDir,
                    sessionFolderName = { "auto_bk_session" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { capturedRecordedAt = it },
                    recordFailure = { },
                )

            val result = coordinator.backupToAppBackupDirectory(BackupTrigger.MANUAL)

            val sessionDir = File(backupDir, "auto_bk_session")
            assertThat(result.destinationPath).isEqualTo(sessionDir.absolutePath)
            assertThat(File(sessionDir, "payanam.db").exists()).isTrue()
            assertThat(capturedRecordedAt).isNotNull()
            assertThat(result.recordedAtMillis).isEqualTo(capturedRecordedAt)
            assertThat(result.attemptsUsed).isEqualTo(1)
        }

    @Test
    fun `backupToAppBackupDirectory failure records failure and throws`() =
        runBlocking {
            val dbFile = File(tempFolder.root, "nonexistent.db")
            val snapshotDir = tempFolder.newFolder("snapshots")
            val backupDir = File(tempFolder.root, "backups")
            val checkpointPort = createCheckpointPort(walEmpty = true)
            val retryPolicy = BackupRetryPolicy(maxAttempts = 3, retryDelayMs = 10L)
            var capturedFailureMessage: String? = null
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = backupDir,
                    sessionFolderName = { "auto_bk_session" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { capturedFailureMessage = it },
                )

            try {
                coordinator.backupToAppBackupDirectory(BackupTrigger.MANUAL)
                assertThat("Expected IllegalStateException").isEmpty()
            } catch (e: IllegalStateException) {
                assertThat(e.message).contains("Database file not found")
                assertThat(capturedFailureMessage).isEqualTo(e.message)
            }
        }

    @Test
    fun `backupToAppBackupDirectory rotation prunes old artifacts but keeps non-artifacts`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            val backupDir = File(tempFolder.root, "backups")
            // Pre-create an old backup artifact and a non-artifact file
            val oldArtifactDir = File(backupDir, "auto_bk_20200101_000000").apply { mkdirs() }
            File(oldArtifactDir, "old.db").writeBytes(ByteArray(10))
            val keepFile = File(backupDir, "keepme.txt").apply { writeText("keep") }
            val checkpointPort = createCheckpointPort(walEmpty = true)
            val retryPolicy = BackupRetryPolicy(maxAttempts = 3, retryDelayMs = 10L)
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = checkpointPort,
                    retryPolicy = retryPolicy,
                    logger = NoOpUnifiedLogger,
                    backupDirectory = backupDir,
                    sessionFolderName = { "auto_bk_session" },
                    destinationFileName = "payanam.db",
                    rotationRows = {
                        mapOf(
                            AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to "true",
                            AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "1",
                        )
                    },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            coordinator.backupToAppBackupDirectory(BackupTrigger.MANUAL)

            assertThat(oldArtifactDir.exists()).isFalse()
            assertThat(keepFile.exists()).isTrue()
        }

    @Test
    fun `backup succeeds even when rotation rows throw`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            var succeeded = false
            var failed = false
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = createCheckpointPort(walEmpty = true),
                    retryPolicy = BackupRetryPolicy(maxAttempts = 1, retryDelayMs = 1L),
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    // The policy read is best-effort: a throwing reader must be logged by
                    // cleanup and must never fail a backup that already succeeded.
                    rotationRows = { throw IllegalStateException("rotation read failed") },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { succeeded = true },
                    recordFailure = { failed = true },
                )

            val result = coordinator.backupToAppBackupDirectory(BackupTrigger.MANUAL)

            assertThat(result.destinationPath).isNotEmpty()
            assertThat(succeeded).isTrue()
            assertThat(failed).isFalse()
        }

    @Test
    fun `createSnapshot with zero attempts fails with the unknown-retry fallback`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = createCheckpointPort(walEmpty = true),
                    retryPolicy = BackupRetryPolicy(maxAttempts = 0, retryDelayMs = 1L),
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed: ${error.message}" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            try {
                coordinator.createSnapshot(BackupTrigger.MANUAL)
                assertThat("Expected IllegalStateException").isEmpty()
            } catch (e: IllegalStateException) {
                assertThat(e.message).contains("unknown reason")
            }
        }

    @Test
    fun `failure with a null message logs the simple class name`() =
        runBlocking {
            val dbFile = createDummyDbFile()
            val snapshotDir = tempFolder.newFolder("snapshots")
            val throwingPort = object : BackupCheckpointPort {
                override fun isDbOpen(): Boolean = true
                // No message: the logger's fallback path must use the simple class name.
                override fun foldWal(): WalCheckpointRule.FoldReport = throw IllegalStateException()
            }
            val coordinator =
                BackupCoordinatorCore(
                    dbFile = dbFile,
                    tempSnapshotDir = snapshotDir,
                    checkpointPort = throwingPort,
                    retryPolicy = BackupRetryPolicy(maxAttempts = 1, retryDelayMs = 1L),
                    logger = NoOpUnifiedLogger,
                    backupDirectory = java.io.File(tempFolder.root, "backups"),
                    sessionFolderName = { "auto_bk_test" },
                    destinationFileName = "payanam.db",
                    rotationRows = { emptyMap() },
                    failureMessage = { _, _, error -> "backup failed" },
                    recordSuccess = { },
                    recordFailure = { },
                )

            try {
                coordinator.backupToAppBackupDirectory(BackupTrigger.MANUAL)
                assertThat("Expected IllegalStateException").isEmpty()
            } catch (e: IllegalStateException) {
                assertThat(e.message).contains("backup failed")
            }
        }
}
