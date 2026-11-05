//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbs
import io.payanam.shared.logging.UnifiedLogger
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId

/**
 * Decision tests for [DesktopCrashBreadcrumbStore], the desktop half of the crash-safe breadcrumb trail.
 *
 * The store exists for one situation: the process dies without warning (`kill -9`, an OOM kill, a
 * segfault), and the NEXT start has to be able to read what the dead one was doing. So every case
 * below is chosen for what it would take for that trail to be gone at exactly the moment somebody
 * looks for it - a dropped line, a line attributed to the wrong session, a timestamp that cannot be
 * compared against anything, or a file that grows until the write that matters is the one that fails.
 *
 * **Why fixed literals and a fixed clock.** The obvious version of this test records something and
 * asserts the result is "not null", which passes for any string the store could ever produce,
 * including an empty one. Every expected value here is instead a byte-exact line spelled out in this
 * file, produced by pinning the two injected seams - `clock` returns a fixed [FIXED_INSTANT] and
 * `zone` a fixed zone - so the rendered timestamp is a constant and the assertion is about the
 * store's decisions (which fields, which order, which separator) rather than about the wall clock.
 *
 * **The size cases are written against the real 512 KiB cap, not a shrunk stand-in.** A smaller cap
 * would have to be a constructor parameter, and the store has none - so a test that pretended the cap
 * was injectable would be testing a class that does not exist. Instead the fixture is a real file
 * sized to sit a known number of bytes under the cap, which makes the boundary exact rather than
 * approximate: one byte under and the append lands, and it lands ON the cap, not one byte past it.
 *
 * Style follows the sibling desktop tests: Truth for assertions, `TemporaryFolder` for real files
 * (`DesktopSecurityStorePersistenceTest`, `DesktopPreferencesSourceTest`), and the decision-first
 * comments of the Android sibling `SharedPreferencesBreadcrumbStoreTest`, which covers the same
 * contract over SharedPreferences.
 */
class DesktopCrashBreadcrumbStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val capturedLog = mutableListOf<String>()

    @Before
    fun setUp() {
        // Both holders are process-wide and the module runs every test class in one JVM (`forkEvery`
        // is unset in the build), so a logger or store left installed here would decide what a later
        // class sees. `AppLogger`'s own contract asks for a reset in `@Before` AND `@After`.
        AppLogger.resetForTests()
        AppLogger.install(capturingLogger())
        CrashBreadcrumbs.resetForTests()
        capturedLog.clear()
    }

    @After
    fun tearDown() {
        CrashBreadcrumbs.resetForTests()
        AppLogger.resetForTests()
    }

    @Test
    fun `a record reads back as the exact line the injected clock and zone rendered`() {
        val store = storeOver(trailFile(), zone = UTC)

        store.record(source = SOURCE, stage = "step_one")
        store.record(source = SOURCE, stage = "step_two")
        store.record(source = SOURCE, stage = "step_three")

        val trail = store.readAndClear()

        // Whole lines, compared whole. The three differ from one another only in the stage, so a
        // store that wrote a constant, dropped a field, or reordered the entries could not produce
        // this list - and order is content here: a trail replayed backwards reads as a process that
        // ran its steps in the wrong order, which is a different and wrong diagnosis from the one
        // that happened.
        assertThat(trail)
            .containsExactly(
                "2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | step_one",
                "2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | step_two",
                "2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | step_three",
            ).inOrder()
    }

    @Test
    fun `the second read is empty and the trail file is truncated rather than deleted`() {
        val file = trailFile()
        val store = storeOver(file, zone = UTC)
        store.record(source = SOURCE, stage = "step_one")

        assertThat(store.readAndClear()).hasSize(1)

        // The session-attribution guarantee: a trail drained twice replays the same steps into every
        // later session, so the next start would "recover" a crash that is already explained.
        assertThat(store.readAndClear()).isEmpty()
        // And the clear is a truncate, not a delete (the store's own reason: a delete opens a window
        // in which a breadcrumb recorded by the recovering process is destroyed by the removal).
        assertThat(Files.exists(file)).isTrue()
        assertThat(Files.size(file)).isEqualTo(0L)
    }

    @Test
    fun `one instant rendered in three zones gives three different, exact timestamps`() {
        // The zone is a seam the store passes straight to the shared formatter, so it decides the
        // hours in the trail. Without pinning it the only assertable thing is "now", which no failure
        // can be measured against; with it, a store that dropped the zone on the way to the formatter
        // would render the UTC hours for every machine and this would go red.
        assertThat(storeOver(trailFile(), zone = UTC).recordAndDrain("step_one"))
            .isEqualTo("2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | step_one")
        assertThat(storeOver(trailFile(), zone = KOLKATA).recordAndDrain("step_one"))
            .isEqualTo("2026-03-04 10:36:07.008 | DesktopBreadcrumbStoreTest | step_one")
        assertThat(storeOver(trailFile(), zone = KATHMANDU).recordAndDrain("step_one"))
            .isEqualTo("2026-03-04 10:51:07.008 | DesktopBreadcrumbStoreTest | step_one")
    }

    @Test
    fun `a fresh store over the same file still sees the trail - that is what surviving a crash means`() {
        val file = trailFile()
        storeOver(file, zone = UTC).record(source = SOURCE, stage = "step_before_process_death")

        // A NEW instance over the same path stands in for the next process. The trail is in the file,
        // not in a field of the store object, so nothing about this process's death can take it -
        // which is the entire reason the store is file-backed and forces each line to the device.
        val afterRestart = storeOver(file, zone = UTC)

        assertThat(afterRestart.readAndClear()).containsExactly(
            "2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | step_before_process_death",
        )
    }

    @Test
    fun `a stage carrying a newline still lands as one line, so the ring bound stays meaningful`() {
        val file = trailFile()
        val store = storeOver(file, zone = UTC)

        store.record(source = SOURCE, stage = "step\nforged_line")

        // The file is line-delimited and both the ring bound and the drain depend on one entry per
        // line, so a raw newline surviving into the bytes would let one breadcrumb forge a second
        // one - and would split a single recorded step across two replayed entries.
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8))
            .containsExactly("2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | step forged_line")
        assertThat(store.readAndClear()).hasSize(1)
    }

    @Test
    fun `a record that lands exactly on the size cap is written`() {
        val file = trailFile()
        // The file is a real one line short of the cap after the fixture, so the append this store
        // performs lands EXACTLY on the cap - the boundary case, in the direction that must work.
        val payloadBytes = "$ENTRY_ON_CAP\n".toByteArray(StandardCharsets.UTF_8).size.toLong()
        val fillerBytes = MAX_FILE_BYTES - payloadBytes
        writeFiller(file, fillerBytes)
        assertThat(Files.size(file)).isEqualTo(fillerBytes)

        storeOver(file, zone = UTC).record(source = SOURCE, stage = "size_boundary")

        // GOES RED if the size guard at DesktopMain.kt:545 ever changes `>` to `>=`: the record would
        // be skipped and this would still read `fillerBytes`, one payload short of the cap.
        assertThat(Files.size(file)).isEqualTo(MAX_FILE_BYTES)
        // `containsExactly` WITHOUT `.inOrder()` here, unlike the first case above, and deliberately:
        // this file's content is one pre-existing filler line plus whatever the store appends, and the
        // store holds no ordering contract over content it did not write. The multiset is the claim.
        assertThat(storeOver(file, zone = UTC).readAndClear())
            .containsExactly(
                "$ENTRY_ON_CAP",
                "f".repeat((fillerBytes - 1).toInt()),
            )
    }

    @Test
    fun `a record that would pass the cap is skipped whole, leaving the bytes already on disk`() {
        val file = trailFile()
        // A file sitting exactly ON the cap: one more byte of any size is over it, so the next
        // record is the one the guard exists for.
        writeFiller(file, MAX_FILE_BYTES)
        val before = Files.readString(file, StandardCharsets.UTF_8)
        val store = storeOver(file, zone = UTC)

        store.record(source = SOURCE, stage = "size_overflow")

        // Byte-identical, which covers both halves of the decision at DesktopMain.kt:545-547: the
        // write is skipped rather than throwing at the caller, and it is skipped rather than
        // truncating to make room. A truncation would silently discard the crash-adjacent entries
        // the cap exists to protect, which is worse than the disk filling up.
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(before)
        assertThat(Files.size(file)).isEqualTo(MAX_FILE_BYTES)
        // And the skipped breadcrumb is genuinely absent - a "handled" write that left the line
        // behind anyway would replay a step the process never reached.
        assertThat(store.readAndClear().none { it.contains("size_overflow") }).isTrue()
    }

    @Test
    fun `a store whose trail file is absent answers with an empty trail and creates nothing`() {
        val file = trailFile()

        val trail = storeOver(file, zone = UTC).readAndClear()

        assertThat(trail).isEmpty()
        // The absence is read, not repaired: the guard at DesktopMain.kt:576 returns before touching
        // the disk, so a drain must not leave behind the file (or the directories) it looked for.
        // GOES RED if that early return were ever replaced by a create-then-read.
        assertThat(Files.exists(file)).isFalse()
    }

    @Test
    fun `a store whose trail file is empty answers with an empty trail and throws nothing`() {
        val file = trailFile()
        Files.write(file, ByteArray(0))

        val trail = storeOver(file, zone = UTC).readAndClear()

        // An empty file is a normal state - nothing recorded yet, or just drained - so the drain
        // path runs for real here: parse, trim, then truncate-and-force over a zero-length file.
        assertThat(trail).isEmpty()
        assertThat(Files.size(file)).isEqualTo(0L)
    }

    @Test
    fun `a record creates the missing parent directories and the trail file`() {
        val file = tempFolder.newFolder().toPath().resolve("runtime/nested/deeper/crash-breadcrumbs.log")

        storeOver(file, zone = UTC).record(source = SOURCE, stage = "step_one")

        // Real deployment writes into the app's runtime directory on a first start, which is exactly
        // when nothing under it exists yet. GOES RED if the directory creation at DesktopMain.kt:539
        // were ever dropped: the record would throw NoSuchFileException at the caller, i.e. on the
        // one breadcrumb that exists to explain a first-run crash.
        assertThat(Files.exists(file)).isTrue()
        assertThat(storeOver(file, zone = UTC).readAndClear())
            .containsExactly("2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | step_one")
    }

    @Test
    fun `data values are redacted through the shared sanitizer, and only the values`() {
        val store = storeOver(trailFile(), zone = UTC)

        store.record(
            source = SOURCE,
            stage = "redaction",
            data = mapOf("note" to "a private note", "processCount" to 3),
        )

        // The detail segment is asserted whole, not with `contains`: a store that rendered
        // `<redacted>` for the count too, or dropped the key, or reordered the pairs, would all pass
        // a substring check. The count survives because numbers are diagnostics, and it is asserted
        // here so the redaction rule cannot quietly widen to swallow counts as well.
        assertThat(store.readAndClear()).containsExactly(
            "2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | redaction | note=<redacted>,processCount=3",
        )
    }

    @Test
    fun `the trail file is tightened to owner-only, and re-tightened after it is loosened`() {
        val file = trailFile()
        val store = storeOver(file, zone = UTC)

        store.record(source = SOURCE, stage = "step_one")
        assertThat(Files.getPosixFilePermissions(file))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"))

        // Asserted without a POSIX guard, matching `DesktopSecurityStorePersistenceTest`, which
        // makes the same assertion over the same kind of temp folder: a filesystem that cannot
        // express POSIX modes is one the app's own packaging does not target, and the store's answer
        // there is to keep writing rather than to refuse (DesktopMain.kt:606-608).
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))

        store.record(source = SOURCE, stage = "step_two")

        // The trail describes what the user was doing and outlives the process, so it stays readable
        // only by the user who owns it. GOES RED if the tightening at DesktopMain.kt:557 were ever
        // moved inside a branch that only runs on the first write: the second entry here would then
        // leave the file group- and world-writable.
        assertThat(Files.getPosixFilePermissions(file))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"))
        assertThat(store.readAndClear()).hasSize(2)
    }

    @Test
    fun `location names the file the trail lives in`() {
        val file = trailFile()

        assertThat(storeOver(file, zone = UTC).location).isEqualTo(file.toString())
    }

    @Test
    fun `an unwritable trail is reported as one log line, not as an exception at the recorded step`() {
        // A breadcrumb is raised BEFORE the step it describes, so the step must be attempted whether
        // or not the note could be written. This drives the production entry point -
        // `CrashBreadcrumbs.record`, which is what `DesktopMain.kt:82` calls - over a store whose
        // parent path is a regular file, so the directory creation the store attempts cannot succeed.
        val blocker = tempFolder.newFile("runtime-is-a-file").toPath()
        val store =
            DesktopCrashBreadcrumbStore(
                trailFile = blocker.resolve("crash-breadcrumbs.log"),
                clock = { FIXED_INSTANT },
                zone = UTC,
            )
        CrashBreadcrumbs.install(store)

        CrashBreadcrumbs.record(source = SOURCE, stage = "step_with_unwritable_parent")

        // Not merely "did not throw": the failure reached a logger with the stage named, because a
        // store that reported nothing would be indistinguishable from one that had nothing to record.
        // GOES RED if the report at `CrashBreadcrumbs.record` were dropped, or renamed - a store that
        // threw instead is not exercised here on purpose, since that would pin the store's choice to
        // propagate rather than pin the caller's promise not to be interrupted.
        assertThat(
            capturedLog.any { line ->
                line.startsWith("WARN CrashBreadcrumbs.record ") &&
                    line.contains("Failed to persist crash-safe breadcrumb") &&
                    line.contains("step_with_unwritable_parent")
            },
        ).isTrue()
    }

    @Test
    fun `a failed clear still hands the caller the trail it just read, and reports the clear separately`() {
        // The failure being forced here is the CLEAR, not the read. A trail file left mode-read-only
        // (r--r--r--) is readable but cannot be opened for writing, which is exactly the split the
        // production code now depends on: `Files.readString` succeeds, the `RandomAccessFile("rw")`
        // that truncates it does not. Measured on JDK 17: the read returns the content and the
        // truncate raises FileNotFoundException("Permission denied").
        val file = trailFile()
        val store = storeOver(file, zone = UTC)
        store.record(source = SOURCE, stage = "step_before_clearing_broke")
        makeTrailUnwritable(file)
        assumeTrue(
            "a mode-read-only trail file is still writable as root, so this cannot force a clear failure",
            fileCannotBeTruncated(file),
        )

        val trail = store.readAndClear()

        // THE claim, and the reason this store exists: the caller is told what the dead process was
        // doing. GOES RED if the read and the clear are ever put back into one `runCatching` again -
        // the handler would answer with `emptyList()` and the caller would report "no breadcrumbs"
        // for a crash whose trail it had just read off the disk. Production line that must change:
        // the `getOrElse { ... emptyList() }` around the read in DesktopMain.kt:612-620.
        assertThat(trail).containsExactly("2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | step_before_clearing_broke")
        // And the trail really was on disk, so "the caller got an empty list" could not be confused
        // with "there was nothing there": the file is non-empty and was read successfully.
        assertThat(Files.size(file)).isGreaterThan(0L)
        // The honest degradation the KDoc promises: the truncate failed, so the bytes survive to be
        // replayed next start. Asserted because the alternative failure mode - a store that reports
        // the clear failure but truncates anyway - is not observable from the return value alone.
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
            .contains("step_before_clearing_broke")
    }

    @Test
    fun `a drain failure reaches the session log, not the error stream`() {
        // The same forced clear failure as the case above, asserted on the CHANNEL rather than on the
        // data: this store holds no logger of its own, so it reports through the `AppLogger` seam the
        // rest of the desktop reports through, and `AppLogger.current` is what the test class installs.
        val file = trailFile()
        val store = storeOver(file, zone = UTC)
        store.record(source = SOURCE, stage = "step_with_unclearable_trail")
        makeTrailUnwritable(file)
        assumeTrue(
            "a mode-read-only trail file is still writable as root, so this cannot force a clear failure",
            fileCannotBeTruncated(file),
        )
        val before = capturedLog.size

        val trail = store.readAndClear()

        assertThat(trail).hasSize(1)
        // The report names the store, names the step that failed, and carries the reason - all three,
        // because a bare "drain failed" in a log that has just recovered a crash trail tells nobody
        // which half of the drain failed or against which file. GOES RED if `reportDrainFailure`
        // (DesktopMain.kt:652-668) is deleted, or its `AppLogger.current.w` call is dropped, or the
        // report is sent to `System.err` as it was: stderr is not captured by `capturedLog`, so the
        // old `System.err.println` line fails this assertion exactly as it should.
        val reported = capturedLog.drop(before)
        assertThat(
            reported.any { line ->
                line.startsWith("WARN DesktopCrashBreadcrumbStore ") &&
                    line.contains("Failed to clear the crash-safe breadcrumb trail") &&
                    line.contains("step=clear") &&
                    line.contains("location=$file")
            },
        ).isTrue()
        // The read half is reported under its own step name rather than folded into the clear's, so a
        // future reader can tell which operation degraded without counting log lines.
        assertThat(reported.none { line -> line.contains("step=read") }).isTrue()
    }

    @Test
    fun `an unreadable trail is reported to the session log and answers with an empty trail`() {
        // A DIRECTORY at the trail path makes the READ fail (`Files.readString` raises
        // IOException("Is a directory")), which is the one failure this store can produce without
        // permissions and therefore without a root guard. Measured on JDK 17.
        val asDirectory = tempFolder.newFolder("trail-is-a-directory").toPath()
        val store = storeOver(asDirectory, zone = UTC)

        val trail = store.readAndClear()

        // Nothing to return - there is no file to read - but the failure is reported rather than
        // swallowed, so a start that found an unreadable trail says so instead of looking clean.
        assertThat(trail).isEmpty()
        assertThat(
            capturedLog.any { line ->
                line.startsWith("WARN DesktopCrashBreadcrumbStore ") &&
                    line.contains("Failed to read the crash-safe breadcrumb trail") &&
                    line.contains("step=read")
            },
        ).isTrue()
    }

    @Test
    fun `record propagates its I-O failure, which is the store's documented deviation from the port`() {
        // The third documented contract, pinned against the CODE rather than against the port's
        // wording. `CrashBreadcrumbStore.record`'s KDoc says an implementation MUST NOT throw, and
        // this store's own KDoc says its I/O failures propagate; those two statements cannot both
        // describe this method, so this test says which one the code implements.
        //
        // It propagates. GOES RED if `record` ever gains a `runCatching` - i.e. if the store ever
        // starts swallowing, which would move the reporting point away from `CrashBreadcrumbs.record`,
        // the only layer that knows the breadcrumb's own source and stage.
        //
        // Deliberately NOT driven through `CrashBreadcrumbs.record`: the entry point is what makes the
        // deviation safe on every real call path, so a test that used it would prove nothing about
        // this method's own behaviour.
        val blocker = tempFolder.newFile("runtime-is-a-file").toPath()

        val thrown =
            assertThrows(IOException::class.java) {
                storeOver(blocker.resolve("crash-breadcrumbs.log"), zone = UTC)
                    .record(source = SOURCE, stage = "step_that_cannot_be_written")
            }

        // A named exception, not just "something was thrown": `runCatching` around the whole body
        // would satisfy a bare `assertThrows(Throwable...)`, and a wrapper that swallowed into a bare
        // `RuntimeException` would be a worse failure than one that propagates.
        assertThat(thrown).hasMessageThat().contains("runtime-is-a-file")
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────

    /**
     * Makes the trail file readable but not writable, so a drain's read succeeds and its clear fails.
     *
     * Mode `r--r--r--` rather than `chmod` or a directory: the store opens the file for writing
     * through `RandomAccessFile("rw")`, and POSIX permission bits are the one thing that stops that
     * while leaving `Files.readString` working. Portable to any POSIX filesystem - verified on the
     * tmpfs this machine's tests run on - and it is the failure the production split is written for.
     *
     * It is NOT portable to root, which bypasses the permission bits entirely. The tests that use it
     * guard with `fileCannotBeTruncated` rather than assuming, so a root-run CI reports a skip instead
     * of a false green.
     */
    private fun makeTrailUnwritable(target: Path) {
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"))
    }

    /**
     * Whether [target] genuinely cannot be truncated in this process, checked by trying.
     *
     * The guard exists because the alternative is a test that silently stops testing: under root the
     * permission bits above are ignored, the clear would succeed, and an assertion that the trail
     * survived would still pass - a false green on the one behaviour that matters here. Probing is
     * the only honest form of the check; a "am I root" test would be a proxy for it.
     */
    private fun fileCannotBeTruncated(target: Path): Boolean =
        runCatching {
            RandomAccessFile(target.toFile(), "rw").use { it.setLength(0) }
        }.isFailure

    private fun trailFile(): Path = tempFolder.newFolder().toPath().resolve(CRASH_BREADCRUMB_FILE)

    private fun storeOver(
        file: Path,
        zone: ZoneId,
    ) = DesktopCrashBreadcrumbStore(
        trailFile = file,
        clock = { FIXED_INSTANT },
        zone = zone,
    )

    /** Records one entry and drains it, for the cases that only care about the rendered line. */
    private fun DesktopCrashBreadcrumbStore.recordAndDrain(stage: String): String {
        record(source = SOURCE, stage = stage)
        return readAndClear().single()
    }

    /**
     * Writes a trail file of exactly [bytes] bytes, as ONE line.
     *
     * A single line on purpose: the cap cases care about bytes on disk, and a many-line filler would
     * make the drained trail's size a second thing to reason about. The content is a filler character
     * rather than a rendered breadcrumb because this file is a stand-in for a process that was killed
     * repeatedly without ever getting far enough to drain - which is the situation the cap defends.
     */
    private fun writeFiller(
        file: Path,
        bytes: Long,
    ) {
        Files.write(
            file,
            ("f".repeat((bytes - 1).toInt()) + "\n").toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun capturingLogger(): UnifiedLogger =
        object : UnifiedLogger {
            override fun d(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) = Unit

            override fun i(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) {
                capturedLog += "INFO $source $message $data"
            }

            override fun w(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) {
                capturedLog += "WARN $source $message $data"
            }

            override fun e(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit

            override fun eSync(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit
        }

    private companion object {
        /**
         * A fixed clock, so a rendered timestamp is a constant this file can spell out instead of a
         * value compared against the machine's idea of now. 05:06:07.008 UTC on this date.
         */
        private val FIXED_INSTANT: Instant = Instant.parse("2026-03-04T05:06:07.008Z")

        private val UTC: ZoneId = ZoneId.of("UTC")

        /** +05:30, the project's own zone and half an hour off UTC - not a whole-hour offset. */
        private val KOLKATA: ZoneId = ZoneId.of("Asia/Kolkata")

        /** +05:45: a quarter-hour offset, which a whole-hour arithmetic mistake could not produce. */
        private val KATHMANDU: ZoneId = ZoneId.of("Asia/Kathmandu")

        private const val SOURCE = "DesktopBreadcrumbStoreTest"

        private const val CRASH_BREADCRUMB_FILE = "crash-breadcrumbs.log"

        /** The byte-exact entry `DesktopMain.kt:540` renders for the cap-boundary case above. */
        private const val ENTRY_ON_CAP =
            "2026-03-04 05:06:07.008 | DesktopBreadcrumbStoreTest | size_boundary"

        /** The store's own ceiling, mirrored from `DesktopMain.kt:620` because it is not injectable. */
        private const val MAX_FILE_BYTES: Long = 512L * 1024L
    }
}
