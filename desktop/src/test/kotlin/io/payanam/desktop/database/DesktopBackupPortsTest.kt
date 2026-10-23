//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.DesktopKeyValueEntry
import io.payanam.desktop.desktopScheduledBackupDestination
import io.payanam.domain.port.BackupTrigger
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringWriter
import java.util.Properties

/**
 * Tests for the desktop's backup ports.
 *
 * The status port is asserted through its observable snapshot rather than its internals, and the
 * coordinator port through both the result it returns and the status it records — a successful run
 * that forgot to record, or a failure that recorded nothing, must fail these tests.
 */
class DesktopBackupPortsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * A status store over a fresh temporary directory.
     *
     * The port resolves its directory from the platform, so a no-argument construction would read and
     * write the DEVELOPER'S real preferences file - a test that passed or failed depending on what was
     * already on the machine, and that left state behind. Every case here goes through a temp folder.
     */
    private fun statusStore() = DesktopBackupStatusPort(tempFolder.newFolder().toPath())

    private fun statusStoreIn(directory: java.nio.file.Path) = DesktopBackupStatusPort(directory)

    @Test
    fun `a recorded success sets the display timestamp and clears any failure`() {
        val store = statusStore()
        store.recordFailure("previous failure")

        store.recordSuccess(1_700_000_000_000L)

        val snapshot = store.status.value
        assertThat(snapshot.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
        assertThat(snapshot.lastSuccessDisplay).isEqualTo(store.formatBackupTimestamp(1_700_000_000_000L))
        assertThat(snapshot.latestFailure).isNull()
    }

    @Test
    fun `a recorded failure keeps the last success and truncates long messages`() {
        val store = statusStore()
        store.recordSuccess(1_700_000_000_000L)

        store.recordFailure("x".repeat(500))

        val snapshot = store.status.value
        assertThat(snapshot.latestFailure?.message).hasLength(400)
        assertThat(snapshot.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `dismissing the failure clears only the failure`() {
        val store = statusStore()
        store.recordSuccess(1_700_000_000_000L)
        store.recordFailure("boom")

        store.dismissLatestFailure()

        val snapshot = store.status.value
        assertThat(snapshot.latestFailure).isNull()
        assertThat(snapshot.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `a successful backup reports the destination and records the success`() =
        runBlocking {
            val store = statusStore()
            val port =
                DesktopDatabaseBackupPort(
                    statusStore = store,
                    performBackup = { "/tmp/payanam-backups/auto_bk/payanam.db" },
                    logger = NoOpUnifiedLogger,
                )

            val result = port.backupToAppBackupDirectory(BackupTrigger.AUTO)

            assertThat(result.destinationPath).isEqualTo("/tmp/payanam-backups/auto_bk/payanam.db")
            assertThat(result.attemptsUsed).isEqualTo(1)
            assertThat(result.recordedAtDisplay).isEqualTo(store.status.value.lastSuccessDisplay)
            assertThat(store.status.value.latestFailure).isNull()
        }

    @Test
    fun `a failed backup records the failure and throws with the reason`() =
        runBlocking {
            val store = statusStore()
            val port =
                DesktopDatabaseBackupPort(
                    statusStore = store,
                    performBackup = { error("disk full") },
                    logger = NoOpUnifiedLogger,
                )

            val thrown = runCatching { port.backupToAppBackupDirectory(BackupTrigger.MANUAL) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            assertThat(thrown?.message).contains("disk full")
            assertThat(
                store.status.value.latestFailure
                    ?.message,
            ).contains("disk full")
        }

    // ── Rotation: keep N newest, prune the rest ───────────────────────────

    /**
     * Creates a backup directory named the way both platforms name them, with an explicit
     * modification time so "newest" is a fact of the fixture rather than of the clock's speed.
     */
    private fun backupDir(
        root: java.io.File,
        name: String,
        lastModified: Long,
    ): java.io.File {
        val dir = java.io.File(root, name)
        assertThat(dir.mkdirs()).isTrue()
        assertThat(dir.setLastModified(lastModified)).isTrue()
        return dir
    }

    @Test
    fun `rotation keeps the newest N directories and prunes the rest`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("payanam-rotation")
                .toFile()
        val oldest = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}oldest", 1_000L)
        val middle = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}middle", 2_000L)
        val newest = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}newest", 3_000L)

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 2),
                NoOpUnifiedLogger,
            )

        // Three directories, keep two: exactly one is pruned, and it is the oldest.
        assertThat(deleted.map { it.name }).containsExactly(oldest.name)
        assertThat(oldest.exists()).isFalse()
        assertThat(middle.exists()).isTrue()
        // The newest is never pruned, whatever the count says.
        assertThat(newest.exists()).isTrue()
    }

    @Test
    fun `rotation disabled deletes nothing`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("payanam-rotation-off")
                .toFile()
        val old = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}old", 1_000L)

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = false, keepCount = 1),
                NoOpUnifiedLogger,
            )

        assertThat(deleted).isEmpty()
        assertThat(old.exists()).isTrue()
    }

    @Test
    fun `rotation within the keep count deletes nothing`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("payanam-rotation-under")
                .toFile()
        val first = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}a", 1_000L)
        val second = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}b", 2_000L)

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 5),
                NoOpUnifiedLogger,
            )

        assertThat(deleted).isEmpty()
        assertThat(first.exists()).isTrue()
        assertThat(second.exists()).isTrue()
    }

    @Test
    fun `rotation ignores files and directories that are not backups`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("payanam-rotation-others")
                .toFile()
        val oldBackup = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}old", 1_000L)
        val newBackup = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}new", 2_000L)
        val otherDir = backupDir(root, "manual_export", 500L)
        val otherFile = java.io.File(root, "notes.txt").apply { writeText("keep me") }

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 1),
                NoOpUnifiedLogger,
            )

        assertThat(deleted.map { it.name }).containsExactly(oldBackup.name)
        assertThat(newBackup.exists()).isTrue()
        // Neither a hand-made export folder nor a loose file is a backup this rotation owns.
        assertThat(otherDir.exists()).isTrue()
        assertThat(otherFile.exists()).isTrue()
    }

    @Test
    fun `a successful backup prunes according to the rotation rows`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("payanam-rotation-port")
                .toFile()
        val stale = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}stale", 1_000L)
        val store = statusStore()
        val port =
            DesktopDatabaseBackupPort(
                statusStore = store,
                performBackup = { backupDir(root, "${BACKUP_ARTIFACT_PREFIX}fresh", 9_000L).absolutePath },
                logger = NoOpUnifiedLogger,
                rotationPolicy = { BackupRotationPolicy(enabled = true, keepCount = 1) },
                backupDirectory = root,
            )

        val result = runBlocking { port.backupToAppBackupDirectory(BackupTrigger.MANUAL) }

        assertThat(result.attemptsUsed).isEqualTo(1)
        // Two backups exist and one is kept: the one this run just wrote. The stale one is gone,
        // which is only possible if the rotation rows were read and acted on.
        assertThat(stale.exists()).isFalse()
        assertThat(java.io.File(result.destinationPath!!).exists()).isTrue()
        assertThat(store.status.value.lastSuccessAtMillis).isEqualTo(result.recordedAtMillis)
    }

    @Test
    fun `a failed backup prunes nothing`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("payanam-rotation-fail")
                .toFile()
        val stale = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}stale", 1_000L)
        val port =
            DesktopDatabaseBackupPort(
                statusStore = statusStore(),
                performBackup = { throw IllegalStateException("disk full") },
                logger = NoOpUnifiedLogger,
                rotationPolicy = { BackupRotationPolicy(enabled = true, keepCount = 1) },
                backupDirectory = root,
            )

        runCatching { runBlocking { port.backupToAppBackupDirectory(BackupTrigger.AUTO) } }

        // A failed attempt must never be the reason an existing backup disappears.
        assertThat(stale.exists()).isTrue()
    }

    @Test
    fun `the artifact a scheduled backup writes is one rotation prunes`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("payanam-rotation-producer")
                .toFile()

        // The REAL producer, not a hand-made name: if this used a literal string, the assertion would
        // only prove the literal matches the matcher, not that a backup this app writes is prunable.
        val written = desktopScheduledBackupDestination(root.toPath())
        assertThat(written.fileName.toString()).startsWith(BACKUP_ARTIFACT_PREFIX)
        assertThat(written.toFile().createNewFile()).isTrue()
        // A second, older artifact from the same naming rule. Its age is set explicitly, so which one
        // is newest is a fact of the fixture rather than of the clock.
        val older =
            java.io.File(root, scheduledBackupArtifactName("19700101-000000")).apply {
                writeText("an older artifact")
                assertThat(setLastModified(1_000L)).isTrue()
            }

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 1),
                NoOpUnifiedLogger,
            )

        // Two artifacts exist and one is kept, so exactly the older of the two went - which is only
        // possible if rotation recognises the name the producer writes.
        assertThat(deleted.map { it.name }).containsExactly(older.name)
        assertThat(older.exists()).isFalse()
        assertThat(written.toFile().exists()).isTrue()
    }

    @Test
    fun `rotation prunes a flat artifact file as well as a session directory`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("payanam-rotation-flat")
                .toFile()
        val oldFile =
            java.io.File(root, "${BACKUP_ARTIFACT_PREFIX}old.$BACKUP_ARTIFACT_EXTENSION").apply {
                writeText("an older flat artifact")
            }
        assertThat(oldFile.setLastModified(1_000L)).isTrue()
        val newDir = backupDir(root, "${BACKUP_ARTIFACT_PREFIX}new", 2_000L)

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 1),
                NoOpUnifiedLogger,
            )

        // Android keeps both shapes in the same folder, so rotation that read only one of them would
        // let the other accumulate without bound.
        assertThat(deleted.map { it.name }).containsExactly(oldFile.name)
        assertThat(oldFile.exists()).isFalse()
        assertThat(newDir.exists()).isTrue()
    }

    @Test
    fun `a file that is not a backup artifact is never pruned`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("payanam-rotation-foreign")
                .toFile()
        // Right prefix, wrong extension: a name that is close enough to look like a backup and must
        // still be left alone, rather than deleted because the matcher was loosened.
        val impostor = java.io.File(root, "${BACKUP_ARTIFACT_PREFIX}notes.txt").apply { writeText("not a backup") }
        val unrelated = java.io.File(root, "payanam-auto-old.db").apply { writeText("not a backup either") }

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 1),
                NoOpUnifiedLogger,
            )

        assertThat(deleted).isEmpty()
        assertThat(impostor.exists()).isTrue()
        assertThat(unrelated.exists()).isTrue()
    }

    @Test
    fun `a rotation policy that keeps nothing is rejected`() {
        assertThat(
            runCatching { BackupRotationPolicy(enabled = true, keepCount = 0) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    // ------------------------------------------------------------------ persistence across a restart
    // Every case builds a SECOND store over the SAME directory, because that is the shape of a restart.
    // A store that persisted nothing would pass a suite where each case used its own directory.

    @Test
    fun `a recorded success is still reported after a restart`() {
        val directory = tempFolder.newFolder().toPath()

        statusStoreIn(directory).recordSuccess(1_700_000_000_000L)

        val afterRestart = statusStoreIn(directory).status.value
        assertThat(afterRestart.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
        assertThat(afterRestart.lastSuccessDisplay).isNotNull()
    }

    @Test
    fun `a recorded failure is still reported after a restart and keeps the last success`() {
        val directory = tempFolder.newFolder().toPath()
        statusStoreIn(directory).recordSuccess(1_700_000_000_000L)

        statusStoreIn(directory).recordFailure("boom")

        val afterRestart = statusStoreIn(directory).status.value
        assertThat(afterRestart.latestFailure?.message).isEqualTo("boom")
        assertThat(afterRestart.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `a dismissed failure is still dismissed after a restart`() {
        val directory = tempFolder.newFolder().toPath()
        statusStoreIn(directory).recordFailure("boom")

        statusStoreIn(directory).dismissLatestFailure()

        val afterRestart = statusStoreIn(directory).status.value
        assertThat(afterRestart.latestFailure).isNull()
    }

    @Test
    fun `a blank persisted failure message loads as no failure`() {
        val directory = tempFolder.newFolder().toPath()
        val properties =
            Properties().apply {
                setProperty("last_backup_success_at_millis", "1700000000000")
                setProperty("last_backup_failure_at_millis", "1700000000001")
                setProperty("last_backup_failure_display", "yesterday")
                setProperty("last_backup_failure_message", "   ")
            }
        val payload =
            StringWriter().use { writer ->
                properties.store(writer, "Payanam Backup Status")
                writer.toString()
            }
        DesktopKeyValueEntry(DesktopBackupStatusPort.backupStatusFileIn(directory)).writeEntry(payload)

        val snapshot = statusStoreIn(directory).status.value
        assertThat(snapshot.latestFailure).isNull()
        assertThat(snapshot.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `an absent status file loads as the empty snapshot rather than throwing`() {
        val directory = tempFolder.newFolder().toPath()

        val snapshot = statusStoreIn(directory).status.value

        assertThat(snapshot.lastSuccessAtMillis).isEqualTo(0L)
        assertThat(snapshot.lastSuccessDisplay).isNull()
        assertThat(snapshot.latestFailure).isNull()
    }
}
