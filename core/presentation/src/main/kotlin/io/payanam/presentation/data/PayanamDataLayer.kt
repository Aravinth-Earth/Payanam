//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.data

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
import io.payanam.scoring.port.TaskRecurrenceHandler

/**
 * Every repository the shared ViewModels drive, in one contract.
 *
 * WHY A CONTRACT INSTEAD OF INJECTION PER VIEWMODEL:
 *
 * The shared ViewModels are platform-neutral classes, so nothing can hand them repositories
 * automatically: Hilt is Android-only, and the desktop has no DI container at all. Passing repositories
 * one-by-one to each call site would mean every platform repeating a wiring list per screen - the
 * per-screen duplication this whole migration is removing.
 *
 * So the repositories are grouped here, and each platform installs ONE implementation of this interface
 * once, at startup (see [LocalDataLayer]). Android's implementation is Room-backed, the desktop's is
 * JDBC/SQLCipher-backed; both satisfy the same `:core:domain` interfaces, so the ViewModels above them
 * cannot tell which platform they are running on. One contract, two implementations, no adapters, and no
 * second code path.
 *
 * This interface GROWS by one entry each time a ViewModel moves into `:core:presentation` - adding a
 * repository or a capability here is a compile error in every platform that has not provided it yet, which is
 * the intended way to make the gap visible instead of discovering it at runtime.
 */
interface PayanamDataLayer {
    /** Persistent notes. */
    val notes: NoteRepository

    /** Cross-module tags, attached to notes, tasks and time entries. */
    val tags: TagRepository

    /** Tasks and their recurrence rules. */
    val tasks: TaskRepository

    /** Tracked time entries. */
    val timeEntries: TimeEntryRepository

    /** Per-day planned allocations. */
    val dayPlans: DayPlanRepository

    /** One row per scheduled occurrence of a recurring task. */
    val taskOccurrences: TaskOccurrenceRepository

    /** Journal entries. */
    val journal: JournalRepository

    /** The app's stored settings, read and written as key/value rows. */
    val appSettings: AppSettingsRepository

    /**
     * The runtime life-dimension catalog: the rows a user can rename, recolour and weight, and the rows
     * every dimension-aware dialog must offer.
     *
     * It sits next to [appSettings] because that is the pair the settings screen reads together: Android's
     * `AppPreferencesViewModel` `combine`s the two, and the shared mapper takes the catalog as its own
     * named argument. A platform with no way to read the table would have to fake this entry, and a fake
     * is exactly the failure this contract exists to make loud - see the KDoc on `notInstalled` below.
     */
    val lifeDimensions: LifeDimensionCatalogRepository

    /** Habit L1 (per-habit score) summaries. */
    val habitMetrics: HabitMetricRepository

    /** The day/dimension score windows the score-detail screen reads. */
    val scoreWindows: ScoreWindowRepository

    /** One row per task reschedule. */
    val taskReschedules: TaskRescheduleRepository

    // ---------------------------------------------------------------- platform capabilities
    //
    // The entries below are NOT repositories: they are the capabilities a shared ViewModel needs and cannot
    // build itself. They live here for the same reason the repositories do - the shared state layer has no DI
    // container, so the platform has to hand them over through one installed contract, and a platform that has
    // not provided one fails to compile rather than silently doing nothing.
    //
    // They were added when the Tasks ViewModel family moved: until then every shared ViewModel drove
    // repositories only, so the seam had never had to carry a capability.

    /** Arms and cancels a task's reminder. */
    val reminderScheduler: TaskReminderScheduler

    /** Recomputes the score roll-ups after a task's status or rule changes. */
    val scoreRollupTrigger: ScoreRollupTrigger

    /** Advances a recurring task's schedule when it is completed, skipped or missed. */
    val recurrenceHandler: TaskRecurrenceHandler

    /** Records the time entry a completed habit produces. */
    val habitTimeEntryRecorder: HabitTimeEntryRecorder

    /** Computes a task's initial and updated score. */
    val scoreCalculator: ScoreCalculator

    /** Records screen and query performance events. */
    val tracer: PerformanceTracer

    /** The days whose score layers were recomputed, as a stream a screen can react to. */
    val scoreChanges: ScoreChangeStream

    /** The Lens workspace's daily snapshots, ranges and reflections. */
    val lenses: LensRepository

    /** The scoring model's configuration: dimension weights and the knobs the settings screen edits. */
    val scoringConfig: ScoringConfigRepository

    /**
     * Starts and stops a tracking session on the platform, completing the attached task when it had one.
     *
     * Paired with [timeTrackingWidgetRefresher] and carried here for the same reason as the capabilities
     * above: starting a session also drives a foreground service and a home-screen widget, which the shared
     * state layer cannot build for itself, so the platform has to hand the capability over through the one
     * installed contract. A platform with no tracking session of its own refuses in its implementation rather
     * than faking a started session - see the `notInstalled` KDoc on [lifeDimensions] for why a fake is the
     * failure this contract exists to make loud.
     */
    val timeTrackingController: TimeTrackingController

    /**
     * Repaints the home-screen time-tracking widget.
     *
     * Its own entry rather than another member of [timeTrackingController], matching the port's own
     * reasoning: the widget is redrawn after changes that never touch a session, so a caller that only
     * needs a repaint is not handed a session controller.
     */
    val timeTrackingWidgetRefresher: TimeTrackingWidgetRefresher
}

/**
 * The process-wide holder for the platform's [PayanamDataLayer].
 *
 * Shaped like [io.payanam.shared.logging.AppLogger], the convention this repository already uses for
 * platform-supplied singletons that platform-neutral code must reach. The platform installs its
 * implementation once, early, from its startup path; everything above reads [current].
 *
 * ONE DELIBERATE DIFFERENCE FROM `AppLogger`: the un-installed default is not a silent no-op. An
 * un-installed logger loses a log line, which is harmless; an un-installed data layer makes every query
 * return nothing, so a user would see an app that looks empty rather than one that is misconfigured.
 * That failure has to be loud, so reading a repository before [install] throws with the reason.
 */
object LocalDataLayer {
    /**
     * The repositories in force for this process.
     *
     * Defaults to [UninstalledDataLayer], which fails on any repository access.
     */
    @Volatile
    var current: PayanamDataLayer = UninstalledDataLayer
        private set

    /** Installs [layer] process-wide. Call once from the app's startup path. */
    fun install(layer: PayanamDataLayer) {
        current = layer
    }

    /**
     * Restores the [UninstalledDataLayer] default.
     *
     * Test-only. Test classes in a module share one JVM, so a test that installs a layer must reset it in
     * **both** `@Before` and `@After`: the `@Before` guard stops an assertion from passing on state leaked
     * out of an earlier class, and the `@After` guard stops this class leaking into the next.
     */
    fun resetForTests() {
        current = UninstalledDataLayer
    }
}

/**
 * The default layer present before a platform installs its own; every access fails with the fix.
 */
object UninstalledDataLayer : PayanamDataLayer {
    /** Fails: no data layer has been installed. */
    override val notes: NoteRepository
        get() = notInstalled("notes")

    /** Fails: no data layer has been installed. */
    override val tags: TagRepository
        get() = notInstalled("tags")

    /** Fails: no data layer has been installed. */
    override val tasks: TaskRepository
        get() = notInstalled("tasks")

    /** Fails: no data layer has been installed. */
    override val timeEntries: TimeEntryRepository
        get() = notInstalled("timeEntries")

    /** Fails: no data layer has been installed. */
    override val dayPlans: DayPlanRepository
        get() = notInstalled("dayPlans")

    /** Fails: no data layer has been installed. */
    override val taskOccurrences: TaskOccurrenceRepository
        get() = notInstalled("taskOccurrences")

    /** Fails: no data layer has been installed. */
    override val journal: JournalRepository
        get() = notInstalled("journal")

    /** Fails: no data layer has been installed. */
    override val appSettings: AppSettingsRepository
        get() = notInstalled("appSettings")

    /** Fails: no data layer has been installed. */
    override val lifeDimensions: LifeDimensionCatalogRepository
        get() = notInstalled("lifeDimensions")

    /** Fails: no data layer has been installed. */
    override val habitMetrics: HabitMetricRepository
        get() = notInstalled("habitMetrics")

    /** Fails: no data layer has been installed. */
    override val scoreWindows: ScoreWindowRepository
        get() = notInstalled("scoreWindows")

    /** Fails: no data layer has been installed. */
    override val taskReschedules: TaskRescheduleRepository
        get() = notInstalled("taskReschedules")

    /** Fails: no data layer has been installed. */
    override val reminderScheduler: TaskReminderScheduler
        get() = notInstalled("reminderScheduler")

    /** Fails: no data layer has been installed. */
    override val scoreRollupTrigger: ScoreRollupTrigger
        get() = notInstalled("scoreRollupTrigger")

    /** Fails: no data layer has been installed. */
    override val recurrenceHandler: TaskRecurrenceHandler
        get() = notInstalled("recurrenceHandler")

    /** Fails: no data layer has been installed. */
    override val habitTimeEntryRecorder: HabitTimeEntryRecorder
        get() = notInstalled("habitTimeEntryRecorder")

    /** Fails: no data layer has been installed. */
    override val scoreCalculator: ScoreCalculator
        get() = notInstalled("scoreCalculator")

    /** Fails: no data layer has been installed. */
    override val tracer: PerformanceTracer
        get() = notInstalled("tracer")

    /** Fails: no data layer has been installed. */
    override val scoreChanges: ScoreChangeStream
        get() = notInstalled("scoreChanges")

    /** Fails: no data layer has been installed. */
    override val lenses: LensRepository
        get() = notInstalled("lenses")

    /** Fails: no data layer has been installed. */
    override val scoringConfig: ScoringConfigRepository
        get() = notInstalled("scoringConfig")

    /** Fails: no data layer has been installed. */
    override val timeTrackingController: TimeTrackingController
        get() = notInstalled("timeTrackingController")

    /** Fails: no data layer has been installed. */
    override val timeTrackingWidgetRefresher: TimeTrackingWidgetRefresher
        get() = notInstalled("timeTrackingWidgetRefresher")

    private fun notInstalled(name: String): Nothing =
        error(
            "LocalDataLayer.$name was read before a data layer was installed. " +
                "Install the platform's repositories once from its startup path with " +
                "LocalDataLayer.install(...).",
        )
}
