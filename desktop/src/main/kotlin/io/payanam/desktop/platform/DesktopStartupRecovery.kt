//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.database.jvm.backfill.ScoreRollupBackfillJdbc
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.scoring.RecurrenceManager
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.platform.StartupRecovery
import io.payanam.shared.platform.StartupRecoveryStep

/**
 * The desktop's [StartupRecovery]: the startup work whose step list lives in the shared enum.
 *
 * The bodies here are the desktop's own — a JDBC backfill over [PayanamDatabaseSession] and recurrence driven
 * through the [DesktopPayanamDataLayer] repositories — but the steps are not written out again. They come from
 * [StartupRecoveryStep], so the drift this port exists to prevent cannot happen here: a step added to the enum
 * and not run shows up as a return value shorter than the enumeration.
 *
 * Each step gets its own [runCatching], and that is load-bearing rather than stylistic. One handler wrapped
 * around several steps reports a failure under whichever name sits nearest the catch, which is how a score
 * roll-up failure became a log line reading "recurring tasks may not have advanced". A step that throws is
 * logged under its own name and then skipped; the steps that already ran are still returned, so a partial
 * recovery is reported as the partial list it was.
 *
 * Ordering is incidental, matching the enum's declaration order rather than asserting a rule: each step owns
 * its own idempotence and its own guard.
 */
internal class DesktopStartupRecovery(
    private val layer: DesktopPayanamDataLayer,
) : StartupRecovery {
    override suspend fun runStartupRecovery(): List<StartupRecoveryStep> {
        // The build flag decides, and it decides once, the same way Android's startup maintenance decides: the
        // recurrence work exists for habits, so a build without habits does not do it - and does not report it
        // as done either, which is why the list is empty rather than full.
        if (PlatformFeatureFlags.current.minimalModeEnabled) {
            AppLogger.current.i(SOURCE, "Minimal mode: skipping recurrence maintenance (habits disabled)")
            return emptyList()
        }
        val completed = mutableListOf<StartupRecoveryStep>()

        runCatching {
            RecurrenceManager(layer.tasks, layer.taskOccurrences).repairStuckRecurringTasks()
        }.onSuccess { repaired ->
            if (repaired > 0) {
                AppLogger.current.i(
                    SOURCE,
                    "Startup recovery repaired $repaired stuck recurring task occurrence(s)",
                )
            }
            completed += StartupRecoveryStep.REPAIR_STUCK_RECURRING_TASKS
        }.onFailure { failure ->
            AppLogger.current.e(
                SOURCE,
                "Startup recovery step ${StartupRecoveryStep.REPAIR_STUCK_RECURRING_TASKS} failed",
                failure,
            )
        }

        runCatching {
            RecurrenceManager(layer.tasks, layer.taskOccurrences).autoAdvanceRecurringTasks()
        }.onSuccess {
            completed += StartupRecoveryStep.AUTO_ADVANCE_RECURRING_TASKS
        }.onFailure { failure ->
            AppLogger.current.e(
                SOURCE,
                "Startup recovery step ${StartupRecoveryStep.AUTO_ADVANCE_RECURRING_TASKS} failed",
                failure,
            )
        }

        runCatching {
            ScoreRollupBackfillJdbc(layer.session).runIfNeeded()
        }.onSuccess { result ->
            if (result.failureMessage != null) {
                // runIfNeeded rolls back and leaves the guard unset on its own failure path rather than
                // throwing, so the step is reached and reported, and the next startup retries it.
                AppLogger.current.e(
                    SOURCE,
                    "Startup recovery step ${StartupRecoveryStep.BACKFILL_SCORE_ROLLUPS} failed: " +
                        result.failureMessage,
                )
            } else if (result.ran) {
                AppLogger.current.i(
                    SOURCE,
                    "Startup recovery backfilled score roll-ups: guard already set=" +
                        "${result.guardAlreadySet}, recurrence rules=${result.recurrenceRulesConverted}, " +
                        "habit metric rows=${result.habitMetricRows}, " +
                        "dimension metric rows=${result.dimensionMetricRows}, " +
                        "day metric rows=${result.dayMetricRows}",
                )
            }
            completed += StartupRecoveryStep.BACKFILL_SCORE_ROLLUPS
        }.onFailure { failure ->
            AppLogger.current.e(
                SOURCE,
                "Startup recovery step ${StartupRecoveryStep.BACKFILL_SCORE_ROLLUPS} failed",
                failure,
            )
        }

        return completed
    }

    private companion object {
        /** Matches the log source RecurrenceManager uses for its own lines. */
        const val SOURCE = "DesktopStartupRecovery"
    }
}