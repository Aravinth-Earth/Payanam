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
 * every one of those tests passed while the running app pruned nothing - the port has no production
 * constructor, so the class that owned the policy was a class nothing called. The live scheduled path
 * wrote a backup artifact and returned. On Android the same fact is not true: the coordinator prunes on
 * EVERY backup through it, so a retention row there is honoured by each run.
 *
 * **How the wiring is asserted now (2026-10-08 shape).** The scheduled path and the Run-Backup-Now
 * path both construct `BackupCoordinatorCore` and hand it `rotationRows = backupRows`; rotation runs
 * inside the core, after `recordSuccess`, in the success branch only. So the wiring facts this file
 * pins are: the desktop constructs the core with the shared reader and the real backup directory, the
 * core reaches cleanup only after a success record (never in its failure branch), and the reader is
 * still defined once. The retention READER stays asserted behaviourally, because the reader's job is
 * to reproduce Android's own coercion rules rather than to be called.
 *
 * **The mutants this is built to catch:**
 *
 * 1. the core construction is deleted from the scheduled path (the rotation call disappears with it)
 *    - the behavioural cases below keep passing, which is precisely why the wiring needs its own
 *    assertion;
 * 2. cleanup is moved out of the success path so a failed backup prunes - the position assertions on
 *    the core source make that mutation visible;
 * 3. the prune is pointed at some other directory than the one the backup writes into;
 * 4. the reader silently disagrees with Android: a count of `0` or a non-numeric row reaches
 *    `BackupRotationPolicy`, which rejects `keepCount < 1`, and the backup fails.
 */
class DesktopBackupRotationWiringTest {
    private val appSource =
        source("desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt").readText()

    private val coreSource =
        source("core/backup/src/main/kotlin/io/payanam/backup/BackupCoordinatorCore.kt").readText()

    /**
     * The body of `runScheduledDesktopBackup`, matched by BRACES from its signature so the slice is
     * exactly the function no matter how its interior is reordered.
     */
    private val hoistedBackupBody: String by lazy {
        braceBody(appSource, "private suspend fun runScheduledDesktopBackup(")
    }

    /**
     * The text INSIDE the `withContext(BACKUP_DISPATCHER)` hop, again by brace match - the block whose
     * contents are asserted to be doing the file work off the caller's thread.
     */
    private val backupHopBlock: String by lazy {
        braceBody(hoistedBackupBody, "withContext(BACKUP_DISPATCHER) {")
    }

    @Test
    fun `the scheduled backup path prunes old artifacts after a successful run`() {
        // The wiring: the running path CONSTRUCTS the shared coordinator core - the exact defect this
        // file exists for was a policy that exists, is tested, and is not called by production.
        assertThat(hoistedBackupBody).contains("BackupCoordinatorCore(")
        // Rotation reaches the folder the backup actually writes into, resolved through the SAME
        // function the destination is resolved through. A prune pointed anywhere else deletes nothing
        // and can never be noticed from a render.
        assertThat(hoistedBackupBody).contains("backupDirectory = DesktopAppPaths.resolveDatabaseDirectory()")
        // It reads retention through the shared reader the schedule reconciles from, not through a
        // second copy of the row names.
        assertThat(hoistedBackupBody).contains("rotationRows = backupRows")
        // The Run-Backup-Now seam is the SAME lambda, so the manual path prunes through the core too.
        assertThat(appSource).contains("runBackup = { runScheduledDesktopBackup(")
    }

    @Test
    fun `the rotation call sits inside the success branch and not beside it`() {
        // In the shared core: cleanup is reached only AFTER recordSuccess, inside the success path.
        // The position is asserted on the core source, since that is where the branch lives now.
        val recordSuccessIndex = coreSource.indexOf("recordSuccess(recordedAtMillis)")
        val cleanupIndex = coreSource.indexOf("cleanupOldBackups(backupRootDir")
        assertThat(recordSuccessIndex).isAtLeast(0)
        assertThat(cleanupIndex).isGreaterThan(recordSuccessIndex)

        // The failure branch records a failure and NEVER prunes: the getOrElse block must contain no
        // cleanup call, so a failed backup cannot prune.
        val failureBranch = braceBody(coreSource, ".getOrElse { error ->")
        assertThat(failureBranch).contains("recordFailure(finalMessage)")
        assertThat(failureBranch).doesNotContain("cleanupOldBackups(")
    }

    @Test
    fun `the rows reader is defined once and shared by the schedule and the rotation`() {
        // One reader, defined before the seams that take it: two implementations of "read the shared
        // rows" is how a cadence gets honoured while a retention is ignored, or the reverse.
        val seamsStart = appSource.indexOf("val backupRows: suspend () -> Map<String, String?> = {")
        val seamsConsumption = appSource.indexOf("val platformSeams =")
        assertThat(seamsStart).isAtLeast(0)
        assertThat(seamsConsumption).isGreaterThan(seamsStart)

        // The schedule consumes the shared reader rather than re-reading the table inline, and the
        // same reader instance is what the core rotates with.
        val seams =
            slice(
                source = appSource,
                from = "desktopPlatformSeams(\n",
                to = "\n            )",
            )
        assertThat(seams).contains("backupRows = backupRows")
        assertThat(hoistedBackupBody).contains("rotationRows = backupRows")
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
                    backupDirectory = root,
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
            // throws - and it throws AFTER a backup succeeded, turning a good backup into a failed one.
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
                    backupDirectory = root,
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

    // ── The file work, off the thread the UI is drawn on ───────────────────────

    @Test
    fun `the backup's blocking file work runs on the IO dispatcher and not the caller's`() {
        // The dispatcher is NAMED and bound to IO. Naming it is what the module idiom does - a bare
        // `withContext(Dispatchers.IO)` inline would pass a `contains("Dispatchers.IO")` check while
        // leaving nothing for the KDoc to point at, and nothing for a reviewer to grep.
        assertThat(appSource).contains("private val BACKUP_DISPATCHER: CoroutineDispatcher = Dispatchers.IO")

        // The dispatcher is NOT a main-thread one. `Dispatchers.Main` on Compose Desktop is the AWT
        // event dispatch thread - the very thread being frozen - and `Unconfined`/`Default` run the
        // caller inline or on a CPU pool that will still block the caller for the length of the copy.
        // Comments are stripped before this scan precisely so the KDoc that NAMES these, and the
        // explanatory comments below the hop, cannot satisfy or defeat it.
        val code = code(appSource)
        for (mainThread in listOf("Dispatchers.Main", "Dispatchers.Unconfined", "Dispatchers.Default")) {
            assertThat(code).doesNotContain(mainThread)
        }

        // THE PROPERTY, not a string: every blocking step is INSIDE the hop - the temp directory
        // creation, the core run (snapshot copy + verify + rotation), and the log.
        assertThat(backupHopBlock).contains("Files.createTempDirectory(")
        assertThat(backupHopBlock).contains("core.backupToAppBackupDirectory(")
        // The backup directory is resolved inside the hop, so the destination rule's stats and
        // directory creation are file work on the IO dispatcher too.
        assertThat(backupHopBlock).contains("DesktopAppPaths.resolveDatabaseDirectory()")

        // And the whole hop is inside the function, not merely somewhere in the file.
        assertThat(hoistedBackupBody).contains("withContext(BACKUP_DISPATCHER)")
    }

    @Test
    fun `the recursive delete is inside the hop without its own signature being changed`() {
        // `pruneOldBackupDirectories` is NOT suspend and stays that way - it has direct test callers,
        // so the delete moves off the UI thread by changing WHO CALLS it, not by changing what it is.
        assertThat(appSource).doesNotContain("suspend fun pruneOldBackupDirectories(")

        // The core call - the only path that reaches the recursive delete from the scheduled run -
        // is inside the hop, so the delete runs on the IO dispatcher. Asserted through the core run
        // rather than through `deleteRecursively`, because the delete lives in the core/ports files
        // and this slice cannot see into them: the call being wrapped is what is in scope here.
        assertThat(backupHopBlock).contains("backupToAppBackupDirectory(")
        // Still pointed at the folder the backup writes into, still reading retention through the one
        // shared reader - the hop moved the thread and nothing else.
        assertThat(hoistedBackupBody).contains("backupDirectory = DesktopAppPaths.resolveDatabaseDirectory()")
        assertThat(hoistedBackupBody).contains("rotationRows = backupRows")
    }

    @Test
    fun `the backup artifact is what it was before the thread moved`() {
        // The gate is about the THREAD, so the artifact must be provably untouched in its essentials:
        // same backup directory, same shared session-folder naming rule, rotation still reached only
        // after a success. A "fix" that wrote somewhere easier, or renamed the folder to dodge the
        // recursive delete, fails here.
        assertThat(hoistedBackupBody).contains("backupDirectory = DesktopAppPaths.resolveDatabaseDirectory()")
        // The session folder name still comes from the desktop's own backup timestamp rule, so the
        // artifact keeps its auto_bk_ identity (the session-DIR shape is the expected parity change).
        assertThat(hoistedBackupBody).contains("\"auto_bk_\" + LocalDateTime.now().format(BACKUP_FILE_TIMESTAMP)")
        // Rotation stays INSIDE the success branch: recordSuccess then cleanup, failure branch clean.
        val recordSuccessIndex = coreSource.indexOf("recordSuccess(recordedAtMillis)")
        val cleanupIndex = coreSource.indexOf("cleanupOldBackups(backupRootDir")
        assertThat(recordSuccessIndex).isAtLeast(0)
        assertThat(cleanupIndex).isGreaterThan(recordSuccessIndex)
        val failureBranch = braceBody(coreSource, ".getOrElse { error ->")
        assertThat(failureBranch).doesNotContain("cleanupOldBackups(")
    }

    /** Everything from a marker to the brace its block CLOSES on, so a slice cannot run past it. */
    private fun braceBody(
        source: String,
        from: String,
    ): String {
        val start = source.indexOf(from)
        assertThat(start).isAtLeast(0)
        val open = source.indexOf("{", start)
        assertThat(open).isGreaterThan(start)
        var depth = 0
        for (index in open until source.length) {
            val character = source[index]
            if (character == '{') depth++
            if (character == '}') {
                depth--
                if (depth == 0) return source.substring(open, index + 1)
            }
        }
        throw AssertionError("Unbalanced braces after $from")
    }

    /** The file with comments removed, so a scan judges CODE and not prose about the code. */
    private fun code(source: String): String =
        source
            .replace(BLOCK_COMMENT, "")
            .replace(LINE_COMMENT, "")

    private companion object {
        // Comments are stripped before any dispatcher scan: this file's own KDoc NAMES
        // `Dispatchers.Main` and `Dispatchers.Unconfined` to explain why they are banned here, and a
        // scan that counted prose would fail on its own documentation.
        private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("//[^\\n]*")

        /**
         * The source root the module is built from. The working directory a Gradle test runs in is the
         * MODULE's, not the repository's, so the repository root is walked up until the path resolves -
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
