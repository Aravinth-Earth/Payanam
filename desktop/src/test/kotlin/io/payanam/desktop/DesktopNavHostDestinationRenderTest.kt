//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamNavHostScreens
import io.payanam.core.ui.navigation.PayanamRoutes
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.theme.PayanamTheme
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopDatabaseInitCollaborators
import io.payanam.desktop.database.DesktopDatabasePathResolver
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.desktop.database.DesktopPassphraseChangeCollaborators
import io.payanam.desktop.database.DesktopPassphraseUnlockCollaborators
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.desktop.database.DesktopSettingsCollaborators
import io.payanam.desktop.platform.AwtDocumentSource
import io.payanam.desktop.security.DesktopBiometricUnlockPrompt
import io.payanam.desktop.security.DesktopForgottenPassphraseReset
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties

/**
 * The REAL desktop destination bodies, mounted through the REAL shared graph - the seam
 * `DesktopShellGraphProbeTest` deliberately leaves open.
 *
 * **The gap this closes, stated as a defect.** That probe mounts `PayanamNavHost` with its OWN
 * `RecordingScreens`, so it proves the ROUTES, the gates, the bottom bar and the navigation library - and
 * proves nothing about `DesktopPayanamNavHostScreens`, which is the class the desktop actually ships. The
 * only other file that mentions it is `DesktopNavHostParityTest`, and that one READS SOURCE TEXT, so it
 * cannot see a body either. The result is that "wired" and "renders" were independent claims: a destination
 * could be handed a real `databaseInitViewModel(...)` and then call `desktopDestinationNotMigrated(...)` on
 * the next line, and no test in this module would fail. Six bodies still call it, and two of them
 * (`passphraseUnlock`, `databaseInit`) are the ones being wired right now, so that is exactly the hole
 * being cut into.
 *
 * **What is mounted, and what is real.** `PayanamNavHost` is the shared graph and
 * `DesktopPayanamNavHostScreens` is the desktop's real half of `PayanamNavHostScreens`, so a gate flag here
 * routes through the same `startDestination` branch production uses into the same body production runs. The
 * collaborators are the desktop's own classes over a real SQLCipher file and a real security store, never
 * doubles: `DesktopDatabaseSessionController` (as `DesktopDatabaseSessionPortTest` builds it, with the open
 * lambda injected), `AwtDocumentSource`, `DesktopPassphraseVault` / `DesktopForgottenPassphraseReset` /
 * `DesktopBiometricUnlockPrompt` (as `DesktopPassphraseUnlockViewModelTest` builds them), and
 * `DesktopPayanamDataLayer` over a generated fixture (as `DesktopSharedStateExecutionTest` installs it).
 * A fake here would be the wrong instrument: a body that only renders against a fake repository is a
 * different defect than one that renders against the real one.
 *
 * **The three collaborators that must be real, and why each one is unavoidable.**
 *
 *  - `LocalDataLayer`. `databaseInitViewModel` reads `LocalDataLayer.current.appSettings` and
 *    `.lifeDimensions` on the CALLING thread, not inside a coroutine, and `UninstalledDataLayer` throws on
 *    every repository access by design. So the init destination cannot be mounted at all without an
 *    installed layer - that is a finding, not a test convenience, and it is why this file builds a real
 *    encrypted file instead of a repository double.
 *  - `UnifiedLogger`. `DesktopPayanamNavHostScreens` has a `private val logger = UnifiedLogger.getInstance()`
 *    field, so the CONSTRUCTOR throws if `initialize` has not run. Production initializes it from `main()`
 *    before any composable runs, and this file does the same in `@Before` - following
 *    `DesktopStartupGateUiTest`, which resets the singleton in both directions because a test class in a
 *    module shares one JVM.
 *  - `DesktopDatabasePathResolver`. Injected, not defaulted: the default resolves the host's real
 *    `~/.local/share/Payanam` directory, and the init ViewModel health-checks whatever that resolves to. A
 *    render test that probed the developer's own database would be nondeterministic and would touch real
 *    user data. The desktop's own convention for anything that would read the host environment is an
 *    injected lambda, which is why `DesktopDatabasePathResolver` takes a directory lambda at all.
 *
 * **What it does NOT claim.** It renders the three gate bodies that are wired. It says nothing about
 * `passphraseSetup`, `passphraseChange`, `time`, `settings`, `assistant` and `assistantSettings`, which
 * still call `desktopDestinationNotMigrated` on purpose - `DesktopNavHostParityTest` and that function's own
 * KDoc own those gaps. Nor does it exercise the callback flows (`onUnlocked`, `onDatabaseReady`): the graph
 * owns those, and a body that never fires them here cannot be distinguished from one that fires them.
 */
class DesktopNavHostDestinationRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val passphrase = "desktop-nav-dest-render-test"
    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var securityDirectory: Path
    private lateinit var databaseFile: File
    private lateinit var vault: DesktopPassphraseVault
    private lateinit var sessionLogger: UnifiedLogger

    @Before
    fun setUp() {
        // The class under test's `logger` field calls `UnifiedLogger.getInstance()` at CONSTRUCTION, so a
        // missing logger fails every case here with a message about logging rather than about a destination.
        // Reset first, as `DesktopStartupGateUiTest` does, so a leaked instance from an earlier class cannot
        // point this one at a deleted TemporaryFolder.
        UnifiedLogger.resetForTests()
        sessionLogger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("nav-dest-logs").toPath(),
                clock = { Instant.EPOCH },
            )

        // A real encrypted file, because `databaseInitViewModel` reads through the INSTALLED layer and the
        // layer is built over a session. `app_settings` is created so the repository the init ViewModel names
        // is backed by a real table; the generated fixture is version 21, as every other desktop fixture is.
        val databasePath = temporaryFolder.newFolder("database").toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${databasePath.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE app_settings (
                    `key` TEXT NOT NULL,
                    value TEXT,
                    updatedAt TEXT NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        // The holder is process-wide, so a leaked install would let a later class pass on this one's state.
        LocalDataLayer.resetForTests()
        LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))

        securityDirectory = temporaryFolder.newFolder("security").toPath()
        vault = DesktopPassphraseVault(DesktopSecurityStore(securityDirectory = securityDirectory))
        databaseFile = securityDirectory.resolve("payanam.db").toFile()
    }

    /**
     * The one session controller this class's screens are built over.
     *
     * A refused open is the honest default for a render probe - mounting a destination is not an unlock - and
     * one instance for the whole class, because two controllers would mean two shutdown targets and two
     * possible installations of the shared data layer.
     */
    private val sessionController: DesktopDatabaseSessionController by lazy {
        DesktopDatabaseSessionController(
            databaseFilePath = { databaseFile.absolutePath },
            open = { _, _ ->
                DatabaseOpenResult.Refused(
                    DatabaseBootIssue(
                        type = DatabaseBootIssueType.OPEN_FAILED,
                        detailMessage = "not opened by a render probe",
                    ),
                )
            },
            logger = sessionLogger,
        )
    }

    @After
    fun tearDown() {
        // Both holders are reset in BOTH directions for the reason `LocalDataLayer.resetForTests` documents:
        // the `@Before` guard stops this class asserting on state leaked out of an earlier one, and the
        // `@After` guard stops it leaking onward. A failing assertion aborts the test body before it can
        // close the logger, so the reset cannot live at the end of a case.
        LocalDataLayer.resetForTests()
        sessionLogger.close()
        UnifiedLogger.resetForTests()
        if (!session.isClosed) session.close()
    }

    @Test
    fun `the desktop unlock gate renders the shared screen's copy instead of throwing`() {
        mountGate(DesktopStartupMode.UnlockPassphrase)

        // The header, rendered unconditionally by `DatabasePassphraseUnlockScreen` and first in its scrolling
        // column, so `assertIsDisplayed` is safe and is the strongest statement this case makes: the body's own
        // copy reached the tree, in the language the graph was handed.
        composeRule.onNodeWithText(strings.appName).assertIsDisplayed()
        // The primary action and the typed-passphrase label below it. `assertExists` rather than
        // `assertIsDisplayed`: this screen is one `verticalScroll` column of 24dp-spaced sections, so how far
        // down the unlock button falls depends on the test window's height, and a button scrolled past the fold
        // is still the correct screen. Both are rendered unconditionally, so neither depends on which branch
        // the ViewModel's state is in while the lockout read and the file stat are still in flight.
        composeRule.onNodeWithText(strings.dbPassphraseUnlockAction).assertExists()
        // The typed-passphrase label is the one affordance this platform keeps - the biometric slot is empty -
        // so its presence is what distinguishes a real render from a screen that merely failed to throw.
        composeRule.onNodeWithText(strings.dbPassphraseInputLabel).assertExists()
    }

    @Test
    fun `the desktop passphrase setup gate renders the shared screen's copy instead of throwing`() {
        mountGate(DesktopStartupMode.SetupPassphrase)

        // `dbPassphraseSetupTitle` is the one header only the setup body renders: the unlock gate shares
        // `dbPassphraseInputLabel`, so an assertion on that alone could not tell the two apart. Before the body
        // was wired this case failed with the `desktopDestinationNotMigrated` `IllegalStateException` - which is
        // what makes "wired" a measurement rather than a claim.
        composeRule.onNodeWithText(strings.dbPassphraseSetupTitle).assertIsDisplayed()
        // The primary action sits at the foot of the setup column, so how far down it falls depends on the test
        // window's height; a button scrolled past the fold is still the correct screen.
        composeRule.onNodeWithText(strings.dbPassphraseSetupAction).assertExists()
    }

    @Test
    fun `the desktop database init gate renders the shared screen's copy instead of throwing`() {
        mountGate(DesktopStartupMode.InitializeDatabase)

        // The storage icon's contentDescription sits at the top of a `Column` with
        // `verticalArrangement = Center`, so a settled frame can push it outside the test window -
        // `assertExists`, for the same reason `DesktopShellGraphProbeTest` uses it for the bottom bar: a
        // centred-off element is still a correct screen. What the assertion proves is that the body
        // COMPOSED, which is precisely what re-stubbing it to `desktopDestinationNotMigrated` destroys.
        composeRule.onNodeWithContentDescription(strings.settingsDatabaseTitle).assertExists()
    }

    @Test
    fun `the desktop focus mode gate renders the shared screen's copy instead of throwing`() {
        mountGate(DesktopStartupMode.FocusModeSelection)

        // `FocusModeSelectionScreen` takes no ViewModel and no collaborators at all, which makes it the
        // cheapest real body in the file - and the one a re-stub would most plausibly be written against,
        // since nothing in its wiring has to change to break it.
        composeRule.onNodeWithText(strings.chooseYourFocus).assertIsDisplayed()
        composeRule.onNodeWithText(strings.focusModeExplanation).assertExists()
    }

    @Test
    fun `the graph is handed the desktop's own screens, not a test double`() {
        // The property the three cases above assert one destination at a time, pinned in one place: the
        // graph receives the DESKTOP's own `PayanamNavHostScreens` implementation. A future fixture that
        // passed a recording double here would satisfy their copy assertions vacuously, and this is the
        // assertion such a fixture cannot satisfy.
        val screens: PayanamNavHostScreens = desktopNavHostScreens()

        assertThat(screens).isInstanceOf(DesktopPayanamNavHostScreens::class.java)
    }

    /**
     * The passphrase-change destination renders the shared form, and its SUBMIT reaches the shared
     * ViewModel rather than a default-valued screen.
     *
     * Why this case is not in the three above: it is the only one of the four whose body takes a ViewModel
     * the shell MUTATES through, so "it composed" would prove much less than it does for the setup and
     * focus-mode gates. A destination wired with `isSaving = false` and no `onSubmit` would render this
     * screen perfectly while doing nothing at all - which is the exact shape of the stub this file exists to
     * rule out. So the assertion is on the BEHAVIOUR: a mismatched confirmation has to produce the shared
     * error copy, and the only code that can produce that is the shared ViewModel's own `submit`.
     *
     * The mismatch is chosen deliberately over a successful change: it is refused inside the shared
     * ViewModel before any port is touched, so this stays a pure-JVM assertion about wiring - no rekey, no
     * file, no process restart - while still failing if the destination stopped calling `submit`.
     *
     * Reached through `nav.openPassphraseChange()` from the start destination rather than by making it the
     * landing route, so the destination is entered the way the graph actually enters it.
     */
    @Test
    fun `the desktop passphrase change destination submits into the shared ViewModel instead of throwing`() {
        val screens = desktopNavHostScreens()

        // Reached the way the graph reaches it: `landingRoute` is the route the graph opens when no gate is
        // raised, so naming `passphrase_change` here puts this destination on screen through the graph's own
        // route table rather than through a hand-rolled navigation. That matters because the desktop's own
        // settings pane - the entry point on Android - is still one of the destinations that refuses to render,
        // so there is no desktop button to click here, and pretending otherwise would make this test depend
        // on a gap this file does not own. `DesktopShellGraphProbeTest` drives the same destination through a
        // recording double for the same reason.
        composeRule.setContent {
            PayanamTheme(darkTheme = false) {
                PayanamNavHost(
                    screens = screens,
                    language = UiLanguage.ENGLISH,
                    minimalModeEnabled = false,
                    landingRoute = PayanamRoutes.PASSPHRASE_CHANGE,
                    isLandingReady = true,
                )
            }
        }
        composeRule.waitForIdle()

        // The shared screen's own header. Before this body was wired this failed with the
        // `desktopDestinationNotMigrated` IllegalStateException, which is what makes "wired" a measurement.
        composeRule.onNodeWithText(strings.dbPassphraseChangeTitle).assertExists()
        // The three fields and the submit button: the whole form, not a heading alone.
        composeRule.onNodeWithText(strings.dbPassphraseChangeCurrentLabel).assertExists()
        composeRule.onNodeWithText(strings.dbPassphraseChangeNewLabel).assertExists()
        composeRule.onNodeWithText(strings.dbPassphraseChangeConfirmLabel).assertExists()
        composeRule.onNodeWithText(strings.dbPassphraseChangeAction).assertExists()

        // A confirmation that does not match the new passphrase: the shared ViewModel refuses this before
        // any port is touched, and reports it as `mismatch`. If the destination were rendering default-valued
        // state with an empty `onSubmit`, this error text would never appear - which is the whole assertion.
        enterPassphrase(strings.dbPassphraseChangeCurrentLabel, "current-passphrase")
        enterPassphrase(strings.dbPassphraseChangeNewLabel, "NewPassphrase123!")
        enterPassphrase(strings.dbPassphraseChangeConfirmLabel, "DifferentPassphrase123!")
        composeRule.onNodeWithText(strings.dbPassphraseChangeAction).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(strings.dbPassphraseErrorMismatch).assertExists()
    }

    /**
     * Types into the field carrying [label], which is how the shared screen names all three passphrase
     * inputs - there are no distinct test tags on them.
     *
     * `performTextInput` rather than `performTextReplacement`: the field starts empty, and clearing a
     * default first would be asserting on the composable's initial state rather than on the wiring.
     */
    private fun enterPassphrase(
        label: String,
        value: String,
    ) {
        composeRule.onNodeWithText(label).performTextInput(value)
        composeRule.waitForIdle()
    }

    /**
     * Mounts the REAL graph with the REAL desktop screens and exactly one gate raised.
     *
     * The mode goes through the desktop's own `desktopNavGateFlags` projection rather than four booleans
     * written out here, because that projection is what `DesktopApp.kt` feeds the graph and
     * `DesktopNavGateFlagsTest` owns: at most one gate is ever true, and re-deciding that here would be a
     * second implementation of the rule. `isLandingReady = false` is what makes the gate the start
     * destination - with it true the graph opens `landingRoute` and no gate body composes at all, which is
     * the mistake a reader of this fixture could most easily make.
     */
    private fun mountGate(mode: DesktopStartupMode) {
        val flags = desktopNavGateFlags(mode)
        val screens = desktopNavHostScreens()
        composeRule.setContent {
            // `PayanamTheme`, unlike `DesktopShellGraphProbeTest` which can skip it: these are Material3
            // screens and that probe's bodies are `BasicText`. The production shell supplies the same theme.
            PayanamTheme(darkTheme = false) {
                PayanamNavHost(
                    screens = screens,
                    language = UiLanguage.ENGLISH,
                    minimalModeEnabled = false,
                    landingRoute = PayanamTab.Tasks.route,
                    isLandingReady = false,
                    shouldShowPassphraseSetup = flags.shouldShowPassphraseSetup,
                    shouldShowPassphraseUnlock = flags.shouldShowPassphraseUnlock,
                    shouldShowDatabaseInit = flags.shouldShowDatabaseInit,
                    shouldShowFocusModeOnboarding = flags.shouldShowFocusModeOnboarding,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * The desktop's real destination bodies, with the desktop's real collaborators.
     *
     * `DesktopDatabaseInitCollaborators` takes the session controller, the document source and the vault -
     * the same three `DesktopApp.kt` supplies, built here over the fixture rather than over a live session.
     * `DesktopPassphraseUnlockCollaborators` takes the vault, the prompt, the reset and the database file,
     * and the reset is built over the SAME `databaseFile` the unlock gate is handed, so a reset cannot delete
     * an artifact family the gate never opened - the drift `DesktopPassphraseUnlockViewModelTest` pins by
     * identity. `databasePathResolver` points at an empty directory: the default would resolve the host's
     * real one.
     */
    private fun desktopNavHostScreens(): DesktopPayanamNavHostScreens =
        DesktopPayanamNavHostScreens(
            databaseInitCollaborators =
                DesktopDatabaseInitCollaborators(
                    sessionController = sessionController,
                    // The real AWT source. The init body's `onImportFileSelected` is a no-op, so no dialog is
                    // ever opened - only a picker a test could not answer would be a problem.
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
            // The settings destination takes its own collaborators, and this file asserts about the GATE
            // bodies rather than this one - so the holder is supplied with its defaults, which are empty for
            // the same reason every other member here is the real thing: nothing this file mounts reads them.
            // `DesktopSettingsDestinationRenderTest` mounts this destination with a real holder.
            settingsCollaborators =
                DesktopSettingsCollaborators(
                    // Undefaulted in the class for the same reason the other three collaborators are real
                    // objects here: a default would be a state this file never measured. These three are the
                    // values the SHELL would pass, and this file mounts no settings destination - which is
                    // what `DesktopSettingsDestinationRenderTest` exists for.
                    preferences = AppPreferencesState(),
                    unlockTimeoutMinutes = 120,
                    databaseFile = databaseFile,
                ),
        )
}
