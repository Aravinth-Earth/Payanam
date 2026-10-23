//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.database.jvm.AppSettingsRepositoryJvm
import io.payanam.database.jvm.DayPlanRepositoryJvm
import io.payanam.database.jvm.HabitMetricRepositoryJvm
import io.payanam.database.jvm.JournalRepositoryJvm
import io.payanam.database.jvm.LensRepositoryJvm
import io.payanam.database.jvm.LifeDimensionCatalogRepositoryJvm
import io.payanam.database.jvm.NoteRepositoryJvm
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.database.jvm.ScoreWindowRepositoryJvm
import io.payanam.database.jvm.ScoringConfigRepositoryJvm
import io.payanam.database.jvm.TagRepositoryJvm
import io.payanam.database.jvm.TaskOccurrenceRepositoryJvm
import io.payanam.database.jvm.TaskRepositoryJvm
import io.payanam.database.jvm.TaskRescheduleRepositoryJvm
import io.payanam.database.jvm.TimeEntryRepositoryJvm
import io.payanam.desktop.port.DesktopTaskReminderScheduler
import io.payanam.desktop.port.DesktopTimeTrackingController
import io.payanam.desktop.port.DesktopUnsupportedPorts
import io.payanam.domain.port.HabitTimeEntryRecorder
import io.payanam.domain.port.PerformanceTracer
import io.payanam.domain.port.ScoreCalculator
import io.payanam.domain.port.ScoreChangeStream
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.port.TimeTrackingController
import io.payanam.domain.port.TimeTrackingWidgetRefresher
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.HabitMetricRepository
import io.payanam.domain.repository.JournalRepository
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.ScoreWindowRepository
import io.payanam.domain.repository.ScoringConfigRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TaskRescheduleRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.scoring.ElegantTaskScoring
import io.payanam.scoring.port.TaskRecurrenceHandler

/**
 * The desktop's [PayanamDataLayer]: the shared ViewModels' repositories, backed by JDBC over the same
 * encrypted database Android writes.
 *
 * This is the second of the two implementations the contract was built for. It contains no adapters and no
 * per-ViewModel wrappers — the repository implementations already satisfy the shared `:core:domain`
 * interfaces, so the layer is a list of them. Every repository built here reads the SAME connection, which
 * is deliberate: the deleted stores each opened their own, so cross-store reads were never mutually excluded
 * at the Kotlin level.
 *
 * The seam grows by one entry each time a ViewModel moves into `:core:presentation`, which turns "the
 * desktop does not implement this yet" into a compile error rather than an empty screen.
 */
class DesktopPayanamDataLayer(
    // Internal rather than private so startup maintenance can hand the session to the JDBC port that runs the
    // score-rollup backfill step. The controller deliberately does not publish its own session - a caller could
    // keep a reference across a close - but this layer already exposes sixteen repositories over the same session,
    // and the reference passed on is scoped to one call rather than cached.
    internal val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : PayanamDataLayer {
    override val notes: NoteRepository = NoteRepositoryJvm(session, pollIntervalMs)

    override val tags: TagRepository = TagRepositoryJvm(session, pollIntervalMs)

    override val tasks: TaskRepository = TaskRepositoryJvm(session, pollIntervalMs)

    override val timeEntries: TimeEntryRepository = TimeEntryRepositoryJvm(session, pollIntervalMs)

    override val dayPlans: DayPlanRepository = DayPlanRepositoryJvm(session, pollIntervalMs)

    override val taskOccurrences: TaskOccurrenceRepository =
        TaskOccurrenceRepositoryJvm(session, pollIntervalMs)

    override val journal: JournalRepository = JournalRepositoryJvm(session, pollIntervalMs)

    override val habitMetrics: HabitMetricRepository =
        HabitMetricRepositoryJvm(session)

    override val scoreWindows: ScoreWindowRepository =
        ScoreWindowRepositoryJvm(session)

    override val taskReschedules: TaskRescheduleRepository =
        TaskRescheduleRepositoryJvm(session, pollIntervalMs)

    override val appSettings: AppSettingsRepository = AppSettingsRepositoryJvm(session)

    /**
     * The `life_dimensions` catalog, over the SAME connection every repository above uses.
     *
     * A real implementation, not one of the [DesktopUnsupportedPorts] no-ops, and the reason is that the
     * desktop does have this capability: the rows live in the same encrypted file the desktop already opens,
     * and `LifeDimensionCatalogRepositoryJvm` speaks the shared `:core:domain` interface over JDBC. A no-op
     * here would not degrade the screen - it would make it lie, rendering an empty dimension list that reads
     * exactly like "the user has no dimensions" while the table holds ten rows.
     *
     * Takes [pollIntervalMs] like every other observing repository, so `observeAllDimensions` re-reads on the
     * same cadence and a catalog edit made on the other platform reaches this one.
     */
    override val lifeDimensions: LifeDimensionCatalogRepository =
        LifeDimensionCatalogRepositoryJvm(session, pollIntervalMs)

    // Platform capabilities. The three the desktop genuinely has are shared implementations; the rest are this
    // platform's own no-ops, and each one says so in the log - see [DesktopUnsupportedPorts].
    override val scoreCalculator: ScoreCalculator = ElegantTaskScoring

    override val reminderScheduler: TaskReminderScheduler = DesktopTaskReminderScheduler()

    override val scoreRollupTrigger: ScoreRollupTrigger = DesktopUnsupportedPorts.scoreRollupTrigger

    override val recurrenceHandler: TaskRecurrenceHandler = DesktopUnsupportedPorts.recurrenceHandler

    override val habitTimeEntryRecorder: HabitTimeEntryRecorder =
        DesktopUnsupportedPorts.habitTimeEntryRecorder

    override val tracer: PerformanceTracer = DesktopUnsupportedPorts.tracer

    override val scoreChanges: ScoreChangeStream = DesktopUnsupportedPorts.scoreChanges

    /**
     * The tracking-session port, over THIS layer's repositories and this platform's recurrence answer.
     *
     * Reachable through the shared seam rather than only through this class: the ViewModel that needs it is
     * still in `:app`, but carrying the port on [PayanamDataLayer] is what lets a shared ViewModel reach the
     * desktop's real implementation once it moves, instead of finding no implementation at all.
     *
     * The two repositories and the recurrence handler are read rather than reconstructed, for the reason
     * [lenses] gives: one connection and one recurrence owner, not a second copy of each.
     */
    override val timeTrackingController: TimeTrackingController =
        DesktopTimeTrackingController(
            timeEntries = timeEntries,
            tasks = tasks,
            taskOccurrences = taskOccurrences,
            recurrenceHandler = recurrenceHandler,
        )

    /** The home-screen widget port: no launcher widget host exists on this platform, so it says so. */
    override val timeTrackingWidgetRefresher: TimeTrackingWidgetRefresher =
        DesktopUnsupportedPorts.timeTrackingWidgetRefresher

    // Declared last on purpose: the JDBC lens repository reads the sibling repositories above, and a property
    // initialiser may only read properties declared before it.
    override val lenses: LensRepository =
        LensRepositoryJvm(session, tasks, timeEntries, taskOccurrences, dayPlans, pollIntervalMs)

    override val scoringConfig: ScoringConfigRepository = ScoringConfigRepositoryJvm(session, pollIntervalMs)

    companion object {
        /**
         * How often an observing repository re-reads for a change.
         *
         * Not zero: a JDBC read has no Room-style invalidation, so the shared `observe` helper re-reads on an
         * interval and emits only when the value changed. Tests drive it at ~10 ms.
         */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 1_000L
    }
}
