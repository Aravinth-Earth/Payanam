//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.theme.PayanamTheme
import io.payanam.database.jvm.DatabaseOpenResult
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
import io.payanam.presentation.data.UninstalledDataLayer
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
 * The crash a FRESH desktop install hits, reproduced as a failing test.
 *
 * **The crash, from the app's own log.** A machine with no database reaches the shared
 * database-init gate and dies with
 * `IllegalStateException: LocalDataLayer.appSettings was read before a data layer was installed`,
 * thrown by `UninstalledDataLayer.notInstalled` through `databaseInitViewModel` and
 * `DesktopPayanamNavHostScreens.databaseInit`. In one line: the shared init ViewModel is built -
 * and reads `LocalDataLayer.current.appSettings` while being CONSTRUCTED - at a point in startup
 * where the desktop has no session yet, so the holder is still `UninstalledDataLayer`.
 *
 * **Why no existing test sees it.** `DesktopNavHostDestinationRenderTest` mounts these same real
 * destinations through these same real screens, but its `@Before` calls
 * `LocalDataLayer.install(DesktopPayanamDataLayer(session, ...))` over a real SQLCipher file. The
 * init gate therefore has a repository to read and its case passes. That install is also what
 * PRODUCTION does later (`DesktopDatabaseSessionController.openAndInstall` installs on
 * `DatabaseOpenResult.Opened`) - just too late: the gate has already been composed. This file is
 * the missing half of that pair. Where that test answers "does the body render once a layer
 * exists", this one answers "does the gate survive before any layer exists", and the fix has to
 * keep both green.
 *
 * **The premise is asserted, not assumed.** The case pins
 * `LocalDataLayer.current === UninstalledDataLayer` immediately before mounting, and the session
 * controller's `open` lambda is a REFUSAL, because a controller installs the layer only on
 * `DatabaseOpenResult.Opened`. A refused open means nothing on this path can install a layer
 * mid-test, so a green run cannot be an accident of a session opening underneath the assertion.
 *
 * **The failure is caught, then judged - because this harness hands it back.** It is a composition
 * failure, but not an unhandleable one: the fixture's `DesktopSynchronization.runOnUiThread` catches
 * Swing's `ExecutionException` and rethrows the cause, so a throw during the content's first
 * composition arrives on the test thread as the original throwable, and
 * `SkikoComposeUiTest.waitForIdle` calls `UncaughtExceptionHandler.throwUncaught()` so a throw from a
 * later frame is rethrown once, on that same thread. So the case captures the throwable rather than
 * letting it escape, and asserts on the CAUSE CHAIN. A bare "does not throw" assertion would leave
 * the reader with a raw `IllegalStateException` and no idea whether it is this defect or
 * `desktopDestinationNotMigrated`; a bare "is an IllegalStateException" would accept both. Naming
 * the pre-install read is what makes the failure diagnostic.
 *
 * **What the fix has to be.** Not a repository inside the init ViewModel and not a nullable read:
 * the platform's own bootstrap install, so `LocalDataLayer` is in force from the startup path
 * before the graph mounts the gate.
 */
class DesktopDatabaseInitPreLayerMountTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var securityDirectory: Path
    private lateinit var databaseFile: File
    private lateinit var vault: DesktopPassphraseVault
    private lateinit var sessionLogger: UnifiedLogger

    @Before
    fun setUp() {
        // `DesktopPayanamNavHostScreens` reads `UnifiedLogger.getInstance()` in a FIELD INITIALIZER,
        // so the screens cannot even be constructed before the logger exists - and that failure would
        // name logging rather than the data layer. Reset first, so a logger leaked by an earlier class in this
        // shared JVM cannot point here at its own deleted TemporaryFolder.
        UnifiedLogger.resetForTests()
        sessionLogger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("pre-layer-logs").toPath(),
                clock = { Instant.EPOCH },
            )
        // `AppLogger` is deliberately NOT installed here. Its `NoOpUnifiedLogger` default is enough:
        // the only log line on this path is `databaseInitViewModel`'s own, which is emitted AFTER the
        // `appSettings` read that this test is about. Installing a process-wide logger from a test
        // would also leak it into whichever class runs next, which is the state leak
        // `LocalDataLayer.resetForTests` exists to prevent.
        LocalDataLayer.resetForTests()

        securityDirectory = temporaryFolder.newFolder("security").toPath()
        vault = DesktopPassphraseVault(DesktopSecurityStore(securityDirectory = securityDirectory))
        databaseFile = securityDirectory.resolve("payanam.db").toFile()
    }

    @After
    fun tearDown() {
        // Reset in BOTH directions, for the reason `LocalDataLayer.resetForTests` documents: a
        // `@Before` guard stops this class asserting on an earlier class's state, an `@After` guard
        // stops it leaking onward. A failing assertion aborts the test body before it can close the
        // logger, so the reset cannot live at the end of a case.
        LocalDataLayer.resetForTests()
        sessionLogger.close()
        UnifiedLogger.resetForTests()
    }

    @Test
    fun `the database init gate mounts on a fresh install before any data layer is installed`() {
        // The premise, pinned: the holder is the un-installed default, so the read the init factory
        // performs has nothing to resolve to.
        // The fix's premise, pinned rather than the defect's. The defect was that a fresh desktop reached
        // this gate with NO layer installed; the fix is that the desktop installs a PRE-SESSION layer before
        // the first frame, so "the bootstrap layer is in force" is the state this gate actually composes in.
        // With it in the holder the shared screen must render rather than throw.
        LocalDataLayer.install(io.payanam.desktop.database.DesktopBootstrapDataLayer)
        assertThat(LocalDataLayer.current)
            .isSameInstanceAs(io.payanam.desktop.database.DesktopBootstrapDataLayer)

        val thrown = mountInitGate()

        if (thrown == null) return

        assertWithMessage(
            preInstallFailureMessage(thrown),
        ).fail()
    }

    /**
     * The REAL graph, the REAL desktop screens, one gate raised, and no data layer installed.
     *
     * The mode goes through the production seam `startupGateInputs(state)` rather than four booleans
     * written out here, because that seam is what the desktop shell feeds the graph and the shared startup
     * module owns. `isLandingReady = false` is what makes the gate the start destination; with it true the
     * graph opens `landingRoute` and no gate body composes at all.
     *
     * The collaborators are the desktop's own classes over temporary directories, never doubles: the
     * init body reads through the INSTALLED layer (which this test withholds) and the path resolver
     * is injected rather than defaulted, because the default resolves the host's real
     * `~/.local/share/Payanam` directory.
     *
     * Returns the throwable instead of propagating it, because the CAUSE CHAIN is the subject and a
     * frame-boundary choice of `setContent` versus `waitForIdle` is not.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: the assertion is about WHICH throw, not whether one happens
    private fun mountInitGate(): Throwable? =
        try {
            val state =
                DesktopStartupRuntimeState(
                    hasPassphraseConfigured = true,
                    hasDatabaseArtifacts = false,
                    databaseLifecycleReady = false,
                    sessionOpen = true,
                    focusModeOnboardingCompleted = false,
                    securityFilePath = "",
                    databaseFilePath = "",
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
                )
            val inputs = startupGateInputs(state)
            composeRule.setContent {
                PayanamTheme(darkTheme = false) {
                    PayanamNavHost(
                        screens = desktopNavHostScreens(),
                        language = UiLanguage.ENGLISH,
                        minimalModeEnabled = false,
                        landingRoute = PayanamTab.Tasks.route,
                        isLandingReady = false,
                        shouldShowPassphraseSetup = inputs.shouldShowPassphraseSetup,
                        shouldShowPassphraseUnlock = inputs.shouldShowPassphraseUnlock,
                        shouldShowDatabaseInit = inputs.shouldShowDatabaseInit,
                        shouldShowFocusModeOnboarding = inputs.shouldShowFocusModeOnboarding,
                    )
                }
            }
            // The start destination is installed by the graph's own composition, so a body that
            // composes late throws here rather than at `setContent`. Both are the same defect.
            composeRule.waitForIdle()
            null
        } catch (thrown: Throwable) {
            thrown
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
                        DesktopDatabasePathResolver(
                            databaseDirectory = { temporaryFolder.newFolder("init-probe").absolutePath },
                        ),
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
            // This file mounts the INIT destination, so the settings collaborators are supplied with their
            // defaults: nothing it renders reads them, and a real holder would imply a coverage it does not
            // have. `DesktopSettingsDestinationRenderTest` mounts that destination with a real one.
            settingsCollaborators =
                DesktopSettingsCollaborators(
                    // The shell's own shape, supplied rather than defaulted so this file cannot pass on a
                    // state it never measured. No settings destination is mounted here, and the destination
                    // that IS mounted is asserted by `DesktopSettingsDestinationRenderTest`.
                    preferences = AppPreferencesState(),
                    unlockTimeoutMinutes = 120,
                    databaseFile = databaseFile,
                ),
        )

    /**
     * The one session controller these screens are built over, whose `open` REFUSES.
     *
     * Refusing is what makes the premise hold for the whole test rather than only at its first line:
     * `DesktopDatabaseSessionController` installs the shared data layer only on
     * `DatabaseOpenResult.Opened`, so a refusal means the init ViewModel's own session path cannot
     * quietly install a layer and turn this into a passing test. One instance for the class, because
     * two controllers would mean two shutdown targets and two possible installations.
     */
    private val sessionController: DesktopDatabaseSessionController by lazy {
        DesktopDatabaseSessionController(
            databaseFilePath = { databaseFile.absolutePath },
            open = { _, _ ->
                DatabaseOpenResult.Refused(
                    DatabaseBootIssue(
                        type = DatabaseBootIssueType.OPEN_FAILED,
                        detailMessage = "not opened by a pre-install mount test",
                    ),
                )
            },
            logger = sessionLogger,
        )
    }

    /**
     * The failure text, and it names the fix.
     *
     * The whole point of the cause-chain walk is the difference between "the gate threw something"
     * and "the gate threw the pre-install read". So the message quotes the `UninstalledDataLayer`
     * line verbatim when that is what happened, and says plainly when it is not - because a
     * different throw means the gate is still broken by something else and naming the wrong cause
     * would send the fix in the wrong direction.
     */
    private fun preInstallFailureMessage(thrown: Throwable): String {
        val preInstallRead =
            thrown
                .causeChain()
                .mapNotNull { failure -> failure.message }
                .firstOrNull { message -> message.contains(PRE_INSTALL_READ_MARKER) }
        return buildString {
            appendLine(
                "Mounting the shared database-init gate with NO data layer installed must not throw, " +
                    "because that is the state a fresh desktop install is in when it reaches this gate.",
            )
            if (preInstallRead != null) {
                appendLine()
                appendLine("It threw the pre-install read, from UninstalledDataLayer.notInstalled:")
                appendLine("  $preInstallRead")
                appendLine()
                appendLine(
                    "THE FIX IS THE PLATFORM'S BOOTSTRAP INSTALL: install the desktop's repositories " +
                        "with LocalDataLayer.install(...) from the desktop's own startup path, so the " +
                        "holder is in force BEFORE the graph mounts the gate. Do not read the " +
                        "repositories inside the init ViewModel, and do not make the read optional.",
                )
            } else {
                appendLine()
                appendLine("It threw something else, so the pre-install read is not the whole story:")
                appendLine("  $thrown")
                appendLine()
                appendLine("Its full cause chain was:")
                thrown.causeChain().forEachIndexed { index, failure -> appendLine("  [$index] $failure") }
                appendLine()
                appendLine(
                    "Even after the bootstrap install lands, this gate must mount with no layer in force; " +
                        "no other throw is an acceptable outcome for it either.",
                )
            }
        }
    }

    /**
     * This throwable and everything it was wrapped in, outermost first.
     *
     * A composition failure reaches the test thread through at least one wrapper - the Swing
     * `InvocationTargetException`, the frame's own composition context, the test rule's
     * `UncaughtExceptionHandler` - so the pre-install read is only findable below the top. Bounded
     * because a cause chain that cycles would otherwise spin.
     */
    private fun Throwable.causeChain(): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = this
        while (current != null && chain.size < MAX_CAUSE_DEPTH) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private companion object {
        /**
         * The invariant half of `UninstalledDataLayer.notInstalled`'s message.
         *
         * Half rather than the whole line on purpose: the property named in front of it is whichever
         * repository the init factory reads first, and pinning that would make this fail with a
         * confusing "wrong message" the day a field is reordered - while still missing the only thing
         * that matters, which is that the read happened before anything was installed.
         */
        const val PRE_INSTALL_READ_MARKER = "was read before a data layer was installed"

        /** Matches `ErrorPayload`'s own bound, so a cyclic chain cannot spin here. */
        const val MAX_CAUSE_DEPTH = 10
    }
}
