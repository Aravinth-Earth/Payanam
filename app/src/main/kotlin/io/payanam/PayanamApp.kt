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
import io.payanam.notification.NotificationActionCoordinator
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.flags.FeatureFlagsState
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.scoring.port.TaskRecurrenceHandler
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbs
import io.payanam.ui.logging.DebugLoggingPrefs
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/** Application entry point: logging first, then crash handling, notification channels, update check. */
@HiltAndroidApp
class PayanamApp : Application() {

    /** Initializes the logger before any manifest component can run; initialize() is idempotent. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        UnifiedLogger.initialize(base, BuildConfig.VERSION_NAME, BuildConfig.BUILD_SHORT_SHA)
    }

    /** Boot sequence: logger, crash handler + breadcrumbs, notification channels, then update check. */
    @Suppress("TooGenericExceptionCaught") // Intentional: app-level defensive catch
    override fun onCreate() {
        super.onCreate()

        // Initialize UnifiedLogger FIRST (persistent logs to app internal storage /logs/)
        val logger = UnifiedLogger.initialize(this, BuildConfig.VERSION_NAME, BuildConfig.BUILD_SHORT_SHA)
        // Hand the real logger to the platform-neutral modules (core/domain, core/scoring), which read
        // AppLogger.current; without this they log to a no-op default and lose every diagnostic.
        AppLogger.install(logger)
        // Crash-safe breadcrumb store: installed here rather than lazily, because a ViewModel or an
        // injected component can record a breadcrumb before any Activity is composed.
        CrashBreadcrumbs.install(SharedPreferencesBreadcrumbStore(this))
        // The shared state layer cannot read BuildConfig, so the flags reach it through the shared
        // holder; installed here because a ViewModel can be constructed before a composition exists.
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
        // Debug-level logging follows the user's stored toggle through a plain-prefs mirror, because the
        // encrypted-DB preference cannot be read before unlock; unset falls back to the build default.
        UnifiedLogger.setDebugLoggingEnabled(DebugLoggingPrefs.resolve(this))
        logger.i(
            "PayanamApp.onCreate",
            "Application starting",
            mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "buildShortSha" to BuildConfig.BUILD_SHORT_SHA,
                "debug" to BuildConfig.DEBUG,
            ),
        )

        // Initialize Timber logging for dev
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        logPreviousProcessExitReason(logger)
        // Drain the previous process's trail. Wrapped because the shared replay deliberately does not
        // swallow, while startup must continue: a diagnostic gap is the lesser failure.
        runCatching { CrashBreadcrumbs.replayToLoggerAndClear("PayanamApp.onCreate") }
            .onFailure { failure ->
                logger.w(
                    "PayanamApp.onCreate",
                    "Could not replay the crash-safe breadcrumb trail",
                    mapOf("reason" to (failure.message ?: "unknown")),
                )
            }
        installGlobalCrashLogging(logger)

        createNotificationChannels()

        // The shared ViewModels in :core/presentation read LocalDataLayer.current; installed at process
        // start because the guard is the session lock, not the holder.
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
                    // Both ports are bound in `PortBindingsModule`; naming them makes the seam carry them.
                    timeTrackingController = dataLayerEntryPoint.timeTrackingController(),
                    timeTrackingWidgetRefresher = dataLayerEntryPoint.timeTrackingWidgetRefresher(),
                ),
            )
        } catch (e: Exception) {
            logger.e("PayanamApp.onCreate", "Shared data layer not installed", e)
        }
        // The deferred reminder-action drain has to exist before any unlock can happen, and a receiver
        // tap can cold-start this process, so it is started here rather than from an Activity.
        try {
            val notificationActionCoordinator =
                EntryPointAccessors
                    .fromApplication(this, NotificationActionEntryPoint::class.java)
                    .notificationActionCoordinator()
            notificationActionCoordinator.startObservingUnlocks()
        } catch (e: Exception) {
            logger.e("PayanamApp.onCreate", "Deferred reminder actions not started", e)
        }
        logger.i("PayanamApp.onCreate", "Application initialized successfully")

        // App-start update check — resolved LAZILY via EntryPoint so nothing extra is constructed
        // during super.onCreate(), where a failure would crash before the crash handler exists.
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

    /** Hilt entry point for the app-start update check, resolved lazily out of super.onCreate(). */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppStartUpdateCheckerEntryPoint {
        /** Resolves the update checker lazily (kept out of super.onCreate()). */
        fun appStartUpdateChecker(): AppStartUpdateChecker
    }

    /** Hilt entry point for the deferred reminder-action drain, resolved lazily out of super.onCreate(). */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotificationActionEntryPoint {
        /** The coordinator that applies reminder actions recorded while the DB was locked. */
        fun notificationActionCoordinator(): NotificationActionCoordinator
    }

    /** Hilt entry point for exactly the repositories [io.payanam.presentation.data.PayanamDataLayer] holds. */
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
         * The Room-backed life-dimension catalog, carried by [io.payanam.presentation.data.PayanamDataLayer].
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
                    "buildShortSha" to BuildConfig.BUILD_SHORT_SHA,
                ),
            )
            // Auto-export the full log ZIP on crash — lands in Documents/Payanam[-debug]/exported-logs/
            // so it is reachable via the Files app even when the app itself cannot start (crash loop).
            val exportThread = Thread {
                try {
                    // Explicit final flush so the crash line (eSync above) reaches the file.
                    runBlocking { logger.flush() }
                    runBlocking { logger.exportAllLogs() }
                } catch (_: Exception) {
                    // export must never mask the original crash
                }
            }
            exportThread.start()
            try {
                // 15s budget: flush + zip must fit before Android kills the process.
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

    /** Logs why the previous process instance exited, from the app sentinel and the system records. */
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
                            // "reasonKind", not "reasonDescription": the sanitiser redacts any key
                            // containing "description" as user content.
                            "reasonKind" to describeExitReason(info.reason),
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

            val remindersChannel = NotificationChannel(
                CHANNEL_TASK_REMINDERS,
                getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.task_reminders_channel_description)
                enableVibration(true)
            }

            val habitChannel = NotificationChannel(
                CHANNEL_HABIT_TRACKING,
                getString(R.string.notification_channel_habits),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.habit_tracking_channel_description)
                enableVibration(true)
            }

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
