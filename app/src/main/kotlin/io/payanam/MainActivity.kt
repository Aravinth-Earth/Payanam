//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

package io.payanam

import android.app.LocaleManager
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.FeatureFlags
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.PayanamDatabase
import io.payanam.database.backfill.ScoreRollupBackfillService
import io.payanam.database.security.DatabaseArtifactJanitor
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.service.AutoBackupWorker
import io.payanam.ui.PayanamNavHost
import io.payanam.ui.theme.PayanamTheme
import io.payanam.ui.viewmodel.AppLanguageOption
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.AppPreferencesViewModel
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.usecase.RecurrenceManager
import io.payanam.widget.TimeTrackingWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.inject.Inject

/**
 * Sealed set of navigation commands the activity must handle from view models.
 */
sealed interface ExternalNavigationCommand {
    /**
     * OpenTimeScreen.
     */
    data class OpenTimeScreen(
        /** Open quick start. */
        val openQuickStart: Boolean,
        /** Open stop tracking. */
        val openStopTracking: Boolean,
        /** Source. */
        val source: String,
        /** Request id. */
        val requestId: Long = System.currentTimeMillis(),
    ) : ExternalNavigationCommand
}

@AndroidEntryPoint
/**
 * MainActivity.
 */
class MainActivity : FragmentActivity() {
    private val logger = UnifiedLogger.getInstance()
    private val pendingExternalCommand = MutableStateFlow<ExternalNavigationCommand?>(null)
    private var hasEnteredForegroundOnce = false
    private var lastStoppedAtElapsedMs: Long? = null

    @Inject
    /** Notification scheduler. */
    lateinit var notificationScheduler: Lazy<NotificationScheduler>

    @Inject
    /** Recurrence manager. */
    lateinit var recurrenceManager: Lazy<RecurrenceManager>

    @Inject
    /** Score rollup backfill service. */
    lateinit var scoreRollupBackfillService: Lazy<ScoreRollupBackfillService>

    @Inject
    /** App settings repository. */
    lateinit var appSettingsRepository: Lazy<AppSettingsRepository>

    @Inject
    /** Session manager. */
    lateinit var sessionManager: DatabaseSessionManager

    private var showDatabaseInit by mutableStateOf(false)
    private var showFocusModeOnboarding by mutableStateOf(false)
    private var showPassphraseSetup by mutableStateOf(false)
    private var showPassphraseUnlock by mutableStateOf(false)
    private var showExternalDeletionWarning = mutableStateOf(false)
    private var resumeToRouteAfterUnlock by mutableStateOf<String?>(null)

    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionManager.touch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.i("MainActivity.onCreate", "Activity creating")
        /** Handle external navigation intent. */
        handleExternalNavigationIntent(intent)
        /** Pre janitor snapshot. */
        val preJanitorSnapshot = captureDbArtifactSnapshot()
        logger.i(
            "MainActivity.onCreate",
            "DB artifact snapshot before startup janitor",
            preJanitorSnapshot.toLogMap(),
        )
        DatabaseArtifactJanitor.cleanupStaleArtifacts(this, "MainActivity.onCreate")
        /** Post janitor snapshot. */
        val postJanitorSnapshot = captureDbArtifactSnapshot()
        logger.i(
            "MainActivity.onCreate",
            "DB artifact snapshot after startup janitor",
            postJanitorSnapshot.toLogMap(),
        )
        /** Log pending restart marker. */
        logPendingRestartMarker(preJanitorSnapshot, postJanitorSnapshot)

        /** Has database artifacts. */
        val hasDatabaseArtifacts = DatabaseHealthChecker.hasDatabaseArtifacts(this)
        /** Db file. */
        val dbFile = getDatabasePath(io.payanam.database.PayanamDatabase.DATABASE_NAME)
        logger.i(
            "MainActivity.onCreate",
            "DB artifact presence at startup",
            /** Map of. */
            mapOf(
                "db" to dbFile.exists(),
                "wal" to java.io.File(dbFile.parent, "${io.payanam.database.PayanamDatabase.DATABASE_NAME}-wal").exists(),
                "shm" to java.io.File(dbFile.parent, "${io.payanam.database.PayanamDatabase.DATABASE_NAME}-shm").exists(),
                "sizeKB" to if (dbFile.exists()) dbFile.length() / 1024 else 0L,
            ),
        )
        /** Encryption manager. */
        val encryptionManager = DatabaseEncryptionManager(this)
        /** Has passphrase configured. */
        var hasPassphraseConfigured = encryptionManager.hasPassphraseConfigured()
        logger.i("MainActivity.onCreate", "Encryption state resolved", mapOf("hasPassphraseConfigured" to hasPassphraseConfigured))

        // Detect and self-heal an invalid boot state: passphrase/Keystore state exists but no DB
        // file is present. This can happen if a previous session's backup worker corruption
        // handler deleted the DB, or if the DB was otherwise lost.
        // Show a warning to the user so they know data was lost (may be recoverable from backup).
        /** If. */
        if (!hasDatabaseArtifacts && hasPassphraseConfigured) {
            logger.e(
                "MainActivity.onCreate",
                "Invalid boot state: passphrase configured but no DB artifacts found",
            )
            showExternalDeletionWarning.value = true
            encryptionManager.resetEncryptionState()
            hasPassphraseConfigured = false
        }

        // Unlock screen is needed when passphrase is configured but the DB session is not yet open.
        // (Old design used a SharedPrefs timestamp; new design checks the live Room session state.)
        /** Should show passphrase unlock. */
        val shouldShowPassphraseUnlock =
            hasPassphraseConfigured && encryptionManager.isEncryptionEnabled() && !sessionManager.isOpen.value
        logger.i("MainActivity.onCreate", "Startup gate resolved", mapOf("showPassphraseUnlock" to shouldShowPassphraseUnlock, "sessionOpen" to sessionManager.isOpen.value))

        // Health check is only meaningful when the DB is open (session already active).
        // For encrypted DBs at cold boot, shouldShowPassphraseUnlock=true so this block is skipped.
        /** Passphrase for health check. */
        val passphraseForHealthCheck = if (!shouldShowPassphraseUnlock && encryptionManager.isEncryptionEnabled()) {
            runCatching { sessionManager.requireOpenPassphrase() }.getOrNull()
        } else {
            /** Null. */
            null
        }
        /** Health result. */
        val healthResult = if (hasDatabaseArtifacts && !shouldShowPassphraseUnlock) {
            DatabaseHealthChecker.checkDatabaseHealth(this, passphraseForHealthCheck)
        } else {
            /** Null. */
            null
        }

        // AppSettingsRepository reads from Room which requires an open DB session.
        // For unencrypted DBs (e.g. freshly created before the user sets a passphrase),
        // the session is null at cold boot, so we open a bootstrap session here.
        // Encrypted DBs are covered by the shouldShowPassphraseUnlock path which opens
        // the session after the user enters their passphrase.
        /** If. */
        if (hasDatabaseArtifacts && healthResult?.isHealthy == true && !shouldShowPassphraseUnlock &&
            !encryptionManager.isEncryptionEnabled() && !sessionManager.isDbOpen()
        ) {
            runBlocking { sessionManager.openDatabase("") }
        }
        /** Database init completed. */
        val databaseInitCompleted = if (hasDatabaseArtifacts && healthResult?.isHealthy == true && !shouldShowPassphraseUnlock) {
            runBlocking { appSettingsRepository.get().getSetting("database_init_completed")?.toBoolean() ?: false }
        } else {
            /** False. */
            false
        }

        /** Should show database init. */
        val shouldShowDatabaseInit = resolveShouldShowDatabaseInit(
            hasDatabaseArtifacts = hasDatabaseArtifacts,
            shouldShowPassphraseUnlock = shouldShowPassphraseUnlock,
            isHealthy = healthResult?.isHealthy == true,
            databaseInitCompleted = databaseInitCompleted,
        )
        /** Should show passphrase setup. */
        val shouldShowPassphraseSetup = !hasPassphraseConfigured && !shouldShowDatabaseInit && !shouldShowPassphraseUnlock

        showPassphraseSetup = shouldShowPassphraseSetup
        showPassphraseUnlock = shouldShowPassphraseUnlock
        showDatabaseInit = shouldShowDatabaseInit

        // Focus mode onboarding is disabled in minimal mode and when focus settings are feature-gated off.
        /** Focus mode onboarding eligible. */
        val focusModeOnboardingEligible = !FeatureFlags.minimalModeEnabled && FeatureFlags.focusModeSettingsEnabled
        /** Should show focus mode onboarding. */
        val shouldShowFocusModeOnboarding = if (
            focusModeOnboardingEligible &&
            !shouldShowDatabaseInit &&
            !shouldShowPassphraseSetup &&
            !shouldShowPassphraseUnlock
        ) {
            runBlocking {
                appSettingsRepository.get().getSetting("focus_mode_onboarding_completed")?.toBoolean() ?: false
            }.not()
        } else {
            /** False. */
            false
        }
        showFocusModeOnboarding = shouldShowFocusModeOnboarding

        /** Startup health log summary. */
        val startupHealthLogSummary = resolveStartupHealthLogSummary(
            hasDatabaseArtifacts = hasDatabaseArtifacts,
            shouldShowPassphraseUnlock = shouldShowPassphraseUnlock,
            healthResult = healthResult,
        )
        logger.i(
            "MainActivity.onCreate",
            "Database health check",
            /** Map of. */
            mapOf(
                "healthStatus" to startupHealthLogSummary.status,
                "isHealthy" to (startupHealthLogSummary.isHealthy?.toString() ?: "N/A"),
                "needsRepair" to (startupHealthLogSummary.needsRepair?.toString() ?: "N/A"),
                "errorMessage" to (startupHealthLogSummary.errorMessage ?: "N/A"),
                "hasDatabaseArtifacts" to hasDatabaseArtifacts,
                "databaseInitCompleted" to databaseInitCompleted,
                "hasPassphraseConfigured" to hasPassphraseConfigured,
                "showPassphraseSetup" to shouldShowPassphraseSetup,
                "showPassphraseUnlock" to shouldShowPassphraseUnlock,
                "showInitScreen" to shouldShowDatabaseInit,
                "focusModeOnboardingEligible" to focusModeOnboardingEligible,
                "showFocusModeOnboarding" to shouldShowFocusModeOnboarding,
            ),
        )
        /** If. */
        if (healthResult?.needsRepair == true) {
            logger.w(
                "MainActivity.onCreate",
                "Database needs repair - showing init screen",
                /** Map of. */
                mapOf(
                    "reason" to healthResult.errorMessage,
                ),
            )
        }
        /** Enable edge to edge. */
        enableEdgeToEdge()
        logger.i("MainActivity.onCreate", "Composing UI surface")
        setContent {
            /** Startup gate screen active. */
            val startupGateScreenActive = showDatabaseInit || showPassphraseSetup || showPassphraseUnlock

            // External DB deletion warning dialog — shown above any startup gate
            val showExtDeletionWarning by showExternalDeletionWarning
            /** If. */
            if (showExtDeletionWarning) {
                /** Alert dialog. */
                AlertDialog(
                    onDismissRequest = { /* non-dismissable */ },
                    title = { Text(stringResource(R.string.db_external_deletion_title)) },
                    text = { Text(stringResource(R.string.db_external_deletion_message)) },
                    confirmButton = {
                        /** Text button. */
                        TextButton(onClick = {
                            showExternalDeletionWarning.value = false
                            logger.i("MainActivity.onCreate", "User acknowledged external DB deletion warning")
                        }) {
                            /** Text. */
                            Text(stringResource(R.string.loc_continue))
                        }
                    },
                )
            }

            /** If. */
            if (startupGateScreenActive) {
                /** Default prefs state. */
                val defaultPrefsState = AppPreferencesState()
                /** Payanam theme. */
                PayanamTheme(
                    themeMode = defaultPrefsState.themeMode,
                    fontFamily = defaultPrefsState.fontFamily,
                ) {
                    /** Composition local provider. */
                    CompositionLocalProvider(LocalAppPreferences provides defaultPrefsState) {
                        /** Surface. */
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            val externalCommand by pendingExternalCommand.collectAsState()
                            /** Payanam nav host. */
                            PayanamNavHost(
                                shouldShowPassphraseUnlock = showPassphraseUnlock,
                                shouldShowPassphraseSetup = showPassphraseSetup,
                                shouldShowDatabaseInit = showDatabaseInit,
                                shouldShowFocusModeOnboarding = showFocusModeOnboarding,
                                resumeToRouteAfterUnlock = resumeToRouteAfterUnlock,
                                onPassphraseUnlocked = { handlePostUnlockInitState() },
                                onDatabaseReady = {
                                    /** If. */
                                    if (sessionManager.isDbOpen()) {
                                        logger.i("MainActivity.onCreate", "Database init completed (create-new); session open, dismissing init gate")
                                        showDatabaseInit = false
                                    } else {
                                        logger.i("MainActivity.onCreate", "Database init completed (import); restarting process for clean Room/Hilt re-initialization")
                                        /** Restart process. */
                                        restartProcess()
                                    }
                                },
                                onRestartAfterDelete = {
                                    logger.i("MainActivity.onCreate", "Delete all data confirmed; restarting process for clean Room/Hilt re-initialization")
                                    /** Restart process. */
                                    restartProcess()
                                },
                                externalCommand = externalCommand,
                                onExternalCommandConsumed = {
                                    pendingExternalCommand.value = null
                                },
                                onUnlockReturnRouteConsumed = {
                                    resumeToRouteAfterUnlock = null
                                    intent?.removeExtra(EXTRA_RETURN_ROUTE_AFTER_UNLOCK)
                                },
                                appPreferencesViewModel = null,
                            )
                        }
                    }
                }
                logger.i("MainActivity.onCreate", "UI composition complete")
                return@setContent
            }

            /** Prefs view model. */
            val prefsViewModel: AppPreferencesViewModel = hiltViewModel()
            val prefsState by prefsViewModel.uiState.collectAsState()
            var localeGeneration by remember { mutableIntStateOf(0) }
            /** Current configuration. */
            val currentConfiguration = LocalConfiguration.current

            /** Launched effect. */
            LaunchedEffect(currentConfiguration) {
                prefsViewModel.updateSystemLanguageTag(resolveSystemLanguageTag())
            }

            /** Launched effect. */
            LaunchedEffect(prefsState.isLoading, prefsState.appLanguage, prefsState.effectiveLanguageTag) {
                /** If. */
                if (prefsState.isLoading) return@LaunchedEffect
                /** If. */
                if (showPassphraseSetup || showPassphraseUnlock) {
                    return@LaunchedEffect
                }

                /** Locale changed. */
                val localeChanged = applyLanguagePreference(
                    appLanguage = prefsState.appLanguage,
                    effectiveLanguageTag = prefsState.effectiveLanguageTag,
                )
                /** If. */
                if (localeChanged) {
                    localeGeneration++
                    logger.i(
                        "MainActivity.onCreate",
                        "Applied app language preference; forcing Compose recomposition via LocalConfiguration",
                        /** Map of. */
                        mapOf(
                            "appLanguage" to prefsState.appLanguage.key,
                            "effectiveLanguageTag" to prefsState.effectiveLanguageTag,
                            "api" to Build.VERSION.SDK_INT,
                            "localeGeneration" to localeGeneration,
                        ),
                    )
                }
            }

            // Provide updated LocalConfiguration to force stringResource() re-reads.
            // Compose's internal resources() reads LocalConfiguration.current to trigger
            // recomposition — a new Configuration object makes all stringResource() calls
            // re-execute with the already-patched Resources from applyLanguageTag().
            /** Updated config. */
            val updatedConfig = remember(localeGeneration) {
                /** Configuration. */
                Configuration(resources.configuration)
            }
            /** Composition local provider. */
            CompositionLocalProvider(LocalConfiguration provides updatedConfig) {
                /** Payanam theme. */
                PayanamTheme(
                    themeMode = prefsState.themeMode,
                    fontFamily = prefsState.fontFamily,
                ) {
                    /** Composition local provider. */
                    CompositionLocalProvider(LocalAppPreferences provides prefsState) {
                        /** Surface. */
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            val externalCommand by pendingExternalCommand.collectAsState()
                            /** Payanam nav host. */
                            PayanamNavHost(
                                shouldShowPassphraseUnlock = showPassphraseUnlock,
                                shouldShowPassphraseSetup = showPassphraseSetup,
                                shouldShowDatabaseInit = showDatabaseInit,
                                shouldShowFocusModeOnboarding = showFocusModeOnboarding,
                                resumeToRouteAfterUnlock = resumeToRouteAfterUnlock,
                                onPassphraseUnlocked = { handlePostUnlockInitState() },
                                onDatabaseReady = {
                                    /** If. */
                                    if (sessionManager.isDbOpen()) {
                                        logger.i("MainActivity.onCreate", "Database init completed (create-new); session open, dismissing init gate")
                                        showDatabaseInit = false
                                    } else {
                                        logger.i("MainActivity.onCreate", "Database init completed (import); restarting process for clean Room/Hilt re-initialization")
                                        /** Restart process. */
                                        restartProcess()
                                    }
                                },
                                onRestartAfterDelete = {
                                    logger.i("MainActivity.onCreate", "Delete all data confirmed; restarting process for clean Room/Hilt re-initialization")
                                    /** Restart process. */
                                    restartProcess()
                                },
                                externalCommand = externalCommand,
                                onExternalCommandConsumed = {
                                    pendingExternalCommand.value = null
                                },
                                onUnlockReturnRouteConsumed = {
                                    resumeToRouteAfterUnlock = null
                                    intent?.removeExtra(EXTRA_RETURN_ROUTE_AFTER_UNLOCK)
                                },
                                appPreferencesViewModel = prefsViewModel,
                            )
                        }
                    }
                }
            } // CompositionLocalProvider(LocalConfiguration)
        }
    }

    override fun onStart() {
        super.onStart()
        /** Maybe start new log session. */
        maybeStartNewLogSession()
        logger.d("MainActivity.onStart", "Activity started")
        lifecycleScope.launch {
            try {
                TimeTrackingWidgetProvider.requestUpdate(this@MainActivity)
                logger.d("MainActivity.onStart", "Requested widget refresh on app start")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("MainActivity.onStart", "Failed to request widget refresh", e)
            }
        }
        /** If. */
        if (!showDatabaseInit && !showPassphraseSetup && !showPassphraseUnlock) {
            /** Run startup maintenance. */
            runStartupMaintenance()
        }
    }

    /** Rotates the log session if the app was stopped long enough to warrant a fresh file. */
    private fun maybeStartNewLogSession() {
        /** Stopped at elapsed ms. */
        val stoppedAtElapsedMs = lastStoppedAtElapsedMs
        lastStoppedAtElapsedMs = null
        /** If. */
        if (!hasEnteredForegroundOnce) {
            hasEnteredForegroundOnce = true
            /** Return. */
            return
        }

        /** Background duration ms. */
        val backgroundDurationMs = stoppedAtElapsedMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        /** If. */
        if (backgroundDurationMs < LOG_SESSION_ROLLOVER_MIN_BACKGROUND_MS) {
            /** Return. */
            return
        }

        logger.startNewSession("main_activity_foreground")
    }

    /** Runs lightweight startup housekeeping (log rotation, maintenance triggers). */
    private fun runStartupMaintenance() {
        /** If. */
        if (startupMaintenanceJob?.isActive == true) {
            logger.d("MainActivity.onStart", "Startup maintenance already running; skipping duplicate launch")
            /** Return. */
            return
        }

        /** App context. */
        val appContext = applicationContext
        startupMaintenanceJob = startupMaintenanceScope.launch {
            /** If. */
            if (FeatureFlags.minimalModeEnabled) {
                logger.i("MainActivity.onStart", "Minimal mode: skipping recurrence maintenance (habits disabled)")
            } else {
                try {
                    /** Repaired count. */
                    val repairedCount = recurrenceManager.get().repairStuckRecurringTasks()
                    /** If. */
                    if (repairedCount > 0) {
                        logger.i(
                            "MainActivity.onStart",
                            "Repaired stuck recurring tasks",
                            /** Map of. */
                            mapOf(
                                "repairedCount" to repairedCount,
                            ),
                        )
                    }
                    recurrenceManager.get().autoAdvanceRecurringTasks()
                    logger.i("MainActivity.onStart", "Auto-advanced recurring tasks")
                    // One-time score roll-up backfill (rule conversion + L1/L2/L3)
                    scoreRollupBackfillService.get().runIfNeeded()
                } catch (e: CancellationException) {
                    logger.d("MainActivity.onStart", "Startup recurrence maintenance cancelled")
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    logger.e("MainActivity.onStart", "Failed to auto-advance recurring tasks", e)
                }
            }
            /** If. */
            if (FeatureFlags.remindersEnabled) {
                try {
                    notificationScheduler.get().scheduleAllPendingTasks()
                    logger.i("MainActivity.onStart", "Scheduled task reminders on start")
                } catch (e: CancellationException) {
                    logger.d("MainActivity.onStart", "Startup reminder scheduling cancelled")
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    logger.e("MainActivity.onStart", "Failed to schedule task reminders", e)
                }
            } else {
                logger.i("MainActivity.onStart", "Skipped startup reminder scheduling; reminders disabled")
            }
            try {
                AutoBackupWorker.reconcileSchedule(appContext, appSettingsRepository.get())
                logger.i("MainActivity.onStart", "Reconciled auto-backup schedule from settings")
            } catch (e: CancellationException) {
                logger.d("MainActivity.onStart", "Startup auto-backup schedule reconcile cancelled")
                throw e
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("MainActivity.onStart", "Failed to reconcile auto-backup schedule", e)
            }
        }
    }

    /** Resolves and applies the post-unlock init/DB state once the database is open. */
    private fun handlePostUnlockInitState() {
        showPassphraseUnlock = false
        /** Init completed. */
        val initCompleted = runBlocking {
            appSettingsRepository.get()
                .getSetting("database_init_completed")
                ?.toBoolean() ?: false
        }
        /** If. */
        if (!initCompleted) {
            logger.i(
                "MainActivity.onCreate",
                "Passphrase unlocked with incomplete DB-init state; routing to mandatory DatabaseInit",
            )
            showDatabaseInit = true
        } else {
            // Cold boot with passphrase skips startup maintenance while the
            // unlock gate is visible (onStart guard). Run it now that the DB
            // session is open: recurrence auto-advance + one-time score
            // roll-up backfill both depend on it.
            logger.i("MainActivity.handlePostUnlockInitState", "DB unlocked; running startup maintenance")
            /** Run startup maintenance. */
            runStartupMaintenance()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        /** Set intent. */
        setIntent(intent)
        /** Handle external navigation intent. */
        handleExternalNavigationIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        logger.d("MainActivity.onResume", "Activity resumed")
        /** Encryption manager. */
        val encryptionManager = DatabaseEncryptionManager(this)
        // Safety-net: if the process stayed alive but the DB session is gone (e.g., SessionManager
        // timer fired and closed the DB without killing the process, which shouldn't happen but is
        // defensive), restart the process for a clean cold-boot auth flow.
        /** Should require unlock. */
        val shouldRequireUnlock = encryptionManager.hasPassphraseConfigured() &&
            encryptionManager.isEncryptionEnabled() &&
            !sessionManager.isOpen.value
        /** If. */
        if (!showPassphraseSetup && !showPassphraseUnlock && shouldRequireUnlock) {
            logger.i(
                "MainActivity.onResume",
                "DB session not open while app was backgrounded; presenting in-place unlock gate",
            )
            /** Existing route. */
            val existingRoute = intent?.getStringExtra(EXTRA_RETURN_ROUTE_AFTER_UNLOCK)
            /** If. */
            if (!existingRoute.isNullOrBlank()) {
                resumeToRouteAfterUnlock = existingRoute
            }
            showPassphraseUnlock = true
            /** Return. */
            return
        }
    }

    override fun onPause() {
        super.onPause()
        logger.d("MainActivity.onPause", "Activity paused")
    }

    override fun onStop() {
        super.onStop()
        logger.d("MainActivity.onStop", "Activity stopped")
        /** If. */
        if (!isChangingConfigurations) {
            lastStoppedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        // Flush WAL journal so data is durable if process dies while backgrounded
        sessionManager.checkpoint()
        // Flush the log buffer so a background kill loses at most the lines
        // written between here and process death (async; buffer is small).
        logger.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        logger.i("MainActivity.onDestroy", "Activity destroyed")
    }

    // Full process restart: ensures Hilt @Singleton beans (including Room DB) are re-created from
    // scratch with the correct encryption state. Use after operations that replace the DB file on
    // disk (e.g. import), where activity.recreate() leaves a stale Room singleton pointing at the
    // old bootstrap file descriptor.
    /** Forces a process restart (used after unrecoverable DB/init state). */
    private fun restartProcess() {
        logger.i("MainActivity.restartProcess", "Restarting process for clean Room/Hilt re-initialization")
        /** Snapshot. */
        val snapshot = captureDbArtifactSnapshot()
        /** Persist restart marker. */
        persistRestartMarker(snapshot)
        CrashSafeBreadcrumbs.record(
            context = this,
            source = "MainActivity.restartProcess",
            stage = "kill_process_for_restart",
        )
        logger.flush()
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            /** Start activity. */
            startActivity(intent)
        }
        Process.killProcess(Process.myPid())
    }

    /** Attempts a silent (no-UI) unlock and navigates to [returnRoute] on success. */
    fun requestSilentUnlock(returnRoute: String?) {
        /** If. */
        if (returnRoute.isNullOrBlank()) {
            /** Return. */
            return
        }
        /** If. */
        if (resumeToRouteAfterUnlock == returnRoute) {
            /** Return. */
            return
        }
        logger.i(
            "MainActivity.requestSilentUnlock",
            "Captured return route for silent unlock",
            /** Map of. */
            mapOf("returnRoute" to returnRoute),
        )
        resumeToRouteAfterUnlock = returnRoute
        intent?.putExtra(EXTRA_RETURN_ROUTE_AFTER_UNLOCK, returnRoute)
    }

    /** Captures a snapshot of DB artifact state (db/wal/shm size + existence) for diagnostics. */
    private fun captureDbArtifactSnapshot(): DbArtifactSnapshot {
        /** Db file. */
        val dbFile = getDatabasePath(io.payanam.database.PayanamDatabase.DATABASE_NAME)
        /** Db dir. */
        val dbDir = dbFile.parentFile
        /** Wal file. */
        val walFile = File(dbFile.parent, "${io.payanam.database.PayanamDatabase.DATABASE_NAME}-wal")
        /** Shm file. */
        val shmFile = File(dbFile.parent, "${io.payanam.database.PayanamDatabase.DATABASE_NAME}-shm")
        /** Dir listing. */
        val dirListing = dbDir?.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.take(25)
            ?.joinToString(";") { "${it.name}:${it.length()}" }
            ?: "none"
        return DbArtifactSnapshot(
            dbExists = dbFile.exists(),
            dbSize = if (dbFile.exists()) dbFile.length() else 0L,
            walExists = walFile.exists(),
            walSize = if (walFile.exists()) walFile.length() else 0L,
            shmExists = shmFile.exists(),
            shmSize = if (shmFile.exists()) shmFile.length() else 0L,
            dirListing = dirListing,
        )
    }

    /** Persists a restart marker so the next launch can log the pre-restart DB state. */
    private fun persistRestartMarker(snapshot: DbArtifactSnapshot) {
        runCatching {
            /** Get shared preferences. */
            getSharedPreferences(PREFS_RESTART_MARKER, MODE_PRIVATE).edit()
                .putLong(KEY_RESTART_MARKER_TS, System.currentTimeMillis())
                .putBoolean(KEY_RESTART_MARKER_DB, snapshot.dbExists)
                .putLong(KEY_RESTART_MARKER_DB_SIZE, snapshot.dbSize)
                .putBoolean(KEY_RESTART_MARKER_WAL, snapshot.walExists)
                .putLong(KEY_RESTART_MARKER_WAL_SIZE, snapshot.walSize)
                .putBoolean(KEY_RESTART_MARKER_SHM, snapshot.shmExists)
                .putLong(KEY_RESTART_MARKER_SHM_SIZE, snapshot.shmSize)
                .putString(KEY_RESTART_MARKER_DIR_LISTING, snapshot.dirListing)
                .commit()
        }.onFailure { error ->
            logger.w(
                "MainActivity.persistRestartMarker",
                "Failed to persist restart marker",
                /** Map of. */
                mapOf("error" to (error.message ?: "unknown")),
            )
        }
    }

    /** Logs the pending restart marker (pre/post janitor DB snapshots) for startup diagnostics. */
    private fun logPendingRestartMarker(preJanitor: DbArtifactSnapshot, postJanitor: DbArtifactSnapshot) {
        /** Prefs. */
        val prefs = getSharedPreferences(PREFS_RESTART_MARKER, MODE_PRIVATE)
        /** Ts. */
        val ts = prefs.getLong(KEY_RESTART_MARKER_TS, 0L)
        /** If. */
        if (ts <= 0L) return

        logger.i(
            "MainActivity.onCreate",
            "Found prior restart marker for artifact continuity check",
            /** Map of. */
            mapOf(
                "markerTs" to ts,
                "markerDbExists" to prefs.getBoolean(KEY_RESTART_MARKER_DB, false),
                "markerDbSize" to prefs.getLong(KEY_RESTART_MARKER_DB_SIZE, 0L),
                "markerWalExists" to prefs.getBoolean(KEY_RESTART_MARKER_WAL, false),
                "markerWalSize" to prefs.getLong(KEY_RESTART_MARKER_WAL_SIZE, 0L),
                "markerShmExists" to prefs.getBoolean(KEY_RESTART_MARKER_SHM, false),
                "markerShmSize" to prefs.getLong(KEY_RESTART_MARKER_SHM_SIZE, 0L),
                "markerDirListing" to (prefs.getString(KEY_RESTART_MARKER_DIR_LISTING, "none") ?: "none"),
                "preJanitor" to preJanitor.toCompactString(),
                "postJanitor" to postJanitor.toCompactString(),
            ),
        )
        prefs.edit().clear().apply()
    }

    private data class DbArtifactSnapshot(
        /** Db exists. */
        val dbExists: Boolean,
        /** Db size. */
        val dbSize: Long,
        /** Wal exists. */
        val walExists: Boolean,
        /** Wal size. */
        val walSize: Long,
        /** Shm exists. */
        val shmExists: Boolean,
        /** Shm size. */
        val shmSize: Long,
        /** Dir listing. */
        val dirListing: String,
    ) {
        /** Serializes the snapshot to a logging map. */
        fun toLogMap(): Map<String, Any> = mapOf(
            "dbExists" to dbExists,
            "dbSize" to dbSize,
            "walExists" to walExists,
            "walSize" to walSize,
            "shmExists" to shmExists,
            "shmSize" to shmSize,
            "dirListing" to dirListing,
        )

        /** Compact single-line representation for quick log lines. */
        fun toCompactString(): String = "db=$dbExists:$dbSize,wal=$walExists:$walSize,shm=$shmExists:$shmSize"
    }

    /** Handles an external navigation intent (deep link / route after unlock). */
    private fun handleExternalNavigationIntent(intent: Intent?) {
        /** If. */
        if (intent == null) return

        /** Navigate to. */
        val navigateTo = intent.getStringExtra(EXTRA_NAVIGATE_TO)
        /** If. */
        if (navigateTo != NAV_TARGET_TIME) {
            /** Return. */
            return
        }

        /** Source. */
        val source = intent.getStringExtra(EXTRA_NAV_SOURCE) ?: "unknown"
        /** Open quick start. */
        val openQuickStart = intent.getBooleanExtra(EXTRA_OPEN_TIME_QUICK_START, false)
        /** Open stop tracking. */
        val openStopTracking = intent.getBooleanExtra(EXTRA_OPEN_TIME_STOP_TRACKING, false)
        pendingExternalCommand.value = ExternalNavigationCommand.OpenTimeScreen(
            openQuickStart = openQuickStart,
            openStopTracking = openStopTracking,
            source = source,
        )

        logger.i(
            "MainActivity.handleExternalNavigationIntent",
            "Queued external navigation command",
            /** Map of. */
            mapOf(
                "target" to navigateTo,
                "source" to source,
                "openQuickStart" to openQuickStart,
                "openStopTracking" to openStopTracking,
            ),
        )
    }

    /** Applies the chosen [appLanguage] / [effectiveLanguageTag] to the base context.
     *  @return true if the locale was actually changed. */
    private fun applyLanguagePreference(
        /** App language. */
        appLanguage: AppLanguageOption,
        /** Effective language tag. */
        effectiveLanguageTag: String,
    ): Boolean {
        /** If. */
        if (appLanguage == AppLanguageOption.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            /** Locale manager. */
            val localeManager = getSystemService(LocaleManager::class.java)
            /** Has app override. */
            val hasAppOverride = !localeManager.applicationLocales.isEmpty
            /** Current language. */
            val currentLanguage = resolveCurrentAppLanguage()
            /** If. */
            if (!hasAppOverride && currentLanguage == effectiveLanguageTag) {
                return false
            }
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
            logger.i(
                "MainActivity.applyLanguagePreference",
                "Cleared per-app locale override to follow system language",
                /** Map of. */
                mapOf("effectiveLanguageTag" to effectiveLanguageTag),
            )
            return true
        }
        return applyLanguageTag(effectiveLanguageTag)
    }

    /** Sets the app locale to [targetLanguage] via AppCompat context wrapper. @return true if changed. */
    private fun applyLanguageTag(targetLanguage: String): Boolean {
        /** Current language. */
        val currentLanguage = resolveCurrentAppLanguage()
        /** If. */
        if (currentLanguage == targetLanguage) {
            return false
        }

        /** If. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: use per-app locale API (works with android:localeConfig in manifest)
            /** Locale manager. */
            val localeManager = getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = LocaleList.forLanguageTags(targetLanguage)
            logger.i(
                "MainActivity.applyLanguageTag",
                "Applied per-app locale via LocaleManager",
                /** Map of. */
                mapOf(
                    "targetLanguage" to targetLanguage,
                ),
            )
        } else {
            // Android < 13: use deprecated configuration update
            /** Target locale. */
            val targetLocale = Locale.forLanguageTag(targetLanguage)
            Locale.setDefault(targetLocale)
            /** Updated config. */
            val updatedConfig = android.content.res.Configuration(resources.configuration).apply {
                /** Set locale. */
                setLocale(targetLocale)
                /** Set layout direction. */
                setLayoutDirection(targetLocale)
            }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(updatedConfig, resources.displayMetrics)
            @Suppress("DEPRECATION")
            applicationContext.resources.updateConfiguration(
                android.content.res.Configuration(applicationContext.resources.configuration).apply {
                    /** Set locale. */
                    setLocale(targetLocale)
                    /** Set layout direction. */
                    setLayoutDirection(targetLocale)
                },
                applicationContext.resources.displayMetrics,
            )
            logger.i(
                "MainActivity.applyLanguageTag",
                "Applied locale via updateConfiguration (pre-API-33)",
                /** Map of. */
                mapOf(
                    "targetLanguage" to targetLanguage,
                ),
            )
        }
        return true
    }

    /** Returns the app's currently effective language tag. */
    private fun resolveCurrentAppLanguage(): String {
        /** If. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            /** Locale manager. */
            val localeManager = getSystemService(LocaleManager::class.java)
            /** App locales. */
            val appLocales = localeManager.applicationLocales
            /** If. */
            if (!appLocales.isEmpty) {
                return appLocales[0].language.lowercase(Locale.ROOT)
            }
        }
        return resources.configuration.locales[0]
            ?.language
            ?.lowercase(Locale.ROOT)
            ?: Locale.getDefault().language.lowercase(Locale.ROOT)
    }

    /** Returns the system (device) language tag. */
    private fun resolveSystemLanguageTag(): String {
        /** System language. */
        val systemLanguage = Resources.getSystem()
            .configuration
            .locales[0]
            .language
            .lowercase(Locale.ROOT)
        return if (systemLanguage == "ta") {
            "ta"
        } else {
            "en"
        }
    }

    companion object {
        @Volatile
        private var startupMaintenanceJob: Job? = null
        private val startupMaintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val LOG_SESSION_ROLLOVER_MIN_BACKGROUND_MS = 5_000L

        /** Extra navigate to. */
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        /** Extra open time quick start. */
        const val EXTRA_OPEN_TIME_QUICK_START = "open_time_quick_start"
        /** Extra open time stop tracking. */
        const val EXTRA_OPEN_TIME_STOP_TRACKING = "open_time_stop_tracking"
        /** Extra nav source. */
        const val EXTRA_NAV_SOURCE = "nav_source"
        /** Extra return route after unlock. */
        const val EXTRA_RETURN_ROUTE_AFTER_UNLOCK = "return_route_after_unlock"
        /** Nav target time. */
        const val NAV_TARGET_TIME = "time"
        private const val PREFS_RESTART_MARKER = "payanam_restart_marker"
        private const val KEY_RESTART_MARKER_TS = "marker_ts"
        private const val KEY_RESTART_MARKER_DB = "marker_db_exists"
        private const val KEY_RESTART_MARKER_DB_SIZE = "marker_db_size"
        private const val KEY_RESTART_MARKER_WAL = "marker_wal_exists"
        private const val KEY_RESTART_MARKER_WAL_SIZE = "marker_wal_size"
        private const val KEY_RESTART_MARKER_SHM = "marker_shm_exists"
        private const val KEY_RESTART_MARKER_SHM_SIZE = "marker_shm_size"
        private const val KEY_RESTART_MARKER_DIR_LISTING = "marker_dir_listing"
    }
}

/**
 * Pure function: resolves whether DatabaseInit screen should be shown at startup.
 *
 * Key invariant: missing artifacts ALWAYS shows DatabaseInit, even if a passphrase is
 * configured. Passphrase unlock only suppresses DatabaseInit when the DB file actually
 * exists (i.e. we have something to unlock). Without this order, a missing DB + configured
 * passphrase → unlock shown → SQLCipher silently creates a blank encrypted DB.
 */
internal fun resolveShouldShowDatabaseInit(
    /** Has database artifacts. */
    hasDatabaseArtifacts: Boolean,
    /** Should show passphrase unlock. */
    shouldShowPassphraseUnlock: Boolean,
    /** Is healthy. */
    isHealthy: Boolean,
    /** Database init completed. */
    databaseInitCompleted: Boolean,
): Boolean = when {
    !hasDatabaseArtifacts -> true
    shouldShowPassphraseUnlock -> false
    !isHealthy -> true
    else -> !databaseInitCompleted
}

internal data class StartupHealthLogSummary(
    /** Status. */
    val status: String,
    /** Is healthy. */
    val isHealthy: Boolean?,
    /** Needs repair. */
    val needsRepair: Boolean?,
    /** Error message. */
    val errorMessage: String?,
)

/** Pure resolver: builds a startup health log summary from DB artifacts + passphrase + health check. */
internal fun resolveStartupHealthLogSummary(
    /** Has database artifacts. */
    hasDatabaseArtifacts: Boolean,
    /** Should show passphrase unlock. */
    shouldShowPassphraseUnlock: Boolean,
    healthResult: DatabaseHealthChecker.HealthCheckResult?,
): StartupHealthLogSummary = when {
    !hasDatabaseArtifacts -> StartupHealthLogSummary(
        status = "no_database_artifacts",
        isHealthy = null,
        needsRepair = null,
        errorMessage = null,
    )

    shouldShowPassphraseUnlock -> StartupHealthLogSummary(
        status = "deferred_until_unlock",
        isHealthy = null,
        needsRepair = null,
        errorMessage = "Deferred until unlock",
    )

    healthResult == null -> StartupHealthLogSummary(
        status = "not_checked",
        isHealthy = null,
        needsRepair = null,
        errorMessage = "Health check not run",
    )

    healthResult.isHealthy -> StartupHealthLogSummary(
        status = "checked_healthy",
        isHealthy = true,
        needsRepair = healthResult.needsRepair,
        errorMessage = healthResult.errorMessage,
    )

    else -> StartupHealthLogSummary(
        status = "checked_unhealthy",
        isHealthy = false,
        needsRepair = healthResult.needsRepair,
        errorMessage = healthResult.errorMessage,
    )
}
