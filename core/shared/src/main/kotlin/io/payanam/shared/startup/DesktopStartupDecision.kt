//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.startup

/**
 * How far the user has advanced through the desktop onboarding scaffolding.
 *
 * It is deliberately NOT persisted, so a restart begins at [Intro] again. That is now only a record of
 * "this process has not been through the steps"; it is NOT what decides whether the tour is shown. The
 * encrypted database is wired in, so "is there a database?" is answered by looking at the filesystem
 * (`hasDatabaseArtifacts`), and a launch with artifacts skips the tour entirely.
 *
 * The tour itself is not a startup GATE on either platform: it is the first step INSIDE the shared
 * `DatabaseInitScreen`, which is where Android renders it. This enum's [Intro] stage used to raise a
 * second copy of the tour ahead of that screen; it no longer routes anywhere, and the stage is kept for
 * the steps that still read it.
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

        runtimeState.scaffoldOnboardingStage != DesktopScaffoldOnboardingStage.Bypassed &&
            !runtimeState.hasDatabaseArtifacts -> {
            // Android's own first clause, unchanged (`MainActivity.resolveShouldShowDatabaseInit`:
            // `!hasDatabaseArtifacts -> true`): nothing on disk means the SHARED database-init screen, which
            // is where the tour lives - `DatabaseInitScreen` renders `OnboardingIntroScreen` as its own first
            // step and the user leaves it for the create/import choice.
            //
            // The desktop used to raise a tour GATE of its own here (`OnboardingIntro`) and then tell the
            // shared screen `onboardingIntroAlreadyCompleted = true` so it would not repeat it: a second copy
            // of the tour, and one Android never had. Android's first-run flow never routes to a separate
            // intro destination - its own screen wiring says so ("today it does not; the flag is the
            // desktop's") - and a returning user was never sent back through it either, because the branch
            // required `!hasDatabaseArtifacts`.
            //
            // [DesktopScaffoldOnboardingStage.Bypassed] is excluded because it means the scaffold is NOT
            // driving this run - the passphrase, unlock and initialization gates decide, which is how the
            // tests that exercise those gates directly mount them. Measured 2026-10-14: without this
            // exclusion the unlock-gate test (a Bypassed state with no artifacts) resolved here instead and
            // composed the database-init screen, and its composition never settled.
            DesktopStartupMode.InitializeDatabase
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
 * What the shared graph is told about the startup gate: the ONE mapping from the resolved decision to the
 * booleans `PayanamNavHost` takes, plus the decision's own name for the log.
 *
 * It lives beside the decision for the same reason the decision does. A platform that keeps its own copy of
 * this mapping is a second place where "what is the user looking at" is answered, free to disagree with the
 * graph - and a platform that has to NAME the decision to use it has kept the vocabulary, if not the logic.
 * [isLandingReady] belongs to the same answer on purpose: with it true the graph opens the landing route and
 * no gate body composes at all.
 */
data class StartupGateInputs(
    val isLandingReady: Boolean,
    val shouldShowPassphraseSetup: Boolean,
    val shouldShowPassphraseUnlock: Boolean,
    val shouldShowDatabaseInit: Boolean,
    val shouldShowFocusModeOnboarding: Boolean,
    val shouldShowSecurityStateUnavailable: Boolean,
    val shouldShowOnboardingIntro: Boolean,
    /** The resolved decision's own name, for logging: the one thing a caller may want to say out loud. */
    val gateName: String,
)

/** Maps the resolved decision to what the graph is told. */
fun startupGateInputs(gate: DesktopStartupMode) =
    StartupGateInputs(
        isLandingReady = gate == DesktopStartupMode.Ready,
        shouldShowPassphraseSetup = gate == DesktopStartupMode.SetupPassphrase,
        shouldShowPassphraseUnlock = gate == DesktopStartupMode.UnlockPassphrase,
        shouldShowDatabaseInit = gate == DesktopStartupMode.InitializeDatabase,
        shouldShowFocusModeOnboarding = gate == DesktopStartupMode.FocusModeSelection,
        shouldShowSecurityStateUnavailable = gate == DesktopStartupMode.SecurityStateUnavailable,
        shouldShowOnboardingIntro = gate == DesktopStartupMode.OnboardingIntro,
        gateName = gate.name,
    )

/**
 * The same answer, reached from the facts.
 *
 * This is the entry point a platform consumes: it resolves the decision and maps it in one step, so a caller
 * never has to hold the decision's type to ask what the graph should be told.
 */
fun startupGateInputs(runtimeState: DesktopStartupRuntimeState) = startupGateInputs(resolveDesktopStartupMode(runtimeState))

/**
 * [inputs] with the LANDING withheld unless the session is genuinely open.
 *
 * WHY THIS EXISTS BESIDE [startupGateInputs]: the resolver reaches `Ready` from
 * [DesktopStartupRuntimeState.sessionOpen], a value a platform supplies - and the shared
 * [io.payanam.domain.port.DatabaseSessionManager] contract forbids feeding it from a UI/Compose flag
 * ("`sessionOpenState` MUST be backed by the session owner's own state — never by a UI/Compose state").
 * A platform that supplies a remembered flag anyway can therefore report `Ready` while nothing is installed,
 * and the graph then composes the app's landing route over an uninstalled data layer - the measured desktop
 * crash `LocalDataLayer.lenses was read before a data layer was installed`.
 *
 * So the platform passes its session OWNER's state here as the second argument, and the landing is the ONE
 * thing withheld: every gate flag the resolver produced is left exactly as it was, because a platform whose
 * session is closed still needs its own gate (unlock, setup or init) to be the one the graph shows. Making
 * [isLandingReady] false is what turns "the app is ready" into "no app route may compose".
 *
 * @param sessionOpen the session owner's own state - never a UI flag derived from it.
 */
fun withLandingBackedBySession(
    inputs: StartupGateInputs,
    sessionOpen: Boolean,
): StartupGateInputs = if (inputs.isLandingReady && !sessionOpen) inputs.copy(isLandingReady = false) else inputs
