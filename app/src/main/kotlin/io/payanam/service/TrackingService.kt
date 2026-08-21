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
    lateinit var sessionManager: DatabaseSessionManager

    companion object {
        const val CHANNEL_ID = "tracking_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "io.payanam.action.START_TRACKING"
        const val ACTION_STOP = "io.payanam.action.STOP_TRACKING"
        const val ACTION_UPDATE = "io.payanam.action.UPDATE_TRACKING"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_DIMENSION = "dimension"
        const val EXTRA_START_TIME = "start_time"
        private const val NOTIFICATION_NAV_SOURCE = "tracking_notification"

        private val _isTracking = MutableStateFlow(false)
        val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

        private val _currentSession = MutableStateFlow<TrackingSession?>(null)
        val currentSession: StateFlow<TrackingSession?> = _currentSession.asStateFlow()

        /**
         * Start tracking.
         */
        fun startTracking(
            context: Context,
            taskId: String?,
            taskTitle: String?,
            dimension: String,
            startTime: String,
        ) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TASK_TITLE, taskTitle)
                putExtra(EXTRA_DIMENSION, dimension)
                putExtra(EXTRA_START_TIME, startTime)
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop tracking.
         */
        fun stopTracking(context: Context) {
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
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        logger.i("TrackingService.onStartCommand", "Command received", mapOf("action" to (action ?: "null")))
        when (action) {
            ACTION_START -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE)
                val dimension = intent.getStringExtra(EXTRA_DIMENSION)
                    ?: getString(R.string.tracking_default_dimension)
                val startTime = intent.getStringExtra(EXTRA_START_TIME)
                    ?: LocalDateTime.now().toString()
                startTrackingSession(taskId, taskTitle, dimension, startTime)
            }

            ACTION_STOP -> {
                stopTrackingSession()
            }

            ACTION_UPDATE -> {
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
        dimension: String,
        startTime: String,
    ) {
        logger.i(
            "TrackingService.startTrackingSession",
            "Starting tracking session",
            mapOf(
                "taskId" to (taskId ?: "none"),
                "dimension" to dimension,
                "startTime" to startTime,
            ),
        )
        val parsedStartTime = try {
            LocalDateTime.parse(startTime)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "TrackingService.startTrackingSession",
                "Failed to parse startTime; using now",
                e,
                mapOf("rawStartTime" to startTime),
            )
            LocalDateTime.now()
        }
        val session = TrackingSession(
            taskId = taskId,
            taskTitle = taskTitle ?: getString(R.string.tracking_default_entry_title),
            dimension = dimension,
            startTime = parsedStartTime,
        )

        _currentSession.value = session
        _isTracking.value = true

        // Start as foreground service
        startForeground(NOTIFICATION_ID, buildNotification(session))

        // Start periodic updates
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (true) {
                delay(1000) // Update every second
                updateNotification()
            }
        }
        logger.i(
            "TrackingService.startTrackingSession",
            "Tracking session started and foreground service running",
            mapOf(
                "taskId" to (taskId ?: "none"),
                "dimension" to dimension,
            ),
        )
    }

    private fun stopTrackingSession() {
        val session = _currentSession.value
        val durationSeconds = session?.let { Duration.between(it.startTime, LocalDateTime.now()).seconds }
        logger.i(
            "TrackingService.stopTrackingSession",
            "Stopping tracking session",
            mapOf(
                "taskId" to (session?.taskId ?: "none"),
                "dimension" to (session?.dimension ?: "none"),
                "durationSeconds" to (durationSeconds ?: 0L),
            ),
        )
        updateJob?.cancel()
        _currentSession.value = null
        _isTracking.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        logger.i("TrackingService.stopTrackingSession", "Tracking session stopped; service stopping")
    }

    private fun updateNotification() {
        _currentSession.value?.let { session ->
            val notification = buildNotification(session)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(session: TrackingSession): Notification {
        val elapsed = Duration.between(session.startTime, LocalDateTime.now())
        val elapsedText = formatDuration(elapsed)

        // Intent to open app (same target semantics as widget quick start)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
            putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, true)
            putExtra(MainActivity.EXTRA_NAV_SOURCE, NOTIFICATION_NAV_SOURCE)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openTimeActionPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Stop action redirects to Time screen focus dialog to keep stop-flow mandatory.
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
            putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, false)
            putExtra(MainActivity.EXTRA_OPEN_TIME_STOP_TRACKING, true)
            putExtra(MainActivity.EXTRA_NAV_SOURCE, NOTIFICATION_NAV_SOURCE)
        }
        val stopPendingIntent = PendingIntent.getActivity(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(session.taskTitle)
            .setContentText(
                getString(
                    R.string.tracking_notification_content,
                    session.dimension,
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
                getString(R.string.tracking_notification_action_open),
                openTimeActionPendingIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.tracking_notification_action_stop),
                stopPendingIntent,
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_tracking),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tracking_service_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.seconds
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
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
    val taskId: String?,
    val taskTitle: String,
    val dimension: String,
    val startTime: LocalDateTime,
)
