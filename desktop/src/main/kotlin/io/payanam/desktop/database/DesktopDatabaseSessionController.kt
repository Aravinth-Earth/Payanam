//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.database.jvm.AssistantConfigurationStoreJvm
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.DesktopDatabaseCreateOutcome
import io.payanam.desktop.createDesktopFirstRunDatabase
import io.payanam.desktop.platform.DesktopStartupRecovery
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
import kotlinx.coroutines.flow.first
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
    /**
     * This platform's day-boundary maintenance, injected so the concurrency guard around it can be proven
     * without a recurring habit in the database.
     *
     * Defaults to the REAL steps - [runDesktopRecurrenceMaintenance] - so production is unchanged and the
     * injection cannot become a second implementation of the maintenance itself. The reason it exists at
     * all is that "does not run twice concurrently" is a claim about a function body, and the only way to
     * assert a call count on that body is to substitute it; asserting it through the real
     * `RecurrenceManager` would mean asserting on which rows a WAL-backed JDBC layer happens to write,
     * which is a weaker statement about a different thing.
     */
    private val maintenance: suspend (DesktopPayanamDataLayer) -> Unit = { layer ->
        runDesktopRecurrenceMaintenance(layer, logger)
    },
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

    /**
     * The layer this controller installed, or null when no session is open.
     *
     * Held so the resume path runs maintenance over the SAME layer every repository reads through,
     * rather than reaching for [LocalDataLayer.current] and re-deriving one. `openAndInstall` builds the
     * layer and installs it in one step, so the reference it keeps is the only one guaranteed to be the
     * layer in force - reading the process-wide holder would work today and break the moment a second
     * controller existed, which is exactly the assumption the session's single-owner design forbids.
     *
     * Cleared with the session in [closeAndUninstall], so "no session" and "no layer" cannot disagree.
     */
    private var installedLayer: DesktopPayanamDataLayer? = null

    /**
     * Whether a maintenance run started by this controller is still in flight.
     *
     * An [AtomicBoolean], matching [shutdownRun] above, and for the same reason: the two callers are
     * different threads - the open path and the window's resume observer - and a plain `Boolean` would let
     * both enter the same run. This is a check-then-act guard, not a lock, because the required behaviour is
     * to SKIP the second caller rather than queue behind the first: a laptop resumed twice in quick
     * succession must roll its recurring tasks forward once per resume, not once per resume plus once for
     * the duplicate that queued behind it.
     */
    private val maintenanceInFlight = AtomicBoolean(false)

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
     * found zero references to `repairStuckRecurringTasks` or `autoAdvanceRecurringTasks`. The
     * consequence was invisible and real — on the desktop a recurring habit did not roll over at
     * midnight and a stuck occurrence was never repaired. The app looked fine and quietly kept
     * yesterday's state.
     *
     * **All THREE of Android's steps, not two.** `MainActivity.runStartupMaintenance` also calls the score
     * roll-up backfill, and the backfill now RUNS on this platform: the compute was promoted and ported into
     * `:core:database-jvm` as `ScoreRollupBackfillJdbc`, a JDBC writer for `habit_metrics` /
     * `dimension_metrics` / `day_metrics` rather than the readers that module used to ship. It is reached
     * from the post-unlock recovery path — [DesktopStartupRecovery] runs it as
     * `StartupRecoveryStep.BACKFILL_SCORE_ROLLUPS` — so it is guarded by the SAME `app_settings` key Android
     * writes, which is what makes one database backfilled once across both platforms instead of twice.
     *
     * The desktop's refusal of the LIVE cascade remains a separate, declared position: this platform still
     * does not recompute roll-ups day by day (see `DesktopUnsupportedPorts.scoreRollupTrigger`), on the
     * stated premise that Android computes them into the same database the desktop reads back. The one-time
     * historical rebuild is not that thing — it reconstructs what an earlier version never wrote, and it is
     * the half that can be reached without a cascade — so the two positions are compatible rather than in
     * conflict, and it is the cascade, not the backfill, that a product decision still holds open. Today's
     * rows therefore stay absent until a cascade equivalent exists, which is the backfill's own documented
     * boundary rather than a failure.
     *
     * What genuinely does not exist here is a RE-ARM of reminders at session resume: delivery itself is
     * complete on this platform (the sink is constructed with a freedesktop delivery behind it and the data
     * layer binds the producer that hands it reminders), but the scheduler arms reminders from task due
     * times and there is no desktop step that replays a backlog of them at resume or after a restart.
     * Pretending otherwise would make this log claim a reconciliation that did not happen.
     *
     * Called from the one place a session comes into existence, so it runs on every open exactly as
     * Android's runs on every `onStart` past the gates. It is fire-and-forget: startup must not be delayed
     * or failed by maintenance, and every step logs its own outcome.
     *
     * `internal`, not `private`, so the RESUME path reaches the SAME steps rather than a second
     * implementation of them - see [runMaintenanceAfterResume], which is the reason this function is
     * reachable from outside. Both callers go through [runMaintenanceGuarded], so an open and a resume
     * cannot run the steps over the same layer at the same time.
     */
    internal fun runStartupMaintenance(layer: DesktopPayanamDataLayer) {
        CoroutineScope(Dispatchers.Default).launch {
            runMaintenanceGuarded(layer, "DesktopDatabaseSessionController.runStartupMaintenance")
        }
    }

    /**
     * The day-boundary work, for a window that has just come BACK - the desktop's counterpart to
     * `MainActivity.onStart` re-running maintenance every time the app returns to the foreground.
     *
     * **The gap this closes is a laptop, not a desktop.** `runStartupMaintenance` ran from exactly one
     * call site - `openAndInstall` - so a machine that suspended across midnight never advanced
     * yesterday's recurring tasks. Nothing failed and nothing looked wrong: the app showed yesterday's
     * state exactly as it had at suspend time, indefinitely, until it was restarted. Android has no such
     * window because its maintenance runs on every `onStart`.
     *
     * Suspend rather than fire-and-forget, unlike [runStartupMaintenance], for two reasons that both
     * matter: a resume is a user-visible moment where the caller may want to know the work finished, and
     * awaiting it is what makes the duplicate-concurrency behaviour observable rather than merely
     * intended. The window's observer launches it on the composition scope.
     *
     * **Returns immediately when no session is open.** Not "runs anyway" and not an error: before the
     * user unlocks there is no layer, so there is nothing to repair or advance, and the pre-unlock half of
     * every launch would otherwise log a failure for a normal state. The window's observer fires on the
     * window's very first focus, which is long before any passphrase is typed.
     *
     * **A duplicate is skipped, not queued.** [maintenanceInFlight] is the gate, matching the shape of
     * Android's `startupMaintenanceJob?.isActive == true` check but with the mechanism this file already
     * uses ([shutdownRun] above). Skipping rather than queueing is the deliberate choice: the steps are
     * idempotent day-boundary repairs, so a second pass adds nothing, while a queued pass would run the
     * whole repair set again for a resume that arrived while the first was still going.
     *
     * **The score roll-up backfill runs here too, so what runs at resume is everything that runs at open.**
     * This passage used to say the opposite, and the reason it could be trusted then was that it was true:
     * the backfill had no desktop writer to call. It does now — `ScoreRollupBackfillJdbc` in
     * `:core:database-jvm`, reached through [DesktopStartupRecovery] — so this path runs it exactly as
     * [runStartupMaintenance] does, and the shared `app_settings` guard is what makes the second run a no-op
     * rather than a second rebuild.
     *
     * Android's `scheduleAllPendingTasks` still has no desktop equivalent, and that one really is absent
     * rather than merely unwired: the reminder DELIVERY surface is complete on this platform (the sink is
     * constructed with a freedesktop delivery behind it and the data layer binds the producer that hands it
     * reminders), but the scheduler arms reminders from task due times and there is no desktop step that
     * replays a backlog of them at resume. Inventing steps this platform cannot perform would make the log
     * claim a reconciliation that did not happen.
     */
    internal suspend fun runMaintenanceAfterResume() {
        val layer = installedLayer
        if (layer == null) {
            logger.d(
                source = "DesktopDatabaseSessionController.runMaintenanceAfterResume",
                message = "No session is open, so there is nothing to reconcile on resume",
            )
            return
        }
        runMaintenanceGuarded(layer, "DesktopDatabaseSessionController.runMaintenanceAfterResume")
    }

    /**
     * Runs this platform's maintenance steps for [layer] at most once at a time, across EVERY caller.
     *
     * Returns whether THIS caller ran the work, so a skipped duplicate is distinguishable from a run that
     * found nothing to do - the distinction the log line below reports and the resume test asserts.
     *
     * The flag is released in a `finally` so a throwing step cannot wedge the guard and silently disable
     * maintenance for the rest of the process: the failure is logged by the steps themselves and the next
     * resume must still be able to try.
     */
    private suspend fun runMaintenanceGuarded(
        layer: DesktopPayanamDataLayer,
        source: String,
    ): Boolean {
        if (!maintenanceInFlight.compareAndSet(false, true)) {
            logger.d(
                source = source,
                message = "Maintenance is already running for this session; skipping the duplicate",
            )
            return false
        }
        return try {
            runMaintenance(layer)
            true
        } finally {
            maintenanceInFlight.set(false)
        }
    }

    /**
     * The steps this platform can actually perform, in Android's order, over [layer].
     *
     * A delegation rather than the body itself, so [maintenance] — the single seam both callers go through
     * — is what actually runs. Keeping the production steps in a top-level function rather than inline here
     * is what lets that seam default to the real thing without the default referring back to an instance
     * that does not exist yet during construction.
     *
     * Never lets an exception escape: every step reports its own outcome, and a failure must not propagate
     * into a resume that has nothing better to do than fail. The exception is handled one level down, inside
     * [runDesktopRecurrenceMaintenance], so this function cannot be the reason a launch fails.
     */
    private suspend fun runMaintenance(layer: DesktopPayanamDataLayer) {
        maintenance(layer)
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
     * True while a close came from the shared passphrase port and a reopen (or a restart) will follow.
     *
     * The passphrase-change flow closes the session itself before the rekey, and the desktop's gate
     * follows [sessionOpenState] LIVE - measured without this: the gate armed ~80ms into the change,
     * the graph reset to `passphrase_unlock`, the change screen was destroyed, and the in-flight
     * submit was cancelled after a rekey that had already succeeded, rolling the backup back over it.
     * `followDesktopSessionOpenedState` is the reader that honours this; it skips the state flip so
     * the gate never arms mid-change. The port clears it when the reopen follows.
     */
    @Volatile
    internal var closeInFlightForRekey = false

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
     * The assistant's stored configuration, over THIS session, or null when nothing is open.
     *
     * A session-BOUND store handed out by the one owner of the session, rather than the session itself.
     * [AssistantConfigurationStoreJvm] needs a `PayanamDatabaseSession` and there is exactly one in this
     * process, so this is the only place the store can be built from the connection the shell already
     * has open: a store built anywhere else would hold a SECOND writable connection to the same file,
     * contending with every repository poll, and its writes would land outside the session the import
     * path has to fold before it can replace the file.
     *
     * Null rather than an error because "no session yet" is a normal state on this platform - it is the
     * pre-unlock half of every launch - and the assistant's own screen reports it as its `DB_LOCKED`
     * state. A caller that needs the store before a session exists has to say what it will do instead,
     * which is exactly the decision this return type forces.
     */
    fun assistantConfigurationStore(): AssistantConfigurationStoreJvm? =
        session
            ?.takeIf { it.isOpen }
            ?.let { AssistantConfigurationStoreJvm(it) }
            .also { store ->
                if (store == null) {
                    logger.d(
                        source = "DesktopDatabaseSessionController.assistantConfigurationStore",
                        message = "No open session, so there is no assistant configuration store yet",
                    )
                }
            }

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
                val layer = DesktopPayanamDataLayer(result.session)
                LocalDataLayer.install(layer)
                // `session` is published only AFTER the layer exists: `isOpen` is DERIVED from it, so assigning
                // it earlier announces an open session whose consumers would still read the PREVIOUS
                // (bootstrap) layer - the same ordering rule as the flag below, one layer down. Found by the
                // publish-before-install sweep, which read this very function.
                session = result.session
                // The layer kept for the resume path, published at the same instant as the session it
                // reads through: `runMaintenanceAfterResume` returns early when this is null, so a
                // mismatch here would either skip the rollover or run it against a released connection.
                installedLayer = layer
                // Flipped only AFTER the layer is installed. This state is what consumers wait on, so a
                // consumer that waited for it must never find the PREVIOUS (bootstrap) layer still installed.
                // Measured 2026-10-02 by the import loop harness: with the flip first, the preference read
                // that this state triggers read the bootstrap layer and got ZERO rows from a database that
                // holds them.
                _sessionOpenState.value = true
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
        // With the session gone, so is the layer that read through it: a resume arriving after this must
        // take the early return rather than run the recurrence steps against a closed connection.
        installedLayer = null
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

        /**
         * Runs this process's day-boundary maintenance for a window that has just come back, if a
         * controller was ever registered.
         *
         * A companion function for the same structural reason as [checkpointAndCloseProcessSession]: the
         * window content in `DesktopMain` is composed BEFORE `desktopApp` builds the controller, and the
         * controller itself lives inside a `remember` in `DesktopApp.kt`. So the window's resume observer
         * cannot hold a controller reference, and the only handle available from `DesktopMain` is the one
         * registered here at construction.
         *
         * Reusing [hookTarget] rather than adding a second registry is deliberate: there is exactly one
         * controller per process, and two independent "who is the current controller" fields would be two
         * answers that could disagree.
         *
         * Suspend because the work is suspend, and because the observer that calls it launches on the
         * composition scope. Returns whether a controller was reachable at all - NOT whether the steps ran:
         * a reachable controller with no session open, or one whose maintenance was already in flight, both
         * take an early return inside [runMaintenanceAfterResume] and leave that distinction in the log,
         * which is where a resume's outcome is read from anyway.
         */
        suspend fun reconcileProcessSessionOnResume(): Boolean {
            val target =
                hookTarget ?: run {
                    // No controller was ever built, so no session exists. The window's observer fires on
                    // the window's first focus, which on a fresh launch is before any controller exists -
                    // a normal state, not a failure to report.
                    return false
                }
            target.runMaintenanceAfterResume()
            return true
        }
    }
}

/**
 * The desktop's day-boundary maintenance: Android's two recurrence steps, over this platform's layer.
 *
 * **TWO steps, and neither is a desktop reimplementation.** `repairStuckRecurringTasks` then
 * `autoAdvanceRecurringTasks`, both on the SAME shared `RecurrenceManager` Android uses. A second rollover
 * rule would be a second answer to "which days is this habit due", and the desktop already reads that
 * answer through the shared repositories — so the rule has to stay shared even when the layer is not.
 *
 * Android's third step, the score roll-up backfill, RUNS here rather than being absent: the compute was
 * ported into `:core:database-jvm` as `ScoreRollupBackfillJdbc`, and the call below reaches it through
 * [DesktopStartupRecovery], which is the same three-step set the open path runs. It is guarded by the same
 * `app_settings` key Android writes, so the two platforms backfill one database between them rather than
 * twice. See the KDoc on `DesktopDatabaseSessionController.runStartupMaintenance` — including why the LIVE
 * cascade this drives is still, and separately, a platform that has not adopted it.
 *
 * Top-level rather than a method, so the controller's `maintenance` parameter can default to it: a default
 * argument cannot reference an instance member of the object being constructed.
 *
 * Never throws. A habit that does not roll over is exactly the kind of failure that would otherwise be
 * invisible on this platform, so the failure is LOGGED at `e` (never level-gated) rather than swallowed.
 */
internal suspend fun runDesktopRecurrenceMaintenance(
    layer: DesktopPayanamDataLayer,
    logger: UnifiedLogger,
) {
    // The three startup recovery steps live behind one call, and each step carries its own failure handler. The
    // handler here is deliberately the outer one only: a step that throws is already excluded, logged under its own
    // name, and its siblings still run, so what arrives here is either a completed run or a run that lost one step.
    runCatching {
        val steps = DesktopStartupRecovery(layer).runStartupRecovery()
        logger.i(
            "DesktopDatabaseSessionController.runMaintenance",
            "Startup maintenance ran",
            mapOf("steps" to steps.joinToString()),
        )
    }.onFailure { error ->
        logger.e(
            "DesktopDatabaseSessionController.runMaintenance",
            "Startup maintenance failed; some recovery steps may not have run",
            error,
            mapOf("error" to (error.message ?: "Unknown error")),
        )
    }
}

/** Thread name the desktop's database shutdown hook runs under, so it is identifiable in a thread dump. */
private const val DESKTOP_DATABASE_SHUTDOWN_HOOK_NAME = "payanam-database-shutdown"

/**
 * Detail code for a data folder this build cannot create, matching the code the first-run gate's own
 * `databaseDirectoryRefusal` uses so one failure reads the same however it was reached.
 */
private const val DESKTOP_DIRECTORY_UNWRITABLE = "desktop_directory_unwritable"
