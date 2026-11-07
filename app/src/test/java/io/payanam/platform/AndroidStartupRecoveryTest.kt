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
import kotlinx.coroutines.CancellationException
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
 * Proves Android runs every step in the shared [StartupRecoveryStep] list, so a new constant cannot
 * compile into a phone build that silently skips it. The step set is compared rather than its order,
 * and the process-wide feature flag is reset in both `setUp` and `tearDown`.
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

    @Test
    fun `a cancelled step is rethrown instead of being dropped like a failure`() {
        val cancelling: RecurrenceManager = mock {
            onBlocking { repairStuckRecurringTasks() } doThrow CancellationException("scope cancelled")
        }

        val thrown = try {
            runBlocking { recovery(cancelling).runStartupRecovery() }
            null
        } catch (e: CancellationException) {
            e
        }

        assertThat(thrown).isNotNull()
    }
}
