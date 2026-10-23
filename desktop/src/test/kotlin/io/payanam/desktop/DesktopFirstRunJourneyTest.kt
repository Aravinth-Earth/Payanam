//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.theme.PayanamTheme
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopAssistantCollaborators
import io.payanam.desktop.database.DesktopBootstrapDataLayer
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties

/**
 * The desktop's first-run journey, walked in-process - the same walk Android's `FullJourneyTest` makes.
 *
 * **Why this exists.** The desktop's journey-level proof ran only as a HEADED run: `desktop-loop.sh`
 * launches the built app under Xvfb, drives real clicks and photographs each step. A photographed frame
 * plus a grepped log line cannot be compared against an assertion, so the parity claim mixed two
 * instruments. This walks the same journey the same way Android does - in-process, asserting each screen
 * by the copy it claims - and writes a frame per step to a run directory on the host.
 *
 * **The run renders at the APP's window size, not the harness default.** `createComposeRule()` renders at
 * `runDesktopComposeUiTest`'s default surface, which is the HARNESS and not the product: measured, it wrote
 * 1024x768 frames of a phone-shaped app, and a frame at the wrong size cannot be compared with the Android
 * reference frames this journey exists to mirror. The surface is therefore sized from the SHIPPED constants
 * ([PHONE_WINDOW_WIDTH_DP] and [PHONE_WINDOW_HEIGHT_DP] - 412x915 dp, Android Studio's "Medium Phone"), and
 * that resolution is asserted by its own case, so a change to the shipped window shape fails here rather
 * than silently re-sizing the evidence.
 *
 * **The journey, in order.**
 *
 *  1. FIRST RUN - an empty, test-owned data root, nothing configured. The app opens on the onboarding
 *     tour. The premise is measured first (the root holds no files; `startupGateInputs` resolves the
 *     fresh state to the intro), so a green run cannot be the gate quietly skipping.
 *  2. THE THREE SCREENS - with a session open over an encrypted fixture, the shell lands on Tasks; the
 *     journey then walks Notes and then Journal, asserting each screen by the name THAT SCREEN CLAIMS
 *     (`PayanamTab.<Tab>.title(strings)`) rather than by anything the fixture wrote.
 *
 * **What a "frame" is here.** Each step writes a PNG (a real image: `captureToImage()` plus a skiko
 * encode) and a text record of the strings that were on screen at that moment, which is what a later
 * reviewer can diff. They are the app's own window size, so they line up with the Android reference
 * frames point for point.
 *
 * **What it does NOT claim.** It walks three screens, not the whole Android journey: passphrase change,
 * settings editing, the theme/font switches and the on-device dialogs are other items' claims. The
 * headed run still owns "the app works under a real window".
 */
@OptIn(ExperimentalTestApi::class)
class DesktopFirstRunJourneyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var dataRoot: File
    private lateinit var securityDirectory: Path
    private lateinit var databaseFile: File
    private lateinit var vault: DesktopPassphraseVault
    private lateinit var sessionLogger: UnifiedLogger
    private lateinit var runDirectory: File
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    private val passphrase = "desktop-first-run-journey"

    @Before
    fun setUp() {
        UnifiedLogger.resetForTests()
        sessionLogger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("journey-logs").toPath(),
                clock = { Instant.EPOCH },
            )
        LocalDataLayer.resetForTests()

        dataRoot = temporaryFolder.newFolder("data-root")
        securityDirectory = dataRoot.toPath().resolve("security")
        securityDirectory.toFile().mkdirs()
        vault = DesktopPassphraseVault(DesktopSecurityStore(securityDirectory = securityDirectory))
        databaseFile = securityDirectory.resolve("payanam.db").toFile()

        // The run directory holds the journey's frames. Inside the repo's gitignored build-logs, so the
        // artefacts land where every other verification artefact does rather than in the machine's root.
        // The repo root is found by walking UP from the working directory: Gradle runs a test with the MODULE
        // directory as its CWD, so a relative path would land outside the repo.
        val repoRoot =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { it.resolve("build-logs").isDirectory }
                ?: File("").absoluteFile
        runDirectory =
            File(repoRoot, "build-logs/desktop-journey-${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        sessionLogger.close()
        UnifiedLogger.resetForTests()
        runCatching { if (!session.isClosed) session.close() }
    }

    @Test
    fun `the first-run journey reaches Tasks, Notes and Journal, each asserted by name`() =
        runAtTheAppWindowSize {
            // ---- LEG 1: first run, nothing on disk ------------------------------------------------
            // The premise, measured: whatever else is true, this root holds no files, so no gate can have
            // skipped over a database that was there.
            assertThat(dataRoot.walkTopDown().filter { it.isFile }.toList()).isEmpty()
            // The gate is the SHARED database-init screen - the screen that renders the tour as its own first
            // step - and never a tour gate of its own. Android has no separate intro gate either; the desktop
            // used to, and this is the assertion that keeps it from coming back.
            assertThat(startupGateInputs(freshState()).shouldShowDatabaseInit).isTrue()
            assertThat(startupGateInputs(freshState()).shouldShowOnboardingIntro).isFalse()

            // The bootstrap layer is what the shell installs before a session exists (`DesktopMain.kt`), and the
            // shared database-init screen reads the INSTALLED layer rather than building repositories of its
            // own - so the mount needs it for the same reason the app does.
            LocalDataLayer.install(DesktopBootstrapDataLayer)
            mountShell(freshState(), isLandingReady = false)
            frame("00-first-screen", "the shared database-init screen's tour", copy = strings.onboardingSlide1Title)
            assertNodeShowsIt(strings.onboardingSlide1Title, "the first-run screen names itself")

            // Dispose before the state changes: a mount cannot become a different app while it is composed.
            setContent { }
            waitForIdle()
            installEncryptedFixtureLayer()

            // ---- LEG 2: the three screens ---------------------------------------------------------
            mountShell(configuredState(), isLandingReady = true)

            // The shell lands on Tasks. Asserted by the screen's OWN title, read from the shipped table, so
            // a copy change breaks this rather than quietly asserting nothing.
            assertTab(frames = "01-tasks", tab = PayanamTab.Tasks)

            goToTab(PayanamTab.Notes)
            assertTab(frames = "02-notes", tab = PayanamTab.Notes)

            goToTab(PayanamTab.Journal)
            assertTab(frames = "03-journal", tab = PayanamTab.Journal)

            // The run directory is the deliverable alongside the assertion: four frames and the journey's
            // steps, readable on the host without a device.
            assertThat(runDirectory.listFiles()?.map { it.name }?.toList() ?: emptyList<String>())
                .isNotEmpty()
        }

    /**
     * The resolution this journey renders at is the SHIPPED one, asserted rather than assumed.
     *
     * Without this case the surface size is a line in a fixture, and a fixture that drifted back to the
     * harness default would keep writing frames whose size nothing checked - which is how the first cut of
     * this file shipped 1024x768 evidence for a 412x915 app. Both expectations come from the production
     * constants, so changing the shipped window shape fails here instead of passing quietly.
     */
    @Test
    fun `the journey renders at the app's own window size, not the harness default`() =
        runAtTheAppWindowSize {
            // Same mount as the journey's first leg, layer included: this case exists to pin the SURFACE, and a
            // mount that cannot compose would pin nothing.
            LocalDataLayer.install(DesktopBootstrapDataLayer)
            mountShell(freshState(), isLandingReady = false)

            val frame = onRoot().captureToImage()

            assertThat(frame.width).isEqualTo(PHONE_WINDOW_WIDTH_DP)
            assertThat(frame.height).isEqualTo(PHONE_WINDOW_HEIGHT_DP)
        }

    /**
     * Runs the block on a surface sized to the app's SHIPPED window, so every frame this class writes is the
     * surface the user actually gets. The JUnit rule factory takes no size; this block form does.
     */
    private fun runAtTheAppWindowSize(block: suspend DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(
            width = PHONE_WINDOW_WIDTH_DP,
            height = PHONE_WINDOW_HEIGHT_DP,
        ) { block() }

    // ---- the journey's steps ------------------------------------------------------------------

    /** Walks to a tab the way a user does - by activating the tab control - then asserts that tab. */
    private fun DesktopComposeUiTest.goToTab(tab: PayanamTab) {
        onNodeWithText(tab.title(strings)).performClick()
        waitForIdle()
    }

    private fun DesktopComposeUiTest.assertTab(
        frames: String,
        tab: PayanamTab,
    ) {
        val claimed = tab.title(strings)
        frame(frames, "the ${tab.route} screen", copy = claimed)
        assertNodeShowsIt(claimed, "the ${tab.route} screen names itself")
    }

    private fun DesktopComposeUiTest.assertNodeShowsIt(
        copy: String,
        because: String,
    ) {
        val found = onAllNodesWithText(copy).fetchSemanticsNodes().isNotEmpty()
        check(found) { "$because: nothing on screen carried \"$copy\" (frame: ${runDirectory.path})" }
    }

    /**
     * Records one step of the journey.
     *
     * The strings on screen are written as text because they are what the assertion reads, so a later
     * reviewer can see exactly what the step was looking at; the PNG beside them is the image a person
     * reads. Both are written every step, and neither is inferred from the other.
     */
    private fun DesktopComposeUiTest.frame(
        name: String,
        screen: String,
        copy: String,
    ) {
        val textOnScreen = visibleTexts().joinToString("\n") { "  - $it" }
        File(runDirectory, "$name.txt").writeText(
            buildString {
                appendLine("step: $name")
                appendLine("screen: $screen")
                appendLine("surface: ${PHONE_WINDOW_WIDTH_DP}x$PHONE_WINDOW_HEIGHT_DP (the app's own window)")
                appendLine("asserted copy: $copy")
                appendLine("asserted copy found: ${onAllNodesWithText(copy).fetchSemanticsNodes().isNotEmpty()}")
                appendLine("--- text on screen ---")
                appendLine(textOnScreen)
            },
        )
        File(runDirectory, "$name.image.txt").writeText(captureRootImage(File(runDirectory, "$name.png")))
    }

    /**
     * Every string the semantics tree is currently carrying, so a frame records what was on screen rather
     * than only what the assertion went looking for.
     */
    private fun DesktopComposeUiTest.visibleTexts(): List<String> =
        onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList<String>()
            }.distinct()

    /** Best-effort image capture; returns a sentence describing what happened, never throws. */
    private fun DesktopComposeUiTest.captureRootImage(target: File): String =
        runCatching {
            val bitmap = onRoot().captureToImage()
            val skia =
                org.jetbrains.skia.Image
                    .makeFromBitmap(bitmap.asSkiaBitmap())
            val data = skia.encodeToData() ?: error("encodeToData returned null")
            target.writeBytes(data.bytes)
            "captured ${target.name} (${bitmap.width}x${bitmap.height}, ${target.length()} bytes)"
        }.getOrElse { "not captured: ${it::class.simpleName}: ${it.message}" }

    // ---- mounting ----------------------------------------------------------------------------

    /**
     * Mounts the real graph with the real desktop screens.
     *
     * `isLandingReady = false` makes the gate the start destination; `true` opens `landingRoute`. Both legs go
     * through the shared `startupGateInputs` rather than gate booleans written out here, because that chain is
     * what `DesktopApp.kt` feeds the graph - re-deciding it here would be a second implementation of the rule.
     */
    private fun DesktopComposeUiTest.mountShell(
        state: DesktopStartupRuntimeState,
        isLandingReady: Boolean,
    ) {
        val screens = desktopNavHostScreens()
        setContent {
            PayanamTheme(darkTheme = false) {
                val gateInputs = startupGateInputs(state)
                PayanamNavHost(
                    screens = screens,
                    language = UiLanguage.ENGLISH,
                    minimalModeEnabled = false,
                    landingRoute = PayanamTab.Tasks.route,
                    isLandingReady = isLandingReady,
                    shouldShowPassphraseSetup = gateInputs.shouldShowPassphraseSetup,
                    shouldShowPassphraseUnlock = gateInputs.shouldShowPassphraseUnlock,
                    shouldShowDatabaseInit = gateInputs.shouldShowDatabaseInit,
                    shouldShowFocusModeOnboarding = gateInputs.shouldShowFocusModeOnboarding,
                    shouldShowSecurityStateUnavailable = gateInputs.shouldShowSecurityStateUnavailable,
                    shouldShowOnboardingIntro = gateInputs.shouldShowOnboardingIntro,
                )
            }
        }
        waitForIdle()
    }

    /** A stranger's machine: nothing configured, nothing on disk, the scaffold at its first stage. */
    private fun freshState(): DesktopStartupRuntimeState =
        DesktopStartupRuntimeState(
            hasPassphraseConfigured = false,
            hasDatabaseArtifacts = false,
            databaseLifecycleReady = false,
            sessionOpen = false,
            focusModeOnboardingCompleted = false,
            securityFilePath = securityDirectory.toString(),
            databaseFilePath = databaseFile.absolutePath,
            scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Intro,
        )

    /** A returning machine that is past every gate and has a session open. */
    private fun configuredState(): DesktopStartupRuntimeState =
        DesktopStartupRuntimeState(
            hasPassphraseConfigured = true,
            hasDatabaseArtifacts = true,
            databaseLifecycleReady = true,
            sessionOpen = true,
            focusModeOnboardingCompleted = true,
            securityFilePath = securityDirectory.toString(),
            databaseFilePath = databaseFile.absolutePath,
            scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
        )

    /**
     * A real encrypted database for the second leg, plus the shared data layer over it.
     *
     * Real rather than a double, for the reason `DesktopNavHostDestinationRenderTest` records: a body that
     * only renders against a fake repository is a different defect from one that renders against the real
     * one, and the task list reads `tags`/`task_tags`, so the fixture must carry them.
     */
    private fun installEncryptedFixtureLayer() {
        val databasePath = dataRoot.toPath().resolve("journey.db")
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
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS tags (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    usage_count INTEGER NOT NULL DEFAULT 0,
                    last_used_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS task_tags (
                    task_id TEXT NOT NULL,
                    tag_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY(task_id, tag_id)
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        LocalDataLayer.resetForTests()
        LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))
    }

    private val sessionController: DesktopDatabaseSessionController by lazy {
        DesktopDatabaseSessionController(
            databaseFilePath = { databaseFile.absolutePath },
            open = { _, _ ->
                DatabaseOpenResult.Refused(
                    DatabaseBootIssue(
                        type = DatabaseBootIssueType.OPEN_FAILED,
                        detailMessage = "the journey drives the graph, not the open path",
                    ),
                )
            },
            logger = sessionLogger,
        )
    }

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

    private companion object {
        val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)
    }
}
