//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.theme.PayanamTheme
import io.payanam.desktop.database.DesktopAssistantCollaborators
import io.payanam.desktop.database.DesktopBootstrapDataLayer
import io.payanam.desktop.database.DesktopDatabaseInitCollaborators
import io.payanam.desktop.database.DesktopDatabaseOpenerAdapter
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
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.PassphraseConfigureOutcome
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * The reset's own journey, in process: unlock gate -> forgot-passphrase reset -> the database-init gate.
 *
 * WHY THIS FILE EXISTS. The owner's build-405 log crashed exactly here - after the reset the graph was left
 * composing with facts the reset had invalidated, and the app route came up with only the bootstrap layer
 * installed (`LocalDataLayer.lenses was read before a data layer was installed`). The route the graph takes
 * next is the whole fix, and the only honest way to pin it is to mount the REAL graph with the REAL desktop
 * screens and drive the REAL reset button.
 *
 * WHAT IS REAL HERE. Everything: the shared `PayanamNavHost`, the desktop's own
 * `DesktopPayanamNavHostScreens`, a real `DesktopSecurityStore` over a temporary directory, the real
 * `DesktopPassphraseVault` with a credential configured into it, the real `DesktopForgottenPassphraseReset`
 * over the same file, and the real shared unlock screen whose reset button is clicked. The credential is
 * created here rather than read from a fixture, because nothing in this file needs to type one.
 *
 * WHAT IT CLAIMS. That a completed reset leaves the user on the database-init gate, at the CREATE form -
 * not on the tour, not on the create/import chooser - and that the gate is told it follows a reset
 * (`resumesAfterReset`), which is what makes the passphrase be collected there. And that the platform's own
 * fact-reload duty is entered, because without it the startup decision keeps the facts the reset deleted.
 *
 * WHAT IT DOES NOT CLAIM. That the dimension step saves (that is the shared ViewModel's own test), or that
 * the graph reaches the app afterwards - the desktop half of that is the login-free `onGraphDatabaseReady`
 * path already covered by the shell's own tests.
 */
class DesktopResetContinuationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    /** How many times the shell's fact-reload duty ran; the lambda arrives from the platform's dispatcher. */
    private val resetObservedCalls = AtomicInteger(0)

    private lateinit var logger: UnifiedLogger
    private lateinit var securityDirectory: Path
    private lateinit var databaseFile: File
    private lateinit var vault: DesktopPassphraseVault
    private lateinit var onboardingStore: DesktopOnboardingStore
    private lateinit var sessionController: DesktopDatabaseSessionController

    @Before
    fun setUp() {
        UnifiedLogger.resetForTests()
        logger = UnifiedLogger.initialize(logsDirectory = temporaryFolder.newFolder("reset-logs").toPath())
        AppLogger.install(logger)
        LocalDataLayer.resetForTests()
        // The PRE-SESSION layer, exactly as the desktop's own startup path installs it before the first
        // frame: the init gate reads these repositories while it is constructed.
        LocalDataLayer.install(DesktopBootstrapDataLayer)

        securityDirectory = temporaryFolder.newFolder("reset-security").toPath()
        vault = DesktopPassphraseVault(DesktopSecurityStore(securityDirectory = securityDirectory))
        databaseFile = securityDirectory.resolve("payanam.db").toFile()
        onboardingStore = DesktopOnboardingStore(securityDirectory = securityDirectory)

        // A credential configured into the vault, so the gate under test is the unlock gate and its reset
        // button is live. The passphrase itself is not needed by any assertion here.
        val configured = vault.configurePassphrase("Reset-Continuation!1".toCharArray())
        assertThat(configured).isEqualTo(PassphraseConfigureOutcome.CONFIGURED)

        sessionController =
            DesktopDatabaseSessionController(
                databaseFilePath = { databaseFile.absolutePath },
                open = { path, secret -> DesktopDatabaseOpenerAdapter().openSession(path, secret) },
                logger = logger,
            )
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        logger.close()
        UnifiedLogger.resetForTests()
    }

    /**
     * The reset leg, driven through the real button: the graph must land on the database-init gate at its
     * create form, with no tour and no chooser.
     */
    @Test
    fun `a completed reset continues into the init gate at its create form, with no tour and no chooser`() {
        mountUnlockGate()

        // The unlock gate is up: its reset affordance is the button this journey starts from.
        composeRule.onNodeWithText(strings.dbPassphraseUnlockForgotAction).performClick()
        composeRule.waitForIdle()
        // The confirmation dialog, then the destructive action itself.
        composeRule.onNodeWithText(strings.dbPassphraseUnlockResetConfirm).performClick()
        composeRule.waitForIdle()

        // The reset runs on the shared ViewModel's own dispatcher, so the graph's move is awaited rather than
        // assumed: the create form's title is the observable the gate produces once it is there.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(strings.dbPassphraseSetupTitle).fetchSemanticsNodes().isNotEmpty()
        }

        // The create form - where the passphrase for the database that replaces the wiped one is collected.
        composeRule.onNodeWithText(strings.dbPassphraseSetupTitle).assertIsDisplayed()

        // Not the first-run tour, and not the create/import chooser: the owner's ruling, measured.
        assertNoTour()
        composeRule.onAllNodesWithText(strings.locCreateNewEmptyDatabase).assertCountEquals(0)

        // The platform's fact-reload duty ran, on the callback the graph's own reset path enters.
        assertThat(resetObservedCalls.get()).isEqualTo(1)
    }

    /** A FRESH installation still sees the tour: the marker suppresses it only once it has been finished. */
    @Test
    fun `a first run with no marker still shows the tour at the init gate`() {
        mountInitGate()

        // The tour's FIRST page, which is where a fresh install opens it. Not its forward action: that reads
        // "Next" on the first two slides and only becomes "Get Started" on the last, so a single action label
        // could not tell "the tour is here" from "the tour is on its last page".
        composeRule.onNodeWithText(strings.onboardingSlide1Title).assertIsDisplayed()
    }

    /** The marker is what stops the tour - and it is device-level, so a database wipe cannot clear it. */
    @Test
    fun `a marked installation shows the chooser without the tour`() {
        onboardingStore.markOnboardingCompleted()

        mountInitGate()

        assertNoTour()
        composeRule.onNodeWithText(strings.locCreateNewEmptyDatabase).assertIsDisplayed()
    }

    /**
     * No frame of the tour is on screen.
     *
     * Two labels rather than one, because the tour is a three-slide pager whose controls change with the
     * slide: the first page's title and the skip action are the two that only ever belong to a tour on screen.
     */
    private fun assertNoTour() {
        composeRule.onAllNodesWithText(strings.onboardingSlide1Title).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.onboardingActionSkip).assertCountEquals(0)
    }

    private fun mountUnlockGate() {
        composeRule.setContent {
            PayanamTheme(darkTheme = false) {
                PayanamNavHost(
                    screens = desktopNavHostScreens(),
                    language = UiLanguage.ENGLISH,
                    minimalModeEnabled = false,
                    landingRoute = PayanamTab.Tasks.route,
                    isLandingReady = false,
                    shouldShowPassphraseUnlock = true,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** The init gate, raised the way the platform raises it: no landing, and the init flag set. */
    private fun mountInitGate() {
        composeRule.setContent {
            PayanamTheme(darkTheme = false) {
                PayanamNavHost(
                    screens = desktopNavHostScreens(),
                    language = UiLanguage.ENGLISH,
                    minimalModeEnabled = false,
                    landingRoute = PayanamTab.Tasks.route,
                    isLandingReady = false,
                    shouldShowDatabaseInit = true,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** The desktop's REAL destination bodies over this fixture's own collaborators. */
    private fun desktopNavHostScreens(): DesktopPayanamNavHostScreens =
        DesktopPayanamNavHostScreens(
            onDatabaseResetObserved = { resetObservedCalls.incrementAndGet() },
            databaseInitCollaborators =
                DesktopDatabaseInitCollaborators(
                    sessionController = sessionController,
                    documentSource = AwtDocumentSource(),
                    passphraseVault = vault,
                    databasePathResolver =
                        DesktopDatabasePathResolver(
                            // Injected, never defaulted: the default resolves the host's real data directory.
                            databaseDirectory = { temporaryFolder.newFolder("init-probe").absolutePath },
                        ),
                    onboardingStore = onboardingStore,
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
            settingsCollaborators =
                DesktopSettingsCollaborators(
                    preferences = AppPreferencesState(),
                    unlockTimeoutMinutes = 120,
                    databaseFile = databaseFile,
                ),
            assistantCollaborators =
                DesktopAssistantCollaborators(
                    sessionController = sessionController,
                    databaseFile = databaseFile.toPath(),
                ),
        )
}
