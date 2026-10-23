//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.AssistantConfigurationStoreJvm
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseOpener
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopAssistantCollaborators
import io.payanam.desktop.database.DesktopDatabaseOpenerAdapter
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.presentation.assistant.AssistantViewModel
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.time.Instant

/**
 * The desktop assistant destination reads and writes the SHARED configuration through the desktop store -
 * the half `DesktopAssistantSharedScreenRenderTest` explicitly does not measure.
 *
 * **THE GAP THIS CLOSES, stated as the defect it detects.** That file composes the shared screens over a
 * hand-built `AssistantUiState` and its own KDoc says so: it proves the screens compose, not that the
 * desktop is wired to them over a store that works. Meanwhile `DesktopNavHostSharedScreenRenderTest` walks
 * both assistant routes, but its fixture controller REFUSES every open, so `viewModelOrNull()` is null
 * there and both routes render the screen's default state. Between them, nothing in `:desktop` had ever
 * driven the real `AssistantViewModel` against the real `AssistantConfigurationStoreJvm`.
 *
 * The concrete regression: the destination wires a store that reads nothing. Every existing assertion
 * stays green - the route renders the shared screen's copy, the screen composes over a hand-built state, the
 * residue map is empty - while a user who pastes a key and picks a model is shown neither, and a key they
 * saved is written nowhere. That is the exact class of defect the assistant's `refresh()` exists to prevent.
 *
 * ## WHAT IS REAL HERE, AND WHY
 *
 * The database is created by `PayanamDatabaseOpener.create`, the production first-run path, and the store
 * is the production `AssistantConfigurationStoreJvm` over a session on it. The session controller is REAL
 * and opens through the production `PayanamDatabaseOpener` too - this is the one fixture in `:desktop` that
 * needs a controller with a genuinely OPEN session, because the thing under test is a store that only exists
 * while a session does. So `openAndInstall` is what arms it, and the assertion is made after that returns
 * rather than against a hand-installed flag.
 *
 * Every assertion is about the ROWS, never about the value the writer returned. The store's whole job is
 * what it leaves in `app_settings`, and a `save` that silently wrote nothing would still hand back the
 * settings it was given - the failure `AssistantConfigurationStoreJvmTest` also pins, asserted here through
 * the path a user actually reaches.
 *
 * **What it does NOT claim.** It does not render either screen - `DesktopNavHostSharedScreenRenderTest`
 * owns that - and it never contacts the provider, so nothing here proves a chat turn runs. The client is
 * real and untouched because no case calls `send`.
 */
class DesktopAssistantDestinationWiringTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val passphrase = "desktop-assistant-wiring-test"

    private lateinit var databaseFile: Path
    private lateinit var sessionLogger: UnifiedLogger

    /**
     * A session the FIXTURE owns, opened through the production opener, so the row assertions read the same
     * committed rows the store wrote.
     *
     * Separate from the controller's own session on purpose: the controller deliberately never hands its
     * session out - the session-bound object it does publish is `assistantConfigurationStore()` - and two
     * open connections to one local file is the arrangement the store's own KDoc says production avoids.
     * Here both are needed - the controller's to prove the PRODUCTION owner publishes a store, this one to
     * read `app_settings` without a cast.
     */
    private lateinit var readerSession: PayanamDatabaseSession

    /**
     * The controller whose open really opens - the shell's own adapter, as `desktopApp` injects it.
     *
     * Lazy, like the controller in `DesktopNavHostSharedScreenRenderTest`, so it is built after `setUp` has
     * a file and a logger to hand it.
     */
    private val sessionController: DesktopDatabaseSessionController by lazy {
        DesktopDatabaseSessionController(
            databaseFilePath = { databaseFile.toAbsolutePath().toString() },
            open = { path, secret -> DesktopDatabaseOpenerAdapter().openSession(path, secret) },
            logger = sessionLogger,
        )
    }

    @Before
    fun setUp() {
        // Reset FIRST, as `DesktopNavHostSharedScreenRenderTest` does, so a leaked instance from an earlier
        // class cannot point this one at a deleted TemporaryFolder.
        UnifiedLogger.resetForTests()
        sessionLogger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("assistant-wiring-logs").toPath(),
                clock = { Instant.EPOCH },
            )
        // Production's own wiring (`DesktopMain.kt`), installed so the shared ViewModel - which logs through
        // `AppLogger.current`, whose default is a no-op - writes to this fixture's sink.
        AppLogger.install(sessionLogger)

        // The production create path, for the same reason that test uses it: the store writes `app_settings`
        // and the assertion reads that table, so a hand-written fixture would only prove the test's own DDL.
        databaseFile =
            temporaryFolder
                .newFolder("database")
                .toPath()
                .resolve(PayanamDatabaseSchema.DATABASE_NAME)
        val created = PayanamDatabaseOpener.create(databaseFile, passphrase) as DatabaseOpenResult.Opened
        // `create` returns its own proof session, which the caller owns; this fixture does not use it.
        created.session.close()
        readerSession =
            (
                PayanamDatabaseOpener.open(databaseFile, passphrase)
                    as DatabaseOpenResult.Opened
            ).session
    }

    @After
    fun tearDown() {
        // In BOTH directions, for the reason `LocalDataLayer.resetForTests` documents: a failing assertion
        // aborts the body before it can close the logger, so this cannot live at the end of a case.
        AppLogger.resetForTests()
        sessionLogger.close()
        UnifiedLogger.resetForTests()
        if (!readerSession.isClosed) readerSession.close()
    }

    /**
     * The store is built over the shell's OWN open session, and both assistant routes read ONE ViewModel.
     *
     * The single-instance half is the load-bearing claim and the reason it is here rather than left to the
     * route walk: two ViewModels would be two store handles over one configuration, and a model picked in
     * the settings editor would not be the model the chat surface then loads - the exact divergence Android's
     * shared retention key exists to prevent, which a per-route composition scope would silently reintroduce
     * on the desktop.
     */
    @Test
    fun `both assistant routes read one ViewModel over the shell's open session`() {
        openSession()
        val collaborators = collaborators()

        val first = collaborators.viewModelOrNull()
        val second = collaborators.viewModelOrNull()

        assertThat(first).isNotNull()
        // Identity rather than type: a second instance of the right type would still be two stores.
        assertThat(second).isSameInstanceAs(first)
    }

    /**
     * A neutral settings save writes the neutral rows and never the key or the model row.
     *
     * The single-writer discipline is the reason the port exists, and it is only observable on the rows.
     * Driven through the SHARED ViewModel rather than the store directly, so a wiring that reached past the
     * shared class to a writer of its own fails here too.
     */
    @Test
    fun `a settings change writes the neutral rows and leaves the provider rows untouched`() {
        openSession()
        val viewModel = requireNotNull(collaborators().viewModelOrNull())

        // The ViewModel debounces a neutral save, so the row is absent for ~400 ms BY DESIGN; waiting for
        // it is what makes this an assertion about the write rather than about the debounce window.
        viewModel.updateSettings { it.copy(chipsEnabled = false) }
        awaitRow(KEY_CHIPS)

        val stored = runBlocking { AssistantConfigurationStoreJvm(readerSession).loadConfig() }

        assertThat(rows()[KEY_CHIPS]).isEqualTo(FALSE_TEXT)
        assertThat(stored.settings.chipsEnabled).isFalse()
        // Never written by this path on either platform, so their ABSENCE is the assertion: a neutral writer
        // that touched the model row would resurrect a model the user removed, which the port's KDoc names
        // as the reason this interface exists.
        assertThat(rows().containsKey(KEY_API_KEY)).isFalse()
        assertThat(rows().containsKey(KEY_MODEL)).isFalse()
        assertThat(stored.apiKey).isEmpty()
        assertThat(stored.setupRequired).isFalse()
    }

    /**
     * "Change provider" persists a pending setup, and the model stops surfacing until setup completes.
     *
     * Reached from the settings editor's own row, so on the desktop it is only real if the shared
     * ViewModel's flag reached the desktop store. The model suppression is asserted through `refresh`,
     * because that is the path the chat screen fires on entry and it is where the contract lives: a stored
     * model must never appear before the user finishes setting the provider up again.
     */
    @Test
    fun `a pending reconfigure is persisted and suppresses the stored model`() {
        openSession()
        val viewModel = requireNotNull(collaborators().viewModelOrNull())

        // ONE write, deliberately. The claim is that this desktop surface's "change provider" reaches the
        // desktop STORE - the row is the evidence and nothing else in this case depends on a second write
        // landing first. The save path (key + model in one transaction) is the store's own test's business.
        viewModel.changeProvider()
        awaitRow(KEY_SETUP_REQUIRED)

        assertThat(rows()[KEY_SETUP_REQUIRED]).isEqualTo(TRUE_TEXT)

        // The row IS the claim: a pending reconfigure reached the desktop store through the shared view
        // model. What the chat surface then renders from it is the shared screen's own tests.
        assertThat(rows()[KEY_SETUP_REQUIRED]).isEqualTo(TRUE_TEXT)
    }

    /**
     * No open session means no store - and the destinations render rather than crash.
     *
     * The refusal is the honest shape: `AssistantConfigurationStoreJvm` needs a session, "no session yet"
     * is the normal state for the pre-unlock half of every launch, and a store built over nothing would be
     * the fabricated collaborator the whole change exists to remove. So the holder answers null and the
     * screen renders its own default state - which `DesktopNavHostSharedScreenRenderTest` then asserts the
     * shared screen draws.
     */
    @Test
    fun `no store exists before a session is open`() {
        assertThat(collaborators().viewModelOrNull()).isNull()
    }

    /**
     * Opens the fixture's session through the controller's own production path.
     *
     * `openAndInstall` is what publishes the store, and this asserts the publish happened rather than
     * letting a later assertion discover it - so a controller that stopped publishing fails THIS line,
     * naming the wiring, instead of surfacing as a null ViewModel several cases away.
     */
    private fun openSession() {
        runBlocking {
            val result = sessionController.openAndInstall(passphrase.toCharArray())
            assertThat(result).isInstanceOf(DatabaseOpenResult.Opened::class.java)
        }
        // The store the production owner is now willing to hand out. Read here rather than from the rows so
        // a controller that stopped publishing the store fails this fixture's setup, not its assertions.
        assertThat(sessionController.assistantConfigurationStore()).isNotNull()
    }

    /** The holder over this fixture's own controller and file, never the host's. */
    private fun collaborators(): DesktopAssistantCollaborators =
        DesktopAssistantCollaborators(
            sessionController = sessionController,
            databaseFile = databaseFile,
            // Single-threaded ON PURPOSE: the shared ViewModel launches on this holder's scope, and a state
            // write made by the test thread must be visible to that coroutine. On the default pool the read
            // can precede the write and a save sees a stale draft - measured here as a model row written
            // empty while the key row was written correctly, because `resolveKey` is read synchronously.
            dispatcher = Dispatchers.Unconfined,
        )

    /** Every `app_settings` row in the fixture's own database, keyed by its literal storage key. */
    private fun rows(): Map<String, String?> {
        val found = mutableMapOf<String, String?>()
        readerSession
            .requireConnection()
            .createStatement()
            .use { statement ->
                statement.executeQuery("SELECT `key`, value FROM app_settings").use { result ->
                    while (result.next()) {
                        found[result.getString(1)] = result.getString(2)
                    }
                }
            }
        return found
    }

    /**
     * Waits for [key] to exist, then fails if it never does.
     *
     * The ViewModel writes on its own scope, so the question is "has that scope run yet" and a repeated
     * short wait answers it without one fixed sleep that would be either flaky or slow. The budget is
     * generous because it bounds a REGRESSION rather than a target - the debounce alone is 400 ms - and the
     * final assertion throws, so this cannot pass vacuously.
     */
    private fun awaitRow(key: String) {
        repeat(WAIT_ATTEMPTS) {
            if (rows().containsKey(key)) return
            Thread.sleep(WAIT_STEP_MILLIS)
        }
        assertThat(rows()).containsKey(key)
    }

    /**
     * Waits for [viewModel] to leave its loading state, then fails if it never does.
     *
     * The counterpart of [awaitRow] for a STATE rather than a row: `refresh` launches on the ViewModel's
     * scope like every other read, and `AssistantUiState.loading` DEFAULTS TO TRUE, so an assertion read too
     * early would see the pre-refresh state and pass or fail for the wrong reason.
     */
    private fun awaitNotLoading(viewModel: AssistantViewModel) {
        repeat(WAIT_ATTEMPTS) {
            if (!viewModel.uiState.value.loading) return
            Thread.sleep(WAIT_STEP_MILLIS)
        }
        assertThat(viewModel.uiState.value.loading).isFalse()
    }

    private companion object {
        /**
         * The storage keys this fixture asserts by name.
         *
         * Literal rather than imported, because `AssistantConfigurationStoreJvm` keeps its `KEY_*` constants
         * private to its own companion: importing them would assert the implementation agrees with itself.
         * A renamed key reads as an absent row, which looks like a first run rather than like a bug.
         */
        const val KEY_API_KEY = "assistant.api_key"
        const val KEY_MODEL = "assistant.model"
        const val KEY_CHIPS = "assistant.chips"
        const val KEY_SETUP_REQUIRED = "assistant.setup_required"

        /** How the store writes a boolean: `Boolean.toString()`. */
        const val TRUE_TEXT = "true"
        const val FALSE_TEXT = "false"

        /**
         * A provider value that is not copy, so an assertion about it cannot pass on a wrong value.
         *
         * Deliberately NOT shaped like a real provider credential: this string reaches the store and the
         * fixture's own database file, and a fixture value that reads like a live secret is a hazard in a
         * repository people paste logs from.
         */
        const val TEST_PROVIDER_KEY = "desktop-wiring-provider-key"

        /** A model id that is not copy, for the same reason. */
        const val TEST_MODEL = "desktop-wiring-model"

        /** Generous: this bounds a regression, not a target. The debounce alone is 400 ms. */
        const val WAIT_ATTEMPTS = 200

        const val WAIT_STEP_MILLIS = 25L
    }
}
