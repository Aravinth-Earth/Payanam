//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Looper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import io.payanam.common.logging.SharedPreferencesBreadcrumbStore
import io.payanam.common.logging.UnifiedLogger
import io.payanam.data.RoomPayanamDataLayer
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
import io.payanam.feature.settings.AppStartUpdateChecker
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.flags.FeatureFlagsState
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.scoring.port.TaskRecurrenceHandler
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbs
import io.payanam.ui.logging.DebugLoggingPrefs
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Application entry point: initializes logging before anything else, installs
 * a crash handler that sync-logs and auto-exports the log ZIP, records the
 * previous process exit reason, and creates notification channels.
 */
@HiltAndroidApp
class PayanamApp : Application() {

    /**
     * Boot sequence: logger first, crash handler + breadcrumbs, notification
     * channels, then the lazy app-start update check.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: app-level defensive catch
    override fun onCreate() {
        super.onCreate()

        // Initialize UnifiedLogger FIRST (persistent logs to app internal storage /logs/)
        val logger = UnifiedLogger.initialize(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        // Hand the real logger to the platform-neutral modules (core/domain, core/scoring). They
        // cannot reach this Android singleton, so they read AppLogger.current instead. Without this
        // call they would log to the no-op default and every diagnostic from those modules would be
        // discarded silently - the failure mode this project forbids.
        AppLogger.install(logger)
        // Then the crash-safe breadcrumb store, and for the same reason plus one: it is the same
        // install-once-then-read shape as AppLogger.install, and the reason it must come after it is
        // the trail's own lifecycle - a breadcrumb recorded before the logger exists could be reported
        // into a no-op default and lost, and the replay below logs through AppLogger.current. So:
        // logger, then store, then drain. Installed in onCreate rather than lazily because a ViewModel
        // or a Hilt-injected component can record a breadcrumb before any Activity is composed, and a
        // store that arrives late silently discards the steps it would have covered.
        CrashBreadcrumbs.install(SharedPreferencesBreadcrumbStore(this))
        // The shared state layer cannot read BuildConfig either, so the flags reach it through the shared
        // holder - the same shape as AppLogger.install above and LocalDataLayer.install below. Installed here
        // rather than in MainActivity because a ViewModel can be constructed before a composition exists; the
        // UI reads the same values through LocalFeatureFlags, so both paths carry one source of truth.
        PlatformFeatureFlags.install(
            FeatureFlagsState(
                minimalModeEnabled = FeatureFlags.minimalModeEnabled,
                recurringTasksEnabled = FeatureFlags.recurringTasksEnabled,
                scoringEnabled = FeatureFlags.scoringEnabled,
                remindersEnabled = FeatureFlags.remindersEnabled,
                tagsEnabled = FeatureFlags.tagsEnabled,
                plansCtaEnabled = FeatureFlags.plansCtaEnabled,
            ),
        )
        // Debug-level logging follows the user's stored toggle via a plain-prefs mirror (the
        // encrypted-DB preference cannot be read before unlock): a stored disable is respected
        // from process start, and only a never-set value falls back to the build-type default.
        // The fallback keeps the interaction trace working from process start on debug builds —
        // the onboarding / DB-init / dimension gates all run before preferences are loaded.
        UnifiedLogger.setDebugLoggingEnabled(DebugLoggingPrefs.resolve(this))
        logger.i(
            "PayanamApp.onCreate",
            "Application starting",
            mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "debug" to BuildConfig.DEBUG,
            ),
        )

        // Initialize Timber logging for dev
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        logPreviousProcessExitReason(logger)
        // Drain the trail left by the previous process, through the shared seam. Wrapped because the
        // shared replay deliberately does not swallow - a store that cannot be read is the finding, not
        // an inconvenience - while startup must continue: the Android breadcrumb object this replaces
        // did swallow, and losing the app over a diagnostic gap is the worse failure.
        runCatching { CrashBreadcrumbs.replayToLoggerAndClear("PayanamApp.onCreate") }
            .onFailure { failure ->
                logger.w(
                    "PayanamApp.onCreate",
                    "Could not replay the crash-safe breadcrumb trail",
                    mapOf("reason" to (failure.message ?: "unknown")),
                )
            }
        installGlobalCrashLogging(logger)

        // Create notification channels
        createNotificationChannels()

        // Put the shared data layer in force. The shared ViewModels in :core/presentation cannot use Hilt,
        // so they read LocalDataLayer.current; without this the holder stays un-installed and every shared
        // screen fails with a loud "no data layer" error rather than showing the user's data.
        //
        // Installed at process start, unlike the desktop which installs on unlock. The reason is a real
        // platform difference, not a divergence: Android's repository graph is app-scoped and exists from
        // process start, and its actual guard is the SESSION lock - a read before unlock fails inside the
        // repository, with the correct reason. The desktop has neither, so it has nothing to install until
        // it has opened the file.
        //
        // Resolved through an EntryPoint rather than field injection, for the same reason as the update
        // check below: nothing extra is constructed during super.onCreate(), and a failure here is logged
        // instead of crashing before the crash handler is installed.
        try {
            val dataLayerEntryPoint =
                EntryPointAccessors.fromApplication(this, DataLayerEntryPoint::class.java)
            LocalDataLayer.install(
                RoomPayanamDataLayer(
                    notes = dataLayerEntryPoint.noteRepository(),
                    tags = dataLayerEntryPoint.tagRepository(),
                    tasks = dataLayerEntryPoint.taskRepository(),
                    timeEntries = dataLayerEntryPoint.timeEntryRepository(),
                    dayPlans = dataLayerEntryPoint.dayPlanRepository(),
                    taskOccurrences = dataLayerEntryPoint.taskOccurrenceRepository(),
                    journal = dataLayerEntryPoint.journalRepository(),
                    habitMetrics = dataLayerEntryPoint.habitMetricRepository(),
                    scoreWindows = dataLayerEntryPoint.scoreWindowRepository(),
                    taskReschedules = dataLayerEntryPoint.taskRescheduleRepository(),
                    appSettings = dataLayerEntryPoint.appSettingsRepository(),
                    // The Room-backed catalog. It was already bound in Hilt (`RepositoryModule`) and already
                    // injected into `AppPreferencesViewModel`; it just was not reachable through the SHARED
                    // holder, which is what left the desktop with no way to read the same rows.
                    lifeDimensions = dataLayerEntryPoint.lifeDimensionCatalogRepository(),
                    lenses = dataLayerEntryPoint.lensRepository(),
                    scoringConfig = dataLayerEntryPoint.scoringConfigRepository(),
                    reminderScheduler = dataLayerEntryPoint.reminderScheduler(),
                    scoreRollupTrigger = dataLayerEntryPoint.scoreRollupTrigger(),
                    recurrenceHandler = dataLayerEntryPoint.recurrenceHandler(),
                    habitTimeEntryRecorder = dataLayerEntryPoint.habitTimeEntryRecorder(),
                    scoreCalculator = dataLayerEntryPoint.scoreCalculator(),
                    tracer = dataLayerEntryPoint.tracer(),
                    scoreChanges = dataLayerEntryPoint.scoreChanges(),
                    // Both ports are already bound in `PortBindingsModule`; naming them here is what makes
                    // the shared seam carry Android's real objects rather than leaving the entries to be
                    // filled by a platform that has none.
                    timeTrackingController = dataLayerEntryPoint.timeTrackingController(),
                    timeTrackingWidgetRefresher = dataLayerEntryPoint.timeTrackingWidgetRefresher(),
                ),
            )
        } catch (e: Exception) {
            logger.e("PayanamApp.onCreate", "Shared data layer not installed", e)
        }
        logger.i("PayanamApp.onCreate", "Application initialized successfully")

        // App-start update check — resolved LAZILY via EntryPoint so nothing
        // extra is constructed during super.onCreate() (Hilt field injection
        // there would eagerly build the DB-session chain before the crash
        // handler is installed; a failure would crash with no log export).
        try {
            val checker = EntryPointAccessors.fromApplication(
                this,
                AppStartUpdateCheckerEntryPoint::class.java,
            ).appStartUpdateChecker()
            checker.onAppStart()
        } catch (e: Exception) {
            logger.e("PayanamApp.onCreate", "App-start update check skipped", e)
        }
    }

    /**
     * Hilt entry point for the app-start update check, resolved lazily so
     * nothing extra is built during super.onCreate().
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppStartUpdateCheckerEntryPoint {
        /**
         * Resolves the update checker lazily (kept out of super.onCreate()).
         */
        fun appStartUpdateChecker(): AppStartUpdateChecker
    }

    /**
     * Hilt entry point for the repositories the shared data layer needs.
     *
     * Not a repository-provider list in general - it names exactly the repositories the current
     * [io.payanam.presentation.data.PayanamDataLayer] holds, and grows with that interface.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DataLayerEntryPoint {
        /** The Room-backed notes repository. */
        fun noteRepository(): NoteRepository

        /** The Room-backed tag repository. */
        fun tagRepository(): TagRepository

        /** The Room-backed task repository. */
        fun taskRepository(): TaskRepository

        /** The Room-backed time-entry repository. */
        fun timeEntryRepository(): TimeEntryRepository

        /** The Room-backed day-plan repository. */
        fun dayPlanRepository(): DayPlanRepository

        /** The Room-backed task-occurrence repository. */
        fun taskOccurrenceRepository(): TaskOccurrenceRepository

        /** The Room-backed habit L1 metric repository. */
        fun habitMetricRepository(): HabitMetricRepository

        /** The Room-backed score-window repository. */
        fun scoreWindowRepository(): ScoreWindowRepository

        /** The Room-backed task-reschedule repository. */
        fun taskRescheduleRepository(): TaskRescheduleRepository

        /** Arms and cancels task reminders. */
        fun reminderScheduler(): TaskReminderScheduler

        /** Starts and stops a tracking session on this platform. */
        fun timeTrackingController(): TimeTrackingController

        /** Repaints the home-screen time-tracking widget. */
        fun timeTrackingWidgetRefresher(): TimeTrackingWidgetRefresher

        /** Recomputes the score roll-ups. */
        fun scoreRollupTrigger(): ScoreRollupTrigger

        /** Advances a recurring task's schedule. */
        fun recurrenceHandler(): TaskRecurrenceHandler

        /** Records the time entry a completed habit produces. */
        fun habitTimeEntryRecorder(): HabitTimeEntryRecorder

        /** Computes a task's score. */
        fun scoreCalculator(): ScoreCalculator

        /** Records performance events. */
        fun tracer(): PerformanceTracer

        /** The Room-backed lens repository. */
        fun lensRepository(): LensRepository

        /** The Room-backed scoring-configuration repository. */
        fun scoringConfigRepository(): ScoringConfigRepository

        /** The score-change stream the shared state layer listens to. */
        fun scoreChanges(): ScoreChangeStream

        /** The Room-backed app-settings repository. */
        fun appSettingsRepository(): AppSettingsRepository

        /** The Room-backed journal repository. */
        fun journalRepository(): JournalRepository

        /**
         * The Room-backed life-dimension catalog.
         *
         * Named here because [io.payanam.presentation.data.PayanamDataLayer] holds it: this entry point
         * lists exactly the repositories the shared data layer carries, and grows with that interface.
         */
        fun lifeDimensionCatalogRepository(): LifeDimensionCatalogRepository
    }

    private fun installGlobalCrashLogging(logger: UnifiedLogger) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logger.eSync(
                source = "PayanamApp.uncaughtException",
                message = "Unhandled crash captured",
                error = throwable,
                data = mapOf(
                    "threadName" to thread.name,
                    "threadId" to thread.id,
                    "isMainThread" to (thread == Looper.getMainLooper().thread),
                    "versionName" to BuildConfig.VERSION_NAME,
                    "versionCode" to BuildConfig.VERSION_CODE,
                ),
            )
            // Auto-export the full log ZIP on crash — lands in
            // Documents/payanam[-debug]/exported-logs/ so it is reachable via the
            // Files app even when the app itself cannot start (crash loop).
            // Best-effort on a separate thread with a hard cap; never blocks.
            val exportThread = Thread {
                try {
                    // Explicit final flush so the crash line (eSync above) and
                    // any sibling lines reach the file before the zip runs.
                    runBlocking { logger.flush() }
                    runBlocking { logger.exportAllLogs() }
                } catch (_: Exception) {
                    // export must never mask the original crash
                }
            }
            exportThread.start()
            try {
                // 15s budget: flush + zip of the full history must fit before
                // Android kills the process after the handler returns.
                exportThread.join(15_000)
            } catch (_: InterruptedException) {
                // give up waiting; original handler still runs below
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        logger.i(
            "PayanamApp.installGlobalCrashLogging",
            "Installed default uncaught exception handler",
            mapOf("hasPreviousHandler" to (previousHandler != null)),
        )
    }

    /**
     * Logs why the previous process instance exited. Checks both the app-written sentinel
     * (for inactivity-timeout kills) and the system ActivityManager exit reasons (API 30+).
     */
    private fun logPreviousProcessExitReason(logger: UnifiedLogger) {
        // Check sentinel written by DatabaseSessionManager before inactivity kill
        val prefs = getSharedPreferences(PREFS_PROCESS_LIFECYCLE, Context.MODE_PRIVATE)
        val lastExitReason = prefs.getString(KEY_LAST_EXIT_REASON, null)
        val lastExitTs = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        if (lastExitReason != null) {
            logger.i(
                "PayanamApp.processRestart",
                "Previous process exit was app-initiated",
                mapOf(
                    "reason" to lastExitReason,
                    "exitTimestamp" to lastExitTs,
                ),
            )
            prefs.edit().remove(KEY_LAST_EXIT_REASON).remove(KEY_LAST_EXIT_TIMESTAMP).apply()
        }

        // System-level exit reasons (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(packageName, 0, 3)
            if (reasons.isNotEmpty()) {
                for (info in reasons) {
                    logger.i(
                        "PayanamApp.processRestart",
                        "System process exit record",
                        mapOf(
                            "reason" to info.reason,
                            "reasonDescription" to describeExitReason(info.reason),
                            "importance" to info.importance,
                            "status" to info.status,
                            "description" to (info.description ?: "none"),
                            "timestamp" to info.timestamp,
                            "pss" to info.pss,
                            "rss" to info.rss,
                        ),
                    )
                }
            }
        }
    }

    private fun describeExitReason(reason: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "unknown"
        return when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else -> "UNKNOWN($reason)"
        }
    }

    private fun createNotificationChannels() {
        val logger = UnifiedLogger.getInstance()
        logger.d("PayanamApp.createNotificationChannels", "Creating notification channels")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Time Tracking channel (ongoing, low priority - no sound)
            val trackingChannel = NotificationChannel(
                CHANNEL_TRACKING,
                getString(R.string.notification_channel_tracking),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.tracking_notification_channel_description)
                setShowBadge(false)
            }

            // Task Reminders channel (higher priority for due dates)
            val remindersChannel = NotificationChannel(
                CHANNEL_TASK_REMINDERS,
                getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.task_reminders_channel_description)
                enableVibration(true)
            }

            // Habit Tracking channel (recurring tasks)
            val habitChannel = NotificationChannel(
                CHANNEL_HABIT_TRACKING,
                getString(R.string.notification_channel_habits),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.habit_tracking_channel_description)
                enableVibration(true)
            }

            // Missed Tasks channel (alerts when overdue)
            val missedChannel = NotificationChannel(
                CHANNEL_MISSED_TASKS,
                getString(R.string.notification_channel_missed),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.missed_tasks_channel_description)
                enableVibration(true)
            }

            notificationManager.createNotificationChannel(trackingChannel)
            notificationManager.createNotificationChannel(remindersChannel)
            notificationManager.createNotificationChannel(habitChannel)
            notificationManager.createNotificationChannel(missedChannel)

            logger.i(
                "PayanamApp.createNotificationChannels",
                "Channels created successfully",
                mapOf(
                    "tracking" to CHANNEL_TRACKING,
                    "reminders" to CHANNEL_TASK_REMINDERS,
                    "habits" to CHANNEL_HABIT_TRACKING,
                    "missed" to CHANNEL_MISSED_TASKS,
                ),
            )
        } else {
            logger.d("PayanamApp.createNotificationChannels", "Skipped - API < O")
        }
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking_channel"
        const val CHANNEL_TASK_REMINDERS = "task_reminders"
        const val CHANNEL_HABIT_TRACKING = "habit_tracking"
        const val CHANNEL_MISSED_TASKS = "missed_tasks"
        const val PREFS_PROCESS_LIFECYCLE = "payanam_process_lifecycle"
        const val KEY_LAST_EXIT_REASON = "last_exit_reason"
        const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    }
}
