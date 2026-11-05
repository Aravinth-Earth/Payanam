//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.settings

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems

/**
 * The context-free database-artifact helpers, exercised on a real temp directory.
 *
 * These were promoted out of the app's `SettingsDatabaseArtifactSupport` so BOTH platforms run them,
 * but nothing here needs Android: every function takes a `File` and does real filesystem work. So the
 * tests use a real directory and drive the branches that a happy path misses — the absent parent, a
 * parent that is not a directory, a copy whose source is gone, a backup that is missing at restore, and
 * a backup that cannot be deleted — because those are the arms a user hits when an import is
 * interrupted halfway, and they are the arms no unit test could reach while the logic sat behind a
 * `Context`.
 *
 * The logger is installed for the whole class because two of the functions report through
 * `AppLogger.current`, and "warns that the directory is unavailable" is part of what they promise.
 */
class SettingsDatabaseArtifactsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var logger: RecordingLogger

    @Before
    fun installLogger() {
        AppLogger.resetForTests()
        logger = RecordingLogger()
        AppLogger.install(logger)
    }

    @After
    fun resetLogger() {
        AppLogger.resetForTests()
    }

    private fun dbFile(): File = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME)

    // -------------------------------------------------------------------------
    // listDatabaseArtifactFiles
    // -------------------------------------------------------------------------

    @Test
    fun `listing keeps only files whose name starts with the database name, sorted`() {
        dbFile().writeText("main")
        File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}-wal").writeText("wal")
        // A backup still carries the database name as its prefix, so it is listed (it is what the UI
        // then flags as stale) — the filter is name-prefix, not "is an active artifact".
        File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}.bak").writeText("bak")
        File(temporaryFolder.root, "unrelated.txt").writeText("x")
        // A directory with the right prefix is not a file and must not be listed.
        File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}-dir").mkdirs()

        val listed = listDatabaseArtifactFiles(dbFile()).map { it.name }

        assertThat(listed)
            .containsExactly(
                PayanamDatabaseSchema.DATABASE_NAME,
                "${PayanamDatabaseSchema.DATABASE_NAME}-wal",
                "${PayanamDatabaseSchema.DATABASE_NAME}.bak",
            ).inOrder()
    }

    @Test
    fun `a file with no parent directory falls back to itself and warns`() {
        val relative = File(PayanamDatabaseSchema.DATABASE_NAME)

        val listed = listDatabaseArtifactFiles(relative)

        assertThat(listed).containsExactly(relative)
        assertThat(logger.messages.any { it.contains("DB directory unavailable") }).isTrue()
    }

    @Test
    fun `a parent that is not a directory lists nothing rather than throwing`() {
        val orphan = File("/nonexistent-payanam-dir-xyz/${PayanamDatabaseSchema.DATABASE_NAME}")

        assertThat(listDatabaseArtifactFiles(orphan)).isEmpty()
    }

    // -------------------------------------------------------------------------
    // File.toDatabaseArtifactUiModel
    // -------------------------------------------------------------------------

    @Test
    fun `mapping reports the name, whole kilobytes, a formatted timestamp and activeness`() {
        val wal = File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}-wal")
        // 2500 bytes is two whole kilobytes (integer division: the UI shows KB, not a rounded value).
        wal.writeBytes(ByteArray(2500))
        val stale = File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}.bak")
        stale.writeBytes(ByteArray(1024))

        val walModel = wal.toDatabaseArtifactUiModel()
        val staleModel = stale.toDatabaseArtifactUiModel()

        assertThat(walModel.fileName).isEqualTo("${PayanamDatabaseSchema.DATABASE_NAME}-wal")
        assertThat(walModel.sizeKb).isEqualTo(2L)
        assertThat(walModel.isActive).isTrue()
        assertThat(walModel.lastModifiedLabel).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")

        assertThat(staleModel.sizeKb).isEqualTo(1L)
        assertThat(staleModel.isActive).isFalse()
    }

    @Test
    fun `mapping derives the label from the file's own modified time`() {
        val older = File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}.a")
        val newer = File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}.b")
        older.writeText("x")
        newer.writeText("x")
        older.setLastModified(1_000_000_000_000L)
        newer.setLastModified(1_700_000_000_000L)

        val olderLabel = older.toDatabaseArtifactUiModel().lastModifiedLabel
        val newerLabel = newer.toDatabaseArtifactUiModel().lastModifiedLabel

        assertThat(olderLabel).isNotEqualTo(newerLabel)
    }

    // -------------------------------------------------------------------------
    // deleteRuntimeDatabaseArtifacts
    // -------------------------------------------------------------------------

    @Test
    fun `runtime delete removes active artifacts and transient import temps only`() {
        val active =
            listOf("", "-wal", "-shm", "-journal").map { File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME + it) }
        val transient =
            listOf(
                DatabaseArtifacts.exactTransientTempName,
                DatabaseArtifacts.transientTempNamePrefix + DatabaseArtifacts.ENCRYPTED_RENAME_TEMP_TYPED_SUFFIX + "1739",
                DatabaseArtifacts.transientTempNamePrefix + DatabaseArtifacts.WAL_MERGE_TEMP_SUFFIX,
                DatabaseArtifacts.transientTempNamePrefix + DatabaseArtifacts.IMPORT_DECRYPT_TEMP_SUFFIX,
            ).map { File(temporaryFolder.root, it) }
        val bystanders =
            listOf(
                File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}.bak"),
                File(temporaryFolder.root, "unrelated.tmp"),
            )
        (active + transient + bystanders).forEach { it.writeText("x") }
        // A directory inside the database dir is skipped, never walked into.
        val subDir = File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}-sub").apply { mkdirs() }
        File(subDir, "child").writeText("x")

        val deleted = deleteRuntimeDatabaseArtifacts(dbFile())

        assertThat(deleted).isEqualTo(active.size + transient.size)
        (active + transient).forEach { assertThat(it.exists()).isFalse() }
        bystanders.forEach { assertThat(it.exists()).isTrue() }
        assertThat(subDir.exists()).isTrue()
    }

    @Test
    fun `runtime delete returns zero when there is no parent or no directory`() {
        assertThat(deleteRuntimeDatabaseArtifacts(File(PayanamDatabaseSchema.DATABASE_NAME))).isEqualTo(0)
        assertThat(
            deleteRuntimeDatabaseArtifacts(File("/nonexistent-payanam-dir-xyz/${PayanamDatabaseSchema.DATABASE_NAME}")),
        ).isEqualTo(0)
    }

    @Test
    fun `runtime delete reports zero and keeps a file it cannot remove`() {
        assumeTrue(
            "A non-removable file needs POSIX permissions",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val active = dbFile().apply { writeText("x") }

        temporaryFolder.root.setWritable(false)
        try {
            val deleted = deleteRuntimeDatabaseArtifacts(dbFile())

            assertThat(deleted).isEqualTo(0)
            assertThat(active.exists()).isTrue()
            assertThat(logger.messages.any { it.contains("Failed to delete file") }).isTrue()
        } finally {
            temporaryFolder.root.setWritable(true)
        }
    }

    // -------------------------------------------------------------------------
    // backupDatabaseArtifactFiles
    // -------------------------------------------------------------------------

    @Test
    fun `backup copies each file to a timestamped sibling and returns the mappings`() {
        val first = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME).apply { writeText("main") }
        val second = File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}-wal").apply { writeText("wal") }

        val mappings = backupDatabaseArtifactFiles(listOf(first, second))

        assertThat(mappings.map { it.first }).containsExactly(first, second).inOrder()
        mappings.forEach { (original, backup) ->
            assertThat(backup.parentFile).isEqualTo(temporaryFolder.root)
            assertThat(backup.name)
                .matches("${Regex.escape(original.name)}\\.before_import_\\d{8}_\\d{6}_\\d{3}\\.bak")
            assertThat(backup.exists()).isTrue()
            assertThat(backup.readText()).isEqualTo(original.readText())
        }
    }

    @Test
    fun `an empty input backs up nothing`() {
        assertThat(backupDatabaseArtifactFiles(emptyList())).isEmpty()
    }

    @Test
    fun `backing up a file that no longer exists propagates the failed copy`() {
        val missing = File(temporaryFolder.root, "vanished.db")

        assertThrows(IOException::class.java) { backupDatabaseArtifactFiles(listOf(missing)) }
    }

    // -------------------------------------------------------------------------
    // restoreDatabaseArtifactFiles
    // -------------------------------------------------------------------------

    @Test
    fun `restore copies each backup over its original and skips a missing backup`() {
        val restorable = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME).apply { writeText("live") }
        val restorableBackup = File(temporaryFolder.root, "restore.bak").apply { writeText("snapshot") }
        val orphan = File(temporaryFolder.root, "${PayanamDatabaseSchema.DATABASE_NAME}-wal").apply { writeText("live-wal") }
        val missingBackup = File(temporaryFolder.root, "gone.bak")

        val restored = restoreDatabaseArtifactFiles(listOf(restorable to restorableBackup, orphan to missingBackup))

        assertThat(restored).isEqualTo(1)
        assertThat(restorable.readText()).isEqualTo("snapshot")
        assertThat(orphan.readText()).isEqualTo("live-wal")
        assertThat(logger.messages.any { it.contains("Backup mapping missing during restore") }).isTrue()
    }

    @Test
    fun `restore counts every mapping when all backups are present`() {
        val first = File(temporaryFolder.root, "a.db").apply { writeText("live-a") }
        val firstBackup = File(temporaryFolder.root, "a.bak").apply { writeText("old-a") }
        val second = File(temporaryFolder.root, "b.db").apply { writeText("live-b") }
        val secondBackup = File(temporaryFolder.root, "b.bak").apply { writeText("old-b") }

        val restored = restoreDatabaseArtifactFiles(listOf(first to firstBackup, second to secondBackup))

        assertThat(restored).isEqualTo(2)
        assertThat(first.readText()).isEqualTo("old-a")
        assertThat(second.readText()).isEqualTo("old-b")
    }

    // -------------------------------------------------------------------------
    // cleanupDatabaseArtifactBackups
    // -------------------------------------------------------------------------

    @Test
    fun `cleanup removes the backups it is given and tolerates an already-missing one`() {
        val present = File(temporaryFolder.root, "present.bak").apply { writeText("x") }
        val absent = File(temporaryFolder.root, "absent.bak")

        cleanupDatabaseArtifactBackups(listOf(File(temporaryFolder.root, "a.db") to present, File(temporaryFolder.root, "b.db") to absent))

        assertThat(present.exists()).isFalse()
    }

    @Test
    fun `cleanup leaves a backup it cannot delete in place and warns`() {
        // A non-empty directory makes File.delete() return false everywhere: it is the portable stand-in
        // for a locked artifact, and it exercises the arm that must not be reported as deleted.
        val undeletable = File(temporaryFolder.root, "locked.bak").apply { mkdirs() }
        File(undeletable, "child").writeText("x")

        cleanupDatabaseArtifactBackups(listOf(File(temporaryFolder.root, "a.db") to undeletable))

        assertThat(undeletable.exists()).isTrue()
        assertThat(logger.messages.any { it.contains("Failed to delete backup artifact") }).isTrue()
    }

    /** Records what it was told, so "warns" is an assertion and not a hope. */
    private class RecordingLogger : UnifiedLogger {
        val messages = mutableListOf<String>()

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            messages += message
        }

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            messages += message
        }

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            messages += message
        }

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            messages += message
        }

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            messages += message
        }
    }
}
