//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.backfill.ScoreRollupBackfillService
import io.payanam.presentation.flags.FeatureFlagsState
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.scoring.RecurrenceManager
import io.payanam.shared.platform.StartupRecoveryStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * `AndroidStartupRecovery`: Android's side of the ONE shared startup step list.
 *
 * **WHY THIS FILE EXISTS.** The point of the port is that Android's startup sequence is the SAME sequence
 * the desktop runs, described by ONE shared enumeration ([StartupRecoveryStep]) rather than by two lists
 * that drift. Drift is the failure mode nobody sees: adding a fourth step to the enum, wiring it on the
 * desktop, and forgetting Android compiles cleanly, passes every existing test, and shows up weeks later
 * as a phone build that silently skips a repair pass. [runsEveryStepInTheSharedStepList] is the test that
 * catches it - it fails the moment a constant exists in the enum that Android does not run, which is the
 * whole reason this class exists.
 *
 * **A SET, not a list.** The contract is "every step the platform can name is run", not "in this order".
 * `runCatching` per step also means a throwing step is deliberately dropped from the returned list, so a
 * set comparison is what keeps a reordering (harmless) from looking like a missing step (not harmless)
 * while still failing on either an added-but-unrun or a run-but-undeclared constant.
 *
 * **The flag is a global, so it is restored.** `PlatformFeatureFlags` is a process-wide `object`: the
 * minimal-build case flips it for real and would otherwise leak `minimalModeEnabled = true` into whatever
 * runs next in the same JVM. Hence the `resetForTests()` in both `setUp` and `tearDown`.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidStartupRecoveryTest {
    private val recurrenceManager: RecurrenceManager = mock {
        onBlocking { repairStuckRecurringTasks() } doReturn 0
        onBlocking { autoAdvanceRecurringTasks(any()) } doReturn 0
    }
    private val backfillService: ScoreRollupBackfillService = mock()

    private fun recovery(recurrence: RecurrenceManager = recurrenceManager): AndroidStartupRecovery = AndroidStartupRecovery({ recurrence }, { backfillService })

    @Before
    fun setUp() {
        PlatformFeatureFlags.resetForTests()
        if (!UnifiedLogger.isInitialized()) {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            UnifiedLogger.initialize(context, versionName = "test", buildNumber = 0)
        }
    }

    @After
    fun tearDown() {
        PlatformFeatureFlags.resetForTests()
    }

    @Test
    fun `runs every step in the shared step list`(): Unit = runBlocking {
        assertThat(recovery().runStartupRecovery().toSet())
            .isEqualTo(StartupRecoveryStep.entries.toSet())
    }

    @Test
    fun `a minimal build returns no steps rather than claiming them`(): Unit = runBlocking {
        PlatformFeatureFlags.install(FeatureFlagsState(minimalModeEnabled = true))
        assertThat(recovery().runStartupRecovery()).isEmpty()
    }

    @Test
    fun `a step that throws is dropped, and the other steps still run`(): Unit = runBlocking {
        val throwing: RecurrenceManager = mock {
            onBlocking { repairStuckRecurringTasks() } doThrow RuntimeException("boom")
            onBlocking { autoAdvanceRecurringTasks(any()) } doReturn 0
        }

        val ran = recovery(throwing).runStartupRecovery().toSet()

        assertThat(ran).doesNotContain(StartupRecoveryStep.REPAIR_STUCK_RECURRING_TASKS)
        assertThat(ran).contains(StartupRecoveryStep.AUTO_ADVANCE_RECURRING_TASKS)
        assertThat(ran).contains(StartupRecoveryStep.BACKFILL_SCORE_ROLLUPS)
    }
}
