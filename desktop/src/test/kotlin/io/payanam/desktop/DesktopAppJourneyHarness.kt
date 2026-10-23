//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.theme.PayanamTheme
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.DesktopDatabaseEncryptionProbe
import io.payanam.database.jvm.PayanamDatabaseOpener
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
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.startup.DesktopScaffoldOnboardingStage
import io.payanam.shared.startup.DesktopStartupRuntimeState
import io.payanam.shared.startup.startupGateInputs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties

/** Long enough for a real dispatcher hop on a loaded CI box, short enough to fail rather than hang. */
private const val JOURNEY_TIMEOUT_MS = 10_000L

/**
 * The shared in-process harness the desktop's JOURNEY tests mount through.
 *
 * **Why this exists.** `DesktopFirstRunJourneyTest` grew a complete full-app harness - a test-owned data
 * root, a real encrypted database, the real desktop nav-host screens and a shell mount that drives the
 * shared graph the way `DesktopApp.kt` does - and then it was the only class that could use it, because
 * every piece was private. The Tasks and Time journeys need the SAME harness: mounting a screen against
 * a hand-built fixture would be a different instrument from the one the first-run journey proved, and a
 * second copy of the mount is exactly how two journey tests start measuring two different apps. So the
 * harness is extracted here, verbatim, and both classes extend it.
 *
 * **What a subclass gets.** A `TemporaryFolder` rule and the `@Before`/`@After` lifecycle that stand up a
 * test-owned data root and a real `UnifiedLogger`; the real desktop nav-host screens and session
 * controller; [mountShell], which installs the shared graph over the real desktop screens at the app's
 * OWN window size; the tab navigation and copy-assertion helpers; the per-step frame writer; and the two
 * database fixtures ([installEncryptedFixtureLayer] for a minimal hand-built schema,
 * [installProductionFixtureLayer] for a real create-path database with the canonical dimensions seeded).
 *
 * **The window size is the app's, not the harness default.** [runAtTheAppWindowSize] sizes the surface
 * from the SHIPPED constants ([PHONE_WINDOW_WIDTH_DP] / [PHONE_WINDOW_HEIGHT_DP] - 411x891 dp, the tested
 * phone's viewport), so a frame written here lines up with the Android reference frames point for point. `DesktopFirstRunJourneyTest` asserts that size by its own case.
 *
 * **Preferences are the caller's choice, deliberately.** `mountShell` provides `LocalAppPreferences` only
 * when handed a state: the first-run journey walks screens that read no preference rows, while the Tasks
 * and Time journeys open `AddTaskScreen` / the time-entry modal, which read the dimension options from
 * that local and disable their save button when it holds none. [loadedPreferences] derives the real state
 * from the installed database through the same `DesktopPreferencesSource` the shell uses, so the journeys
 * do not invent a preference state the app would never have.
 */
@OptIn(ExperimentalTestApi::class)
abstract class DesktopAppJourneyHarness {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    protected lateinit var dataRoot: File
    protected lateinit var securityDirectory: Path
    protected lateinit var databaseFile: File
    private lateinit var vault: DesktopPassphraseVault
    protected lateinit var sessionLogger: UnifiedLogger
    protected lateinit var runDirectory: File
    protected lateinit var connection: Connection
    protected lateinit var session: PayanamDatabaseSession

    /**
     * The passphrase the fixture database is created with.
     *
     * POLICY-VALID on purpose: `SharedPassphrasePolicy` requires 12+ chars with upper, lower, digit and
     * symbol, and the vault REFUSES to write a verifier for anything else - silently, as a
     * `REJECTED_BY_POLICY` outcome. A fixture passphrase that fails the policy leaves the vault with no
     * verifier, and every unlock attempt then takes the store's "verifier missing" path, which records no
     * failure and never locks out.
     */
    protected val passphrase = "Desktop-First-Run-Journey1!"

    /** The shipped English table, so every journey asserts names rather than copy that happens to be hardcoded. */
    protected val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    @Before
    fun setUpHarness() {
        UnifiedLogger.resetForTests()
        sessionLogger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("journey-logs").toPath(),
                clock = { Instant.EPOCH },
            )
        // The shared ViewModels log through `AppLogger.current`, whose default is a no-op. Installing the
        // session logger is production's own wiring (`DesktopMain.kt`) and is what makes a journey's
        // structured markers land in the session log - the cross-platform LOG contract `assertLogged` reads.
        AppLogger.install(sessionLogger)
        LocalDataLayer.resetForTests()

        dataRoot = temporaryFolder.newFolder("data-root")
        securityDirectory = dataRoot.toPath().resolve("security")
        securityDirectory.toFile().mkdirs()
        // The database lives in the DATA root, beside the security directory rather than inside it, so the
        // path the controller opens is the path the init flow's `DesktopDatabasePathResolver` resolves. When
        // the two disagreed, a create wrote one file while the controller opened another.
        databaseFile = dataRoot.toPath().resolve("payanam.db").toFile()
        vault =
            DesktopPassphraseVault(
                store =
                    DesktopSecurityStore(
                        securityDirectory = securityDirectory,
                        databaseFile = { databaseFile },
                    ),
                // The probe is what answers `isEncryptionEnabled()`, which the passphrase-CHANGE flow gates on
                // before it will rekey: a vault without one reports encryption disabled and the change refuses
                // for the wrong reason.
                encryptionProbe = DesktopDatabaseEncryptionProbe(),
                databaseFile = { databaseFile },
            )

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
    fun tearDownHarness() {
        LocalDataLayer.resetForTests()
        AppLogger.resetForTests()
        // Copy the session log into the run directory BEFORE the logger is closed and the TemporaryFolder is
        // reaped, so `build-tools/scripts/parity-log-diff.py` can diff it against the Android log for the
        // same interaction. Best-effort: a missing log must not fail a green case.
        runCatching {
            val log = sessionLogger.getLogPath()
            if (java.nio.file.Files
                    .isRegularFile(log)
            ) {
                java.nio.file.Files.copy(
                    log,
                    File(runDirectory, "session.log").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        sessionLogger.close()
        UnifiedLogger.resetForTests()
        runCatching { if (!session.isClosed) session.close() }
    }

    /**
     * Runs the block on a surface sized to the app's SHIPPED window, so every frame this class writes is the
     * surface the user actually gets. The JUnit rule factory takes no size; this block form does.
     */
    protected fun runAtTheAppWindowSize(block: suspend DesktopComposeUiTest.() -> Unit) =
        runAtWindowSize(widthDp = PHONE_WINDOW_WIDTH_DP, heightDp = PHONE_WINDOW_HEIGHT_DP, block = block)

    /**
     * Runs the block on a surface of an EXPLICIT size, in dp at density 1 (so dp == px on the surface).
     *
     * The journey tests use [runAtTheAppWindowSize]; this exists for the layout-parity probe, which must
     * render at a NON-shipped size - the size a device actually exposes - to compare layouts like for like.
     */
    protected fun runAtWindowSize(
        widthDp: Int,
        heightDp: Int,
        block: suspend DesktopComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = widthDp, height = heightDp) { block() }

    // ---- journey steps ------------------------------------------------------------------------

    /** Walks to a tab the way a user does - by activating the tab control - then asserts that tab. */
    protected fun DesktopComposeUiTest.goToTab(tab: PayanamTab) {
        onNodeWithText(tab.title(strings)).performClick()
        waitForIdle()
    }

    protected fun DesktopComposeUiTest.assertTab(
        frames: String,
        tab: PayanamTab,
    ) {
        val claimed = tab.title(strings)
        frame(frames, "the ${tab.route} screen", copy = claimed)
        assertNodeShowsIt(claimed, "the ${tab.route} screen names itself")
    }

    protected fun DesktopComposeUiTest.assertNodeShowsIt(
        copy: String,
        because: String,
    ) {
        val found = onAllNodesWithText(copy).fetchSemanticsNodes().isNotEmpty()
        check(found) { "$because: nothing on screen carried \"$copy\" (frame: ${runDirectory.path})" }
    }

    /**
     * Waits for a node carrying [text] to exist.
     *
     * A POLL, not an idle wait: the shared ViewModels read the database on a background dispatcher the
     * harness clock does not control (the same fact `SPEC.md` records for the Android tier), so an interaction
     * that triggers a write is followed by a wait on the STATE it should produce rather than a settle.
     */
    protected fun DesktopComposeUiTest.awaitNodeWithText(
        text: String,
        timeoutMillis: Long = JOURNEY_TIMEOUT_MS,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits for a node carrying [description] as its contentDescription to exist. */
    protected fun DesktopComposeUiTest.awaitNodeWithContentDescription(
        description: String,
        timeoutMillis: Long = JOURNEY_TIMEOUT_MS,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits for a node carrying [tag] as its testTag to exist. */
    protected fun DesktopComposeUiTest.awaitNodeWithTag(
        tag: String,
        timeoutMillis: Long = JOURNEY_TIMEOUT_MS,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Waits for a node whose contentDescription CONTAINS [fragment] to exist.
     *
     * The timeline's entry blocks build their description from the dimension label plus a computed range and
     * duration, so the label is a fragment of the block's own name rather than the whole of it.
     */
    protected fun DesktopComposeUiTest.awaitContentDescriptionContaining(
        fragment: String,
        timeoutMillis: Long = JOURNEY_TIMEOUT_MS,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithContentDescription(fragment, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits for every node whose contentDescription contains [fragment] to disappear. */
    protected fun DesktopComposeUiTest.awaitContentDescriptionContainingGone(
        fragment: String,
        timeoutMillis: Long = JOURNEY_TIMEOUT_MS,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithContentDescription(fragment, substring = true).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Waits for every node carrying [text] to disappear - a delete's own confirmation. */
    protected fun DesktopComposeUiTest.awaitNodeGone(
        text: String,
        timeoutMillis: Long = JOURNEY_TIMEOUT_MS,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) { onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    // ---- the cross-platform log contract --------------------------------------------------------

    /**
     * This run's session log, as text.
     *
     * The SAME file format both platforms write (`timestamp | level | n | thread=<name> | source | message
     * | [data]`), which is what makes a marker comparable across platforms:
     * `build-tools/scripts/parity-log-diff.py` diffs two such files.
     */
    protected fun sessionLogText(): String = runCatching { File(sessionLogger.getLogPath().toString()).readText() }.getOrDefault("")

    /**
     * Requires the app's own log to carry the structured marker [source] with [message].
     *
     * This is the LOG half of cross-platform parity: for the same interaction both apps must emit the same
     * success markers, so a behaviour that works on one platform and silently fails on the other shows up as
     * a missing marker or an extra error line rather than as a difference nobody looked for.
     */
    protected fun assertLogged(
        source: String,
        message: String,
    ) {
        val text = sessionLogText()
        check(text.contains("| $source |")) {
            "the session log carries no '$source' marker (log: ${sessionLogger.getLogPath()})"
        }
        check(text.contains(message)) { "the session log carries '$source' but not the message '$message'" }
    }

    /**
     * Records one step of the journey.
     *
     * The strings on screen are written as text because they are what the assertion reads, so a later
     * reviewer can see exactly what the step was looking at; the PNG beside them is the image a person
     * reads. Both are written every step, and neither is inferred from the other.
     */
    protected fun DesktopComposeUiTest.frame(
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
    protected fun DesktopComposeUiTest.visibleTexts(): List<String> =
        onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList<String>()
            }.distinct()

    /** Best-effort image capture; returns a sentence describing what happened, never throws. */
    protected fun DesktopComposeUiTest.captureRootImage(target: File): String =
        runCatching {
            val bitmap = onRoot().captureToImage()
            val skia =
                org.jetbrains.skia.Image
                    .makeFromBitmap(bitmap.asSkiaBitmap())
            val data = skia.encodeToData() ?: error("encodeToData returned null")
            target.writeBytes(data.bytes)
            "captured ${target.name} (${bitmap.width}x${bitmap.height}, ${target.length()} bytes)"
        }.getOrElse { "not captured: ${it::class.simpleName}: ${it.message}" }

    // ---- mounting -----------------------------------------------------------------------------

    /**
     * Mounts the real graph with the real desktop screens.
     *
     * `isLandingReady = false` makes the gate the start destination; `true` opens `landingRoute`. Both legs go
     * through the shared `startupGateInputs` rather than gate booleans written out here, because that chain is
     * what `DesktopApp.kt` feeds the graph - re-deciding it here would be a second implementation of the rule.
     *
     * @param preferences the shared preference rows the screens below read. Null installs NO provider, which
     *   is what the first-run journey wants: the screens it walks read none, and a fabricated state would be
     *   a preference state the app would never hold. The Tasks and Time journeys pass [loadedPreferences],
     *   because their save buttons read the dimension options from this local.
     */
    protected fun DesktopComposeUiTest.mountShell(
        state: DesktopStartupRuntimeState,
        isLandingReady: Boolean,
        preferences: AppPreferencesState? = null,
        language: UiLanguage = UiLanguage.ENGLISH,
        fontScale: Float = 1f,
    ) {
        val screens = desktopNavHostScreens(preferences ?: AppPreferencesState())
        setContent {
            PayanamTheme(darkTheme = false) {
                val gateInputs = startupGateInputs(state)
                val graph: @Composable () -> Unit = {
                    PayanamNavHost(
                        screens = screens,
                        language = language,
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
                val withPreferences: @Composable () -> Unit = {
                    if (preferences == null) {
                        graph()
                    } else {
                        CompositionLocalProvider(LocalAppPreferences provides preferences) { graph() }
                    }
                }
                // The font scale is a SURFACE fact, not an app one: a device's own text-size setting scales
                // every glyph while the dp window stays put, so a layout comparison has to reproduce it or the
                // two platforms are compared at different text sizes.
                if (fontScale == 1f) {
                    withPreferences()
                } else {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale),
                    ) { withPreferences() }
                }
            }
        }
        waitForIdle()
    }

    /** A stranger's machine: nothing configured, nothing on disk, the scaffold at its first stage. */
    protected fun freshState(): DesktopStartupRuntimeState =
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
    protected fun configuredState(): DesktopStartupRuntimeState =
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
     * A returning machine whose session is CLOSED, so the UNLOCK gate is the start destination.
     *
     * The same state as [configuredState] except the session, which is what makes the difference between
     * opening the shell and asking for the passphrase.
     */
    protected fun lockedState(): DesktopStartupRuntimeState = configuredState().copy(sessionOpen = false)

    /**
     * Configures this run's vault with [passphrase], so the unlock gate has a credential to verify against.
     *
     * The fixture layer alone leaves the vault EMPTY, and an empty vault refuses every passphrase rather
     * than refusing the WRONG one - a different claim from the one an unlock journey makes.
     */
    protected fun configureVault() {
        vault.configurePassphrase(passphrase.toCharArray())
    }

    /**
     * A real encrypted database for the second leg, plus the shared data layer over it.
     *
     * Real rather than a double, for the reason `DesktopNavHostDestinationRenderTest` records: a body that
     * only renders against a fake repository is a different defect from one that renders against the real
     * one, and the task list reads `tags`/`task_tags`, so the fixture must carry them.
     *
     * This is the MINIMAL hand-built schema the first-run journey's render walk needs. It is deliberately
     * small: a journey that WRITES (a task, a time entry) must use [installProductionFixtureLayer] instead,
     * because this fixture carries only the three tables that walk reads.
     */
    protected fun installEncryptedFixtureLayer() {
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

    /**
     * A real create-path database at the shipped schema, with the canonical life dimensions seeded, plus
     * the shared data layer over it.
     *
     * A journey that WRITES needs this rather than [installEncryptedFixtureLayer]: the write path touches
     * `tasks` / `time_entries` / `task_occurrences` and the shared ViewModels read about twenty tables
     * between them, so a three-table fixture would fail on the first save. The database is built by
     * `PayanamDatabaseOpener.create` - the production first-run path, which emits every table from the Room
     * schema export - so it cannot be incomplete.
     *
     * `create` deliberately does NOT seed the life dimensions (the app's own dimension setup does), so they
     * are seeded here from the SHARED catalog. Without them the task / time-entry save buttons stay disabled
     * (the screen reads its dimension options from `LocalAppPreferences`) and the foreign keys from
     * `tasks.dimension_id` / `time_entries.dimension_id` to `life_dimensions.id` would reject the write.
     */
    protected fun installProductionFixtureLayer() {
        // The controller OPENS (or creates) the database at its own path and installs the layer, exactly as
        // `DesktopApp` does - so it HOLDS the passphrase the assistant's read-only port and the
        // passphrase-change flow need. A fixture that installed the layer directly left the controller with
        // no passphrase, which is why those surfaces could not be driven.
        val result = runBlocking { sessionController.openAndInstall(passphrase.toCharArray()) }
        check(result is DatabaseOpenResult.Opened) { "the fixture open refused: $result" }
        session = result.session
        session.withSerializedAccess { connection -> seedCanonicalDimensions(connection) }
    }

    /** Inserts the canonical life dimensions the write paths reference, from the SHARED catalog. */
    private fun seedCanonicalDimensions(connection: Connection) {
        connection
            .prepareStatement(
                """
                INSERT INTO life_dimensions
                    (id, `key`, label, description, color, icon, sortOrder, isActive, weight, createdAt, updatedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 'T0', 'T0')
                """.trimIndent(),
            ).use { statement ->
                DimensionTaxonomyCatalog.entries.forEach { definition ->
                    statement.setString(1, definition.id)
                    statement.setString(2, definition.slug)
                    statement.setString(3, definition.fallbackLabel)
                    statement.setString(4, definition.fallbackDescription)
                    statement.setString(5, definition.defaultColorHex)
                    statement.setString(6, definition.defaultIconKey)
                    statement.setInt(7, definition.sortOrder)
                    statement.setDouble(8, definition.defaultWeight)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }

    /**
     * The real shared preference state, derived from the installed database exactly as the shell does.
     *
     * `DesktopPreferencesSource` is the desktop's own reader of the `app_settings` rows and the
     * `life_dimensions` catalog, feeding the SHARED `mapAppPreferences` - so this is the state the app
     * publishes after unlock, not a fixture. `reload()` is suspend (it reads the rows and the catalog), so it
     * is driven to completion here; the value it publishes is what [mountShell] installs.
     */
    protected fun loadedPreferences(systemLanguageTag: String = "en"): AppPreferencesState {
        val source =
            DesktopPreferencesSource(
                sessionLogger = sessionLogger,
                systemLanguageTag = systemLanguageTag,
                dimensionRows = {
                    LocalDataLayer.current.lifeDimensions
                        .observeAllDimensions()
                        .first()
                },
            )
        runBlocking { source.reload() }
        return source.preferences.value
    }

    private val sessionController: DesktopDatabaseSessionController by lazy {
        DesktopDatabaseSessionController(
            databaseFilePath = { databaseFile.absolutePath },
            // A REAL open, because the journeys that need the passphrase - the assistant's read-only port
            // and the passphrase-change flow - read it from the controller, and a refusing controller left
            // them with nothing. The fixture creates the file if it is absent, so this is only ever asked
            // for an existing one.
            open = { path, passphrase -> PayanamDatabaseOpener.open(Path.of(path), passphrase.concatToString()) },
            logger = sessionLogger,
        )
    }

    private fun desktopNavHostScreens(preferences: AppPreferencesState): DesktopPayanamNavHostScreens =
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
                    // The same directory the init gate and the controller resolve, so the file this flow
                    // rekeys is the fixture's rather than the operator's real data directory.
                    databasePathResolver = DesktopDatabasePathResolver(databaseDirectory = { dataRoot.absolutePath }),
                ),
            language = UiLanguage.ENGLISH,
            settingsCollaborators =
                DesktopSettingsCollaborators(
                    preferences = preferences,
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
