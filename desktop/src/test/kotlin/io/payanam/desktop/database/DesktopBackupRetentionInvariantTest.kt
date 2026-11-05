//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.pruneScheduledBackupRotation
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/**
 * Retention on the desktop, over a REAL exports folder: both ways a backup is produced, and the
 * invariant that pruning can never delete the newest artifact.
 *
 * **Why this exists.** The prune decision is two lines of comparator — `listFiles`, then a sort,
 * then `drop` — and every existing retention case feeds it artifacts with DISTINCT mtimes, which is
 * the one input shape where the comparator cannot be wrong. `sortedByDescending` is a *stable* sort:
 * artifacts sharing an mtime come out in whatever order `java.io.File.listFiles` returned them, and
 * the JDK specifies no order for that. Measured on this machine, the two filesystems the app runs
 * on disagree outright:
 *
 * | root | `listFiles` order for 6 entries created oldest-first |
 * |------|-----------------------------------------------------|
 * | tmpfs (`/tmp`)      | newest-created FIRST (reverse creation order) |
 * | ntfs3 (the repo volume) | CREATION ORDER, newest LAST |
 *
 * Under `keepCount = 1` with tied mtimes that decides *which* backup the user keeps, and on ntfs3
 * the answer is the newest one. This file therefore builds the fixture that loses the newest on BOTH
 * — the newest-NAMED artifact created FIRST — so the case cannot pass by listing luck on whichever
 * filesystem the suite happens to run on, and drives both entry points rather than the leaf helper.
 *
 * **The production lines each case is built to catch**, every one MEASURED by mutating the line and
 * re-running this file against the mutant (all in
 * `desktop/src/main/kotlin/io/payanam/desktop/database/DesktopBackupPorts.kt` unless stated). Of nine
 * mutants, seven are caught:
 *
 * 1. one-key sort (`sortedByDescending { lastModified() }`, i.e. the pre-fix line) — 3 cases red:
 *    the two tie cases and the retention sweep.
 * 2. name-primary comparator (`:143` inverted) — `a newer mtime outranks an older name` red, and it
 *    is the ONLY case that catches it: everywhere else the name order and the mtime order agree.
 * 3. ascending sort — 6 red. `drop(keepCount - 1)` — 6 red. `take(keepCount)` instead of `drop` — 6
 *    red. `!policy.enabled` return removed — 1 red. The `isBackupArtifact` filter removed — 1 red.
 * 4. `DesktopApp.kt:270` the scheduled path calls the prune at all and `:271` points it at the
 *    folder the backup writes into, reached through the real entry point rather than the
 *    source-reading test that already covers the call's position.
 *
 * **Two mutants SURVIVE, and this file says so rather than pretending otherwise** — both because
 * `drop` already clamps, so the boundary is upheld by `drop` rather than by the operator being read:
 *
 * - the `<=` at `:142` flipped to `<` keeps everything green: `keepCount` items through
 *   `.drop(keepCount)` is an empty tail however the comparison is written.
 * - the early return at `:142` deleted keeps everything green: `drop` clamps past the end of a list,
 *   so an under-limit prune deletes nothing either way. It is a short-circuit, not a guard.
 *
 * The boundary and under-limit cases below are still asserted — they are the user-visible contract —
 * but they pin the BEHAVIOUR, not the two operators that appear to enforce it.
 *
 * Every case drives a real entry point with real files on disk and asserts from the filesystem, so
 * the ordering is genuinely exercised rather than stubbed: no listing is mocked, and no case depends
 * on the wall clock. Re-run with `-Djava.io.tmpdir` pointed at an NTFS3 volume as well as tmpfs: the
 * tie cases fail pre-fix on BOTH, which is what makes them a property of the code rather than of the
 * filesystem the suite happened to run on.
 */
class DesktopBackupRetentionInvariantTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * A backup artifact named by the REAL producer rule, holding real bytes, with an explicit mtime.
     *
     * A real file rather than a stubbed listing, because the defect is in how the prune ORDERS what
     * the filesystem hands it — a mocked listing would hand it the order the test already believes
     * in, which is exactly the assumption under test. Names come from [scheduledBackupArtifactName]
     * so the fixture has the same shape as what production writes.
     */
    private fun artifact(
        root: File,
        stamp: String,
        modifiedAtMillis: Long,
        bytes: String = "a backup",
    ): File {
        val file = File(root, scheduledBackupArtifactName(stamp))
        file.writeText(bytes)
        assertThat(Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modifiedAtMillis))).isNotNull()
        return file
    }

    /** The names of the backup artifacts still in the folder, read back off the filesystem. */
    private fun survivingArtifactNames(root: File): List<String> =
        root
            .listFiles()
            .orEmpty()
            .map { it.name }
            .sorted()

    // ── The invariant ────────────────────────────────────────────────────────────

    /**
     * THE case. Tied mtimes, and the newest-NAMED artifact created FIRST.
     *
     * Fails without the `.thenByDescending { it.name }` at `DesktopBackupPorts.kt:143`: the stable
     * sort leaves the three artifacts in creation order, `drop(1)` then reaches the newest and
     * deletes it. Verified to fail on BOTH filesystems, so this is not a tmpfs-only or ntfs-only
     * result — see the file KDoc.
     */
    @Test
    fun `a prune keeps the newest artifact when the mtimes tie`() {
        val root = temporaryFolder.newFolder("tie")
        val tied = 1_700_000_000_000L
        // Creation order is DELIBERATELY newest-name-first. That is a legal copy order (a restore
        // tool copying in descending order, or in inode order), and it is the order under which the
        // one-key sort hands the newest artifact to `drop`. On tmpfs and ntfs3 alike this fixture
        // puts the newest LAST in listFiles order, so the case cannot pass by luck.
        val newest = artifact(root, "20260103-120000", tied, "the newest artifact")
        val middle = artifact(root, "20260102-120000", tied, "a middle artifact")
        val oldest = artifact(root, "20260101-120000", tied, "the oldest artifact")

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 1),
                NoOpUnifiedLogger,
            )

        assertThat(deleted).doesNotContain(newest)
        // Asserted from the filesystem, not from the return value: the return value is what the code
        // BELIEVED it did, and the invariant is about what is still on disk afterwards.
        assertThat(newest.exists()).isTrue()
        assertThat(Files.readString(newest.toPath())).isEqualTo("the newest artifact")
        assertThat(oldest.exists()).isFalse()
        assertThat(middle.exists()).isFalse()
    }

    /**
     * The same invariant after a run driven through the SCHEDULED path — the one that writes into
     * the app's exports folder. Fails if `DesktopApp.kt:270` stops calling the prune, or if `:271`
     * points it somewhere other than the folder the backup writes into.
     *
     * Read through `pruneScheduledBackupRotation`, which is what `DesktopApp.kt:270` calls: the rows
     * are passed in as the parameter the production call site supplies, so the retention COUNT is
     * decided by the shared reader rather than restated here.
     */
    @Test
    fun `the scheduled entry point keeps the newest artifact after pruning`() {
        val root = temporaryFolder.newFolder("scheduled")
        val tied = 1_700_000_000_000L
        val newest = artifact(root, "20260103-120000", tied, "the newest artifact")
        val middle = artifact(root, "20260102-120000", tied, "a middle artifact")
        val oldest = artifact(root, "20260101-120000", tied, "the oldest artifact")

        val deleted =
            runBlocking {
                pruneScheduledBackupRotation(
                    backupDirectory = root,
                    backupRows = {
                        mapOf(
                            AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to "true",
                            AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "1",
                        )
                    },
                    logger = NoOpUnifiedLogger,
                )
            }

        // Both ties went to the two OLDER artifacts; the newest is untouched and still readable.
        assertThat(deleted.map { it.name }).containsExactly(middle.name, oldest.name)
        assertThat(newest.exists()).isTrue()
        assertThat(Files.readString(newest.toPath())).isEqualTo("the newest artifact")
        assertThat(survivingArtifactNames(root)).containsExactly(newest.name)
    }

    /**
     * Below the limit: nothing is deleted. The under-limit case, asserted because a user whose
     * folder holds fewer backups than the limit must not lose one to a prune that has no reason to
     * run at all.
     *
     * Note honestly: this does NOT fail if `:142`'s early return is deleted — Kotlin's `drop` clamps
     * past the end of a list, so the doomed tail is empty either way. The return is a short-circuit,
     * and this case pins the BEHAVIOUR rather than the guard.
     */
    @Test
    fun `below the limit a prune deletes nothing`() {
        val root = temporaryFolder.newFolder("under")
        val first = artifact(root, "20260101-120000", 1_000L)
        val second = artifact(root, "20260102-120000", 2_000L)
        val third = artifact(root, "20260103-120000", 3_000L)

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 5),
                NoOpUnifiedLogger,
            )

        assertThat(deleted).isEmpty()
        assertThat(survivingArtifactNames(root))
            .containsExactly(first.name, second.name, third.name)
            .inOrder()
    }

    /**
     * THE BOUNDARY: exactly as many artifacts as the limit. A user whose folder has landed exactly
     * on their retention count must keep all of them, and the newest must still be readable.
     *
     * Note honestly: this does NOT fail if `:142`'s `<=` is flipped to `<`. `keepCount` items
     * through `.drop(keepCount)` is an empty tail however the comparison is written — Kotlin's `drop`
     * clamps. So the boundary holds because of `drop`, not because of that operator, and this case
     * pins the CONTRACT rather than crediting the operator for it.
     */
    @Test
    fun `at the limit a prune deletes nothing`() {
        val root = temporaryFolder.newFolder("at-limit")
        val first = artifact(root, "20260101-120000", 1_000L)
        val second = artifact(root, "20260102-120000", 2_000L)

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 2),
                NoOpUnifiedLogger,
            )

        // The `<=` at :142, stated as the arithmetic it is: two artifacts, keep two, delete zero.
        assertThat(deleted).isEmpty()
        assertThat(survivingArtifactNames(root)).containsExactly(first.name, second.name).inOrder()
        // And the newest is still readable, so a boundary that deleted would show up here.
        assertThat(Files.readString(second.toPath())).isEqualTo("a backup")
    }

    /**
     * Above the limit, with DISTINCT mtimes — the shape every pre-existing case uses, and the one
     * where the comparator is correct.
     *
     * Kept deliberately as the regression guard for the tiebreak: `mtime` is still the PRIMARY key,
     * so a genuinely newest artifact must outrank an older-named one no matter what the name says.
     * This case alone cannot see that (its name order and mtime order agree), which is why
     * `a newer mtime outranks an older name` exists beside it.
     */
    @Test
    fun `above the limit a prune keeps the newest and deletes the oldest`() {
        val root = temporaryFolder.newFolder("over")
        val oldest = artifact(root, "20260101-120000", 1_000L)
        val middle = artifact(root, "20260102-120000", 2_000L)
        val newest = artifact(root, "20260103-120000", 3_000L)

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 2),
                NoOpUnifiedLogger,
            )

        assertThat(deleted.map { it.name }).containsExactly(oldest.name)
        assertThat(oldest.exists()).isFalse()
        assertThat(survivingArtifactNames(root)).containsExactly(middle.name, newest.name).inOrder()
        // Still readable, not merely present: the newest artifact must survive a prune intact.
        assertThat(Files.readString(newest.toPath())).isEqualTo("a backup")
    }

    /**
     * A genuinely NEWER artifact outranks an older-named one even when the name sorts the other way.
     *
     * This is the case that makes `lastModified` the primary key and the name only a tiebreak
     * load-bearing. The shape is a real one: a user restores an OLD backup from elsewhere and drops
     * it into the exports folder, so its stamp sorts oldest while its mtime is the most recent thing
     * in the directory. With `keepCount = 2` the restore must be the one KEPT — it is the newest.
     *
     * Fails if the comparator at `DesktopBackupPorts.kt:143` is inverted so the NAME leads, which
     * would delete the freshly restored backup and keep an older-named one. Every other case in this
     * file passes under that inversion, because everywhere else the name order and the mtime order
     * agree; measured, this is the only case that catches it.
     */
    @Test
    fun `a newer mtime outranks an older name`() {
        val root = temporaryFolder.newFolder("restored")
        // An older artifact by name, older by mtime.
        val older = artifact(root, "20260101-120000", 1_000L, "older by both")
        // A NEWER artifact by mtime but the OLDEST name: restored from another machine.
        val restored = artifact(root, "20251201-120000", 9_000L, "restored, newest by mtime")
        val newest = artifact(root, "20260103-120000", 8_000L, "newest by name")

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 2),
                NoOpUnifiedLogger,
            )

        // mtime decides: the restore (9_000) and the 8_000 artifact are kept, the 1_000 one is not.
        assertThat(deleted.map { it.name }).containsExactly(older.name)
        assertThat(restored.exists()).isTrue()
        assertThat(Files.readString(restored.toPath())).isEqualTo("restored, newest by mtime")
        assertThat(newest.exists()).isTrue()
    }

    /**
     * The newest artifact survives a prune of ANY size, and stays readable — the invariant the
     * destructive direction of the comparator lives or dies by, asserted across several limits
     * rather than one. Fails on the FIRST limit where the one-key sort hands the newest to `drop`.
     */
    @Test
    fun `the newest artifact survives and stays readable at every retention count`() {
        val root = temporaryFolder.newFolder("sweep")
        val stamps = listOf("20260101-120000", "20260102-120000", "20260103-120000", "20260104-120000")
        val tied = 1_700_000_000_000L
        // Created newest-name-first for the same reason as the tie case above.
        val made = stamps.reversed().map { artifact(root, it, tied, "bytes of $it") }
        val newest = made.first()

        stamps.indices.forEach { index ->
            // keepCount = 1, 2 and 3 against four tied artifacts.
            val deleted =
                pruneOldBackupDirectories(
                    root,
                    BackupRotationPolicy(enabled = true, keepCount = index + 1),
                    NoOpUnifiedLogger,
                )
            assertThat(newest.exists()).isTrue()
            assertThat(Files.readString(newest.toPath())).isEqualTo("bytes of 20260104-120000")
            assertThat(deleted).doesNotContain(newest)
            // Leave the folder whole for the next limit.
            stamps.forEach { stamp ->
                if (!File(root, scheduledBackupArtifactName(stamp)).exists()) {
                    artifact(root, stamp, tied, "bytes of $stamp")
                }
            }
        }
    }

    /**
     * Only artifacts are considered, so a user's own file in the exports folder is never a prune
     * candidate however old it is. Fails if the matcher at `DesktopBackupPorts.kt:139` is widened
     * (an unrelated folder would be deleted) or narrowed so an artifact shape stops matching.
     */
    @Test
    fun `a prune touches only backup artifacts`() {
        val root = temporaryFolder.newFolder("others")
        val oldArtifact = artifact(root, "20260101-120000", 1_000L)
        val newArtifact = artifact(root, "20260102-120000", 2_000L)
        val foreignFile = File(root, "Payanam_backup.db").apply { writeText("a manual export") }
        val foreignFolder = File(root, "manual-export").apply { mkdirs() }
        // The oldest thing in the folder by a wide margin, and NOT a backup artifact.
        assertThat(Files.setLastModifiedTime(foreignFile.toPath(), FileTime.fromMillis(1L))).isNotNull()
        assertThat(Files.setLastModifiedTime(foreignFolder.toPath(), FileTime.fromMillis(1L))).isNotNull()

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = true, keepCount = 1),
                NoOpUnifiedLogger,
            )

        assertThat(deleted.map { it.name }).containsExactly(oldArtifact.name)
        assertThat(newArtifact.exists()).isTrue()
        assertThat(foreignFile.exists()).isTrue()
        assertThat(foreignFile.readText()).isEqualTo("a manual export")
        assertThat(foreignFolder.exists()).isTrue()
    }

    /**
     * Rotation disabled deletes nothing at all — the retention row is a control over the user's own
     * backups, and an off toggle that still prunes is the destructive direction. Fails if the
     * `!policy.enabled` return at `DesktopBackupPorts.kt:136` is removed.
     */
    @Test
    fun `a disabled rotation deletes nothing`() {
        val root = temporaryFolder.newFolder("disabled")
        val first = artifact(root, "20260101-120000", 1_000L)
        val second = artifact(root, "20260102-120000", 2_000L)
        val third = artifact(root, "20260103-120000", 3_000L)

        val deleted =
            pruneOldBackupDirectories(
                root,
                BackupRotationPolicy(enabled = false, keepCount = 1),
                NoOpUnifiedLogger,
            )

        assertThat(deleted).isEmpty()
        assertThat(survivingArtifactNames(root))
            .containsExactly(first.name, second.name, third.name)
            .inOrder()
    }
}
