//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.theme.PayanamTheme
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
import io.payanam.shared.startup.DesktopScaffoldOnboardingStage
import io.payanam.shared.startup.DesktopStartupRuntimeState
import io.payanam.shared.startup.startupGateInputs
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * ONE desktop unlock, and the post-unlock callback counted exactly once - the case
 * `DesktopNavHostDestinationRenderTest` explicitly disclaims.
 *
 * **What was missing before this file existed, in two sentences.** The wire was already there:
 * `DesktopApp.kt:1022` registers `onPassphraseUnlocked = { unlockScope.launch {
 * DesktopDatabaseSessionController.reconcileProcessSessionOnResume() } }` on the shared graph, so every
 * desktop unlock had a post-unlock step that nothing counted. No desktop test supplied that callback -
 * `DesktopNavHostDestinationRenderTest` mounts this very unlock destination and states in its own KDoc that
 * it does not exercise the callback flows (`onUnlocked`, `onDatabaseReady`) because "a body that never fires
 * them here cannot be distinguished from one that fires them" - so a body that stopped firing the callback
 * entirely, or fired it twice, passed every gate in this module.
 *
 * **What is real here.** The mount is `DesktopNavHostDestinationRenderTest`'s own idiom verbatim: the shared
 * `PayanamNavHost` with the desktop's real `DesktopPayanamNavHostScreens`, the start destination chosen by the
 * production seam `startupGateInputs(state)`, and the desktop's real
 * collaborators over a real `DesktopSecurityStore` and a real `DesktopDatabaseSessionController`. The unlock
 * is the real one: the typed passphrase goes into the shared screen's own field, the shared
 * `DatabasePassphraseUnlockViewModel` verifies it against the desktop's real vault and opens a real SQLCipher
 * file through the controller, which installs the shared data layer - the same sequence production runs,
 * minus the window.
 *
 * **WHY THE CALLBACK HERE IS AN EQUIVALENT, NOT PRODUCTION'S LITERAL, AND WHAT THAT STILL PROVES.** The
 * production register is a lambda literal inside a `private` composable in `DesktopApp.kt`, so there is no
 * way to read it as a value from a test without standing up the whole shell; this file therefore SUPPLIES a
 * counting callback in the slot `DesktopApp.kt:1022` fills, and counts it. That is the honest shape of the
 * claim: the callback the host registers is the one the desktop's unlock path runs, and one unlock runs it
 * once. Nothing here reaches an internal by reflection to make that true - the counter is incremented inside
 * the lambda the graph was handed, from the ViewModel's own dispatcher, which is why it is an
 * `AtomicInteger` and not a `var`.
 *
 * **WHY IT IS OPT-IN.** The passphrase is read from a file and is never hardcoded, logged, or put into an
 * assertion message - only its length would be reportable, and nothing here reports it. The file is supplied
 * by the same `-Dpayanam.loop.pwd` property `DesktopImportLoopHarnessTest` reads (that harness's
 * `passphraseFromFile()`, reused verbatim), so one fixture file serves both; only this file's use of it is
 * required here, not the harness's database. A run without it skips this case rather than failing with a
 * message about logging.
 *
 * **WHAT IT DOES NOT CLAIM.** It does not assert that the reconcile runs, only that the slot that launches
 * it is entered once per unlock; and it does not re-open the render file's claim - a body rendering the
 * shared screen is still owned by `DesktopNavHostDestinationRenderTest`.
 */
class DesktopUnlockCallbackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** The fixture passphrase file, exactly as the import loop harness names it. */
    private val passphraseProperty: String? = System.getProperty("payanam.loop.pwd")

    /**
     * The passphrase this case unlocks with.
     *
     * The fixture file is honoured when supplied, because a run that HAS the loop fixture should exercise the
     * same credential as the import harness. But this case builds its OWN vault in a temp directory, so the
     * credential only has to satisfy the shared policy - requiring the external fixture made the assertion
     * skip in every run that did not carry it, which is a gate satisfied vacuously. The fallback is a
     * test-local value for a test-local vault, never the fixture's own secret.
     */
    private val unlockPassphrase: String
        get() = if (passphraseProperty != null) passphraseFromFile() else "Unlock-Callback-Test!1"

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    /**
     * How many times the registered post-unlock callback ran.
     *
     * An `AtomicInteger`, not a `var`: the increment happens on the shared unlock ViewModel's own
     * dispatcher (the one that opened the database), never on the test thread, so a plain field read by the
     * assertion below would be a race rather than a measurement.
     */
    private val unlockCalls = AtomicInteger(0)

    /** The class under test's collaborators read `AppLogger.current`, so this must be installed. */
    private lateinit var logger: UnifiedLogger

    private lateinit var logsDirectory: Path
    private lateinit var securityDirectory: Path
    private lateinit var databaseFile: File
    private lateinit var vault: DesktopPassphraseVault
    private lateinit var sessionLogger: UnifiedLogger

    /**
     * The desktop's own controller, wired to a REAL open.
     *
     * The render test's controller refuses every open, which is right for a render probe and wrong here: a
     * refusal means `openSessionAndSucceed` reports `open_failed` instead of calling `onSuccess`, so the
     * callback would never fire and the case would pass vacuously. `DesktopDatabaseOpenerAdapter` is the
     * desktop's own adapter, and a file that does not exist yet is CREATED by `openAndInstall` - which is
     * what a first-run desktop unlock actually does.
     */
    private val sessionController: DesktopDatabaseSessionController by lazy {
        DesktopDatabaseSessionController(
            databaseFilePath = { databaseFile.absolutePath },
            open = { path, secret -> DesktopDatabaseOpenerAdapter().openSession(path, secret) },
            logger = sessionLogger,
        )
    }

    @Before
    fun setUp() {
        UnifiedLogger.resetForTests()
        logsDirectory = temporaryFolder.newFolder("unlock-callback-logs").toPath()
        logger = UnifiedLogger.initialize(logsDirectory = logsDirectory)
        sessionLogger = logger
        AppLogger.install(logger)
        LocalDataLayer.resetForTests()

        // A fresh store per case, and the passphrase CONFIGURED into it rather than typed against a guess:
        // this is the same store the unlock gate verifies against, so the credential that answers the unlock
        // is the one read from the fixture file.
        securityDirectory = temporaryFolder.newFolder("unlock-callback-security").toPath()
        vault = DesktopPassphraseVault(DesktopSecurityStore(securityDirectory = securityDirectory))
        // The SAME file the forgot-passphrase reset is built over, so the two cannot disagree about which
        // artifact family an unlock touches - the identity `DesktopPassphraseUnlockViewModelTest` pins.
        databaseFile = securityDirectory.resolve("payanam.db").toFile()

        val configured = vault.configurePassphrase(unlockPassphrase.toCharArray())
        // A SKIP and not an assertion: whether the fixture's own passphrase satisfies the shared policy
        // (length + four character classes) is a property of the fixture, not of this wiring. The reason code
        // is named so a skip is self-explaining - the passphrase itself is never in the message.
        assumeTrue(
            "The fixture passphrase was refused by SharedPassphrasePolicy: $configured",
            configured == PassphraseConfigureOutcome.CONFIGURED,
        )
    }

    @After
    fun tearDown() {
        // The composition is torn down FIRST, and deliberately: the graph retains the unlock ViewModel with
        // an `onRelease` hook that disposes its coroutine scope, and that hook only runs when the composition
        // is disposed. A teardown that reset the holders while the scope was still live left the unlock
        // coroutine running into the next test class - which surfaced as an "uncaught exceptions before the
        // test started" failure in a completely different file.
        runCatching {
            composeRule.setContent { }
            composeRule.waitForIdle()
        }
        // Every step guarded and in this order, for the two reasons the model file states: the assumption
        // above can skip before the logger exists (an unguarded `lateinit` close turns a skip into a
        // failure), and the session must be released before the process-wide holders are reset.
        runCatching { sessionController.closeAndUninstall() }
        LocalDataLayer.resetForTests()
        AppLogger.resetForTests()
        if (this::logger.isInitialized) {
            logger.close()
            UnifiedLogger.resetForTests()
        }
    }

    /**
     * THE GATE: one unlock invokes the registered post-unlock callback EXACTLY once.
     *
     * One unlock, driven the way a user drives it: the passphrase is typed into the shared screen's own
     * field and the screen's own primary action is clicked, so the only code that can reach the callback is
     * `DatabasePassphraseUnlockScreen`'s `viewModel.unlock(passphrase) { ... onPassphraseUnlocked() }` running
     * to success, and this file's counter. The assertion is the SPECIFIC number, not `isGreaterThan(0)`:
     * "exactly once" is the clause, and a body that fired the callback on every recomposition - or twice
     * after one success - would satisfy a lower bound while breaking the desktop's own launch of a
     * reconcile.
     */
    @Test
    fun `one desktop unlock invokes the registered post-unlock callback exactly once`() {
        mountUnlockGate(onPassphraseUnlocked = { unlockCalls.incrementAndGet() })
        val passphrase = unlockPassphrase

        // Mounting the destination is not an unlock: the callback has not been entered yet, so this counts
        // zero and rules out a mount-time fire before the drive below is what put the number where it is.
        assertThat(unlockCalls.get()).isEqualTo(0)

        composeRule.onNodeWithText(strings.dbPassphraseInputLabel).performTextInput(passphrase)
        composeRule.waitForIdle()

        // `performScrollTo` before the click, and for the reason `DesktopNavHostDestinationRenderTest`
        // gives when it asserts this very button with `assertExists`: the unlock column is one
        // `verticalScroll` of 24dp-spaced sections, so how far down the action falls depends on the test
        // window's height. Scrolling is the fix for that; a bare `performClick` on a button past the fold is
        // a gesture at coordinates outside the window.
        composeRule.onNodeWithText(strings.dbPassphraseUnlockAction).performScrollTo()
        composeRule.onNodeWithText(strings.dbPassphraseUnlockAction).performClick()
        composeRule.waitForIdle()

        // The unlock is ASYNCHRONOUS - the shared ViewModel verifies and opens the database on its own
        // scope - so the callback is waited for in real time rather than sampled once after a fixed pause.
        // The timeout NAMES what was awaited, because a bare TimeoutCancellationException would send the
        // reader back into the body to work out which wait expired.
        runBlocking { await("the registered post-unlock callback") { unlockCalls.get() > 0 } }

        assertThat(unlockCalls.get()).isEqualTo(1)
    }

    /**
     * Mounts the REAL graph with the REAL desktop screens, unlock gate raised, and [onPassphraseUnlocked]
     * handed to the graph exactly where `DesktopApp.kt:1022` hands it.
     *
     * `DesktopNavHostDestinationRenderTest`'s `mountGate`, with one change: the callback parameter. The gate
     * flags go through the production seam `startupGateInputs(state)` rather than four booleans written
     * out here, so "at most one gate is ever true" stays owned by the shared startup module; and
     * `isLandingReady = false` is what makes the gate the start destination rather than letting the graph
     * open `landingRoute` with no gate body composed at all.
     */
    private fun mountUnlockGate(onPassphraseUnlocked: () -> Unit) {
        val screens = desktopUnlockScreens()
        composeRule.setContent {
            // `PayanamTheme`, as the render test supplies: these are Material3 screens.
            PayanamTheme(darkTheme = false) {
                val state =
                    DesktopStartupRuntimeState(
                        hasPassphraseConfigured = true,
                        hasDatabaseArtifacts = false,
                        databaseLifecycleReady = false,
                        sessionOpen = false,
                        focusModeOnboardingCompleted = false,
                        securityFilePath = "",
                        databaseFilePath = "",
                        scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
                    )
                val inputs = startupGateInputs(state)
                PayanamNavHost(
                    screens = screens,
                    language = UiLanguage.ENGLISH,
                    minimalModeEnabled = false,
                    landingRoute = PayanamTab.Tasks.route,
                    isLandingReady = false,
                    shouldShowPassphraseSetup = inputs.shouldShowPassphraseSetup,
                    shouldShowPassphraseUnlock = inputs.shouldShowPassphraseUnlock,
                    shouldShowDatabaseInit = inputs.shouldShowDatabaseInit,
                    shouldShowFocusModeOnboarding = inputs.shouldShowFocusModeOnboarding,
                    onPassphraseUnlocked = onPassphraseUnlocked,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * The desktop's real destination bodies, with the desktop's real collaborators.
     *
     * `DesktopNavHostDestinationRenderTest`'s own construction, with the ONE difference that matters here:
     * the session controller [sessionController] opens for real, so the unlock this file drives actually
     * succeeds and installs the shared data layer - which is also what the destination the graph navigates
     * to after the callback reads.
     */
    private fun desktopUnlockScreens(): DesktopPayanamNavHostScreens =
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
                    // Injected, never defaulted: the default resolves the host's real data directory, and no
                    // assertion here should be able to read the developer's own database.
                    databasePathResolver =
                        DesktopDatabasePathResolver(
                            databaseDirectory = { temporaryFolder.newFolder("init-probe").absolutePath },
                        ),
                ),
            passphraseUnlockCollaborators =
                DesktopPassphraseUnlockCollaborators(
                    vault = vault,
                    // The desktop's real prompt, which REFUSES. That is load-bearing here and not
                    // incidental: the shared screen auto-triggers a biometric unlock whenever this prompt
                    // reports available, and that auto-trigger is a SECOND caller of the same callback.
                    // A prompt that refused is why the count here can be exactly one.
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
            // The settings destination is not mounted by this file, so its holder carries the shell's own
            // values rather than defaults - the render test's reason, unchanged: a default would be a state
            // this file never measured.
            settingsCollaborators =
                DesktopSettingsCollaborators(
                    preferences = AppPreferencesState(),
                    unlockTimeoutMinutes = 120,
                    databaseFile = databaseFile,
                ),
        )

    /**
     * The passphrase, from its file, WITHOUT ever logging it.
     *
     * `DesktopImportLoopHarnessTest.passphraseFromFile()`, reused verbatim - same property, same rule: the
     * last non-empty line is the secret and the file's earlier lines are its own label. A harness that asked
     * a human to paste it into a chat would defeat itself, and a test that hardcoded one would put a
     * credential in version control.
     */
    private fun passphraseFromFile(): String =
        Files
            .readAllLines(Path.of(requireNotNull(passphraseProperty)))
            .map { it.trim() }
            .last { it.isNotEmpty() }

    /**
     * Real time, because the shared unlock ViewModel's scope is not this test's dispatcher - and the failure
     * NAMES what was being awaited. The model's `await`, unchanged.
     */
    private suspend fun await(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        try {
            withTimeout(60_000) {
                while (!condition()) delay(25)
            }
        } catch (timedOut: TimeoutCancellationException) {
            throw AssertionError("Timed out after 60s waiting for $what", timedOut)
        }
        assertThat(condition()).isTrue()
    }
}
