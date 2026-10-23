//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.desktop.database.DesktopBootstrapDataLayer
import io.payanam.desktop.database.DesktopDatabaseInitCollaborators
import io.payanam.desktop.database.DesktopDatabaseOpenerAdapter
import io.payanam.desktop.database.DesktopDatabasePathResolver
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.desktop.database.databaseInitViewModel
import io.payanam.desktop.platform.AwtDocumentSource
import io.payanam.desktop.security.DesktopEncryptionStateCheckpoint
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.startup.DesktopScaffoldOnboardingStage
import io.payanam.shared.startup.startupGateInputs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop's import flow, END TO END, with a real encrypted database - and no window.
 *
 * WHY THIS EXISTS RATHER THAN A GUI RUN: this session is Wayland, so an agent cannot SEE the app (every
 * X11 grab of the screen comes back uniformly black, while the app itself IS an XWayland client - measured),
 * and the file picker is the one step where seeing matters. Everything after the picker is plain Kotlin over
 * the `DocumentSource` port, so this harness supplies the token a picker would have produced and runs the
 * REST exactly as the app does: the shared `DatabaseInitViewModel`, the desktop's own collaborators, the real
 * `AwtDocumentSource` (its `copyInto` reads a path; only `requestSource` opens a dialog, and that is the seam
 * this test deliberately stands on), a real session through `DesktopDatabaseSessionController`, and then the
 * desktop's own preferences source and startup gate.
 *
 * OPT-IN, so ordinary runs and CI are unaffected: it does nothing unless the loop script supplies
 * `payanam.loop.db`, `payanam.loop.pwd` and `payanam.loop.dataRoot`. The password is read from its file and is
 * never logged, echoed, or put in an assertion message - only its length is ever reported.
 *
 * WHAT "WORKS" MEANS HERE, in the four facts the startup gate actually consumes:
 *  1. the bytes landed at the app's OWN database path (the defect that made the first import fail);
 *  2. a session is open over the imported file (really usable, not merely copied);
 *  3. the shared preference rows were READ (the bug that made every preference a default - and the reason
 *     an imported database with focus onboarding already done was still asked to pick a preset);
 *  4. the startup mode resolves to `Ready`, i.e. no gate is left in front of the app.
 */
class DesktopImportLoopHarnessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val databaseProperty: String? = System.getProperty("payanam.loop.db")
    private val passphraseProperty: String? = System.getProperty("payanam.loop.pwd")
    private val dataRootProperty: String? = System.getProperty("payanam.loop.dataRoot")

    /** The desktop's own logger class: its `initialize`/`close` live here, not on the shared interface. */
    private lateinit var logger: UnifiedLogger
    private lateinit var logsDirectory: Path

    /**
     * Held so teardown can stop the ViewModel's work BEFORE the process-wide holders are reset: the scope is
     * its own `Dispatchers.Default` job, so without this the session, the controller and the SQLCipher
     * connection stay reachable for the rest of the task's JVM.
     */
    private var viewModelScope: CoroutineScope? = null
    private var sessionController: DesktopDatabaseSessionController? = null

    @Before
    fun setUp() {
        assumeTrue(
            "Loop fixture not supplied: set -Dpayanam.loop.db/-Dpayanam.loop.pwd/-Dpayanam.loop.dataRoot",
            databaseProperty != null && passphraseProperty != null && dataRootProperty != null,
        )
        UnifiedLogger.resetForTests()
        logsDirectory = temporaryFolder.newFolder("loop-logs").toPath()
        logger = UnifiedLogger.initialize(logsDirectory = logsDirectory)
        AppLogger.install(logger)
        LocalDataLayer.resetForTests()
    }

    @After
    fun tearDown() {
        // EVERY teardown step is guarded, because `setUp` can return early: when the fixture is absent the
        // assumption skips the test before the logger exists, and an unguarded `logger.close()` on a
        // `lateinit` property turns that skip into a FAILURE (org.junit.TestCouldNotBeSkippedException: Test
        // could not be skipped due to other failures) - measured by the desktop gate, which runs with no
        // fixture at all.
        // Order: stop the ViewModel's work, then close the session, THEN reset the process-wide holders.
        // WHY THE JOIN, and why it stays FIRST: cancelling the scope is not WAITING for it. A collector
        // still inside its JDBC call when the session closes throws for real - not a cancellation - and
        // with no CoroutineExceptionHandler on this scope that failure reaches the JVM-global exception
        // collector, which re-throws it as UncaughtExceptionsBeforeTest in whichever LATER test opens a
        // TestScope: the victim moves from run to run. Joining here is what proves nothing of this scope
        // is still running when the session closes.
        runBlocking { viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
        sessionController?.closeAndUninstall()
        LocalDataLayer.resetForTests()
        AppLogger.resetForTests()
        if (this::logger.isInitialized) {
            logger.close()
            UnifiedLogger.resetForTests()
        }
    }

    /**
     * The passphrase, from its file, WITHOUT ever logging it.
     *
     * The last non-empty line is the secret; the file's earlier lines are its own label. Reading it here is
     * the point of the harness - a loop that asked a human to paste it into a chat would defeat itself.
     */
    private fun passphraseFromFile(): String =
        Files
            .readAllLines(Path.of(requireNotNull(passphraseProperty)))
            .map { it.trim() }
            .last { it.isNotEmpty() }

    @Test
    fun `the fixture database imports, opens, reads its rows and leaves no gate in front of the app`() =
        runBlocking {
            val dataRoot = Path.of(requireNotNull(dataRootProperty))
            dataRoot.toFile().deleteRecursively()
            Files.createDirectories(dataRoot)
            val databaseFile = dataRoot.resolve("Payanam").resolve("database").resolve("payanam.db")
            val securityDirectory = dataRoot.resolve("Payanam").resolve("security")
            val passphrase = passphraseFromFile()

            val controller =
                DesktopDatabaseSessionController(
                    databaseFilePath = { databaseFile.toAbsolutePath().toString() },
                    open = { path, secret -> DesktopDatabaseOpenerAdapter().openSession(path, secret) },
                    logger = logger,
                )
            // Handed to teardown so the session is closed before the process-wide holders are reset.
            sessionController = controller
            // The bootstrap layer is what the shell installs before a session exists, and the shared
            // ViewModel reads the INSTALLED layer rather than building repositories of its own.
            LocalDataLayer.install(DesktopBootstrapDataLayer)
            val vault: PassphraseVault = DesktopPassphraseVault(DesktopSecurityStore(securityDirectory = securityDirectory))
            val viewModel =
                databaseInitViewModel(
                    collaborators =
                        DesktopDatabaseInitCollaborators(
                            sessionController = controller,
                            // A TEST-OWNED marker, never the default: the tour's completion WRITES through this
                            // store, and the default resolves the operator's real data directory.
                            onboardingStore = DesktopOnboardingStore(securityDirectory = temporaryFolder.newFolder().toPath()),
                            documentSource = AwtDocumentSource(),
                            passphraseVault = vault,
                            databasePathResolver =
                                DesktopDatabasePathResolver(
                                    databaseDirectory = { databaseFile.parent.toAbsolutePath().toString() },
                                ),
                            encryptionStateCheckpoint = DesktopEncryptionStateCheckpoint(),
                        ),
                    strings = UiStrings.of(UiLanguage.ENGLISH),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { viewModelScope = it },
                )

            var importSucceeded = false
            // THE PRODUCTION PATH, not a hand-rolled sequence: this is the same follower the shell installs,
            // so what it observes is what the app observes on a real import.
            val preferences = DesktopPreferencesSource(sessionLogger = logger, systemLanguageTag = null)
            val gateFlag = androidx.compose.runtime.mutableStateOf(false)
            val follower =
                launch {
                    followDesktopSessionOpenedState(
                        sessionController = controller,
                        gateFlag = gateFlag,
                        preferencesSource = preferences,
                    )
                }
            try {
                // The token a picker would have produced, for the file the user chose. Everything below this
                // line is the app's own code path.
                viewModel.importDatabase(requireNotNull(databaseProperty)) { importSucceeded = true }
                await("the import to reach the passphrase step") { viewModel.uiState.value.awaitingImportPassphrase }
                assertThat(viewModel.uiState.value.errorMessage).isNull()

                viewModel.resumeImportWithPassphrase(passphrase) { importSucceeded = true }
                await("the import to finish") { importSucceeded || controller.isOpen }
                await("the follower to see the session") { gateFlag.value }
                // And the READ the session triggers must produce rows: this is the assertion that fails if the
                // layer is installed after the state consumers wait on.
                await("the preference rows the session triggers to arrive") { rawSettingsRows() > 0 }

                // 1. The bytes are at the APP's path, not merely readable from the user's file.
                assertThat(databaseFile.toFile().exists()).isTrue()
                val landedBytes = Files.size(databaseFile)
                assertThat(landedBytes).isGreaterThan(0L)
                // 2. A session is open over it.
                assertThat(controller.isOpen).isTrue()

                // 3 & 4. The desktop's own preferences source and its own gate, over the imported database.
                val state = preferences.preferences.value
                val rawRows =
                    LocalDataLayer.current.appSettings
                        .getAllSettings()
                        .first()
                val focusKey = AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED
                val focusRowRaw = rawRows[focusKey]
                val inputs =
                    startupGateInputs(
                        buildDesktopStartupRuntimeState(
                            focusModeOnboardingCompleted = state.focusModeOnboardingCompleted,
                            bootstrapSchema = DesktopBootstrapSchemaProbe().loadSnapshot(),
                            securitySnapshot = DesktopSecurityStore(securityDirectory = securityDirectory).loadSnapshot(),
                            databaseSnapshot = DesktopDatabaseProbe(databaseFilePath = databaseFile).loadSnapshot(),
                            sessionOpen = controller.isOpen,
                            nowEpochMillis = System.currentTimeMillis(),
                            scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Finished,
                        ),
                    )
                val reloadLine =
                    appLog()
                        .lastOrNull { it.contains("Preferences loaded from the encrypted database") }
                        ?: "<no reload line found>"

                // The three datasets the gate serves: tasks, notes and journal must ALL be readable
                // on the desktop after the import, not just app_settings.
                val taskCount = rawTaskRows()
                val noteCount = rawNoteRows()
                val journalCount = rawJournalRows()

                println(
                    "LOOP-VERDICT: import=OK bytes=$landedBytes session=${if (controller.isOpen) "open" else "CLOSED"} " +
                        "rawSettingsRows=${rawRows.size} focusRowPresent=${rawRows.containsKey(focusKey)} " +
                        "focusRowRaw=$focusRowRaw mappedFocusCompleted=${state.focusModeOnboardingCompleted} " +
                        "startupMode=${inputs.gateName} passphraseProvided=${passphrase.isNotEmpty()} " +
                        "tasks=$taskCount notes=$noteCount journal=$journalCount",
                )
                println("LOOP-EVIDENCE: $reloadLine")

                // THE FIX'S PROPERTY: the rows were really READ (before the fix this was always zero, and
                // every preference was a default).
                assertThat(rawRows.size).isGreaterThan(0)
                // The three datasets the gate serves must ALL be READABLE, and READABILITY is the floor, not
                // non-emptiness: the only fixture this loop is handed holds zero rows in `tasks`, `notes` and
                // `day_journal_entries` (the run prints `tasks=0 notes=0 journal=0`), so `>0` would assert a
                // data question rather than the gate's requirement that each query returned a printable count.
                assertThat(taskCount).isAtLeast(0)
                assertThat(noteCount).isAtLeast(0)
                assertThat(journalCount).isAtLeast(0)
                // AND the gate is CONSISTENT with what the database actually says: `Ready` when the step is
                // done, `FocusModeSelection` when it is not (which is also what Android does for a database
                // without that row - a data question, not a desktop one). A gate stuck on a step the data
                // says is finished is the defect; matching the data is not.
                val expectedMode =
                    if (focusRowRaw?.toBoolean() == true) {
                        "Ready"
                    } else {
                        "FocusModeSelection"
                    }
                assertThat(inputs.gateName).isEqualTo(expectedMode)
                // The NAME above says which decision it is; this says what the graph is TOLD, which is what the
                // user actually meets. Asserting only the name would still pass if the gate rose and the graph
                // were handed the wrong surface.
                assertThat(inputs.shouldShowFocusModeOnboarding)
                    .isEqualTo(expectedMode == "FocusModeSelection")
            } finally {
                follower.cancel()
            }
        }

    /** How many `app_settings` rows the installed layer can actually read right now. */
    private suspend fun rawSettingsRows(): Int =
        LocalDataLayer.current.appSettings
            .getAllSettings()
            .first()
            .size

    /** How many `tasks` rows the installed layer can actually read right now. */
    private suspend fun rawTaskRows(): Int =
        LocalDataLayer.current.tasks
            .getAllTasks()
            .first()
            .size

    /** How many `notes` rows the installed layer can actually read right now. */
    private suspend fun rawNoteRows(): Int =
        LocalDataLayer.current.notes
            .getAllNotes()
            .first()
            .size

    /** How many `day_journal_entries` rows the installed layer can actually read right now. */
    private suspend fun rawJournalRows(): Int =
        LocalDataLayer.current.journal
            .getAllJournalEntries()
            .first()
            .size

    /** Every line this run's logger wrote. */
    private fun appLog(): List<String> {
        val lines = mutableListOf<String>()
        Files.newDirectoryStream(logsDirectory, "*.log").use { entries ->
            entries.forEach { path -> lines += Files.readAllLines(path) }
        }
        return lines
    }

    /**
     * Real time, because the ViewModel's scope is not this test's dispatcher - and the failure NAMES what was
     * being awaited: a bare `TimeoutCancellationException` sends the reader back into the test body to work out
     * which of the four waits expired.
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
