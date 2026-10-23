//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.startup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the startup decision now that it lives in shared code, so both platforms resolve the same gate from the
 * same rules.
 *
 * Every branch gets a case, because the resolver is a priority `when`: a clause that stops firing does not throw,
 * it silently lets a LATER clause answer - which is how an unreadable security state once resolved to
 * "set up a passphrase" and let the store be bypassed. Two of the cases below pin traps the resolver's own KDoc
 * calls out: the intro runs only when there is nothing to return to, and `Finished` is not "everything is
 * configured".
 */
class DesktopStartupDecisionTest {
    private fun state(
        securityStateUnavailable: Boolean = false,
        scaffoldOnboardingStage: DesktopScaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
        hasDatabaseArtifacts: Boolean = true,
        hasPassphraseConfigured: Boolean = true,
        databaseLifecycleReady: Boolean = true,
        sessionOpen: Boolean = true,
        focusModeOnboardingCompleted: Boolean = true,
    ) = DesktopStartupRuntimeState(
        hasPassphraseConfigured = hasPassphraseConfigured,
        hasDatabaseArtifacts = hasDatabaseArtifacts,
        databaseLifecycleReady = databaseLifecycleReady,
        sessionOpen = sessionOpen,
        focusModeOnboardingCompleted = focusModeOnboardingCompleted,
        securityFilePath = "security",
        databaseFilePath = "database",
        scaffoldOnboardingStage = scaffoldOnboardingStage,
        securityStateUnavailable = securityStateUnavailable,
    )

    @Test
    fun `an unreadable security state refuses before anything else is considered`() {
        // The guard is FIRST and mandatory: with every other fact in place, this must still refuse rather than
        // reach `Ready`, or a store that cannot be read would open the app.
        val mode = resolveDesktopStartupMode(state(securityStateUnavailable = true))

        assertThat(mode).isEqualTo(DesktopStartupMode.SecurityStateUnavailable)
    }

    @Test
    fun `the first run opens the shared database-init screen, which owns the tour`() {
        // Android's first clause, unchanged: nothing on disk resolves to the shared database-init screen, and
        // the tour is that screen's own first step - NOT a startup gate of its own. The desktop used to raise a
        // separate `OnboardingIntro` here and then suppress the shared screen's copy, so the tour existed twice
        // on the desktop and once on Android. The scaffold stage is deliberately not part of the question.
        val mode =
            resolveDesktopStartupMode(
                state(
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Intro,
                    hasDatabaseArtifacts = false,
                    hasPassphraseConfigured = false,
                    sessionOpen = false,
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.InitializeDatabase)
    }

    @Test
    fun `the tour is never a gate of its own, at any stage`() {
        // The whole point of the change: no combination of facts may raise `OnboardingIntro`. The tour is the
        // shared screen's step, so a resolver that still answers `OnboardingIntro` has re-introduced the second
        // copy. Checked across the stage the desktop used to route from and the one it advanced to.
        listOf(
            DesktopScaffoldOnboardingStage.Intro,
            DesktopScaffoldOnboardingStage.DatabaseChoice,
            DesktopScaffoldOnboardingStage.DimensionSetup,
            DesktopScaffoldOnboardingStage.Finished,
            DesktopScaffoldOnboardingStage.Bypassed,
        ).forEach { stage ->
            listOf(false, true).forEach { artifacts ->
                val mode =
                    resolveDesktopStartupMode(
                        state(
                            scaffoldOnboardingStage = stage,
                            hasDatabaseArtifacts = artifacts,
                        ),
                    )

                assertThat(mode).isNotEqualTo(DesktopStartupMode.OnboardingIntro)
            }
        }
    }

    @Test
    fun `a bypassed scaffold leaves the passphrase and unlock gates in charge`() {
        // `Bypassed` means the scaffold is not driving this run - it is how the tests that exercise the
        // passphrase, unlock and initialization gates mount them directly. So the database-init clause must not
        // fire for it, however empty the disk is: a state with a configured passphrase and no session must still
        // reach the UNLOCK gate. Measured 2026-10-14: without the exclusion this resolved to
        // `InitializeDatabase`, the unlock-gate test composed the database-init screen instead, and that
        // composition never settled.
        val bypassed =
            resolveDesktopStartupMode(
                state(
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
                    hasDatabaseArtifacts = false,
                    hasPassphraseConfigured = true,
                    sessionOpen = false,
                ),
            )

        assertThat(bypassed).isEqualTo(DesktopStartupMode.UnlockPassphrase)

        val bypassedWithoutPassphrase =
            resolveDesktopStartupMode(
                state(
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
                    hasDatabaseArtifacts = false,
                    hasPassphraseConfigured = false,
                    sessionOpen = false,
                ),
            )

        assertThat(bypassedWithoutPassphrase).isEqualTo(DesktopStartupMode.SetupPassphrase)
    }

    @Test
    fun `a returning launch with artifacts is not sent back through the tour`() {
        // The stage is transient, so a launch that has artifacts is by definition a returning one; the tour is
        // skipped and the gates below decide.
        val mode =
            resolveDesktopStartupMode(
                state(
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Intro,
                    hasDatabaseArtifacts = true,
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.Ready)
    }

    @Test
    fun `the database choice and dimension stages both resolve to database initialization`() {
        assertThat(
            resolveDesktopStartupMode(
                state(scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.DatabaseChoice),
            ),
        ).isEqualTo(DesktopStartupMode.InitializeDatabase)
        assertThat(
            resolveDesktopStartupMode(
                state(scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.DimensionSetup),
            ),
        ).isEqualTo(DesktopStartupMode.InitializeDatabase)
    }

    @Test
    fun `a finished scaffold is not a configured install`() {
        // `Finished` means the scaffold has run, not that everything is set up: an unconfigured install must
        // still be stopped by the passphrase gate.
        val mode =
            resolveDesktopStartupMode(
                state(
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Finished,
                    hasPassphraseConfigured = false,
                    sessionOpen = false,
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.SetupPassphrase)
    }

    @Test
    fun `a missing passphrase asks for setup`() {
        assertThat(resolveDesktopStartupMode(state(hasPassphraseConfigured = false, sessionOpen = false)))
            .isEqualTo(DesktopStartupMode.SetupPassphrase)
    }

    @Test
    fun `a configured passphrase with a closed session asks to unlock`() {
        assertThat(resolveDesktopStartupMode(state(sessionOpen = false)))
            .isEqualTo(DesktopStartupMode.UnlockPassphrase)
    }

    @Test
    fun `an open session without a ready database asks to initialize`() {
        assertThat(resolveDesktopStartupMode(state(databaseLifecycleReady = false)))
            .isEqualTo(DesktopStartupMode.InitializeDatabase)
    }

    @Test
    fun `an unfinished focus onboarding asks for the preset`() {
        assertThat(resolveDesktopStartupMode(state(focusModeOnboardingCompleted = false)))
            .isEqualTo(DesktopStartupMode.FocusModeSelection)
    }

    @Test
    fun `every other fact satisfied resolves to ready`() {
        assertThat(resolveDesktopStartupMode(state())).isEqualTo(DesktopStartupMode.Ready)
    }

    @Test
    fun `every mode is one of the seven the shared decision can return`() {
        // Migrated from the desktop test that pinned the flag projection: the invariant that mattered was that no
        // mode falls through undecided. With the projection gone the equivalent guard is that the enum the
        // resolver returns still accounts for every gate the flow has, so adding one is a deliberate act.
        assertThat(DesktopStartupMode.entries.map { it.name })
            .containsExactly(
                "SetupPassphrase",
                "UnlockPassphrase",
                "InitializeDatabase",
                "FocusModeSelection",
                "Ready",
                "OnboardingIntro",
                "SecurityStateUnavailable",
            )
    }

    @Test
    fun `the graph is told exactly one gate, and ready is not a gate`() {
        // The mapping is the last thing that can go wrong between the decision and the screen: a caller that
        // receives two raised gates renders two surfaces, and a caller told everything is false renders none.
        val focusIncomplete = startupGateInputs(state(focusModeOnboardingCompleted = false))
        assertThat(focusIncomplete.gateName).isEqualTo("FocusModeSelection")
        assertThat(focusIncomplete.isLandingReady).isFalse()
        assertThat(
            listOf(
                focusIncomplete.shouldShowPassphraseSetup,
                focusIncomplete.shouldShowPassphraseUnlock,
                focusIncomplete.shouldShowDatabaseInit,
                focusIncomplete.shouldShowOnboardingIntro,
                focusIncomplete.shouldShowSecurityStateUnavailable,
            ).count { it },
        ).isEqualTo(0)
        assertThat(focusIncomplete.shouldShowFocusModeOnboarding).isTrue()

        val ready = startupGateInputs(state())
        assertThat(ready.gateName).isEqualTo("Ready")
        assertThat(ready.isLandingReady).isTrue()
        assertThat(
            listOf(
                ready.shouldShowPassphraseSetup,
                ready.shouldShowPassphraseUnlock,
                ready.shouldShowDatabaseInit,
                ready.shouldShowFocusModeOnboarding,
                ready.shouldShowSecurityStateUnavailable,
                ready.shouldShowOnboardingIntro,
            ).count { it },
        ).isEqualTo(0)
    }

    @Test
    fun `every gate the decision can return raises exactly one surface`() {
        // Whichever facts produce it, each of the seven gates must name itself and leave ready false, except
        // the ready gate itself. This is the guard that a new mode cannot arrive without a surface.
        val cases =
            listOf(
                state(securityStateUnavailable = true),
                state(
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Intro,
                    hasDatabaseArtifacts = false,
                    hasPassphraseConfigured = false,
                    sessionOpen = false,
                ),
                state(scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.DatabaseChoice),
                state(hasPassphraseConfigured = false, sessionOpen = false),
                state(sessionOpen = false),
                state(databaseLifecycleReady = false),
                state(focusModeOnboardingCompleted = false),
                state(),
            )
        cases.forEach { facts ->
            val inputs = startupGateInputs(facts)
            assertThat(inputs.gateName).isNotEmpty()
            // At most one surface is raised at any moment: that is what "one gate at a time" means downstream.
            val raised =
                listOf(
                    inputs.shouldShowPassphraseSetup,
                    inputs.shouldShowPassphraseUnlock,
                    inputs.shouldShowDatabaseInit,
                    inputs.shouldShowFocusModeOnboarding,
                    inputs.shouldShowSecurityStateUnavailable,
                    inputs.shouldShowOnboardingIntro,
                ).count { it }
            assertThat(raised).isAtMost(1)
            // Ready is the only gate with no surface, so the two must agree.
            assertThat(inputs.isLandingReady).isEqualTo(raised == 0)
        }
    }

    @Test
    fun `a fresh launch state defaults to the shared database-init screen`() {
        // The default is the contract: a first launch has no stage to resume and nothing on disk, so a state
        // built from nothing but the required facts must open the shared database-init screen - the screen that
        // renders the tour as its own first step. The stage stays at its default because it no longer decides
        // this; the filesystem does.
        val fresh =
            DesktopStartupRuntimeState(
                hasPassphraseConfigured = false,
                hasDatabaseArtifacts = false,
                databaseLifecycleReady = false,
                sessionOpen = false,
                focusModeOnboardingCompleted = false,
                securityFilePath = "security",
                databaseFilePath = "database",
            )

        assertThat(fresh.scaffoldOnboardingStage).isEqualTo(DesktopScaffoldOnboardingStage.Intro)
        assertThat(fresh.securityStateUnavailable).isFalse()
        assertThat(fresh.lockoutSecondsRemaining).isEqualTo(0L)

        assertThat(resolveDesktopStartupMode(fresh)).isEqualTo(DesktopStartupMode.InitializeDatabase)
    }

    @Test
    fun `a returning launch built from the default stage is not sent back through the tour`() {
        // The intro clause's guard is the artifacts, not how the stage was set: with database artifacts on disk the
        // same default stage must resolve past onboarding and let the gates below decide.
        val returning =
            DesktopStartupRuntimeState(
                hasPassphraseConfigured = false,
                hasDatabaseArtifacts = true,
                databaseLifecycleReady = true,
                sessionOpen = true,
                focusModeOnboardingCompleted = true,
                securityFilePath = "security",
                databaseFilePath = "database",
            )

        assertThat(returning.scaffoldOnboardingStage).isEqualTo(DesktopScaffoldOnboardingStage.Intro)
        assertThat(resolveDesktopStartupMode(returning)).isEqualTo(DesktopStartupMode.SetupPassphrase)
    }

    @Test
    fun `the direct mode mapping raises the surface that names the mode`() {
        // Called on the mode itself rather than on facts, so the projection is pinned as a projection: each of the
        // six gate modes names its own gate and raises exactly that one surface, and the ready mode raises none.
        fun assertProjects(
            gate: DesktopStartupMode,
            gateName: String,
            raised: (StartupGateInputs) -> Boolean,
        ) {
            val inputs = startupGateInputs(gate)
            assertThat(inputs.gateName).isEqualTo(gateName)
            assertThat(inputs.isLandingReady).isFalse()
            assertThat(
                listOf(
                    inputs.shouldShowPassphraseSetup,
                    inputs.shouldShowPassphraseUnlock,
                    inputs.shouldShowDatabaseInit,
                    inputs.shouldShowFocusModeOnboarding,
                    inputs.shouldShowSecurityStateUnavailable,
                    inputs.shouldShowOnboardingIntro,
                ).count { it },
            ).isEqualTo(1)
            assertThat(raised(inputs)).isTrue()
        }

        assertProjects(DesktopStartupMode.SetupPassphrase, "SetupPassphrase") { it.shouldShowPassphraseSetup }
        assertProjects(DesktopStartupMode.UnlockPassphrase, "UnlockPassphrase") { it.shouldShowPassphraseUnlock }
        assertProjects(DesktopStartupMode.InitializeDatabase, "InitializeDatabase") { it.shouldShowDatabaseInit }
        assertProjects(DesktopStartupMode.FocusModeSelection, "FocusModeSelection") {
            it.shouldShowFocusModeOnboarding
        }
        assertProjects(DesktopStartupMode.OnboardingIntro, "OnboardingIntro") { it.shouldShowOnboardingIntro }
        assertProjects(DesktopStartupMode.SecurityStateUnavailable, "SecurityStateUnavailable") {
            it.shouldShowSecurityStateUnavailable
        }

        val ready = startupGateInputs(DesktopStartupMode.Ready)
        assertThat(ready.gateName).isEqualTo("Ready")
        assertThat(ready.isLandingReady).isTrue()
        assertThat(
            listOf(
                ready.shouldShowPassphraseSetup,
                ready.shouldShowPassphraseUnlock,
                ready.shouldShowDatabaseInit,
                ready.shouldShowFocusModeOnboarding,
                ready.shouldShowSecurityStateUnavailable,
                ready.shouldShowOnboardingIntro,
            ).count { it },
        ).isEqualTo(0)
    }

    @Test
    fun `the database choice offers exactly creating new or importing an existing database`() {
        // The choice is the scaffold's contract with the user at the database step: both answers must exist for
        // the shared screen to offer them, and no third option may appear without a deliberate act.
        assertThat(DesktopDatabaseChoice.entries.map { it.name })
            .containsExactly("CreateNew", "ImportExisting")
    }

    @Test
    fun `the landing is withheld while the session owner reports no open session`() {
        // The state this guards is the one that produced the desktop crash: the resolver answers Ready because
        // the flag it was FED says a session is open, while the session OWNER holds none. No app route may
        // compose then.
        val ready = startupGateInputs(DesktopStartupMode.Ready)
        assertThat(ready.isLandingReady).isTrue()
        val withheld = withLandingBackedBySession(ready, sessionOpen = false)
        assertThat(withheld.isLandingReady).isFalse()

        // Everything else is left alone. In particular a withheld landing must NOT become "set up a passphrase":
        // that would send the user to a gate for a database they already have.
        assertThat(withheld.copy(isLandingReady = ready.isLandingReady)).isEqualTo(ready)
        assertThat(withheld.gateName).isEqualTo(ready.gateName)

        // With the owner's state agreeing, the resolver's answer stands, unchanged.
        assertThat(withLandingBackedBySession(ready, sessionOpen = true)).isEqualTo(ready)

        // And it can only ever WITHHOLD: a gate is never promoted to a landing.
        val unlock = startupGateInputs(DesktopStartupMode.UnlockPassphrase)
        assertThat(unlock.isLandingReady).isFalse()
        assertThat(withLandingBackedBySession(unlock, sessionOpen = true)).isEqualTo(unlock)
        assertThat(withLandingBackedBySession(unlock, sessionOpen = false)).isEqualTo(unlock)
    }
}
