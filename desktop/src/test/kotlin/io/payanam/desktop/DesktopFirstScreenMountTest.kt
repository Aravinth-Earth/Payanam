//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.theme.PayanamTheme
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.desktop.database.DesktopBootstrapDataLayer
import io.payanam.desktop.database.DesktopDatabaseInitCollaborators
import io.payanam.desktop.database.DesktopDatabasePathResolver
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.desktop.database.DesktopPassphraseChangeCollaborators
import io.payanam.desktop.database.DesktopPassphraseUnlockCollaborators
import io.payanam.desktop.database.DesktopSettingsCollaborators
import io.payanam.desktop.platform.AwtDocumentSource
import io.payanam.desktop.security.DesktopBiometricUnlockPrompt
import io.payanam.desktop.security.DesktopForgottenPassphraseReset
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.startup.DesktopScaffoldOnboardingStage
import io.payanam.shared.startup.DesktopStartupRuntimeState
import io.payanam.shared.startup.startupGateInputs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.time.Instant

/**
 * The desktop's FIRST screen, mounted through the REAL graph against a data root that belongs to the test.
 *
 * **The gap this closes.** Every other mount in this module starts somewhere past the first screen:
 * `DesktopNavHostDestinationRenderTest` raises a gate with `scaffoldOnboardingStage = Bypassed` and
 * `DesktopDatabaseInitPreLayerMountTest` mounts the database-init gate specifically to watch it survive
 * before a layer exists. Neither drives the state a stranger's machine is actually in - an empty root,
 * nothing configured - through the real graph to the screen the app opens on. So "the app starts" was
 * covered only by a HEADED run under Xvfb (`desktop-loop.sh`, `smoke-desktop.sh`), which photographs a
 * frame and greps a log line; a frame cannot be compared against an assertion, which is why the parity
 * work needs this path in-process.
 *
 * **What is real here.** `PayanamNavHost` is the shared graph and `DesktopPayanamNavHostScreens` is the
 * desktop's real half of it, so the gate routing is the same branch production takes. The collaborators
 * are the desktop's own classes, built over this test's directories: `DesktopDatabaseSessionController`
 * over a file in the test's own security directory, `DesktopPassphraseVault` over a real security store,
 * and `DesktopDatabasePathResolver` handed a directory lambda pointing at the test's data root. The
 * resolver matters most: its DEFAULT resolves the host's real `~/.local/share/Payanam`, so a mount that
 * took the default would probe the developer's machine rather than the state under test.
 *
 * **The premise is measured, not assumed.** The state is asserted to be a FRESH one before mounting - the
 * data root is checked to hold no database artifacts, and `startupGateInputs` is asserted to resolve that
 * state to the onboarding intro - so a green run cannot be an accident of the root quietly holding
 * something the gate then skipped over.
 *
 * **What it does NOT claim.** It mounts the first screen only. Whether the tour advances, whether the
 * later gates render, and whether the shell behaves under a real window are other tests' claims
 * (`OnboardingIntroScreenTest`, `DesktopNavHostDestinationRenderTest`, `smoke-desktop.sh`).
 */
class DesktopFirstScreenMountTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var dataRoot: File
    private lateinit var securityDirectory: Path
    private lateinit var databaseFile: File
    private lateinit var vault: DesktopPassphraseVault
    private lateinit var sessionLogger: UnifiedLogger

    @Before
    fun setUp() {
        // `DesktopPayanamNavHostScreens` reads `UnifiedLogger.getInstance()` in a FIELD INITIALIZER, so the
        // screens cannot be constructed before the logger exists - and that failure would name logging rather
        // than the screen under test. Reset first, so a logger leaked by an earlier class in this shared JVM
        // cannot point here at its own deleted TemporaryFolder.
        UnifiedLogger.resetForTests()
        sessionLogger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("first-screen-logs").toPath(),
                clock = { Instant.EPOCH },
            )
        // Deliberately NOT installed: no data layer exists on the machine this test describes, and the first
        // screen must not need one. If it ever does, the run fails with `UninstalledDataLayer.notInstalled`,
        // which is the honest answer for an empty root rather than something to paper over with a fixture.
        LocalDataLayer.resetForTests()

        dataRoot = temporaryFolder.newFolder("data-root")
        securityDirectory = dataRoot.toPath().resolve("security")
        securityDirectory.toFile().mkdirs()
        vault = DesktopPassphraseVault(DesktopSecurityStore(securityDirectory = securityDirectory))
        databaseFile = securityDirectory.resolve("payanam.db").toFile()
    }

    @After
    fun tearDown() {
        // Reset in BOTH directions, for the reason `LocalDataLayer.resetForTests` documents: the `@Before`
        // guard stops this class asserting on state leaked out of an earlier one, and this guard stops it
        // leaking onward. A failing assertion aborts the test body, so it cannot live at the end of a case.
        LocalDataLayer.resetForTests()
        sessionLogger.close()
        UnifiedLogger.resetForTests()
    }

    @Test
    fun `an empty test-owned root reaches the shared database-init screen, whose first step is the tour`() {
        // The premise, measured: nothing on disk, so nothing can route the shell past the first screen.
        assertThat(dataRoot.walkTopDown().filter { it.isFile }.toList()).isEmpty()
        assertThat(freshState(premiseCheck = true).let { startupGateInputs(it).shouldShowDatabaseInit }).isTrue()
        // And the tour is NOT raised as a gate of its own - the change under test. If this ever flips back, the
        // desktop has re-acquired the second copy of the tour that Android never had.
        assertThat(freshState(premiseCheck = true).let { startupGateInputs(it).shouldShowOnboardingIntro }).isFalse()

        mountFreshShell()

        // The first slide's own title, read from the shipped copy table rather than typed in - so a copy
        // change breaks this test instead of quietly making it assert nothing. It is asserted HERE, inside the
        // shared screen, because that is where the tour is rendered on both platforms.
        composeRule.onNodeWithText(strings.onboardingSlide1Title).assertIsDisplayed()
        // The mid-tour action is on the first page, so its presence is what distinguishes the tour from a
        // screen that merely failed to throw.
        composeRule.onNodeWithText(strings.onboardingActionNext).assertIsDisplayed()
    }

    @Test
    fun `the data root the shell resolves into belongs to this test`() {
        val resolver = DesktopDatabasePathResolver(databaseDirectory = { dataRoot.absolutePath })

        // The shell's database path is inside the root this test created, never the host's real directory -
        // the claim "against a data root that belongs to the test" made checkable rather than conventional.
        assertThat(File(resolver.databaseFilePath()).canonicalPath)
            .startsWith(temporaryFolder.root.canonicalPath)
        // And the security store the vault was handed is inside it too, so no half of the desktop's state
        // can escape the test's own directory.
        assertThat(securityDirectory.toFile().canonicalPath)
            .startsWith(temporaryFolder.root.canonicalPath)
    }

    /**
     * The state a stranger's machine is in: nothing configured, nothing on disk. The gate this resolves to is
     * the SHARED database-init screen, because nothing on disk is the whole question - the scaffold stage is
     * carried here for fidelity with the running app, not because it decides anything.
     */
    private fun freshState(premiseCheck: Boolean = false): DesktopStartupRuntimeState =
        DesktopStartupRuntimeState(
            hasPassphraseConfigured = false,
            hasDatabaseArtifacts = false,
            databaseLifecycleReady = false,
            sessionOpen = false,
            focusModeOnboardingCompleted = false,
            securityFilePath = if (premiseCheck) "security" else securityDirectory.toString(),
            databaseFilePath = if (premiseCheck) "database" else databaseFile.absolutePath,
            scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Intro,
        )

    /**
     * Mounts the real graph with the real desktop screens over this test's own directories.
     *
     * `isLandingReady = false` is what makes the gate the START DESTINATION: with it true the graph opens
     * `landingRoute` and no gate body composes at all - the mistake a reader of this fixture could most
     * easily make, and the one that would make this file assert nothing while staying green.
     */
    private fun mountFreshShell() {
        val screens = desktopNavHostScreens()
        // The layer the shell installs before a session exists (`DesktopMain.kt`), because the shared
        // database-init screen reads the INSTALLED layer. Without it the mount throws
        // "LocalDataLayer.appSettings was read before a data layer was installed" - which is how this test
        // announced that the gate had moved from the tour to that screen.
        LocalDataLayer.install(DesktopBootstrapDataLayer)
        composeRule.setContent {
            PayanamTheme(darkTheme = false) {
                val gateInputs = startupGateInputs(freshState())
                PayanamNavHost(
                    screens = screens,
                    language = UiLanguage.ENGLISH,
                    minimalModeEnabled = false,
                    landingRoute = PayanamTab.Tasks.route,
                    isLandingReady = false,
                    shouldShowPassphraseSetup = gateInputs.shouldShowPassphraseSetup,
                    shouldShowPassphraseUnlock = gateInputs.shouldShowPassphraseUnlock,
                    shouldShowDatabaseInit = gateInputs.shouldShowDatabaseInit,
                    shouldShowFocusModeOnboarding = gateInputs.shouldShowFocusModeOnboarding,
                    shouldShowSecurityStateUnavailable = gateInputs.shouldShowSecurityStateUnavailable,
                    shouldShowOnboardingIntro = gateInputs.shouldShowOnboardingIntro,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * The one session controller these screens are built over.
     *
     * A refused open is the honest default: mounting the first screen is not an unlock, and this state has
     * no database to open. One instance for the whole class, because two controllers would mean two shutdown
     * targets and two possible installations of the shared data layer.
     */
    private val sessionController: DesktopDatabaseSessionController by lazy {
        DesktopDatabaseSessionController(
            databaseFilePath = { databaseFile.absolutePath },
            open = { _, _ ->
                DatabaseOpenResult.Refused(
                    DatabaseBootIssue(
                        type = DatabaseBootIssueType.OPEN_FAILED,
                        detailMessage = "no database exists in this test's root",
                    ),
                )
            },
            logger = sessionLogger,
        )
    }

    /** The desktop's real destination bodies, with the desktop's real collaborators. */
    private fun desktopNavHostScreens(): DesktopPayanamNavHostScreens =
        DesktopPayanamNavHostScreens(
            // No fact-reload hook: these cases mount the graph, not the reset leg that fires it.
            onDatabaseResetObserved = {},
            databaseInitCollaborators =
                DesktopDatabaseInitCollaborators(
                    sessionController = sessionController,
                    // A TEST-OWNED marker, never the default: the tour's completion WRITES through this
                    // store, and the default resolves the operator's real data directory.
                    onboardingStore = DesktopOnboardingStore(securityDirectory = temporaryFolder.newFolder().toPath()),
                    documentSource = AwtDocumentSource(),
                    passphraseVault = vault,
                    databasePathResolver =
                        DesktopDatabasePathResolver(databaseDirectory = { dataRoot.absolutePath }),
                ),
            passphraseUnlockCollaborators =
                DesktopPassphraseUnlockCollaborators(
                    vault = vault,
                    biometricPrompt = DesktopBiometricUnlockPrompt,
                    forgottenPassphraseReset = DesktopForgottenPassphraseReset(vault, databaseFile.toPath()),
                    sessionController = sessionController,
                    databaseFile = databaseFile,
                ),
            passphraseChangeCollaborators =
                DesktopPassphraseChangeCollaborators(
                    vault = vault,
                    sessionController = sessionController,
                ),
            language = UiLanguage.ENGLISH,
            // This file mounts the FIRST screen, so the settings destination is not composed. Its holder is
            // supplied with the values the SHELL would pass, over this test's own files, so nothing here can
            // read the host's real state even if a later change mounts it.
            settingsCollaborators =
                DesktopSettingsCollaborators(
                    preferences = AppPreferencesState(),
                    unlockTimeoutMinutes = 120,
                    databaseFile = databaseFile,
                ),
        )

    private companion object {
        /** The shipped English table - the same instance the graph builds from [UiLanguage.ENGLISH]. */
        val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)
    }
}
