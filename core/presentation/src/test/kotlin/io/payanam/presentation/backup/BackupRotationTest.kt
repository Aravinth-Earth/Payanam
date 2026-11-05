//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.backup

import com.google.common.truth.Truth.assertThat
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.UnifiedLogger
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileSystems

/**
 * The shared backup-rotation contract: how the two `app_settings` rows become a [BackupRotationPolicy],
 * and which artifacts [pruneBackupArtifacts] removes once it has one.
 *
 * The parser is pure and every one of its malformed inputs is a real row a user could have written or a
 * migration could have left behind, so each is pinned here. The prune half runs on a real temp directory
 * because that is the only place its branches — the disabled short-circuit, an absent directory, a
 * directory already within the keep count, an artifact that refuses to be deleted — are distinguishable.
 *
 * The platform supplies [pruneBackupArtifacts]'s `isArtifact` matcher, so a test is free to pass its own:
 * the point is that ONLY artifacts are considered, and a bystander in the same folder is never touched.
 */
class BackupRotationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logger = RecordingLogger()

    private val artifactMatcher: (File) -> Boolean = { it.name.startsWith("auto_bk_") }

    // -------------------------------------------------------------------------
    // BackupRotationPolicy
    // -------------------------------------------------------------------------

    @Test
    fun `a policy defaults to the shared keep count and rejects a count below one`() {
        val defaulted = BackupRotationPolicy(enabled = false)

        assertThat(defaulted.keepCount).isEqualTo(BackupRotationPolicy.DEFAULT_KEEP_COUNT)
        assertThat(BackupRotationPolicy.DEFAULT_KEEP_COUNT).isEqualTo(50)

        assertThrows(IllegalArgumentException::class.java) {
            BackupRotationPolicy(enabled = true, keepCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupRotationPolicy(enabled = true, keepCount = -1)
        }
    }

    // -------------------------------------------------------------------------
    // rotationPolicyFromBackupRows
    // -------------------------------------------------------------------------

    @Test
    fun `absent rows mean rotation is off at the shared default count`() {
        val policy = rotationPolicyFromBackupRows(emptyMap())

        assertThat(policy.enabled).isFalse()
        assertThat(policy.keepCount).isEqualTo(BackupRotationPolicy.DEFAULT_KEEP_COUNT)
    }

    @Test
    fun `present rows are read verbatim`() {
        val policy =
            rotationPolicyFromBackupRows(
                mapOf(
                    AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to "true",
                    AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "10",
                ),
            )

        assertThat(policy.enabled).isTrue()
        assertThat(policy.keepCount).isEqualTo(10)
    }

    @Test
    fun `explicit false and a non-boolean value both leave rotation off`() {
        assertThat(
            rotationPolicyFromBackupRows(mapOf(AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to "false")).enabled,
        ).isFalse()
        assertThat(
            rotationPolicyFromBackupRows(mapOf(AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to "yes-please")).enabled,
        ).isFalse()
    }

    @Test
    fun `an unparseable count falls back to the shared default rather than throwing`() {
        val policy =
            rotationPolicyFromBackupRows(
                mapOf(
                    AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to "true",
                    AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "many",
                ),
            )

        assertThat(policy.keepCount).isEqualTo(BackupRotationPolicy.DEFAULT_KEEP_COUNT)
    }

    @Test
    fun `a null value is treated the same as an absent row`() {
        val policy =
            rotationPolicyFromBackupRows(
                mapOf(
                    AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to null,
                    AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to null,
                ),
            )

        assertThat(policy.enabled).isFalse()
        assertThat(policy.keepCount).isEqualTo(BackupRotationPolicy.DEFAULT_KEEP_COUNT)
    }

    @Test
    fun `a count is clamped into the 1 to 999 range before it can reach the constructor`() {
        // Below the floor: a stored 0 would be rejected by the constructor AFTER a backup succeeded.
        assertThat(
            rotationPolicyFromBackupRows(
                mapOf(AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "0"),
            ).keepCount,
        ).isEqualTo(1)
        assertThat(
            rotationPolicyFromBackupRows(
                mapOf(AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "-25"),
            ).keepCount,
        ).isEqualTo(1)
        // Above the ceiling: clamped to Android's rule, not carried through.
        assertThat(
            rotationPolicyFromBackupRows(
                mapOf(AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "5000"),
            ).keepCount,
        ).isEqualTo(BackupRotationPolicy.MAX_KEEP_COUNT)
        assertThat(BackupRotationPolicy.MAX_KEEP_COUNT).isEqualTo(999)
    }

    // -------------------------------------------------------------------------
    // pruneBackupArtifacts
    // -------------------------------------------------------------------------

    @Test
    fun `a disabled policy prunes nothing`() {
        val artifacts = listOf("auto_bk_20260101-000000.db", "auto_bk_20260102-000000.db").map { artifact(it) }

        val deleted = pruneBackupArtifacts(temporaryFolder.root, BackupRotationPolicy(enabled = false), artifactMatcher, logger)

        assertThat(deleted).isEmpty()
        artifacts.forEach { assertThat(it.exists()).isTrue() }
    }

    @Test
    fun `an absent directory prunes nothing`() {
        val missing = File(temporaryFolder.root, "does-not-exist")

        val deleted = pruneBackupArtifacts(missing, BackupRotationPolicy(enabled = true, keepCount = 1), artifactMatcher, logger)

        assertThat(deleted).isEmpty()
    }

    @Test
    fun `a folder already within the keep count prunes nothing`() {
        val artifacts = listOf("auto_bk_20260101-000000.db", "auto_bk_20260102-000000.db").map { artifact(it) }

        val deleted =
            pruneBackupArtifacts(temporaryFolder.root, BackupRotationPolicy(enabled = true, keepCount = 2), artifactMatcher, logger)

        assertThat(deleted).isEmpty()
        artifacts.forEach { assertThat(it.exists()).isTrue() }
    }

    @Test
    fun `the oldest artifacts are deleted and only artifacts are considered`() {
        val oldest = artifact("auto_bk_20260101-000000.db", modified = 1_000_000_000_000L)
        val middle = artifact("auto_bk_20260102-000000.db", modified = 2_000_000_000_000L)
        val newest = artifact("auto_bk_20260103-000000.db", modified = 3_000_000_000_000L)
        // Older than every artifact, but not one: the matcher keeps it out of the running entirely.
        val bystander =
            File(temporaryFolder.root, "harmless.txt").apply {
                writeText("keep me")
                setLastModified(0L)
            }

        val deleted =
            pruneBackupArtifacts(temporaryFolder.root, BackupRotationPolicy(enabled = true, keepCount = 2), artifactMatcher, logger)

        assertThat(deleted.map { it.name }).containsExactly("auto_bk_20260101-000000.db")
        assertThat(oldest.exists()).isFalse()
        assertThat(middle.exists()).isTrue()
        assertThat(newest.exists()).isTrue()
        assertThat(bystander.exists()).isTrue()
        assertThat(logger.messages.any { it.contains("Pruned an old backup") }).isTrue()
    }

    @Test
    fun `a tie on modified time is broken by name so the newest named artifact is kept`() {
        // The name carries the stamp, so descending name order is descending time among artifacts the
        // filesystem cannot order. A stable sort alone would drop whichever listFiles returned last.
        val older = artifact("auto_bk_20260101-000000.db", modified = 1_500_000_000_000L)
        val newer = artifact("auto_bk_20260202-000000.db", modified = 1_500_000_000_000L)

        val deleted =
            pruneBackupArtifacts(temporaryFolder.root, BackupRotationPolicy(enabled = true, keepCount = 1), artifactMatcher, logger)

        assertThat(deleted.map { it.name }).containsExactly("auto_bk_20260101-000000.db")
        assertThat(older.exists()).isFalse()
        assertThat(newer.exists()).isTrue()
    }

    @Test
    fun `an artifact that cannot be removed is left in place and not reported as deleted`() {
        assumeTrue(
            "A non-removable directory entry needs POSIX permissions",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val kept = artifact("auto_bk_20260102-000000.db", modified = 2_000_000_000_000L)
        // A non-empty directory under a read-only parent: its child can go, the directory entry cannot.
        val doomed =
            File(temporaryFolder.root, "auto_bk_20260101-000000.db").apply {
                mkdirs()
                File(this, "payload").writeText("x")
                setLastModified(1_000_000_000_000L)
            }

        temporaryFolder.root.setWritable(false)
        try {
            val deleted =
                pruneBackupArtifacts(temporaryFolder.root, BackupRotationPolicy(enabled = true, keepCount = 1), artifactMatcher, logger)

            assertThat(deleted).isEmpty()
            assertThat(doomed.exists()).isTrue()
            assertThat(kept.exists()).isTrue()
            assertThat(logger.messages.any { it.contains("Could not prune") }).isTrue()
        } finally {
            temporaryFolder.root.setWritable(true)
        }
    }

    // -------------------------------------------------------------------------

    private fun artifact(
        name: String,
        modified: Long = System.currentTimeMillis(),
    ): File =
        File(temporaryFolder.root, name).apply {
            writeText("backup")
            setLastModified(modified)
        }

    /** A logger that records its messages, so "logged a prune" is an assertion. */
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
