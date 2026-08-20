//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.MainActivity
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/**
 * Foreground service for active time tracking.
 * Shows an ongoing notification with the current tracking session.
 */
@AndroidEntryPoint
/**
 * TrackingService.
 */
class TrackingService : Service() {

    private val logger = UnifiedLogger.getInstance()

    @javax.inject.Inject
    /** Session manager. */
    lateinit var sessionManager: DatabaseSessionManager

    companion object {
        /** Channel id. */
        const val CHANNEL_ID = "tracking_channel"
        /** Notification id. */
        const val NOTIFICATION_ID = 1001

        /** Action start. */
        const val ACTION_START = "io.payanam.action.START_TRACKING"
        /** Action stop. */
        const val ACTION_STOP = "io.payanam.action.STOP_TRACKING"
        /** Action update. */
        const val ACTION_UPDATE = "io.payanam.action.UPDATE_TRACKING"

        /** Extra task id. */
        const val EXTRA_TASK_ID = "task_id"
        /** Extra task title. */
        const val EXTRA_TASK_TITLE = "task_title"
        /** Extra dimension. */
        const val EXTRA_DIMENSION = "dimension"
        /** Extra start time. */
        const val EXTRA_START_TIME = "start_time"
        private const val NOTIFICATION_NAV_SOURCE = "tracking_notification"

        private val _isTracking = MutableStateFlow(false)
        /** Is tracking. */
        val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

        private val _currentSession = MutableStateFlow<TrackingSession?>(null)
        /** Current session. */
        val currentSession: StateFlow<TrackingSession?> = _currentSession.asStateFlow()

        /**
         * Start tracking.
         */
        fun startTracking(
            /** Context. */
            context: Context,
            taskId: String?,
            taskTitle: String?,
            /** Dimension. */
            dimension: String,
            /** Start time. */
            startTime: String,
        ) {
            /** Intent. */
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_START
                /** Put extra. */
                putExtra(EXTRA_TASK_ID, taskId)
                /** Put extra. */
                putExtra(EXTRA_TASK_TITLE, taskTitle)
                /** Put extra. */
                putExtra(EXTRA_DIMENSION, dimension)
                /** Put extra. */
                putExtra(EXTRA_START_TIME, startTime)
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop tracking.
         */
        fun stopTracking(context: Context) {
            /** Intent. */
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val binder = TrackingBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    inner class TrackingBinder : Binder() {
        /**
         * Get service.
         */
        fun getService(): TrackingService = this@TrackingService
    }

    override fun onCreate() {
        super.onCreate()
        logger.i("TrackingService.onCreate", "Service created")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        /** Create notification channel. */
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /** Action. */
        val action = intent?.action
        logger.i("TrackingService.onStartCommand", "Command received", mapOf("action" to (action ?: "null")))
        /** When. */
        when (action) {
            ACTION_START -> {
                /** Task id. */
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                /** Task title. */
                val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE)
                /** Dimension. */
                val dimension = intent.getStringExtra(EXTRA_DIMENSION)
                    ?: getString(R.string.tracking_default_dimension)
                /** Start time. */
                val startTime = intent.getStringExtra(EXTRA_START_TIME)
                    ?: LocalDateTime.now().toString()

                /** Start tracking session. */
                startTrackingSession(taskId, taskTitle, dimension, startTime)
            }

            ACTION_STOP -> {
                /** Stop tracking session. */
                stopTrackingSession()
            }

            ACTION_UPDATE -> {
                /** Update notification. */
                updateNotification()
            }

            else -> {
                logger.w("TrackingService.onStartCommand", "Unknown or null action; ignoring", mapOf("action" to (action ?: "null")))
            }
        }

        return START_STICKY
    }

    private fun startTrackingSession(
        taskId: String?,
        taskTitle: String?,
        /** Dimension. */
        dimension: String,
        /** Start time. */
        startTime: String,
    ) {
        logger.i(
            "TrackingService.startTrackingSession",
            "Starting tracking session",
            /** Map of. */
            mapOf(
                "taskId" to (taskId ?: "none"),
                "dimension" to dimension,
                "startTime" to startTime,
            ),
        )
        /** Parsed start time. */
        val parsedStartTime = try {
            LocalDateTime.parse(startTime)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "TrackingService.startTrackingSession",
                "Failed to parse startTime; using now",
                /** E. */
                e,
                /** Map of. */
                mapOf("rawStartTime" to startTime),
            )
            LocalDateTime.now()
        }
        /** Session. */
        val session = TrackingSession(
            taskId = taskId,
            taskTitle = taskTitle ?: getString(R.string.tracking_default_entry_title),
            dimension = dimension,
            startTime = parsedStartTime,
        )

        _currentSession.value = session
        _isTracking.value = true

        // Start as foreground service
        /** Start foreground. */
        startForeground(NOTIFICATION_ID, buildNotification(session))

        // Start periodic updates
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            /** While. */
            while (true) {
                /** Delay. */
                delay(1000) // Update every second
                /** Update notification. */
                updateNotification()
            }
        }
        logger.i(
            "TrackingService.startTrackingSession",
            "Tracking session started and foreground service running",
            /** Map of. */
            mapOf(
                "taskId" to (taskId ?: "none"),
                "dimension" to dimension,
            ),
        )
    }

    private fun stopTrackingSession() {
        /** Session. */
        val session = _currentSession.value
        /** Duration seconds. */
        val durationSeconds = session?.let { Duration.between(it.startTime, LocalDateTime.now()).seconds }
        logger.i(
            "TrackingService.stopTrackingSession",
            "Stopping tracking session",
            /** Map of. */
            mapOf(
                "taskId" to (session?.taskId ?: "none"),
                "dimension" to (session?.dimension ?: "none"),
                "durationSeconds" to (durationSeconds ?: 0L),
            ),
        )
        updateJob?.cancel()
        _currentSession.value = null
        _isTracking.value = false

        /** Stop foreground. */
        stopForeground(STOP_FOREGROUND_REMOVE)
        /** Stop self. */
        stopSelf()
        logger.i("TrackingService.stopTrackingSession", "Tracking session stopped; service stopping")
    }

    private fun updateNotification() {
        _currentSession.value?.let { session ->
            /** Notification. */
            val notification = buildNotification(session)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(session: TrackingSession): Notification {
        /** Elapsed. */
        val elapsed = Duration.between(session.startTime, LocalDateTime.now())
        /** Elapsed text. */
        val elapsedText = formatDuration(elapsed)

        // Intent to open app (same target semantics as widget quick start)
        /** Open app intent. */
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            /** Put extra. */
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
            /** Put extra. */
            putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, true)
            /** Put extra. */
            putExtra(MainActivity.EXTRA_NAV_SOURCE, NOTIFICATION_NAV_SOURCE)
        }
        /** Open app pending intent. */
        val openAppPendingIntent = PendingIntent.getActivity(
            /** This. */
            this,
            0,
            /** Open app intent. */
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Open time action pending intent. */
        val openTimeActionPendingIntent = PendingIntent.getActivity(
            /** This. */
            this,
            2,
            /** Open app intent. */
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Stop action redirects to Time screen focus dialog to keep stop-flow mandatory.
        /** Stop intent. */
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            /** Put extra. */
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
            /** Put extra. */
            putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, false)
            /** Put extra. */
            putExtra(MainActivity.EXTRA_OPEN_TIME_STOP_TRACKING, true)
            /** Put extra. */
            putExtra(MainActivity.EXTRA_NAV_SOURCE, NOTIFICATION_NAV_SOURCE)
        }
        /** Stop pending intent. */
        val stopPendingIntent = PendingIntent.getActivity(
            /** This. */
            this,
            1,
            /** Stop intent. */
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(session.taskTitle)
            .setContentText(
                /** Get string. */
                getString(
                    R.string.tracking_notification_content,
                    session.dimension,
                    /** Elapsed text. */
                    elapsedText,
                ),
            )
            .setSubText(getString(R.string.tracking_notification_subtext))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - elapsed.toMillis())
            .setChronometerCountDown(false)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_view,
                /** Get string. */
                getString(R.string.tracking_notification_action_open),
                /** Open time action pending intent. */
                openTimeActionPendingIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                /** Get string. */
                getString(R.string.tracking_notification_action_stop),
                /** Stop pending intent. */
                stopPendingIntent,
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        /** Channel. */
        val channel = NotificationChannel(
            /** Channel id. */
            CHANNEL_ID,
            /** Get string. */
            getString(R.string.notification_channel_tracking),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tracking_service_channel_description)
            /** Set show badge. */
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun formatDuration(duration: Duration): String {
        /** Total seconds. */
        val totalSeconds = duration.seconds
        /** Hours. */
        val hours = totalSeconds / 3600
        /** Minutes. */
        val minutes = (totalSeconds % 3600) / 60
        /** Seconds. */
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        logger.i(
            "TrackingService.onDestroy",
            "Service destroying; flushing WAL checkpoint",
            /** Map of. */
            mapOf(
                "wasTracking" to _isTracking.value,
            ),
        )
        updateJob?.cancel()
        // Flush WAL journal when foreground service exits
        sessionManager.checkpoint()
        super.onDestroy()
    }
}

/**
 * TrackingSession.
 */
data class TrackingSession(
    /** Task id. */
    val taskId: String?,
    /** Task title. */
    val taskTitle: String,
    /** Dimension. */
    val dimension: String,
    /** Start time. */
    val startTime: LocalDateTime,
)
