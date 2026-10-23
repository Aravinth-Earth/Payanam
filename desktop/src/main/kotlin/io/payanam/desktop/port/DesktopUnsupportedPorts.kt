//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import io.payanam.core.ui.updates.PreUnlockUpdateActions
import io.payanam.desktop.DesktopBuildInfo
import io.payanam.domain.model.Task
import io.payanam.domain.port.HabitTimeEntryRecorder
import io.payanam.domain.port.PerformanceTracer
import io.payanam.domain.port.ScoreChangeStream
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.port.TimeTrackingController
import io.payanam.domain.port.TimeTrackingWidgetRefresher
import io.payanam.presentation.updates.UpdateChecker
import io.payanam.scoring.CompletionStats
import io.payanam.scoring.port.TaskRecurrenceHandler
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.platform.ApkUpdateSource
import io.payanam.shared.platform.InstallHandoff
import io.payanam.shared.platform.InstallRefusal
import io.payanam.shared.platform.SupportState
import io.payanam.shared.platform.UpdateAvailability
import io.payanam.shared.platform.UpdateDownload
import io.payanam.shared.updates.UpdateChannel
import io.payanam.shared.updates.UpdateCheckResult
import io.payanam.shared.updates.UpdateOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The build type string this platform passes to the shared update checker.
 *
 * `UpdateChecker.check` takes it to choose which `.apk` asset a DOWNLOAD would fetch; it has no bearing on
 * the build-number verdict, and this platform has no asset to fetch at all (it ships as a `.deb`). The value
 * is the one a stable-channel release is named with, so the check reads the same release list Android's
 * stable channel reads rather than a debug-named one this platform never publishes.
 */
private const val DESKTOP_UPDATE_RUNNING_TYPE = "release"

/**
 * The desktop's implementations of the shared state layer's platform capabilities that it does not have yet.
 *
 * WHY THEY ARE HERE AND NOT SHARED: each one is a statement about this PLATFORM, not about the capability. The
 * desktop has a reminder channel — the freedesktop `notify-send` delivery hook, which the scheduler arms
 * against from user actions — but no score-cascade writer (Android computes the roll-ups and writes them into
 * the same database the desktop reads) and no habit time-entry recorder. Shipping these as the shared default
 * would let a platform that does have them inherit the absence silently; shipping them here keeps the absence
 * visible at exactly one place, and the contract still forces every platform to answer for every capability.
 *
 * WHY THEY LOG: a no-op that says nothing is the failure this project forbids - an action that appears to
 * happen and does not. Each method reports what it was asked to do and that this platform did not do it, so a
 * desktop session that quietly skipped a reminder is legible in its own log rather than only in the UI's
 * absence of a notification.
 *
 * [io.payanam.domain.port.ScoreCalculator] is deliberately NOT here: `ElegantTaskScoring` lives in the shared
 * `:core:scoring` module and is pure arithmetic, so the desktop computes scores with the same object Android
 * uses rather than with an approximation.
 */
internal object DesktopUnsupportedPorts {
    private val logger = AppLogger.current

    /** A reminder channel this platform does not have. */
    val reminderScheduler: TaskReminderScheduler =
        object : TaskReminderScheduler {
            override suspend fun scheduleForTask(
                task: Task,
                overrideScheduledAt: LocalDateTime?,
                overrideDueAt: LocalDateTime?,
                isSnoozed: Boolean,
            ) {
                logger.w(
                    "DesktopUnsupportedPorts.scheduleForTask",
                    "Reminders are not delivered on this platform; the request was not scheduled",
                    mapOf("taskId" to task.id),
                )
            }

            override suspend fun cancelForTask(taskId: String) {
                logger.d(
                    "DesktopUnsupportedPorts.cancelForTask",
                    "Nothing to cancel: reminders are not scheduled on this platform",
                    mapOf("taskId" to taskId),
                )
            }
        }

    /** A cascade writer this platform does not have: the roll-ups are computed on Android and read back here. */
    val scoreRollupTrigger: ScoreRollupTrigger =
        object : ScoreRollupTrigger {
            override suspend fun recalcForStatusChange(
                taskId: String,
                date: LocalDate,
            ) {
                logSkipped("recalcForStatusChange", mapOf("taskId" to taskId, "date" to date.toString()))
            }

            override suspend fun recalcForRuleChange(taskId: String) {
                logSkipped("recalcForRuleChange", mapOf("taskId" to taskId))
            }

            override suspend fun recalcDayOnly(changeDate: LocalDate) {
                logSkipped("recalcDayOnly", mapOf("date" to changeDate.toString()))
            }

            private fun logSkipped(
                operation: String,
                data: Map<String, Any?>,
            ) {
                logger.w(
                    "DesktopUnsupportedPorts.scoreRollupTrigger.$operation",
                    "Score roll-ups are not recomputed on this platform; the stored rows are unchanged",
                    data,
                )
            }
        }

    /**
     * The recurrence model Android advances.
     *
     * `isFrequencyHabit` answers `false` rather than refusing, because it is a QUESTION and the honest answer
     * for a platform that does not maintain the schedule is "not a frequency habit" - the surfaces that branch
     * on it then render the non-recurring shape instead of claiming a quota this app cannot keep. The two
     * mutating methods and the stats read report that they did nothing.
     */
    val recurrenceHandler: TaskRecurrenceHandler =
        object : TaskRecurrenceHandler {
            override fun isFrequencyHabit(task: Task): Boolean {
                logger.d(
                    "DesktopUnsupportedPorts.isFrequencyHabit",
                    "Recurrence is advanced on Android; answering not-a-frequency-habit",
                    mapOf("taskId" to task.id),
                )
                return false
            }

            override suspend fun onTaskCompleted(
                task: Task,
                note: String?,
                reason: String?,
                nextDueStrategy: String?,
            ) = logNotAdvanced("onTaskCompleted", task)

            override suspend fun onTaskSkipped(
                task: Task,
                note: String?,
                reason: String?,
                nextDueStrategy: String?,
            ) = logNotAdvanced("onTaskSkipped", task)

            override suspend fun onTaskMissed(
                task: Task,
                note: String?,
                reason: String?,
                nextDueStrategy: String?,
            ) = logNotAdvanced("onTaskMissed", task)

            override suspend fun getCompletionStats(task: Task): CompletionStats {
                logger.w(
                    "DesktopUnsupportedPorts.getCompletionStats",
                    "Completion stats are computed on Android; reporting zeros",
                    mapOf("taskId" to task.id),
                )
                return EMPTY_COMPLETION_STATS
            }

            private fun logNotAdvanced(
                operation: String,
                task: Task,
            ) {
                logger.w(
                    "DesktopUnsupportedPorts.$operation",
                    "The recurrence schedule is not advanced on this platform",
                    mapOf("taskId" to task.id),
                )
            }
        }

    /** The habit time-entry writer this platform does not have: time is tracked on Android. */
    val habitTimeEntryRecorder: HabitTimeEntryRecorder =
        object : HabitTimeEntryRecorder {
            override suspend operator fun invoke(
                task: Task,
                actualCompletedAt: LocalDateTime?,
                actualDurationMinutes: Int?,
            ) {
                logger.w(
                    "DesktopUnsupportedPorts.recordHabitTimeEntry",
                    "Habit time entries are not recorded on this platform",
                    mapOf("taskId" to task.id, "durationMinutes" to actualDurationMinutes),
                )
            }
        }

    /**
     * A tracer that keeps the counters in memory.
     *
     * Not a refusal: nothing branches on these values - the Android implementation feeds a baseline report -
     * so counting them costs nothing and keeps a call site's contract intact. The counts are not persisted,
     * which is the same outcome as Android's own baseline being per-process.
     */
    val tracer: PerformanceTracer =
        object : PerformanceTracer {
            private val counts = mutableMapOf<String, Int>()

            override fun markEvent(
                screen: String,
                event: String,
                data: Map<String, Any?>,
            ) {
                increment("event:$screen:$event")
            }

            override fun markEventOnce(
                key: String,
                screen: String,
                event: String,
                data: Map<String, Any?>,
            ) {
                if (counts.containsKey("once:$key")) return
                counts["once:$key"] = 1
                increment("event:$screen:$event")
            }

            override fun incrementQuery(
                screen: String,
                source: String,
                amount: Int,
            ): Int = increment("query:$screen:$source", amount)

            override fun incrementRecomposition(
                screen: String,
                section: String,
            ): Int = increment("recomposition:$screen:$section")

            @Synchronized
            private fun increment(
                key: String,
                amount: Int = 1,
            ): Int {
                val next = (counts[key] ?: 0) + amount
                counts[key] = next
                return next
            }
        }

    /**
     * The score-change stream for a platform that never recomputes a score.
     *
     * An empty flow rather than a live bus: the desktop reads the rows Android wrote, so nothing here can
     * publish a change, and a bus that nobody can emit into would be a promise the platform cannot keep.
     */
    val scoreChanges: ScoreChangeStream =
        object : ScoreChangeStream {
            override val events: Flow<LocalDate> = emptyFlow()
        }

    /**
     * In-app updating on a platform that has none, over a caller-supplied channel read.
     *
     * A desktop build ships as a `.deb` its packager owns: there is no APK to replace and no per-app
     * installer to hand a file to, so [ApkUpdateSource.state] is [SupportState.NOT_APPLICABLE] rather than a
     * refusal — [SupportState.DECLINES] would claim a platform that once said yes and changed its mind.
     *
     * The CHECK still answers, and for real: [isNewerBuildAvailable] asks the SHARED checker Android uses,
     * with this build number and this platform's channel, so both platforms report the same answer for the
     * same channel state. What differs is only what can be done about it. A check that cannot conclude maps
     * to [UpdateAvailability.Inconclusive] and says why in the log, never to
     * [UpdateAvailability.UpToDate] — so a shared screen can show "this platform installs apps itself"
     * instead of either a dead button or a false claim of being up to date. Nothing is skipped silently.
     *
     * WHY A FACTORY BESIDE THE [apkUpdateSource] VAL RATHER THAN A SECOND IMPLEMENTATION: this platform's
     * port had no seam at all, so nothing could drive it over a channel state it did not reach itself. The
     * default below is the real check, so every existing call site keeps compiling and keeps asking the
     * release list for real; a caller that needs a specific channel state supplies [checkForUpdates] and gets
     * the SAME mapping this object applies.
     *
     * The parameter is named and shaped exactly as `AndroidApkUpdateSource`'s is, because the point of the
     * seam is that one channel state can be driven through BOTH platforms and the two answers compared. Two
     * differently-shaped seams would make that comparison something written twice or checked by eye.
     *
     * @param checkForUpdates how the release channel is read. Defaults to the real shared check with this
     *   platform's build number and [DESKTOP_UPDATE_RUNNING_TYPE].
     */
    internal fun apkUpdateSource(
        checkForUpdates: suspend (currentBuildNumber: Int, channel: UpdateChannel) -> UpdateCheckResult =
            { currentBuildNumber, requested ->
                UpdateChecker.check(currentBuildNumber, requested, DESKTOP_UPDATE_RUNNING_TYPE)
            },
    ): ApkUpdateSource =
        object : ApkUpdateSource {
            override val state: SupportState = SupportState.NOT_APPLICABLE

            // The build number the packager stamped this binary with. It is real, but nothing on this
            // platform compares it against a release channel, so it is reported and not acted upon.
            override val currentBuildNumber: Int? = DesktopBuildInfo.PLATFORM_BUILD_NUMBER

            // The CHECK still answers, and now does so for real: the same shared checker Android uses,
            // asked with this build number and this platform's channel, so both platforms report the
            // same result for the same channel state. What differs is only what can be done about it -
            // a desktop build ships as a `.deb` its packager owns, so [UpdateAvailability.Newer] is
            // reported with `installable = false`, which the shared type documents as the normal desktop
            // case. `runningType` picks which APK asset a DOWNLOAD would fetch and has no bearing on the
            // build-number verdict, so a constant is right here: there is no asset for this platform and
            // the verdict is still the channel's.
            override suspend fun isNewerBuildAvailable(): UpdateAvailability {
                val build = currentBuildNumber
                if (build == null) {
                    logger.w(
                        "DesktopUnsupportedPorts.isNewerBuildAvailable",
                        "The desktop build number is unknown, so no update check can be made",
                    )
                    return UpdateAvailability.Inconclusive
                }
                val result = checkForUpdates(build, UpdateChannel.STABLE)
                return when (result.outcome) {
                    UpdateOutcome.UPDATE_AVAILABLE ->
                        UpdateAvailability.Newer(
                            buildNumber = result.latestBuildNumber ?: build,
                            // WHY false AND NOT A STORE CHECK: this platform is not declining — it has no
                            // installer at all, so there is nothing to install a fetched artifact with. The
                            // shared type documents `installable = false` as the NORMAL desktop case.
                            // Android's `installable = !isStoreManaged` is the TRUE case for a platform with
                            // one; the two differ because the platforms differ, so this is documented at
                            // the site rather than reconciled into one shared constant.
                            installable = false,
                        )

                    UpdateOutcome.UP_TO_DATE -> UpdateAvailability.UpToDate

                    UpdateOutcome.TYPE_MISMATCH,
                    UpdateOutcome.NO_RELEASE_ON_CHANNEL,
                    UpdateOutcome.RELEASE_UNREADABLE,
                    UpdateOutcome.INDETERMINATE,
                    UpdateOutcome.FAILED,
                    -> {
                        logger.w(
                            "DesktopUnsupportedPorts.isNewerBuildAvailable",
                            "Update check did not produce an answer",
                            mapOf(
                                "outcome" to result.outcome.name,
                                "error" to result.error?.name,
                                "build" to build,
                            ),
                        )
                        UpdateAvailability.Inconclusive
                    }
                }
            }

            override suspend fun downloadNewerBuild(availability: UpdateAvailability): UpdateDownload? {
                logger.w(
                    "DesktopUnsupportedPorts.downloadNewerBuild",
                    "No download started: this platform cannot install an app update",
                    mapOf("newerBuild" to (availability as? UpdateAvailability.Newer)?.buildNumber),
                )
                return null
            }

            override fun startInstall(download: UpdateDownload): InstallHandoff {
                logger.w(
                    "DesktopUnsupportedPorts.startInstall",
                    "No installer launched: this platform has no per-app installer",
                    mapOf("build" to download.buildNumber),
                )
                return InstallHandoff.Refused(InstallRefusal.PLATFORM_NOT_APPLICABLE)
            }
        }

    /**
     * The desktop's update source over the real channel read - the production instance.
     *
     * A property and a function may share one name in Kotlin (their JVM signatures differ, so there is no
     * clash): the property is what production uses, and the function is the injection seam a parity test
     * drives. Both resolve to the same mapping, because the function IS the implementation.
     */
    val apkUpdateSource: ApkUpdateSource = apkUpdateSource()

    /**
     * The home-screen widget this platform does not have.
     *
     * Not a launcher-widget host and not an alternative to one: on Linux the desktop shell has no
     * equivalent of the Android app-widget the port describes, so there is nothing to redraw and no
     * equivalent surface to redraw it on. The capability's reason for existing — a stop tapped on the
     * widget itself — therefore does not arise, and the session state it would display is already visible
     * in the app window the user is looking at.
     *
     * [refreshWidget] returns [Unit], so this logs at `debug` for the same reason [reminderScheduler]
     * logs its `cancelForTask` at `debug`: this is called from shared code on every session change, and a
     * caller that only wants the widget repainted should not be made to reason about a warning that means
     * "this platform never had one". The absence is stated once, in this object's own documentation and
     * not re-announced on every session change.
     */
    val timeTrackingWidgetRefresher: TimeTrackingWidgetRefresher =
        object : TimeTrackingWidgetRefresher {
            override fun refreshWidget() {
                logger.d(
                    "DesktopUnsupportedPorts.refreshWidget",
                    "Nothing to redraw: this platform has no home-screen time-tracking widget",
                )
            }
        }

    private val EMPTY_COMPLETION_STATS =
        CompletionStats(
            completionRate7Days = 0.0,
            completionRate30Days = 0.0,
            completionRate90Days = 0.0,
            allTimeRate = 0.0,
            currentStreak = 0,
            longestStreak = 0,
        )
}

/**
 * The actions the shared pre-unlock update section would dispatch - none of which this platform can perform.
 *
 * The section returns early on `SupportState.NOT_APPLICABLE`, before any of these can be reached, so a no-op
 * is the honest answer rather than a stub pretending to work: this platform's app is installed by its own
 * package manager, and there is no in-app download or installer handoff to hand it.
 *
 * Declared here rather than beside its call site on purpose. The desktop screens file carries a render guard
 * that scans it for `override fun` lines and reads each as a destination, so an interface implementation
 * declared there is indistinguishable from an unwired screen.
 */
internal object NoOpPreUnlockUpdateActions : PreUnlockUpdateActions {
    override fun onCheckRequested() = Unit

    override fun onDownloadRequested() = Unit

    override fun onInstallRequested() = Unit
}
