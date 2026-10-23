//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseOpener
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * How far the user has advanced through the desktop onboarding scaffolding.
 *
 * The desktop has no real data layer yet (the encrypted database is not wired in), so launching it
 * always begins at [Intro] — there is never a database to find. This enum is what makes that explicit
 * and steppable instead of silently falling through to the passphrase gate.
 *
 * It is deliberately NOT persisted: a restart must show onboarding again, which is exactly what an
 * in-memory value gives us. When the encrypted database is wired in, "is there a database?" becomes a
 * real question answered by looking at the filesystem, and this enum is removed rather than extended.
 */
enum class DesktopScaffoldOnboardingStage {
    /** First launch surface: what the app is, with a single forward action. */
    Intro,

    /** Second: create a new local space, or bring an existing encrypted database in. */
    DatabaseChoice,

    /** Third: name and style the life dimensions the app will track. */
    DimensionSetup,

    /**
     * Mocked onboarding is done, so the app is treated as usable. Reached after the dimension step
     * reports a (mocked) successful save. Distinct from [Bypassed]: this one ENTERS the app.
     */
    Finished,

    /**
     * The scaffold is not driving this run, so the pre-existing startup gates decide. Used by tests
     * that exercise the passphrase/unlock/initialization gates directly.
     */
    Bypassed,
}

/** The two paths offered at [DesktopScaffoldOnboardingStage.DatabaseChoice]. */
enum class DesktopDatabaseChoice {
    /** Start empty. */
    CreateNew,

    /** Adopt an existing encrypted database (Android's export). */
    ImportExisting,
}

/**
 * DesktopStartupRuntimeState.

 */
data class DesktopStartupRuntimeState(
    val hasPassphraseConfigured: Boolean,
    val hasDatabaseArtifacts: Boolean,
    val databaseLifecycleReady: Boolean,
    val sessionOpen: Boolean,
    val focusModeOnboardingCompleted: Boolean,
    val securityFilePath: String,
    val databaseFilePath: String,
    val lockoutSecondsRemaining: Long = 0L,
    /**
     * How far the onboarding scaffold has advanced. Defaults to [DesktopScaffoldOnboardingStage.Intro]
     * so a fresh launch always opens onboarding - the scaffold has no database to skip it with.
     */
    val scaffoldOnboardingStage: DesktopScaffoldOnboardingStage =
        DesktopScaffoldOnboardingStage.Intro,
    /**
     * True when the persisted security state could not be read. Carried separately from
     * [hasPassphraseConfigured] because that is `false` both for a fresh install and for an
     * unreadable store - the two cases must resolve to different gates.
     */
    val securityStateUnavailable: Boolean = false,
)

/**
 * Which startup gate the desktop app must show before reaching the main UI.
 */
enum class DesktopStartupMode {
    /** SCAFFOLD: first onboarding surface. See [DesktopScaffoldOnboardingStage]. */
    OnboardingIntro,

    /** SCAFFOLD: create a new space or import an existing encrypted database. */
    OnboardingDatabaseChoice,

    /** Passphrase has not been configured yet; user must set it up. */
    SetupPassphrase,

    /** Passphrase is configured but the session is locked; user must unlock. */
    UnlockPassphrase,

    /** Database lifecycle is not yet ready; initialization is required. */
    InitializeDatabase,

    /** Passphrase and database ready; focus-mode onboarding remains. */
    FocusModeSelection,

    /** All startup steps complete; the app is ready to use. */
    Ready,

    /**
     * The persisted security state could not be read, so the app cannot tell whether a passphrase is
     * configured. It refuses to proceed rather than guessing: resolving this to [SetupPassphrase]
     * would let an unreadable store be bypassed by setting a new passphrase.
     */
    SecurityStateUnavailable,
}

/**
 * Picks the startup gate to show, in order: unavailable security state → onboarding scaffold →
 * passphrase setup → unlock → database init → focus-mode onboarding → ready.
 *
 * The unavailable branch is **first and mandatory**. This is a boolean `when {}` with `else -> Ready`,
 * so the compiler does *not* force it: an unreadable snapshot carries `hasPassphraseConfigured = false`,
 * and without this branch it would silently resolve to [DesktopStartupMode.SetupPassphrase] - the
 * fail-open this milestone exists to close, reintroduced while looking fixed.
 *
 * The scaffold stages sit BEFORE passphrase setup, and that order is the point: the old order began at
 * passphrase setup because it assumed a local store to protect. Now the first thing the app does is
 * explain itself and ask where the data comes from.
 *
 * [DesktopScaffoldOnboardingStage.Bypassed] deliberately lets the old gates decide, so the resolver's
 * original behaviour is still reachable and still tested. [DesktopScaffoldOnboardingStage.Finished]
 * skips the scaffold but NOT the gates: a finished scaffold means "this run has been through
 * onboarding", not "everything is configured", and treating it as the latter would be the fail-open
 * this file exists to prevent.
 */
fun resolveDesktopStartupMode(runtimeState: DesktopStartupRuntimeState): DesktopStartupMode =
    when {
        runtimeState.securityStateUnavailable -> {
            DesktopStartupMode.SecurityStateUnavailable
        }

        runtimeState.scaffoldOnboardingStage == DesktopScaffoldOnboardingStage.Intro -> {
            DesktopStartupMode.OnboardingIntro
        }

        runtimeState.scaffoldOnboardingStage == DesktopScaffoldOnboardingStage.DatabaseChoice -> {
            // Android asks this question, and runs the dimension step, INSIDE its own database-init screen,
            // so the desktop renders no card of its own here: a new install meets the SHARED screen. The
            // stage is still what the resolver reads, so the ready callback advances it when that screen
            // finishes - without that, the mode would never move past this branch.
            DesktopStartupMode.InitializeDatabase
        }

        runtimeState.scaffoldOnboardingStage == DesktopScaffoldOnboardingStage.DimensionSetup -> {
            // The dimension step lives inside the shared database-init screen on Android. The desktop's own
            // pre-database copy is also the order that silently loses the choice if the app is quit before
            // unlock, so the shared screen is the flow and this stage renders no surface of its own.
            DesktopStartupMode.InitializeDatabase
        }

        // NOTE: Finished deliberately has NO branch here. It means "the scaffold has run", not
        // "everything is configured", so it falls through to the gates below, which still enforce the
        // passphrase, session, database and focus checks. Giving it its own `-> Ready` branch would
        // open an unconfigured install with no passphrase and no database.

        !runtimeState.hasPassphraseConfigured -> {
            DesktopStartupMode.SetupPassphrase
        }

        !runtimeState.sessionOpen -> {
            DesktopStartupMode.UnlockPassphrase
        }

        !runtimeState.databaseLifecycleReady -> {
            DesktopStartupMode.InitializeDatabase
        }

        !runtimeState.focusModeOnboardingCompleted -> {
            DesktopStartupMode.FocusModeSelection
        }

        else -> {
            DesktopStartupMode.Ready
        }
    }

/**
 * Aggregates the settings/bootstrap/security/database snapshots into the
 * single state the startup-mode resolver consumes.
 *
 * [focusModeOnboardingCompleted] arrives as its own value rather than inside a settings snapshot, because
 * the row it comes from now lives in the encrypted database and is read through the SHARED mapper
 * (`AppPreferencesState.focusModeOnboardingCompleted`). The gate used to read the desktop's
 * `DesktopSettingsSnapshot`, which meant the startup gate and the settings screen could disagree about
 * whether the user had already picked a preset - one answered from `app_settings`, the other from a
 * `Properties` payload that does not survive a restart. The value is the fact; who stores it is not this
 * function's business.
 */
fun buildDesktopStartupRuntimeState(
    focusModeOnboardingCompleted: Boolean,
    bootstrapSnapshot: DesktopBootstrapSnapshot,
    securitySnapshot: DesktopSecuritySnapshot,
    databaseSnapshot: DesktopDatabaseSnapshot,
    sessionOpen: Boolean,
    nowEpochMillis: Long,
    /**
     * Scaffold progress, owned by the UI as transient state. It is a parameter rather than a value
     * derived from the snapshots on purpose: the snapshots describe a mock store that has no notion of
     * "onboarding done", and inventing one there would give the mock a durability it must not have.
     */
    scaffoldOnboardingStage: DesktopScaffoldOnboardingStage =
        DesktopScaffoldOnboardingStage.Intro,
): DesktopStartupRuntimeState {
    val lockoutSecondsRemaining =
        ((securitySnapshot.lockedUntilEpochMillis ?: 0L) - nowEpochMillis)
            .div(1000L)
            .coerceAtLeast(0L)
    // The file existing IS the lifecycle being ready. This used to be
    // `bootstrap.databaseLifecycleReady && database.hasArtifacts && database.initCompleted`, where the
    // first term was a flag stored beside the file and the other two read a path that is deliberately
    // never created - so the conjunction was permanently false and the gate's database check could
    // never report ready. The desktop also cannot create a database itself (its migration logic
    // deliberately does not exist), so a present file is exactly the evidence there is.
    val databaseLifecycleReady = databaseSnapshot.initCompleted
    return DesktopStartupRuntimeState(
        hasPassphraseConfigured = securitySnapshot.hasPassphraseConfigured,
        hasDatabaseArtifacts = databaseSnapshot.hasArtifacts,
        databaseLifecycleReady = databaseLifecycleReady,
        sessionOpen = sessionOpen,
        focusModeOnboardingCompleted = focusModeOnboardingCompleted,
        securityFilePath = "",
        databaseFilePath = databaseSnapshot.databaseFilePath,
        lockoutSecondsRemaining = lockoutSecondsRemaining,
        scaffoldOnboardingStage = scaffoldOnboardingStage,
        // Carried straight from the store's read: this is the signal that distinguishes a fresh
        // install from an unreadable one.
        securityStateUnavailable =
            securitySnapshot.availability == DesktopSecurityAvailability.UNAVAILABLE,
    )
}

/**
 * DesktopDatabaseCreateOutcome.
 *
 * What a first-run create actually did. A [Refused] is never reported as a [Created], and the two are
 * distinct types precisely so the caller cannot collapse them: the gate this serves used to print
 * "Desktop database initialized." on a button press that had done nothing at all, which is the one
 * thing a user must never be told about a database they do not have.
 */
internal sealed interface DesktopDatabaseCreateOutcome {
    /** The encrypted database now exists at the target path and was re-opened through the real guards. */
    data class Created(
        val schemaVersion: Int,
    ) : DesktopDatabaseCreateOutcome

    /**
     * The create did not happen.
     *
     * @param issue the shared boot issue, for logs. Its `detailMessage` is a machine-readable code, not
     *   prose and never a path.
     * @param userMessage what the user is shown: one actionable sentence, no path, no exception text.
     */
    data class Refused(
        val issue: DatabaseBootIssue,
        val userMessage: String,
    ) : DesktopDatabaseCreateOutcome
}

/**
 * Creates the encrypted database for a first run, and says honestly whether it worked.
 *
 * The smallest slice of first-run database creation: the desktop's Initialize action can finally
 * make a database, through the SAME [PayanamDatabaseOpener.create] every other platform would use, at
 * the SAME path the open and transfer paths already use. Before this, `onInitializeDatabase` re-read
 * the database snapshot and changed nothing, so a brand-new Linux install had no way forward.
 *
 * **The parent directory is created here, and that is load-bearing, not tidying.**
 * [PayanamDatabaseOpener.create] refuses a target whose directory does not exist
 * (`parent_not_found`), which is precisely the state of a fresh install: nothing in the desktop
 * creates `<root>/database/` before this call (only the logs, runtime and security directories are
 * created elsewhere, and the security one only when a passphrase is saved). Without this line the
 * create would refuse EVERY fresh install and the slice would be dead code.
 *
 * **The passphrase is the one the session was established with, never re-prompted.**
 * [resolveDesktopStartupMode] reaches [DesktopStartupMode.InitializeDatabase] only after
 * `hasPassphraseConfigured && sessionOpen`, so by construction a passphrase exists and the desktop
 * still holds it - `DesktopDatabaseSessionController.passphraseForTransfer()` is the one accessor, and
 * it hands back a COPY. This function does not own that array and does not wipe it; the caller does,
 * as it does for every other passphrase this flow passes on.
 *
 * **The returned session is closed here.** [PayanamDatabaseOpener.create] returns an open session as
 * its proof that the file it built passes the real guards, and its contract makes the caller the
 * owner. The app's own session is established afterwards by the flow's existing success path
 * (`DesktopDatabaseSessionController.openAndInstall`), so keeping this one would mean two live
 * connections to the same file and a handle nothing ever releases.
 *
 * **No gate transition happens here.** This returns the fact; moving the flow forward is the caller's
 * existing success path - re-reading the database snapshot, where `initCompleted` is derived from this
 * very file - so there is still exactly one way past this gate.
 *
 * @param databasePath the target file: the same value `DesktopDatabaseStore.getDatabaseFilePath()`
 *   returns, i.e. `<root>/database/payanam.db`, so the create, the open and the transfer gateway cannot
 *   disagree about where the database lives.
 * @param passphrase the caller's array; read, never retained and never logged.
 * @param create the create, injected so a test drives the accepted and the refused path without the
 *   native SQLCipher payload.
 * @param log the structured log sink; keys and issue codes only - never the passphrase, never a path.
 */
internal fun createDesktopFirstRunDatabase(
    databasePath: Path,
    passphrase: CharArray,
    create: (Path, String) -> DatabaseOpenResult = { path, key -> PayanamDatabaseOpener.create(path, key) },
    log: (Map<String, Any?>) -> Unit = {},
): DesktopDatabaseCreateOutcome {
    databaseDirectoryRefusal(databasePath)?.let { issue ->
        log(
            mapOf(
                "issue" to issue.type.name,
                "detail" to DESKTOP_DIRECTORY_UNWRITABLE,
                "outcome" to "refused",
            ),
        )
        return DesktopDatabaseCreateOutcome.Refused(
            issue = issue,
            userMessage = DESKTOP_DIRECTORY_UNWRITABLE_MESSAGE,
        )
    }
    // The one unavoidable String copy: `create` takes the passphrase as text, and a String cannot be
    // zeroed. It exists only as the argument and is never stored, logged or copied further - the same
    // concession `DesktopDatabaseOpenerAdapter` already makes on the open path.
    val result = create(databasePath, passphrase.concatToString())
    return when (result) {
        is DatabaseOpenResult.Opened -> {
            val schemaVersion = result.session.schemaVersion
            result.session.close()
            log(mapOf("schemaVersion" to schemaVersion, "outcome" to "created"))
            DesktopDatabaseCreateOutcome.Created(schemaVersion)
        }

        is DatabaseOpenResult.Refused -> {
            // WARN, and the issue TYPE plus its code: a failed first run is expected and actionable, and
            // an ERROR here would be a lie about severity. The user is told separately, below.
            log(
                mapOf(
                    "issue" to result.issue.type.name,
                    "detail" to (result.issue.detailMessage ?: "none"),
                    "outcome" to "refused",
                ),
            )
            DesktopDatabaseCreateOutcome.Refused(
                issue = result.issue,
                userMessage = describeDesktopDatabaseCreateRefusal(result.issue),
            )
        }
    }
}

/**
 * The one-line answer for the gate's status area.
 *
 * EXISTS because the gate's own Initialize handler prints an unconditional success string
 * (`DesktopStartupGate.kt`, in the `onInitialize` click lambda), which is how a button that did nothing
 * reported that it had initialised the database. The call site must publish THIS instead, so a refusal
 * reaches the user rather than being overwritten by a success the create never achieved.
 */
internal fun desktopDatabaseCreateStatusMessage(outcome: DesktopDatabaseCreateOutcome): String =
    when (outcome) {
        is DesktopDatabaseCreateOutcome.Created -> "Desktop database created."
        is DesktopDatabaseCreateOutcome.Refused -> outcome.userMessage
    }

/**
 * One actionable sentence per refusal, chosen by the shared [DatabaseBootIssueType] and - where the case
 * is create-specific - by the detail code.
 *
 * The two are matched separately because `:core:database-jvm` sub-classifies several failures as the
 * same `OPEN_FAILED`, and "a database is already there" and "this folder cannot be written" are the two
 * refusals a user can actually DO something about. Collapsing them into one sentence would leave the
 * user at a gate with no next action, which is the dead end this slice exists to remove.
 */
private fun describeDesktopDatabaseCreateRefusal(issue: DatabaseBootIssue): String =
    when (issue.detailMessage) {
        // A database appeared between the snapshot and the click - most often because an import
        // completed. Creating is the wrong action and the existing one is right.
        CORE_DETAIL_ALREADY_EXISTS -> {
            "A database already exists at this location. Unlock it instead of creating a new one."
        }

        CORE_DETAIL_PARENT_NOT_FOUND,
        CORE_DETAIL_PARENT_NOT_WRITABLE,
        CORE_DETAIL_NO_PARENT_DIRECTORY,
        DESKTOP_DIRECTORY_UNWRITABLE,
        -> {
            DESKTOP_DIRECTORY_UNWRITABLE_MESSAGE
        }

        // The SQLCipher native payload is not on the classpath. Not the user's to fix, so it says so
        // rather than suggesting they retry.
        CORE_DETAIL_NATIVE_UNAVAILABLE -> {
            "This build cannot encrypt a database: the encryption engine is missing. Import an " +
                "existing database instead."
        }

        else -> {
            when (issue.type) {
                // A leftover log beside the target. The guards refuse it, and retrying cannot help.
                DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING -> {
                    "Leftover database log files sit beside this location. Move them aside, then try again."
                }

                // Unreachable from a fresh create by construction; stated rather than assumed, because a
                // create that "succeeded" into an unreadable file is exactly what the guards exist to stop.
                DatabaseBootIssueType.DB_TOO_OLD,
                DatabaseBootIssueType.DB_TOO_NEW,
                DatabaseBootIssueType.SCHEMA_INVALID,
                DatabaseBootIssueType.OPEN_FAILED,
                DatabaseBootIssueType.REPAIRABLE_GENERIC,
                DatabaseBootIssueType.NON_REPAIRABLE_GENERIC,
                -> {
                    "The desktop database could not be created. The details are in the session log."
                }
            }
        }
    }

/**
 * The refusal for a database directory that cannot be created, or null when it can.
 *
 * Never throws: the create's contract is that a caller receives a refusal rather than an exception, and
 * an unwritable `~/.local/share` must read as "could not create the database", not as a crash. Only
 * [IOException] and [SecurityException] are caught - a broader catch here would be a swallowed bug.
 */
private fun databaseDirectoryRefusal(databasePath: Path): DatabaseBootIssue? =
    try {
        val parent = databasePath.toAbsolutePath().parent
        when {
            parent == null -> {
                DatabaseBootIssue(DatabaseBootIssueType.OPEN_FAILED, DESKTOP_DIRECTORY_UNWRITABLE)
            }

            // Already there: `create` will report the real problem (an existing FILE at the target, an
            // unwritable directory) with more detail than this layer could.
            Files.isDirectory(parent) -> {
                null
            }

            else -> {
                Files.createDirectories(parent)
                null
            }
        }
    } catch (_: IOException) {
        DatabaseBootIssue(DatabaseBootIssueType.OPEN_FAILED, DESKTOP_DIRECTORY_UNWRITABLE)
    } catch (_: SecurityException) {
        DatabaseBootIssue(DatabaseBootIssueType.OPEN_FAILED, DESKTOP_DIRECTORY_UNWRITABLE)
    }

/**
 * The create-specific refusal codes from `:core:database-jvm`, restated here.
 *
 * `RefusalDetail` is `internal` to that module, so this file cannot read it, and the alternative is
 * losing every actionable case to a single generic sentence. These are matched, never asserted on and
 * never shown to the user, so a rename on the other side can only degrade this mapping to the generic
 * message - it cannot make it say something false. `DESKTOP_DIRECTORY_UNWRITABLE` is prefixed because it
 * is DESKTOP-LOCAL and is not one of them.
 */
private const val CORE_DETAIL_ALREADY_EXISTS = "already_exists"
private const val CORE_DETAIL_PARENT_NOT_FOUND = "parent_not_found"
private const val CORE_DETAIL_PARENT_NOT_WRITABLE = "parent_not_writable"
private const val CORE_DETAIL_NO_PARENT_DIRECTORY = "no_parent_directory"
private const val CORE_DETAIL_NATIVE_UNAVAILABLE = "native_unavailable"
private const val DESKTOP_DIRECTORY_UNWRITABLE = "desktop_directory_unwritable"
private const val DESKTOP_DIRECTORY_UNWRITABLE_MESSAGE =
    "Payanam could not write to its data folder, so no database was created."
