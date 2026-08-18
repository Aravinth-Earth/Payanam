//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
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
class PayanamApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize UnifiedLogger FIRST (persistent logs to app internal storage /logs/)
        val logger = UnifiedLogger.initialize(this, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
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
        CrashSafeBreadcrumbs.dumpToLoggerAndClear(this, "PayanamApp.onCreate")
        installGlobalCrashLogging(logger)

        // Create notification channels
        createNotificationChannels()
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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppStartUpdateCheckerEntryPoint {
        fun appStartUpdateChecker(): AppStartUpdateChecker
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
