//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.desktop.DesktopBuildInfo
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.platform.BackupBackgroundCapability
import io.payanam.shared.platform.BackupScheduler
import io.payanam.shared.platform.DebugFlags
import io.payanam.shared.platform.PlatformCapabilities
import io.payanam.shared.platform.SupportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import io.payanam.desktop.UnifiedLogger as DesktopSessionLogger

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * The desktop's periodic backup, running only while the app is open.
 *
 * [BackupBackgroundCapability.FOREGROUND_ONLY] is reported rather than approximated, because "your last
 * backup was three days ago" means something different on a platform that backs up only when it is running.
 * `schedule` REPLACES: the settings screen and the startup path both reconcile, and stacking a second loop
 * per reconciliation would run N backups per interval.
 */
internal class DesktopBackupScheduler(
    private val scope: CoroutineScope,
    private val logger: UnifiedLogger,
    private val runBackup: suspend () -> Unit,
) : BackupScheduler {
    private var job: Job? = null

    val capability: BackupBackgroundCapability = BackupBackgroundCapability.FOREGROUND_ONLY

    /** The interval in force, or null when no backup is scheduled. */
    var intervalMinutes: Int? = null
        private set

    override suspend fun schedule(intervalMinutes: Int) {
        require(intervalMinutes > 0) { "Backup interval must be positive; use cancel() to disable" }
        job?.cancel()
        this.intervalMinutes = intervalMinutes
        job =
            scope.launch {
                while (isActive) {
                    delay(intervalMinutes.toLong() * MILLIS_PER_MINUTE)
                    runCatching { runBackup() }
                        .onFailure { error ->
                            // The class of failure is logged, never the message: it can carry a path.
                            logger.w(
                                "DesktopBackupScheduler",
                                "Scheduled backup failed",
                                mapOf("failure" to error.javaClass.simpleName),
                            )
                        }
                }
            }
    }

    override suspend fun cancel() {
        job?.cancel()
        job = null
        intervalMinutes = null
    }

    /**
     * Runs ONE backup now, on this platform's OWN gate - the same `runBackup` the scheduled loop calls.
     *
     * Added for the settings screen's "back up now" row, which needs a real answer on this platform rather
     * than a request to a scheduler: the desktop's backup is an in-process run over the export gateway, so
     * "now" is one invocation of it and nothing else. It goes through the same lock and the same failure
     * handling as the scheduled call, so a manual run cannot report a success the loop would have refused.
     *
     * Not part of the shared [BackupScheduler] port because scheduling an immediate run is not a portable
     * concept: Android's manual backup goes through a different mechanism entirely, and a port member only
     * one platform can honour would be a method that exists to be ignored.
     */
    suspend fun runNow() {
        runCatching { runBackup() }
            .onFailure { error ->
                // Cancellation is a stopped coroutine, not a failed backup (Android parity):
                // rethrow so structured concurrency survives the catch.
                if (error is kotlinx.coroutines.CancellationException) throw error
                // The class of failure is logged, never the message: it can carry a path.
                logger.w(
                    "DesktopBackupScheduler",
                    "Manual backup failed",
                    mapOf("failure" to error.javaClass.simpleName),
                )
            }
    }
}

/**
 * The desktop's debug-logging switch, defaulting to the build type's value: a debug build starts with
 * debug logging on, the way Android's `BuildConfig.DEBUG` default behaves, and an unconfigured direct
 * build is off.
 */
internal class DesktopDebugFlags(
    initial: Boolean = DesktopBuildInfo.IS_DEBUG_BUILD,
) : DebugFlags {
    override var debugLoggingEnabled: Boolean = initial
        private set

    override fun setDebugLoggingEnabled(enabled: Boolean): Boolean {
        debugLoggingEnabled = enabled
        // The logger's own gate is what the `d()` call sites consult, so it moves with this setting rather
        // than the setting existing on its own - the same shape Android's flags use. Fully qualified: this
        // file imports the SHARED logger interface under the same name.
        DesktopSessionLogger
            .setDebugLoggingEnabled(enabled)
        return debugLoggingEnabled
    }
}

/**
 * Both desktop platform seams, CONSTRUCTED, for as long as the app runs.
 *
 * This is the class the running app builds. Before it existed, [DesktopBackupScheduler] was implemented
 * and unit-tested and was reachable from nowhere else: the settings screen's auto-backup toggle wrote
 * `auto_backup_enabled` and `auto_backup_interval`, and the row stopped there, so a Linux user could
 * turn auto-backup on, pick 15 minutes, and receive no backups at all. A contract with no caller is not
 * a feature, and the fix is construction, not more contract.
 *
 * Two things are wired here, and each is a fact the app already had:
 *
 * - **the schedule**, reconciled from the SHARED rows on startup and on every later change, by
 *   [reconcileBackupSchedule]. This is Android's `AutoBackupWorker.reconcileSchedule`, same rule and same
 *   rows, applied to an in-process timer instead of a job scheduler;
 * - **the capabilities**, published from the seams that are actually installed rather than from a
 *   constant.
 *
 * The dependencies are injected rather than reached for, for the reason the seams themselves are: whether a
 * coroutine scope, a running session and a backup action exist is the app's business, and this class is
 * the one place that needs to know all three. Nothing here decides WHAT a backup writes or WHERE it goes
 * (that is [io.payanam.desktop.database.DesktopDatabaseTransferController]'s job) or how a reminder is
 * shown (there is no answer yet).
 */
internal class DesktopPlatformSeams(
    scope: CoroutineScope,
    private val logger: UnifiedLogger,
    /**
     * Runs one backup. Injected because the desktop already knows how to fold and copy its encrypted
     * database; this class schedules that work and owns nothing else about it.
     */
    private val runBackup: suspend () -> Unit,
    /**
     * The shared `app_settings` rows, read once per reconcile.
     *
     * A row map rather than two booleans, so the KEY NAMES are read from [AppPreferenceKeys] — the same
     * constants the settings screen writes and Android's worker reads — and this class cannot drift from
     * either by naming a literal.
     */
    private val backupRows: suspend () -> Map<String, String?>,
) {
    /** The installed backup scheduler. Constructed here so the toggle has something to drive. */
    val backupScheduler: DesktopBackupScheduler = DesktopBackupScheduler(scope, logger, runBackup)

    private val reconcileMutex = Mutex()

    private val _backupIntervalMinutes = MutableStateFlow<Int?>(null)

    /**
     * The interval in force on the backup schedule, or null when nothing is scheduled.
     *
     * Published rather than read off the scheduler on demand, because the question a consumer has is "what
     * will the app actually do" and the answer is only trustworthy if it is the value the last reconcile
     * applied — not a value re-derived by a second reader that could disagree with it.
     */
    val backupIntervalMinutes: StateFlow<Int?> = _backupIntervalMinutes.asStateFlow()

    /**
     * What this platform can actually do, derived from the seams INSTALLED rather than assumed.
     *
     * The ONLY construction of [desktopCapabilities] outside tests, which is what makes that function a
     * description of this app instead of a description of an idea.
     */
    val capabilities: StateFlow<PlatformCapabilities> =
        MutableStateFlow(desktopCapabilities(backupScheduler = backupScheduler))

    init {
        // Logged at construction, not on first read: the one thing this class exists to establish is that
        // the seams are BUILT, and a session log that can show that is the evidence for it.
        logger.i(
            source = "DesktopPlatformSeams",
            message = "Desktop platform seams constructed",
            data =
                mapOf(
                    "reminders" to capabilities.value.reminders.name,
                    "backupBackground" to capabilities.value.backupBackground.name,
                    "hasLimitations" to capabilities.value.hasLimitations,
                ),
        )
    }

    /**
     * Aligns the in-process backup schedule with the shared `auto_backup_*` rows. Idempotent.
     *
     * Android's rule, unchanged: enabled schedules at the chosen interval, disabled cancels, and an
     * unknown or absent interval key resolves to the shared default rather than to "off" — because
     * treating an unreadable cadence as disabled is how a user ends up believing backups run when they do
     * not. [DesktopBackupScheduler.schedule] replaces rather than stacks, so calling this on every change
     * and on startup is safe.
     *
     * A row read that FAILS is treated as "no schedule" rather than propagated: before the session is open
     * the data layer is deliberately uninstalled and throws, and a reconcile must not be the thing that
     * takes the window down at launch. The failure is logged — a schedule that silently did not happen is
     * the silent-control failure this class was written to end.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun reconcileBackupSchedule() {
        reconcileMutex.withLock {
            val rows =
                try {
                    backupRows()
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    logger.w(
                        source = "DesktopPlatformSeams.reconcileBackupSchedule",
                        message = "Backup schedule not reconciled: the settings rows are not readable yet",
                        data = mapOf("failure" to failure.javaClass.simpleName),
                    )
                    null
                }
            if (rows == null) {
                backupScheduler.cancel()
                _backupIntervalMinutes.value = null
                return
            }
            // The same resolution the shared mapper applies on the read side, from the same key constants,
            // so a cadence this platform schedules is the cadence the settings screen is showing.
            val enabled = rows[AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED]?.toBoolean() ?: false
            val interval =
                BackupInterval.fromKey(rows[AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL]) ?: BackupInterval.SIXTY_MIN
            if (enabled) {
                // NOT clamped to Android's 15-minute floor: that floor is a WorkManager limit on how
                // often the OS will run a periodic job, not a policy about how often a backup is worth
                // taking, and the in-process timer has no such limit. Same rows, same meaning, the
                // platform's own mechanism underneath.
                backupScheduler.schedule(interval.minutes.toInt())
                _backupIntervalMinutes.value = interval.minutes.toInt()
                logger.i(
                    source = "DesktopPlatformSeams.reconcileBackupSchedule",
                    message = "Auto-backup schedule applied from shared rows",
                    data = mapOf("enabled" to true, "intervalMinutes" to interval.minutes),
                )
            } else {
                backupScheduler.cancel()
                _backupIntervalMinutes.value = null
                logger.i(
                    source = "DesktopPlatformSeams.reconcileBackupSchedule",
                    message = "Auto-backup disabled in shared rows; no schedule in force",
                    data = mapOf("enabled" to false),
                )
            }
        }
    }

    internal companion object {
        /**
         * The seams built for the running app, so the WINDOW can reconcile them on resume.
         *
         * Exists for the same structural reason as the session controller's own registry: the seams are
         * constructed inside a `remember` in `DesktopApp.kt`, while the resume trigger belongs to the window
         * content in `DesktopMain.kt`. Threading the seams through `desktopApp`'s signature would mean adding
         * a parameter to a file whose collaborators object is built FIELD BY FIELD in tests — every one of
         * those constructors breaks. A registry is the smaller change and matches a pattern this module
         * already established.
         *
         * `@Volatile` and a single slot, not a list, because `DesktopApp.kt` builds the seams once per shell,
         * and there is one window per process.
         */
        @Volatile
        private var installed: DesktopPlatformSeams? = null

        /**
         * Records [seams] as this process's seams so [reconcileInstalledBackupSchedule] can reach them.
         *
         * Called from the factory below, which is the only place the app constructs them — so registration
         * cannot be forgotten at a new call site, which is exactly the defect class this module keeps
         * hitting (a contract implemented, tested, and reached from nowhere).
         */
        @Synchronized
        fun install(seams: DesktopPlatformSeams) {
            installed = seams
        }

        /**
         * Reconciles the installed seams' backup schedule, or does nothing when none were ever built.
         *
         * The window-resume counterpart to [reconcileBackupSchedule]. Android reconciles
         * `AutoBackupWorker`'s schedule on every `onStart`; the desktop reconciled it only at session
         * establishment and on a settings write, so a `auto_backup_enabled` row changed on the OTHER
         * platform — or a laptop that suspended past a scheduled backup — left the desktop's in-process
         * timer on a cadence the shared rows no longer describe.
         *
         * Absent seams is a normal state, not a failure: the window's observer fires on the window's first
         * focus, which on a fresh launch is before `desktopApp` has composed anything that builds them.
         *
         * Suspend because the reconcile is suspend and already mutex-guarded internally
         * ([reconcileMutex]), so an observer firing twice cannot run two overlapping reconciles even though
         * nothing here serializes calls.
         */
        suspend fun reconcileInstalledBackupSchedule(): Boolean {
            val seams = installed ?: return false
            seams.reconcileBackupSchedule()
            return true
        }
    }
}

/**
 * Builds the desktop's platform seams over the app's own dependencies, for the running app.
 *
 * This exists so the one call site that constructs them is a SINGLE line in the app shell. Everything
 * the seams need — a scope, a session logger, a backup action, a way to read the shared rows — is
 * something the app already has; naming them here keeps `desktopApp` from having to know the
 * constructor's shape, and keeps this file the only place that knows what a desktop backup IS.
 *
 * @param runBackup one backup, end to end. The caller supplies the existing desktop export machinery
 *   (checkpoint the open session, then copy the encrypted file) — this function does not implement a
 *   second backup path, and cannot: the session and the transfer gateway live in the app's object graph.
 * @param backupRows the shared `app_settings` rows. The caller supplies the read because the data layer
 *   is deliberately uninstalled until the user unlocks, and that throw is meant to stay visible.
 */
internal fun desktopPlatformSeams(
    scope: CoroutineScope,
    logger: UnifiedLogger,
    runBackup: suspend () -> Unit,
    backupRows: suspend () -> Map<String, String?>,
): DesktopPlatformSeams =
    DesktopPlatformSeams(
        scope = scope,
        logger = logger,
        runBackup = runBackup,
        backupRows = backupRows,
    ).also { seams ->
        // Registration HERE, not at the app's call site: this factory is the only place the desktop builds
        // its seams, so registering inside it makes "the window can reach them" true by construction rather
        // than by remembering to say so at a call site.
        DesktopPlatformSeams.install(seams)
    }

/** What the desktop can do, stated in the shared vocabulary — every capability it lacks included. */
internal fun desktopCapabilities(backupScheduler: DesktopBackupScheduler? = null): PlatformCapabilities =
    PlatformCapabilities(
        // No sensor, no Keystore, no wallpaper-derived palette, no vibrator.
        biometrics = SupportState.NOT_APPLICABLE,
        supportsDynamicColor = false,
        supportsHaptics = false,
        // NOT_SURFACED, honestly: the desktop has no reminder sink, so no delivery can ever be shown.
        // `NOT_APPLICABLE` would be the false claim: the desktop does have the concept, it cannot yet
        // show it.
        reminders = SupportState.NOT_SURFACED,
        // The in-process timer is what runs here; a surviving schedule would be the stronger state, and
        // installing a scheduler does not make it so — it stays IN_PROCESS_ONLY with or without one.
        backupBackground =
            if (backupScheduler?.capability == BackupBackgroundCapability.BACKGROUND_CAPABLE) {
                SupportState.SUPPORTED
            } else {
                SupportState.IN_PROCESS_ONLY
            },
    )
