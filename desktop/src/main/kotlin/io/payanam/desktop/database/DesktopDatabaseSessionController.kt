//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.DesktopDatabaseCreateOutcome
import io.payanam.desktop.createDesktopFirstRunDatabase
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.scoring.RecurrenceManager
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.database.WalCheckpointRule.FoldReport
import io.payanam.shared.logging.CrashBreadcrumbs
import io.payanam.shared.logging.UnifiedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens the desktop's encrypted database and puts the shared data layer in force.
 *
 * The ORDER here is the whole point, and it is the reverse of the order that produced the scaffold this
 * replaces: the database is opened and its repositories are installed BEFORE anything reads through the
 * holder. The previous sequence deleted the data layer and left a mock in place, which is why the desktop
 * showed an empty app rather than an error.
 *
 * A refusal is a normal state, not a failure. A desktop install with no imported database has no file to
 * open, and the desktop cannot create one — its migration logic does not exist yet, deliberately, because
 * writing v21 semantics over a database this build does not implement is worse than refusing. So a refusal
 * leaves the holder UNINSTALLED, which makes every repository read throw with the fix in the message,
 * instead of returning empty lists that look like "you have no data".
 */
internal class DesktopDatabaseSessionController(
    private val databaseFilePath: () -> String,
    /** The open, injected: tests drive both the accepted and the refused path without a real driver. */
    private val open: suspend (String, CharArray) -> DatabaseOpenResult,
    private val logger: UnifiedLogger,
) {
    private var session: PayanamDatabaseSession? = null

    /**
     * Whether [checkpointAndCloseForShutdown] has already run in this process.
     *
     * An [AtomicBoolean] rather than a plain `Boolean` because the two callers that can reach the
     * shutdown path — the EDT's quit callback and the JVM shutdown hook thread — are different threads,
     * and a non-atomic check-then-set would let both fold the same session.
     */
    private val shutdownRun = AtomicBoolean(false)

    init {
        // Self-registration, deliberately: the controller is built inside a Composable in
        // `DesktopApp.kt`, so the entry point in `DesktopMain.kt` has no reference to it and could not
        // wire the shutdown hook without a change to a file that owns this lifecycle. A controller that
        // owns a session IS the process's shutdown target, so it says so for itself at construction —
        // which is also before any session exists, so the hook is in place well before it can matter.
        //
        // Costs nothing in tests: the hook is installed once per JVM, targets the most recently built
        // controller, and [checkpointAndCloseForShutdown] is a no-op for a controller with no session.
        installProcessShutdownHook(this)
    }

    /**
     * The day-boundary work Android runs in `MainActivity.runStartupMaintenance` — the SAME
     * [RecurrenceManager], not a desktop reimplementation.
     *
     * This was genuinely MISSING, not merely a missing callback: a repo-wide grep of `desktop/src/main`
     * found zero references to `repairStuckRecurringTasks`, `autoAdvanceRecurringTasks`, or
     * `ScoreRollupBackfillService.runIfNeeded`. The consequence was invisible and real — on the desktop a
     * recurring habit did not roll over at midnight, a stuck occurrence was never repaired, and historical
     * scores were never backfilled. The app looked fine and quietly kept yesterday's state.
     *
     * Called from the one place a session comes into existence, so it runs on every open exactly as
     * Android's runs on every `onStart` past the gates. It is fire-and-forget: startup must not be delayed
     * or failed by maintenance, and every step logs its own outcome.
     */
    private fun runStartupMaintenance(layer: DesktopPayanamDataLayer) {
        val recurrenceManager = RecurrenceManager(layer.tasks, layer.taskOccurrences)
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val repaired = recurrenceManager.repairStuckRecurringTasks()
                if (repaired > 0) {
                    logger.i(
                        "DesktopDatabaseSessionController.runStartupMaintenance",
                        "Repaired stuck recurring tasks",
                        mapOf("repairedCount" to repaired),
                    )
                }
                recurrenceManager.autoAdvanceRecurringTasks()
                logger.i(
                    "DesktopDatabaseSessionController.runStartupMaintenance",
                    "Auto-advanced recurring tasks",
                )
            }.onFailure { error ->
                // Never silent: a habit that does not roll over is exactly the kind of failure that would
                // otherwise be invisible on this platform.
                logger.e(
                    "DesktopDatabaseSessionController.runStartupMaintenance",
                    "Startup maintenance failed; recurring tasks may not have advanced",
                    error,
                    mapOf("error" to (error.message ?: "Unknown error")),
                )
            }
        }
    }

    /**
     * The passphrase the session was opened with, held for as long as the session is.
     *
     * Retained because an IMPORT needs the passphrase+file pair and the user has just supplied it to unlock.
     * Android's own session manager holds it the same way (`openPassphrase`), so this is parity rather than a
     * new concession - and it is cleared whenever the session is, so it does not outlive the unlock.
     */
    private var openPassphrase: CharArray? = null

    /** The open session, or null when the database is not open. */
    val isOpen: Boolean get() = session?.isOpen == true

    private val _sessionOpenState = MutableStateFlow(false)

    /**
     * The HONEST session state: true only from the moment [openAndInstall] assigned a session, false
     * once [closeAndUninstall] has released it, so it always agrees with [isOpen].
     *
     * The UI's optimistic flag stays UI-local: `DesktopRememberedState.sessionOpenState` flips to
     * true the moment the passphrase is ACCEPTED — before the open completes — and that optimism
     * caused a recorded desktop session flip-flop, a screen claiming an open the session never
     * reached. A consumer that needs the truth reads THIS flow, which the session owner flips itself.
     */
    val sessionOpenState: StateFlow<Boolean> = _sessionOpenState.asStateFlow()

    /**
     * The passphrase for the session in force, or null when none was established.
     *
     * A COPY: the caller may need to hand it to the opener again, and a caller that cleared the array it
     * received would otherwise clear this one too.
     */
    fun passphraseForTransfer(): CharArray? = openPassphrase?.copyOf()

    /**
     * Folds the open session's write-ahead log, or returns null when nothing is open.
     *
     * Kept here rather than exposing the session itself: the session is the one object every repository
     * reads through, and handing it out would invite a caller to keep a reference across a close.
     */
    fun checkpoint(): FoldReport? = session?.checkpoint()

    /**
     * Opens the database with [passphrase] and installs the layer, CREATING it when there is no file yet.
     *
     * **The create is the point of this method, not a fallback inside it.** The shared
     * `DatabaseInitViewModel` has no create-vs-open intent: it calls
     * `DatabaseSessionManager.openDatabase(passphrase)` for BOTH the "create new database" and the
     * "unlock" branch, because Room's builder creates a missing file on Android, so that single call is
     * how Android satisfies both. This controller was open-only, so a fresh Linux install asking to
     * CREATE met `PayanamDatabaseOpener.preflight`'s `not_found` and the whole first run stopped one
     * step past the init screen. The two sides of the shared contract now agree.
     *
     * **The create is the SAME one, not a second one.** [createDesktopFirstRunDatabase] ->
     * `PayanamDatabaseOpener.create` is what the desktop's own first-run gate already calls, with its
     * failure-atomic temp-sibling build, its no-`REPLACE_EXISTING` move and its read-back proof. A
     * second create path would be a second, divergent definition of the same file - and the one that
     * can overwrite a database is exactly the one that must not exist.
     *
     * **The parent directory is created FIRST.** `preflightForCreate` refuses with `parent_not_found`
     * when the directory is absent, so creating without it would move the failure one line down
     * rather than remove it. `<root>/database/` is created here and by
     * `databaseDirectoryRefusal`; both use `createDirectories`, which is idempotent.
     *
     * **An existing file is opened, never re-created.** The existence check is the whole branch, and
     * the create's own `already_exists` guard remains the backstop if a file appears between the two.
     * A file this build cannot open is refused with its real detail (`not_readable`, a schema or
     * sidecar problem) rather than silently replaced - overwriting a database its owner cannot open is
     * the one outcome that must never happen.
     *
     * Returns the outcome so the caller decides what the user is told; this class only decides what is
     * INSTALLED. A create that refused installs nothing, exactly like a refused open.
     */
    suspend fun openAndInstall(passphrase: CharArray): DatabaseOpenResult {
        // Nothing is installed until the open has succeeded: an install followed by a refusal would leave
        // repositories reading a database that was never opened.
        closeAndUninstall()

        val path = databaseFilePath()
        val result =
            if (Files.exists(Path.of(path))) {
                open(path, passphrase)
            } else {
                logger.i(
                    "DesktopDatabaseSessionController",
                    "No database file at the configured path; creating it for the shared init flow",
                    mapOf("fileName" to Path.of(path).fileName.toString()),
                )
                // The proof session `create` returns is closed by `createDesktopFirstRunDatabase`
                // itself; what is installed below is a session this controller owns, opened from the
                // file that create just proved.
                createDatabaseFile(path, passphrase) ?: open(path, passphrase)
            }
        // Kept even on a refusal: a fresh install has no database to open, and the import that follows needs
        // the passphrase the user just set. Nothing is installed on that path, so the layer still fails loudly.
        openPassphrase = passphrase.copyOf()
        when (result) {
            is DatabaseOpenResult.Opened -> {
                session = result.session
                // Flipped only now, when a session actually exists — the same instant isOpen turns.
                _sessionOpenState.value = true
                val layer = DesktopPayanamDataLayer(result.session)
                LocalDataLayer.install(layer)
                logger.i(
                    "DesktopDatabaseSessionController",
                    "Database opened and the shared data layer installed",
                    mapOf("schemaVersion" to result.session.schemaVersion),
                )
                runStartupMaintenance(layer)
            }

            is DatabaseOpenResult.Refused -> {
                // The issue TYPE is logged, never the detail message: it can carry a path.
                logger.w(
                    "DesktopDatabaseSessionController",
                    "Database not opened; the shared data layer stays uninstalled",
                    mapOf("issue" to result.issue.type.name),
                )
            }
        }
        return result
    }

    /**
     * Creates the database at [path] through the desktop's EXISTING first-run create, or returns the
     * refusal that stopped it - null when it was created.
     *
     * **A refusal is returned rather than swallowed**, so the caller never falls through to an open and
     * reports a second, weaker reason for a failure that already has one. An unwritable data folder is
     * the common case and must read as "could not write to its data folder", not as `not_found` again.
     *
     * **The directory is created before the create, never inside it.** `preflightForCreate` refuses a
     * missing parent with `parent_not_found`, so delegating directory creation to the create would
     * move the failure rather than remove it.
     *
     * **The proof session is closed inside [createDesktopFirstRunDatabase]**, and the caller opens the
     * file that create proved - deliberately two steps rather than keeping the handle, so this
     * controller owns exactly one connection at a time and `closeAndUninstall` still has nothing to
     * remember here.
     */
    private fun createDatabaseFile(
        path: String,
        passphrase: CharArray,
    ): DatabaseOpenResult.Refused? {
        val target = Path.of(path)
        val parent = target.toAbsolutePath().parent
        if (parent != null && !Files.isDirectory(parent)) {
            runCatching { Files.createDirectories(parent) }
                .onFailure { error ->
                    logger.e(
                        "DesktopDatabaseSessionController.createDatabaseFile",
                        "Could not create the database directory, so no database was created",
                        error,
                        mapOf("error" to (error.message ?: "Unknown error")),
                    )
                    return DatabaseOpenResult.Refused(
                        DatabaseBootIssue(
                            DatabaseBootIssueType.OPEN_FAILED,
                            DESKTOP_DIRECTORY_UNWRITABLE,
                        ),
                    )
                }
        }
        return when (
            val outcome =
                createDesktopFirstRunDatabase(
                    databasePath = target,
                    passphrase = passphrase,
                    log = { entry ->
                        logger.i(
                            source = "DesktopDatabaseSessionController.createDatabaseFile",
                            message = "First-run database create reported",
                            data = entry,
                        )
                    },
                )
        ) {
            is DesktopDatabaseCreateOutcome.Created -> {
                logger.i(
                    "DesktopDatabaseSessionController.createDatabaseFile",
                    "Created the database the shared init flow asked to open",
                    mapOf("schemaVersion" to outcome.schemaVersion),
                )
                null
            }

            // The issue TYPE and its machine code only: a detail can carry a path.
            is DesktopDatabaseCreateOutcome.Refused -> {
                logger.w(
                    "DesktopDatabaseSessionController.createDatabaseFile",
                    "Creating the database was refused; the shared data layer stays uninstalled",
                    mapOf(
                        "issue" to outcome.issue.type.name,
                        "detail" to (outcome.issue.detailMessage ?: "none"),
                    ),
                )
                DatabaseOpenResult.Refused(outcome.issue)
            }
        }
    }

    /** Closes the session and uninstalls the layer, so a read after shutdown fails loudly rather than late. */
    fun closeAndUninstall() {
        // The pre-session layer, NOT the uninstalled default: `openAndInstall` calls this first, so
        // between the release above and the new install below the holder must already answer the gates
        // that compose in that window — `databaseInitViewModel` reads it while being constructed.
        LocalDataLayer.install(DesktopBootstrapDataLayer)
        openPassphrase?.fill('\u0000')
        openPassphrase = null
        session?.close()
        session = null
        // With the session gone, the state that describes it is false — the same instant isOpen is.
        _sessionOpenState.value = false
    }

    /**
     * Folds the open session's log and then closes it, for a real process exit.
     *
     * **The ORDER is the contract, and it is Android's, not a convenience.**
     * `DatabaseSessionManager.closeDatabaseForTimeout`
     * (core/database/.../DatabaseSessionManager.kt:344-378) is the app's own full teardown, and it runs
     * the same four steps in the same sequence: breadcrumb "started" → `checkpoint()` (the
     * `PRAGMA wal_checkpoint(TRUNCATE)` fold, so the main file holds every write) → the release
     * (state flipped false, session closed) → breadcrumb "closed". Mirroring the ORDER rather than just
     * calling [closeAndUninstall] is the point: a close that skipped the fold would leave the last
     * writes in a `-wal` file for the NEXT open to recover, and would make a clean quit
     * indistinguishable from a `kill -9` — the session would never be proven complete.
     *
     * [closeAndUninstall] deliberately does NOT fold, and stays the way it is: it is also the
     * pre-open reset ([openAndInstall] calls it first) and the import-replace release, where a fold
     * would be at best redundant and at worst a checkpoint on a session about to be replaced.
     *
     * Runs at most ONCE per process, whichever path gets here first. Two exits are normal — the quit
     * path and a JVM shutdown hook — and a second fold of a session that is already closed is not just
     * redundant: [PayanamDatabaseSession.checkpoint] checks `!closed` and would throw, turning an
     * orderly exit into a stack trace. The [AtomicBoolean] makes "already shut down" a fact rather
     * than a check-then-act race between the EDT and the hook thread.
     *
     * Never throws: this runs from a shutdown path where an exception would replace the exit the caller
     * asked for with a stack trace and a partially-closed session.
     */
    @Synchronized
    fun checkpointAndCloseForShutdown() {
        if (!shutdownRun.compareAndSet(false, true)) {
            return
        }
        logger.i(
            source = "DesktopDatabaseSessionController.checkpointAndCloseForShutdown",
            message = "Folding the open session's log before the process exits",
            data = mapOf("wasOpen" to isOpen),
        )
        CrashBreadcrumbs.record(
            source = "DesktopDatabaseSessionController.checkpointAndCloseForShutdown",
            stage = "shutdown_started",
        )
        // The fold FIRST, and it is best-effort-but-reported: a fold that did not complete is the
        // outcome the backup and export paths already refuse to ignore, so it is warned about here with
        // the same frame counts rather than being assumed durable.
        runCatching { checkpoint() }
            .onSuccess { result ->
                if (result == null) {
                    logger.i(
                        "DesktopDatabaseSessionController.checkpointAndCloseForShutdown",
                        "No session was open; nothing to fold",
                    )
                } else if (!WalCheckpointRule.walIsEmpty(result)) {
                    logger.w(
                        "DesktopDatabaseSessionController.checkpointAndCloseForShutdown",
                        "The open session's log could not be fully folded; the last writes stay in the -wal file",
                        mapOf(
                            "logFrames" to result.logFrames,
                            "checkpointedFrames" to result.checkpointedFrames,
                        ),
                    )
                }
            }.onFailure { error ->
                logger.e(
                    "DesktopDatabaseSessionController.checkpointAndCloseForShutdown",
                    "Folding the open session's log failed; the session is still being closed",
                    error,
                    mapOf("error" to (error.message ?: "Unknown error")),
                )
            }

        // Then the release, through the same method every other close in the desktop uses, so the
        // uninstall-and-zero-the-passphrase ordering has exactly one implementation to be right about.
        runCatching { closeAndUninstall() }
            .onFailure { error ->
                logger.e(
                    "DesktopDatabaseSessionController.checkpointAndCloseForShutdown",
                    "Closing the database session failed",
                    error,
                    mapOf("error" to (error.message ?: "Unknown error")),
                )
            }
        logger.i(
            source = "DesktopDatabaseSessionController.checkpointAndCloseForShutdown",
            message = "Database session closed for process exit",
            data = mapOf("isOpen" to isOpen),
        )
        CrashBreadcrumbs.record(
            source = "DesktopDatabaseSessionController.checkpointAndCloseForShutdown",
            stage = "session_closed_for_process_exit",
        )
    }

    internal companion object {
        /**
         * The controller the JVM shutdown hook folds and closes, or null when none has been registered.
         *
         * The reference exists because the desktop's quit path is not the only way the process ends.
         * `exitApplication()` and the window's `onCloseRequest` are the orderly paths, but a SIGTERM
         * from the session manager, a `System.exit` from anywhere, or a last-window close that never
         * routes through the quit callback would all skip them — and a session that is never folded on
         * any of those is exactly the defect being closed.
         *
         * A plain `@Volatile` field, not a registry, because only one controller can hold a session:
         * `DesktopApp.kt` builds it inside `remember`, so there is one per process.
         */
        @Volatile
        private var hookTarget: DesktopDatabaseSessionController? = null

        @Volatile
        private var hookInstalled = false

        /**
         * Registers [controller] as this process's shutdown target and installs the JVM shutdown hook.
         *
         * Called from the desktop entry point, once the logger and breadcrumbs exist. Idempotent, so a
         * second call neither installs a second hook nor loses the first: `Runtime.addShutdownHook`
         * throws `IllegalStateException` if the JVM is already shutting down, and two hooks would fold
         * the same session from two threads — the [shutdownRun] gate would make the loser a silent
         * no-op, so the guard exists to keep the log honest about how many times the fold ran.
         */
        @Synchronized
        fun installProcessShutdownHook(controller: DesktopDatabaseSessionController) {
            hookTarget = controller
            if (hookInstalled) {
                return
            }
            // A named Runnable rather than a lambda passed positionally into Thread(...): the hook body
            // needs a non-local exit for "no session was ever opened", and a lambda-as-argument
            // alongside the name argument does not parse cleanly (verified against kotlinc 2.3.20).
            val closeOpenSessionOnExit =
                Runnable {
                    val target = hookTarget
                    if (target == null) {
                        // Nothing to close: no session was ever opened. A hook that logged here would
                        // put a line in every launch log for nothing.
                        return@Runnable
                    }
                    target.checkpointAndCloseForShutdown()
                }
            val installed =
                runCatching {
                    Runtime
                        .getRuntime()
                        .addShutdownHook(
                            Thread(closeOpenSessionOnExit, DESKTOP_DATABASE_SHUTDOWN_HOOK_NAME),
                        )
                }.onFailure { error ->
                    // A JVM that refuses shutdown hooks must still get the orderly quit path, which
                    // calls the same method directly. Reported, never fatal.
                    controller.logger.w(
                        source = "DesktopDatabaseSessionController.installProcessShutdownHook",
                        message = "Could not install the JVM shutdown hook; orderly quits still fold the session",
                        data = mapOf("reason" to (error.message ?: "unknown")),
                    )
                }.isSuccess
            hookInstalled = installed
        }

        /**
         * Folds and closes this process's session, if one was ever registered, for a caller that must
         * see the fold happen BEFORE it finishes its own shutdown work.
         *
         * This is the desktop's `MainActivity.onStop` position: the fold is not left to JVM exit but
         * performed inline, so the log lines describing it land before the session-end marker rather
         * than after it. The shutdown hook still covers the exits that never reach here (SIGTERM,
         * `System.exit`), and [DesktopDatabaseSessionController.shutdownRun] makes the two paths
         * non-conflicting.
         *
         * A companion function rather than an instance method because the desktop entry point builds no
         * controller — it is constructed inside a Composable in `DesktopApp.kt` — so the only handle
         * available from `DesktopMain` is the one registered here.
         */
        fun checkpointAndCloseProcessSession() {
            val target =
                hookTarget ?: run {
                    // No controller was ever built, so no session exists and nothing is open. A normal
                    // state on a launch that never reached the gate, not a failure to report.
                    return
                }
            target.checkpointAndCloseForShutdown()
        }
    }
}

/** Thread name the desktop's database shutdown hook runs under, so it is identifiable in a thread dump. */
private const val DESKTOP_DATABASE_SHUTDOWN_HOOK_NAME = "payanam-database-shutdown"

/**
 * Detail code for a data folder this build cannot create, matching the code the first-run gate's own
 * `databaseDirectoryRefusal` uses so one failure reads the same however it was reached.
 */
private const val DESKTOP_DIRECTORY_UNWRITABLE = "desktop_directory_unwritable"
