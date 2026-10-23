//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.backfill.ScoreRollupBackfillService
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.scoring.RecurrenceManager
import io.payanam.shared.platform.StartupRecovery
import io.payanam.shared.platform.StartupRecoveryStep
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The Room-backed [StartupRecovery]: Android's side of the one startup step list.
 *
 * Its three steps are the shared [StartupRecoveryStep] entries - repaired stuck occurrences, advanced
 * recurrence, converted historical roll-ups - and each gets its own [runCatching] for the same reason the
 * desktop's does: one handler wrapped around several steps reports a failure under whichever name sits
 * nearest the catch, which is how a score roll-up failure once became a log line about recurring tasks.
 * A step that throws is logged under its own name and skipped; the steps that already ran are still
 * returned, so a partial recovery is reported as partial.
 *
 * The minimal-mode decision is made here, from the shared flag holder, so the caller does not have to
 * know which of the two shapes it is in: a build without habits returns an **empty** list rather than
 * claiming steps it skipped.
 */
@Singleton
class AndroidStartupRecovery
@Inject
constructor(
    private val recurrenceManager: Provider<RecurrenceManager>,
    private val scoreRollupBackfillService: Provider<ScoreRollupBackfillService>,
) : StartupRecovery {
    override suspend fun runStartupRecovery(): List<StartupRecoveryStep> {
        if (PlatformFeatureFlags.current.minimalModeEnabled) {
            UnifiedLogger.getInstance().i(SOURCE, "Minimal mode: skipping recurrence maintenance (habits disabled)")
            return emptyList()
        }
        val completed = mutableListOf<StartupRecoveryStep>()

        runCatching {
            recurrenceManager.get().repairStuckRecurringTasks()
        }.onSuccess { repaired ->
            if (repaired > 0) {
                UnifiedLogger.getInstance().i(
                    SOURCE,
                    "Startup recovery repaired $repaired stuck recurring task occurrence(s)",
                )
            }
            completed += StartupRecoveryStep.REPAIR_STUCK_RECURRING_TASKS
        }.onFailure { failure ->
            UnifiedLogger.getInstance().e(
                SOURCE,
                "Startup recovery step ${StartupRecoveryStep.REPAIR_STUCK_RECURRING_TASKS} failed",
                failure,
            )
        }

        runCatching {
            recurrenceManager.get().autoAdvanceRecurringTasks()
        }.onSuccess {
            completed += StartupRecoveryStep.AUTO_ADVANCE_RECURRING_TASKS
        }.onFailure { failure ->
            UnifiedLogger.getInstance().e(
                SOURCE,
                "Startup recovery step ${StartupRecoveryStep.AUTO_ADVANCE_RECURRING_TASKS} failed",
                failure,
            )
        }

        runCatching {
            scoreRollupBackfillService.get().runIfNeeded()
        }.onSuccess {
            completed += StartupRecoveryStep.BACKFILL_SCORE_ROLLUPS
        }.onFailure { failure ->
            UnifiedLogger.getInstance().e(
                SOURCE,
                "Startup recovery step ${StartupRecoveryStep.BACKFILL_SCORE_ROLLUPS} failed",
                failure,
            )
        }

        return completed
    }

    private companion object {
        const val SOURCE = "AndroidStartupRecovery"
    }
}
