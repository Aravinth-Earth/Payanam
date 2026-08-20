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
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.AppStartUpdateChecker
import kotlinx.coroutines.runBlocking
import timber.log.Timber

@HiltAndroidApp
/**
 * PayanamApp.
 */
class PayanamApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize UnifiedLogger FIRST (persistent logs to app internal storage /logs/)
        /** Logger. */
        val logger = UnifiedLogger.initialize(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        logger.i(
            "PayanamApp.onCreate",
            "Application starting",
            /** Map of. */
            mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "debug" to BuildConfig.DEBUG,
            ),
        )

        // Initialize Timber logging for dev
        /** If. */
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        /** Log previous process exit reason. */
        logPreviousProcessExitReason(logger)
        CrashSafeBreadcrumbs.dumpToLoggerAndClear(this, "PayanamApp.onCreate")
        /** Install global crash logging. */
        installGlobalCrashLogging(logger)

        // Create notification channels
        /** Create notification channels. */
        createNotificationChannels()
        logger.i("PayanamApp.onCreate", "Application initialized successfully")

        // App-start update check — resolved LAZILY via EntryPoint so nothing
        // extra is constructed during super.onCreate() (Hilt field injection
        // there would eagerly build the DB-session chain before the crash
        // handler is installed; a failure would crash with no log export).
        try {
            /** Checker. */
            val checker = EntryPointAccessors.fromApplication(
                /** This. */
                this,
                AppStartUpdateCheckerEntryPoint::class.java,
            ).appStartUpdateChecker()
            checker.onAppStart()
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("PayanamApp.onCreate", "App-start update check skipped", e)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    /**
     * AppStartUpdateCheckerEntryPoint.
     */
    interface AppStartUpdateCheckerEntryPoint {
        /**
         * App start update checker.
         */
        fun appStartUpdateChecker(): AppStartUpdateChecker
    }

    private fun installGlobalCrashLogging(logger: UnifiedLogger) {
        /** Previous handler. */
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
            /** Export thread. */
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
            /** Map of. */
            mapOf("hasPreviousHandler" to (previousHandler != null)),
        )
    }

    /**
     * Logs why the previous process instance exited. Checks both the app-written sentinel
     * (for inactivity-timeout kills) and the system ActivityManager exit reasons (API 30+).
     */
    private fun logPreviousProcessExitReason(logger: UnifiedLogger) {
        // Check sentinel written by DatabaseSessionManager before inactivity kill
        /** Prefs. */
        val prefs = getSharedPreferences(PREFS_PROCESS_LIFECYCLE, Context.MODE_PRIVATE)
        /** Last exit reason. */
        val lastExitReason = prefs.getString(KEY_LAST_EXIT_REASON, null)
        /** Last exit ts. */
        val lastExitTs = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        /** If. */
        if (lastExitReason != null) {
            logger.i(
                "PayanamApp.processRestart",
                "Previous process exit was app-initiated",
                /** Map of. */
                mapOf(
                    "reason" to lastExitReason,
                    "exitTimestamp" to lastExitTs,
                ),
            )
            prefs.edit().remove(KEY_LAST_EXIT_REASON).remove(KEY_LAST_EXIT_TIMESTAMP).apply()
        }

        // System-level exit reasons (API 30+)
        /** If. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            /** Am. */
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            /** Reasons. */
            val reasons = am.getHistoricalProcessExitReasons(packageName, 0, 3)
            /** If. */
            if (reasons.isNotEmpty()) {
                /** For. */
                for (info in reasons) {
                    logger.i(
                        "PayanamApp.processRestart",
                        "System process exit record",
                        /** Map of. */
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
        /** If. */
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
        /** Logger. */
        val logger = UnifiedLogger.getInstance()
        logger.d("PayanamApp.createNotificationChannels", "Creating notification channels")

        /** If. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            /** Notification manager. */
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Time Tracking channel (ongoing, low priority - no sound)
            /** Tracking channel. */
            val trackingChannel = NotificationChannel(
                /** Channel tracking. */
                CHANNEL_TRACKING,
                /** Get string. */
                getString(R.string.notification_channel_tracking),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.tracking_notification_channel_description)
                /** Set show badge. */
                setShowBadge(false)
            }

            // Task Reminders channel (higher priority for due dates)
            /** Reminders channel. */
            val remindersChannel = NotificationChannel(
                /** Channel task reminders. */
                CHANNEL_TASK_REMINDERS,
                /** Get string. */
                getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.task_reminders_channel_description)
                /** Enable vibration. */
                enableVibration(true)
            }

            // Habit Tracking channel (recurring tasks)
            /** Habit channel. */
            val habitChannel = NotificationChannel(
                /** Channel habit tracking. */
                CHANNEL_HABIT_TRACKING,
                /** Get string. */
                getString(R.string.notification_channel_habits),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.habit_tracking_channel_description)
                /** Enable vibration. */
                enableVibration(true)
            }

            // Missed Tasks channel (alerts when overdue)
            /** Missed channel. */
            val missedChannel = NotificationChannel(
                /** Channel missed tasks. */
                CHANNEL_MISSED_TASKS,
                /** Get string. */
                getString(R.string.notification_channel_missed),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.missed_tasks_channel_description)
                /** Enable vibration. */
                enableVibration(true)
            }

            notificationManager.createNotificationChannel(trackingChannel)
            notificationManager.createNotificationChannel(remindersChannel)
            notificationManager.createNotificationChannel(habitChannel)
            notificationManager.createNotificationChannel(missedChannel)

            logger.i(
                "PayanamApp.createNotificationChannels",
                "Channels created successfully",
                /** Map of. */
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
        /** Channel tracking. */
        const val CHANNEL_TRACKING = "tracking_channel"
        /** Channel task reminders. */
        const val CHANNEL_TASK_REMINDERS = "task_reminders"
        /** Channel habit tracking. */
        const val CHANNEL_HABIT_TRACKING = "habit_tracking"
        /** Channel missed tasks. */
        const val CHANNEL_MISSED_TASKS = "missed_tasks"
        /** Prefs process lifecycle. */
        const val PREFS_PROCESS_LIFECYCLE = "payanam_process_lifecycle"
        /** Key last exit reason. */
        const val KEY_LAST_EXIT_REASON = "last_exit_reason"
        /** Key last exit timestamp. */
        const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    }
}
