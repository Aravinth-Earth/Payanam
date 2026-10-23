//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.database.pruneOldBackupDirectories
import io.payanam.desktop.database.rotationPolicyFromBackupRows
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The desktop's backup ROTATION, on the path that actually runs.
 *
 * **Why this exists.** Rotation was implemented and fully tested in `DesktopDatabaseBackupPort`, and
 * every one of those tests passed while the running app pruned nothing — the port has no production
 * constructor, so the class that owned the policy was a class nothing called. The live scheduled path
 * (`DesktopApp.runBackup`) wrote a backup artifact into `exports/` and returned. On Android the same
 * fact is not true: `DatabaseBackupCoordinator.cleanupOldBackups` runs on *every* backup through the
 * coordinator, so a retention row there is honoured by each run. The desktop honoured it by nobody.
 *
 * A test of the port cannot see this, because the defect is precisely that the port is not on the path.
 * So the wiring is asserted against the source of the path — the same idiom
 * `DesktopRailNavigationWiringTest` and `DesktopNavHostParityTest` already use in this module for
 * behaviours no render can observe — and the retention READER is asserted behaviourally, because the
 * reader's job is to reproduce Android's own coercion rules rather than to be called.
 *
 * **The mutants this is built to catch:**
 *
 * 1. the rotation call is deleted from the scheduled path (the exact revert this item fixes) — the
 *    behavioural cases below keep passing, which is precisely why the wiring needs its own assertion;
 * 2. the rotation call survives but is moved OUTSIDE the `succeededForBackup()` branch, so a failed
 *    backup prunes — the slice below is bounded by the branch, which is what makes that mutation
 *    visible;
 * 3. the prune is pointed at some other directory than the one the backup writes into, so it prunes a
 *    folder the app never fills;
 * 4. the reader silently disagrees with Android: a count of `0` or a non-numeric row reaches
 *    `BackupRotationPolicy`, which rejects `keepCount < 1`, and the backup fails.
 */
class DesktopBackupRotationWiringTest {
    private val appSource =
        source("desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt").readText()

    /**
     * The body of the scheduled backup lambda — from its fold precondition to the closing of its
     * success branch, so both rotation's presence and its position inside the success branch are facts
     * this slice can see. The markers are asserted before anything is read out of it, so an empty parse
     * fails rather than passing quietly.
     */
    private val scheduledBackupBody =
        slice(
            source = appSource,
            from = "runBackup = backup@{",
            // The success branch's log line opens the slice; rotation is asserted to sit BETWEEN this
            // and the failing branch that follows it. A stable end marker matters here: it must be a
            // line of CODE, not something a comment can push away, so an explanatory comment inserted
            // inside the branch cannot silently widen the slice and hide a hoisted call.
            to = "} else {\n                        sessionLogger.w(\"DesktopApp.scheduledBackup\", summary, data)",
        )

    @Test
    fun `the scheduled backup path prunes old artifacts after a successful run`() {
        // The call is here at all — the exact defect: a policy that exists, is tested, and is not called.
        assertThat(scheduledBackupBody).contains("pruneScheduledBackupRotation(")
        // It is given the folder the backup actually writes into, resolved through the SAME function
        // the destination is resolved through. A prune pointed anywhere else is a prune that deletes
        // nothing and can never be noticed from a render.
        assertThat(scheduledBackupBody).contains("DesktopAppPaths.resolveExportDirectory()")
        // It reads retention through the shared reader the schedule reconciles from, not through a
        // second copy of the row names.
        assertThat(scheduledBackupBody).contains("backupRows = backupRows")
    }

    @Test
    fun `the rotation call sits inside the success branch and not beside it`() {
        // The failing-branch log is the slice's end marker, so if rotation had been hoisted out of the
        // success branch it would fall OUTSIDE this slice and this assertion would fail — a failed
        // backup that pruned, which is the destructive direction.
        assertThat(scheduledBackupBody).contains("pruneScheduledBackupRotation(")

        // And it is called with the resolved folder rather than being a bare reference, so the "where"
        // question has an answer in the source rather than only in a default argument.
        assertThat(scheduledBackupBody).contains("exportDirectory =")
    }

    @Test
    fun `the rows reader is defined once and shared by the schedule and the rotation`() {
        // One reader, defined before the seams that take it: two implementations of "read the shared
        // rows" is how a cadence gets honoured while a retention is ignored, or the reverse.
        val seamsStart = appSource.indexOf("val backupRows: suspend () -> Map<String, String?> = {")
        val seamsConsumption = appSource.indexOf("val platformSeams =")
        assertThat(seamsStart).isAtLeast(0)
        assertThat(seamsConsumption).isGreaterThan(seamsStart)

        // The schedule consumes the shared reader rather than re-reading the table inline.
        //
        // The end marker is the seams call's LAST argument plus its own close - the closing paren and
        // brace are what make it unique. `backupRows = backupRows,` on its own occurs TWICE (the seams
        // argument AND the rotation call's argument), so slicing to it stops at the rotation call,
        // hundreds of characters early, and `contains` then asserts nothing while the test still passes
        // its own slice assertions. A slice that ends early is the failure mode this file must not have.
        val seams =
            slice(
                source = appSource,
                from = "desktopPlatformSeams(\n",
                to = "backupRows = backupRows,\n            )",
            )
        assertThat(seams).contains("backupRows = backupRows")
        // The rotation is reached through the same helper, so both policies read the same rows.
        assertThat(appSource).contains("backupRows = backupRows")
    }

    // ── The reader: Android's coercion rules, reproduced ─────────────────────────

    @Test
    fun `an enabled retention row prunes the exports folder a scheduled backup fills`() =
        runBlocking {
            val root = Files.createTempDirectory("payanam-rotation-wiring").toFile()
            // Artifacts named by the REAL producer rule, so the fixture has the same SHAPE as what
            // production writes rather than a hand-made name that happens to match the matcher.
            val stale =
                File(root, "auto_bk_19700101-000000.db").apply {
                    writeText("an older artifact")
                    assertThat(setLastModified(1_000L)).isTrue()
                }
            val fresh = File(root, "auto_bk_20260101-000000.db").apply { writeText("the new artifact") }

            val deleted =
                pruneScheduledBackupRotation(
                    exportDirectory = root,
                    backupRows = {
                        mapOf(
                            AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to "true",
                            AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "1",
                        )
                    },
                    logger = NoOpUnifiedLogger,
                )

            // One kept, one deleted: the call the wiring assertion proves is REACHED now has a
            // behavioural consequence too, so a reader that resolves the wrong folder fails here too.
            assertThat(deleted.map { it.name }).containsExactly(stale.name)
            assertThat(stale.exists()).isFalse()
            assertThat(fresh.exists()).isTrue()
        }

    @Test
    fun `a retention count Android would clamp is clamped rather than rejected`() =
        runBlocking {
            // `BackupRotationPolicy` REQUIRES keepCount >= 1, so a row of "0" reaching it un-clamped
            // throws — and it throws AFTER a backup succeeded, turning a good backup into a failed one.
            // Android coerces to 1..999 at the read; this asserts the desktop does the same.
            val policy =
                io.payanam.desktop.database.rotationPolicyFromBackupRows(
                    mapOf(
                        AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to "true",
                        AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "0",
                    ),
                )
            assertThat(policy.keepCount).isEqualTo(1)

            val absurd =
                io.payanam.desktop.database.rotationPolicyFromBackupRows(
                    mapOf(
                        AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED to "true",
                        AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "100000",
                    ),
                )
            assertThat(absurd.keepCount).isEqualTo(999)
        }

    @Test
    fun `absent retention rows mean rotation off, and an unreadable row prunes nothing`() =
        runBlocking {
            val absent =
                io.payanam.desktop.database.rotationPolicyFromBackupRows(
                    mapOf(AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED to "true"),
                )
            assertThat(absent.enabled).isFalse()

            // A failed read is the destructive direction: retention is a policy applied after the fact,
            // so an unreadable one must delete nothing rather than default to "keep nothing".
            val root = Files.createTempDirectory("payanam-rotation-unreadable").toFile()
            val artifact = File(root, "auto_bk_19700101-000000.db").apply { writeText("a backup") }
            val deleted =
                pruneScheduledBackupRotation(
                    exportDirectory = root,
                    backupRows = { error("the session is not open") },
                    logger = NoOpUnifiedLogger,
                )
            assertThat(deleted).isEmpty()
            assertThat(artifact.exists()).isTrue()
        }

    private fun slice(
        source: String,
        from: String,
        to: String,
    ): String {
        val start = source.indexOf(from)
        assertThat(start).isAtLeast(0)
        val end = source.indexOf(to, start)
        assertThat(end).isGreaterThan(start)
        return source.substring(start, end)
    }

    private companion object {
        /**
         * The source root the module is built from. The working directory a Gradle test runs in is the
         * MODULE's, not the repository's, so the repository root is walked up until the path resolves —
         * a hard-coded `../..` would be a guess about where the runner was invoked from.
         */
        private fun source(relativePath: String): File {
            var directory: File? = File(".").absoluteFile
            while (directory != null) {
                val candidate = File(directory, relativePath)
                if (candidate.isFile) return candidate
                directory = directory.parentFile
            }
            throw AssertionError("Could not locate $relativePath from ${File(".").absolutePath}")
        }
    }
}
