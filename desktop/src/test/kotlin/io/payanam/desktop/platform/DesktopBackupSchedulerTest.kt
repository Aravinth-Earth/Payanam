//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The desktop's backup SCHEDULE: the timer that turns a settings value into repeated runs.
 *
 * WHY THIS FILE EXISTS: every other test around the desktop backup proves the WRITE (the toggle reaches
 * the row) and the WORK (a run prunes what it should). Neither proves the thing the user actually set -
 * that an interval, once armed, fires the backup when it elapses and keeps firing - and that is what
 * "the interval takes effect without a restart" means. The intervals are whole minutes, so a real-clock
 * test could not reach the first fire inside a test run; virtual time can, and does it deterministically.
 *
 * The loop's own failure rule is pinned here too: a failing backup is caught, logged by class, and the
 * schedule survives - a scheduler that died on the first error would silently stop backing anything up.
 */
class DesktopBackupSchedulerTest {
    @Before
    fun setUp() {
        // The scheduler's default collaborator set reaches for the process-wide session logger; initialize
        // it here rather than relying on a sibling class's leak. The directory outlives this class on
        // purpose, so a leaked instance stays writable for whatever runs next in the shared JVM.
        io.payanam.desktop.UnifiedLogger
            .resetForTests()
        io.payanam.desktop.UnifiedLogger.initialize(
            logsDirectory = Files.createTempDirectory("payanam-backup-scheduler-test"),
        )
    }

    @After
    fun tearDown() {
        io.payanam.desktop.UnifiedLogger
            .resetForTests()
        io.payanam.desktop.UnifiedLogger.initialize(
            logsDirectory = Files.createTempDirectory("payanam-backup-scheduler-after"),
        )
    }

    private fun logger() =
        io.payanam.desktop.UnifiedLogger
            .getInstance()

    @Test
    fun `the schedule fires the backup when the interval elapses, and again on the next one`() =
        runTest {
            var runs = 0
            val scheduler = DesktopBackupScheduler(this, logger()) { runs++ }

            scheduler.schedule(15)
            assertThat(scheduler.intervalMinutes).isEqualTo(15)

            advanceTimeBy(15 * MILLIS_PER_MINUTE - 1)
            assertThat(runs).isEqualTo(0) // not a moment early

            advanceTimeBy(2)
            assertThat(runs).isEqualTo(1)

            // The loop REPEATS: an implementation that ran once would pass everything above.
            advanceTimeBy(15 * MILLIS_PER_MINUTE)
            assertThat(runs).isEqualTo(2)

            scheduler.cancel()
        }

    @Test
    fun `rescheduling replaces the armed interval rather than stacking a second loop`() =
        runTest {
            var runs = 0
            val scheduler = DesktopBackupScheduler(this, logger()) { runs++ }

            scheduler.schedule(15)
            scheduler.schedule(30) // REPLACES: the settings screen reconciles on every change

            advanceTimeBy(15 * MILLIS_PER_MINUTE)
            assertThat(runs).isEqualTo(0) // the 15-minute arm is gone

            advanceTimeBy(15 * MILLIS_PER_MINUTE + 2)
            assertThat(runs).isEqualTo(1) // and the 30-minute one fires once, not twice

            scheduler.cancel()
        }

    @Test
    fun `cancelling stops the loop and clears the interval`() =
        runTest {
            var runs = 0
            val scheduler = DesktopBackupScheduler(this, logger()) { runs++ }

            scheduler.schedule(15)
            advanceTimeBy(15 * MILLIS_PER_MINUTE + 2)
            assertThat(runs).isEqualTo(1)

            scheduler.cancel()
            assertThat(scheduler.intervalMinutes).isNull()

            advanceTimeBy(60 * MILLIS_PER_MINUTE)
            assertThat(runs).isEqualTo(1) // nothing further
        }

    @Test
    fun `a failing backup is caught and the schedule survives it`() =
        runTest {
            var runs = 0
            val scheduler =
                DesktopBackupScheduler(this, logger()) {
                    runs++
                    if (runs == 1) error("first run fails")
                }

            scheduler.schedule(15)
            advanceTimeBy(15 * MILLIS_PER_MINUTE + 2)
            assertThat(runs).isEqualTo(1)

            // A scheduler that died with its first failure would stop backing anything up, silently.
            advanceTimeBy(15 * MILLIS_PER_MINUTE)
            assertThat(runs).isEqualTo(2)

            scheduler.cancel()
        }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
