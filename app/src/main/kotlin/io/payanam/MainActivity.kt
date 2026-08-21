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
     * A command from outside the nav graph (widget/notification) asking to open
     * the Time screen, optionally into quick-start or stop-tracking mode.
     */
    data class OpenTimeScreen(
        val openQuickStart: Boolean,
        val openStopTracking: Boolean,
        val source: String,
        val requestId: Long = System.currentTimeMillis(),
    ) : ExternalNavigationCommand
}

/**
 * Single-activity entry point: owns the startup gate sequence (database init,
 * passphrase setup/unlock, focus-mode onboarding), language/theme application,
 * external navigation intents, DB-session lifecycle (auto-lock touch, WAL
 * checkpointing), and process-restart handling after DB-replacing operations.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val logger = UnifiedLogger.getInstance()
    private val pendingExternalCommand = MutableStateFlow<ExternalNavigationCommand?>(null)
    private var hasEnteredForegroundOnce = false
    private var lastStoppedAtElapsedMs: Long? = null

    @Inject
    lateinit var notificationScheduler: Lazy<NotificationScheduler>

    @Inject
    lateinit var recurrenceManager: Lazy<RecurrenceManager>

    @Inject
    lateinit var scoreRollupBackfillService: Lazy<ScoreRollupBackfillService>

    @Inject
    lateinit var appSettingsRepository: Lazy<AppSettingsRepository>

    @Inject
    lateinit var sessionManager: DatabaseSessionManager

    private var showDatabaseInit by mutableStateOf(false)
    private var showFocusModeOnboarding by mutableStateOf(false)
    private var showPassphraseSetup by mutableStateOf(false)
    private var showPassphraseUnlock by mutableStateOf(false)
    private var showExternalDeletionWarning = mutableStateOf(false)
    private var resumeToRouteAfterUnlock by mutableStateOf<String?>(null)

    /**
     * Keeps the DB session alive: every user interaction resets the auto-lock
     * idle timer.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionManager.touch()
    }

    /**
     * Startup orchestrator: resolves DB artifact/encryption/health state,
     * self-heals an invalid boot state, decides which gate to show (database
     * init / passphrase setup / unlock / focus-mode onboarding), then composes
     * the app UI with language + theme preferences applied.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.i("MainActivity.onCreate", "Activity creating")
        handleExternalNavigationIntent(intent)
        val preJanitorSnapshot = captureDbArtifactSnapshot()
        logger.i(
            "MainActivity.onCreate",
            "DB artifact snapshot before startup janitor",
            preJanitorSnapshot.toLogMap(),
        )
        DatabaseArtifactJanitor.cleanupStaleArtifacts(this, "MainActivity.onCreate")
        val postJanitorSnapshot = captureDbArtifactSnapshot()
        logger.i(
            "MainActivity.onCreate",
            "DB artifact snapshot after startup janitor",
            postJanitorSnapshot.toLogMap(),
        )
        logPendingRestartMarker(preJanitorSnapshot, postJanitorSnapshot)
        val hasDatabaseArtifacts = DatabaseHealthChecker.hasDatabaseArtifacts(this)
        val dbFile = getDatabasePath(io.payanam.database.PayanamDatabase.DATABASE_NAME)
        logger.i(
            "MainActivity.onCreate",
            "DB artifact presence at startup",
            mapOf(
                "db" to dbFile.exists(),
                "wal" to java.io.File(dbFile.parent, "${io.payanam.database.PayanamDatabase.DATABASE_NAME}-wal").exists(),
                "shm" to java.io.File(dbFile.parent, "${io.payanam.database.PayanamDatabase.DATABASE_NAME}-shm").exists(),
                "sizeKB" to if (dbFile.exists()) dbFile.length() / 1024 else 0L,
            ),
        )
        val encryptionManager = DatabaseEncryptionManager(this)
        var hasPassphraseConfigured = encryptionManager.hasPassphraseConfigured()
        logger.i("MainActivity.onCreate", "Encryption state resolved", mapOf("hasPassphraseConfigured" to hasPassphraseConfigured))

        // Detect and self-heal an invalid boot state: passphrase/Keystore state exists but no DB
        // file is present. This can happen if a previous session's backup worker corruption
        // handler deleted the DB, or if the DB was otherwise lost.
        // Show a warning to the user so they know data was lost (may be recoverable from backup).
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
        val shouldShowPassphraseUnlock =
            hasPassphraseConfigured && encryptionManager.isEncryptionEnabled() && !sessionManager.isOpen.value
        logger.i("MainActivity.onCreate", "Startup gate resolved", mapOf("showPassphraseUnlock" to shouldShowPassphraseUnlock, "sessionOpen" to sessionManager.isOpen.value))

        // Health check is only meaningful when the DB is open (session already active).
        // For encrypted DBs at cold boot, shouldShowPassphraseUnlock=true so this block is skipped.
        val passphraseForHealthCheck = if (!shouldShowPassphraseUnlock && encryptionManager.isEncryptionEnabled()) {
            runCatching { sessionManager.requireOpenPassphrase() }.getOrNull()
        } else {
            null
        }
        val healthResult = if (hasDatabaseArtifacts && !shouldShowPassphraseUnlock) {
            DatabaseHealthChecker.checkDatabaseHealth(this, passphraseForHealthCheck)
        } else {
            null
        }

        // AppSettingsRepository reads from Room which requires an open DB session.
        // For unencrypted DBs (e.g. freshly created before the user sets a passphrase),
        // the session is null at cold boot, so we open a bootstrap session here.
        // Encrypted DBs are covered by the shouldShowPassphraseUnlock path which opens
        // the session after the user enters their passphrase.
        if (hasDatabaseArtifacts && healthResult?.isHealthy == true && !shouldShowPassphraseUnlock &&
            !encryptionManager.isEncryptionEnabled() && !sessionManager.isDbOpen()
        ) {
            runBlocking { sessionManager.openDatabase("") }
        }
        val databaseInitCompleted = if (hasDatabaseArtifacts && healthResult?.isHealthy == true && !shouldShowPassphraseUnlock) {
            runBlocking { appSettingsRepository.get().getSetting("database_init_completed")?.toBoolean() ?: false }
        } else {
            false
        }
        val shouldShowDatabaseInit = resolveShouldShowDatabaseInit(
            hasDatabaseArtifacts = hasDatabaseArtifacts,
            shouldShowPassphraseUnlock = shouldShowPassphraseUnlock,
            isHealthy = healthResult?.isHealthy == true,
            databaseInitCompleted = databaseInitCompleted,
        )
        val shouldShowPassphraseSetup = !hasPassphraseConfigured && !shouldShowDatabaseInit && !shouldShowPassphraseUnlock

        showPassphraseSetup = shouldShowPassphraseSetup
        showPassphraseUnlock = shouldShowPassphraseUnlock
        showDatabaseInit = shouldShowDatabaseInit

        // Focus mode onboarding is disabled in minimal mode and when focus settings are feature-gated off.
        val focusModeOnboardingEligible = !FeatureFlags.minimalModeEnabled && FeatureFlags.focusModeSettingsEnabled
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
            false
        }
        showFocusModeOnboarding = shouldShowFocusModeOnboarding
        val startupHealthLogSummary = resolveStartupHealthLogSummary(
            hasDatabaseArtifacts = hasDatabaseArtifacts,
            shouldShowPassphraseUnlock = shouldShowPassphraseUnlock,
            healthResult = healthResult,
        )
        logger.i(
            "MainActivity.onCreate",
            "Database health check",
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
        if (healthResult?.needsRepair == true) {
            logger.w(
                "MainActivity.onCreate",
                "Database needs repair - showing init screen",
                mapOf(
                    "reason" to healthResult.errorMessage,
                ),
            )
        }
        enableEdgeToEdge()
        logger.i("MainActivity.onCreate", "Composing UI surface")
        setContent {
            val startupGateScreenActive = showDatabaseInit || showPassphraseSetup || showPassphraseUnlock

            // External DB deletion warning dialog — shown above any startup gate
            val showExtDeletionWarning by showExternalDeletionWarning
            if (showExtDeletionWarning) {
                AlertDialog(
                    onDismissRequest = { /* non-dismissable */ },
                    title = { Text(stringResource(R.string.db_external_deletion_title)) },
                    text = { Text(stringResource(R.string.db_external_deletion_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showExternalDeletionWarning.value = false
                            logger.i("MainActivity.onCreate", "User acknowledged external DB deletion warning")
                        }) {
                            Text(stringResource(R.string.loc_continue))
                        }
                    },
                )
            }
            if (startupGateScreenActive) {
                val defaultPrefsState = AppPreferencesState()
                PayanamTheme(
                    themeMode = defaultPrefsState.themeMode,
                    fontFamily = defaultPrefsState.fontFamily,
                ) {
                    CompositionLocalProvider(LocalAppPreferences provides defaultPrefsState) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            val externalCommand by pendingExternalCommand.collectAsState()
                            PayanamNavHost(
                                shouldShowPassphraseUnlock = showPassphraseUnlock,
                                shouldShowPassphraseSetup = showPassphraseSetup,
                                shouldShowDatabaseInit = showDatabaseInit,
                                shouldShowFocusModeOnboarding = showFocusModeOnboarding,
                                resumeToRouteAfterUnlock = resumeToRouteAfterUnlock,
                                onPassphraseUnlocked = { handlePostUnlockInitState() },
                                onDatabaseReady = {
                                    if (sessionManager.isDbOpen()) {
                                        logger.i("MainActivity.onCreate", "Database init completed (create-new); session open, dismissing init gate")
                                        showDatabaseInit = false
                                    } else {
                                        logger.i("MainActivity.onCreate", "Database init completed (import); restarting process for clean Room/Hilt re-initialization")
                                        restartProcess()
                                    }
                                },
                                onRestartAfterDelete = {
                                    logger.i("MainActivity.onCreate", "Delete all data confirmed; restarting process for clean Room/Hilt re-initialization")
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
            val prefsViewModel: AppPreferencesViewModel = hiltViewModel()
            val prefsState by prefsViewModel.uiState.collectAsState()
            var localeGeneration by remember { mutableIntStateOf(0) }
            val currentConfiguration = LocalConfiguration.current
            LaunchedEffect(currentConfiguration) {
                prefsViewModel.updateSystemLanguageTag(resolveSystemLanguageTag())
            }
            LaunchedEffect(prefsState.isLoading, prefsState.appLanguage, prefsState.effectiveLanguageTag) {
                if (prefsState.isLoading) return@LaunchedEffect
                if (showPassphraseSetup || showPassphraseUnlock) {
                    return@LaunchedEffect
                }
                val localeChanged = applyLanguagePreference(
                    appLanguage = prefsState.appLanguage,
                    effectiveLanguageTag = prefsState.effectiveLanguageTag,
                )
                if (localeChanged) {
                    localeGeneration++
                    logger.i(
                        "MainActivity.onCreate",
                        "Applied app language preference; forcing Compose recomposition via LocalConfiguration",
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
            val updatedConfig = remember(localeGeneration) {
                Configuration(resources.configuration)
            }
            CompositionLocalProvider(LocalConfiguration provides updatedConfig) {
                PayanamTheme(
                    themeMode = prefsState.themeMode,
                    fontFamily = prefsState.fontFamily,
                ) {
                    CompositionLocalProvider(LocalAppPreferences provides prefsState) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            val externalCommand by pendingExternalCommand.collectAsState()
                            PayanamNavHost(
                                shouldShowPassphraseUnlock = showPassphraseUnlock,
                                shouldShowPassphraseSetup = showPassphraseSetup,
                                shouldShowDatabaseInit = showDatabaseInit,
                                shouldShowFocusModeOnboarding = showFocusModeOnboarding,
                                resumeToRouteAfterUnlock = resumeToRouteAfterUnlock,
                                onPassphraseUnlocked = { handlePostUnlockInitState() },
                                onDatabaseReady = {
                                    if (sessionManager.isDbOpen()) {
                                        logger.i("MainActivity.onCreate", "Database init completed (create-new); session open, dismissing init gate")
                                        showDatabaseInit = false
                                    } else {
                                        logger.i("MainActivity.onCreate", "Database init completed (import); restarting process for clean Room/Hilt re-initialization")
                                        restartProcess()
                                    }
                                },
                                onRestartAfterDelete = {
                                    logger.i("MainActivity.onCreate", "Delete all data confirmed; restarting process for clean Room/Hilt re-initialization")
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

    /**
     * Foreground entry: rotates the log session if needed, refreshes the home-
     * screen widget, and kicks off startup maintenance unless a startup gate
     * is still pending.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun onStart() {
        super.onStart()
        maybeStartNewLogSession()
        logger.d("MainActivity.onStart", "Activity started")
        lifecycleScope.launch {
            try {
                TimeTrackingWidgetProvider.requestUpdate(this@MainActivity)
                logger.d("MainActivity.onStart", "Requested widget refresh on app start")
            } catch (e: Exception) {
                logger.e("MainActivity.onStart", "Failed to request widget refresh", e)
            }
        }
        if (!showDatabaseInit && !showPassphraseSetup && !showPassphraseUnlock) {
            runStartupMaintenance()
        }
    }

    /** Rotates the log session if the app was stopped long enough to warrant a fresh file. */
    private fun maybeStartNewLogSession() {
        val stoppedAtElapsedMs = lastStoppedAtElapsedMs
        lastStoppedAtElapsedMs = null
        if (!hasEnteredForegroundOnce) {
            hasEnteredForegroundOnce = true
            return
        }
        val backgroundDurationMs = stoppedAtElapsedMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        if (backgroundDurationMs < LOG_SESSION_ROLLOVER_MIN_BACKGROUND_MS) {
            return
        }

        logger.startNewSession("main_activity_foreground")
    }

    /** Runs lightweight startup housekeeping (log rotation, maintenance triggers). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun runStartupMaintenance() {
        if (startupMaintenanceJob?.isActive == true) {
            logger.d("MainActivity.onStart", "Startup maintenance already running; skipping duplicate launch")
            return
        }
        val appContext = applicationContext
        startupMaintenanceJob = startupMaintenanceScope.launch {
            if (FeatureFlags.minimalModeEnabled) {
                logger.i("MainActivity.onStart", "Minimal mode: skipping recurrence maintenance (habits disabled)")
            } else {
                try {
                    val repairedCount = recurrenceManager.get().repairStuckRecurringTasks()
                    if (repairedCount > 0) {
                        logger.i(
                            "MainActivity.onStart",
                            "Repaired stuck recurring tasks",
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
                } catch (e: Exception) {
                    logger.e("MainActivity.onStart", "Failed to auto-advance recurring tasks", e)
                }
            }
            if (FeatureFlags.remindersEnabled) {
                try {
                    notificationScheduler.get().scheduleAllPendingTasks()
                    logger.i("MainActivity.onStart", "Scheduled task reminders on start")
                } catch (e: CancellationException) {
                    logger.d("MainActivity.onStart", "Startup reminder scheduling cancelled")
                    throw e
                } catch (e: Exception) {
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
            } catch (e: Exception) {
                logger.e("MainActivity.onStart", "Failed to reconcile auto-backup schedule", e)
            }
        }
    }

    /** Resolves and applies the post-unlock init/DB state once the database is open. */
    private fun handlePostUnlockInitState() {
        showPassphraseUnlock = false
        val initCompleted = runBlocking {
            appSettingsRepository.get()
                .getSetting("database_init_completed")
                ?.toBoolean() ?: false
        }
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
            runStartupMaintenance()
        }
    }

    /**
     * Re-handled while the activity is alive (singleTop): captures external
     * navigation commands from notifications/widgets/deep links.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalNavigationIntent(intent)
    }
    /**
     * Safety-net relock: if encryption is on but the DB session died while
     * backgrounded, presents the in-place unlock gate again.
     */
    override fun onResume() {
        super.onResume()
        logger.d("MainActivity.onResume", "Activity resumed")
        val encryptionManager = DatabaseEncryptionManager(this)
        // Safety-net: if the process stayed alive but the DB session is gone (e.g., SessionManager
        // timer fired and closed the DB without killing the process, which shouldn't happen but is
        // defensive), restart the process for a clean cold-boot auth flow.
        val shouldRequireUnlock = encryptionManager.hasPassphraseConfigured() &&
            encryptionManager.isEncryptionEnabled() &&
            !sessionManager.isOpen.value
        if (!showPassphraseSetup && !showPassphraseUnlock && shouldRequireUnlock) {
            logger.i(
                "MainActivity.onResume",
                "DB session not open while app was backgrounded; presenting in-place unlock gate",
            )
            val existingRoute = intent?.getStringExtra(EXTRA_RETURN_ROUTE_AFTER_UNLOCK)
            if (!existingRoute.isNullOrBlank()) {
                resumeToRouteAfterUnlock = existingRoute
            }
            showPassphraseUnlock = true
            return
        }
    }

    /**
     * Marks the background timestamp used by log-session rotation.
     */
    override fun onPause() {
        super.onPause()
        logger.d("MainActivity.onPause", "Activity paused")
    }

    /**
     * Durability flush before backgrounding: WAL-checkpoints the encrypted DB
     * and flushes the log buffer so a process kill loses nothing.
     */
    override fun onStop() {
        super.onStop()
        logger.d("MainActivity.onStop", "Activity stopped")
        if (!isChangingConfigurations) {
            lastStoppedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        // Flush WAL journal so data is durable if process dies while backgrounded
        sessionManager.checkpoint()
        // Flush the log buffer so a background kill loses at most the lines
        // written between here and process death (async; buffer is small).
        logger.flush()
    }

    /**
     * Final teardown logging for the activity.
     */
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
        val snapshot = captureDbArtifactSnapshot()
        persistRestartMarker(snapshot)
        CrashSafeBreadcrumbs.record(
            context = this,
            source = "MainActivity.restartProcess",
            stage = "kill_process_for_restart",
        )
        logger.flush()
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }
        Process.killProcess(Process.myPid())
    }

    /** Attempts a silent (no-UI) unlock and navigates to [returnRoute] on success. */
    fun requestSilentUnlock(returnRoute: String?) {
        if (returnRoute.isNullOrBlank()) {
            return
        }
        if (resumeToRouteAfterUnlock == returnRoute) {
            return
        }
        logger.i(
            "MainActivity.requestSilentUnlock",
            "Captured return route for silent unlock",
            mapOf("returnRoute" to returnRoute),
        )
        resumeToRouteAfterUnlock = returnRoute
        intent?.putExtra(EXTRA_RETURN_ROUTE_AFTER_UNLOCK, returnRoute)
    }

    /** Captures a snapshot of DB artifact state (db/wal/shm size + existence) for diagnostics. */
    private fun captureDbArtifactSnapshot(): DbArtifactSnapshot {
        val dbFile = getDatabasePath(io.payanam.database.PayanamDatabase.DATABASE_NAME)
        val dbDir = dbFile.parentFile
        val walFile = File(dbFile.parent, "${io.payanam.database.PayanamDatabase.DATABASE_NAME}-wal")
        val shmFile = File(dbFile.parent, "${io.payanam.database.PayanamDatabase.DATABASE_NAME}-shm")
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
                mapOf("error" to (error.message ?: "unknown")),
            )
        }
    }

    /** Logs the pending restart marker (pre/post janitor DB snapshots) for startup diagnostics. */
    private fun logPendingRestartMarker(preJanitor: DbArtifactSnapshot, postJanitor: DbArtifactSnapshot) {
        val prefs = getSharedPreferences(PREFS_RESTART_MARKER, MODE_PRIVATE)
        val ts = prefs.getLong(KEY_RESTART_MARKER_TS, 0L)
        if (ts <= 0L) return

        logger.i(
            "MainActivity.onCreate",
            "Found prior restart marker for artifact continuity check",
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
        val dbExists: Boolean,
        val dbSize: Long,
        val walExists: Boolean,
        val walSize: Long,
        val shmExists: Boolean,
        val shmSize: Long,
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
        if (intent == null) return
        val navigateTo = intent.getStringExtra(EXTRA_NAVIGATE_TO)
        if (navigateTo != NAV_TARGET_TIME) {
            return
        }
        val source = intent.getStringExtra(EXTRA_NAV_SOURCE) ?: "unknown"
        val openQuickStart = intent.getBooleanExtra(EXTRA_OPEN_TIME_QUICK_START, false)
        val openStopTracking = intent.getBooleanExtra(EXTRA_OPEN_TIME_STOP_TRACKING, false)
        pendingExternalCommand.value = ExternalNavigationCommand.OpenTimeScreen(
            openQuickStart = openQuickStart,
            openStopTracking = openStopTracking,
            source = source,
        )

        logger.i(
            "MainActivity.handleExternalNavigationIntent",
            "Queued external navigation command",
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
        appLanguage: AppLanguageOption,
        effectiveLanguageTag: String,
    ): Boolean {
        if (appLanguage == AppLanguageOption.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            val hasAppOverride = !localeManager.applicationLocales.isEmpty
            val currentLanguage = resolveCurrentAppLanguage()
            if (!hasAppOverride && currentLanguage == effectiveLanguageTag) {
                return false
            }
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
            logger.i(
                "MainActivity.applyLanguagePreference",
                "Cleared per-app locale override to follow system language",
                mapOf("effectiveLanguageTag" to effectiveLanguageTag),
            )
            return true
        }
        return applyLanguageTag(effectiveLanguageTag)
    }

    /** Sets the app locale to [targetLanguage] via AppCompat context wrapper. @return true if changed. */
    private fun applyLanguageTag(targetLanguage: String): Boolean {
        val currentLanguage = resolveCurrentAppLanguage()
        if (currentLanguage == targetLanguage) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: use per-app locale API (works with android:localeConfig in manifest)
            val localeManager = getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = LocaleList.forLanguageTags(targetLanguage)
            logger.i(
                "MainActivity.applyLanguageTag",
                "Applied per-app locale via LocaleManager",
                mapOf(
                    "targetLanguage" to targetLanguage,
                ),
            )
        } else {
            // Android < 13: use deprecated configuration update
            val targetLocale = Locale.forLanguageTag(targetLanguage)
            Locale.setDefault(targetLocale)
            val updatedConfig = android.content.res.Configuration(resources.configuration).apply {
                setLocale(targetLocale)
                setLayoutDirection(targetLocale)
            }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(updatedConfig, resources.displayMetrics)
            @Suppress("DEPRECATION")
            applicationContext.resources.updateConfiguration(
                android.content.res.Configuration(applicationContext.resources.configuration).apply {
                    setLocale(targetLocale)
                    setLayoutDirection(targetLocale)
                },
                applicationContext.resources.displayMetrics,
            )
            logger.i(
                "MainActivity.applyLanguageTag",
                "Applied locale via updateConfiguration (pre-API-33)",
                mapOf(
                    "targetLanguage" to targetLanguage,
                ),
            )
        }
        return true
    }

    /** Returns the app's currently effective language tag. */
    private fun resolveCurrentAppLanguage(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            val appLocales = localeManager.applicationLocales
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
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val EXTRA_OPEN_TIME_QUICK_START = "open_time_quick_start"
        const val EXTRA_OPEN_TIME_STOP_TRACKING = "open_time_stop_tracking"
        const val EXTRA_NAV_SOURCE = "nav_source"
        const val EXTRA_RETURN_ROUTE_AFTER_UNLOCK = "return_route_after_unlock"
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
    hasDatabaseArtifacts: Boolean,
    shouldShowPassphraseUnlock: Boolean,
    isHealthy: Boolean,
    databaseInitCompleted: Boolean,
): Boolean = when {
    !hasDatabaseArtifacts -> true
    shouldShowPassphraseUnlock -> false
    !isHealthy -> true
    else -> !databaseInitCompleted
}

internal data class StartupHealthLogSummary(
    val status: String,
    val isHealthy: Boolean?,
    val needsRepair: Boolean?,
    val errorMessage: String?,
)

/** Pure resolver: builds a startup health log summary from DB artifacts + passphrase + health check. */
internal fun resolveStartupHealthLogSummary(
    hasDatabaseArtifacts: Boolean,
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
