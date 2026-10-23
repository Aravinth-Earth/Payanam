//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Pins the desktop startup gate's session answer to the SESSION, which is Android's rule
 * (`MainActivity`: `!sessionManager.isOpen.value`) and was not the desktop's.
 *
 * WHY THIS EXISTS, and the failure it is here to keep dead: the gate read
 * `DesktopRememberedState.sessionOpenState`, and only the desktop's own legacy gate callbacks ever wrote
 * that flag. A session opened by any other path was invisible to it, so the gate kept asking for an unlock
 * it had already been given. Measured 2026-10-02 on the Linux build: the database-init gate's import opens a
 * session, the shared unlock screen then unlocked through the same controller, and the gate's silent re-auth
 * threw the unlock screen back up the instant `lenses` drew - an unlock loop on an OPEN database.
 *
 * The two tests below therefore never call a gate callback at all: an open and a close through the
 * controller, and nothing else, are what the gate's flag must follow.
 */
class DesktopSessionOpenMirrorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "mirror-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    /**
     * Records `w`/`e` lines and drops the rest: the only prose these tests assert on is the guard's refusal,
     * which must be LOGGED rather than swallowed. Everything else here is about state.
     */
    private val loggedMessages = mutableListOf<String>()

    private val silentLogger =
        object : UnifiedLogger {
            override fun d(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) = Unit

            override fun i(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) = Unit

            override fun w(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) {
                loggedMessages += message
            }

            override fun e(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) {
                loggedMessages += message
            }

            override fun eSync(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) {
                loggedMessages += message
            }
        }

    @Before
    fun setUp() {
        // The guard's refusal is asserted below, and it is written through the shared accessor: without this
        // the logger is the no-op default and the assertion would be vacuous. Reset in `@After` because test
        // classes in a module share one JVM.
        AppLogger.install(silentLogger)
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE journal_notes (
                    id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, details TEXT,
                    lifeIntentionCategory TEXT NOT NULL, dimension_id TEXT, day_key TEXT NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE tags (
                    id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, normalized_name TEXT NOT NULL,
                    usage_count INTEGER NOT NULL DEFAULT 0, last_used_at TEXT,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            // The shared settings table, because one of these tests is about the READ of a row in it: the
            // gate's `focus_mode_onboarding_completed` lives here, in the table Android reads it from.
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
        LocalDataLayer.resetForTests()
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        AppLogger.resetForTests()
        if (!session.isClosed) session.close()
    }

    private fun controller() =
        DesktopDatabaseSessionController(
            databaseFilePath = { tempFolder.root.resolve("payanam.db").absolutePath },
            open = { _, _ -> DatabaseOpenResult.Opened(session) },
            logger = silentLogger,
        )

    /** The shared settings source, bare: these tests drive the row READ, not a screen. */
    private fun preferencesSource() = DesktopPreferencesSource(sessionLogger = silentLogger, systemLanguageTag = null)

    /** Writes one `app_settings` row, the way an Android install's own write lands in the database. */
    private fun writeSetting(
        key: String,
        value: String,
    ) {
        connection
            .prepareStatement("INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES (?, ?, ?)")
            .use { statement ->
                statement.setString(1, key)
                statement.setString(2, value)
                statement.setString(3, "2026-10-02T00:00:00Z")
                statement.executeUpdate()
            }
    }

    @Test
    fun `the gate flag turns true for a session opened with no gate callback involved`() =
        runBlocking {
            val controller = controller()
            val gateFlag = mutableStateOf(false)
            val follower = launch { followDesktopSessionOpenedState(controller, gateFlag, preferencesSource()) }
            try {
                // Nothing has opened a session yet.
                assertThat(gateFlag.value).isFalse()

                // Exactly what the import path does: a session through the controller, and no gate callback.
                controller.openAndInstall(passphrase.toCharArray())
                awaitGateFlag(gateFlag, expected = true)

                assertThat(controller.isOpen).isTrue()
                assertThat(gateFlag.value).isTrue()
            } finally {
                follower.cancel()
            }
        }

    @Test
    fun `the gate flag turns false again when that session closes`() =
        runBlocking {
            val controller = controller()
            val gateFlag = mutableStateOf(false)
            val follower = launch { followDesktopSessionOpenedState(controller, gateFlag, preferencesSource()) }
            try {
                controller.openAndInstall(passphrase.toCharArray())
                awaitGateFlag(gateFlag, expected = true)

                controller.closeAndUninstall()
                awaitGateFlag(gateFlag, expected = false)

                assertThat(controller.isOpen).isFalse()
                assertThat(gateFlag.value).isFalse()
                // THIS ASSERTION IS THE GUARD'S PROOF: the read in flight when the session closed fails
                // (`Connection is closed`), and before the guard that exception ended the collector - so the
                // flag stayed true here and this line is what went red. Survival, not prose, is the pin.
            } finally {
                follower.cancel()
            }
        }

    /**
     * A preference read that FAILS does not take the follower down with the session.
     *
     * The read is failed deterministically (the dimension read refuses) rather than by racing a close: the
     * property is that the collector keeps collecting. Before the guard, an exception out of `reload()`
     * ended it, the gate stopped following the session for the rest of the process, and the throw escaped
     * into the composition - in the app that means a gate stuck on whatever it last believed.
     */
    @Test
    fun `a preference read that fails is logged and the follower keeps following`() =
        runBlocking {
            val controller = controller()
            val gateFlag = mutableStateOf(false)
            val failingSource =
                DesktopPreferencesSource(
                    sessionLogger = silentLogger,
                    systemLanguageTag = null,
                    dimensionRows = { throw IllegalStateException("the row read refused") },
                )
            val follower = launch { followDesktopSessionOpenedState(controller, gateFlag, failingSource) }
            try {
                controller.openAndInstall(passphrase.toCharArray())
                awaitGateFlag(gateFlag, expected = true)

                assertThat(gateFlag.value).isTrue()
                assertThat(loggedMessages.any { it.contains("Preference rows could not be read") }).isTrue()
            } finally {
                follower.cancel()
            }
        }

    /**
     * Waits for the follower to publish [expected], in REAL time.
     *
     * Not `runTest`/`runCurrent`: the collector's own body suspends into `Dispatchers.IO` for the row read,
     * so the value can land after any number of test-dispatcher turns. Asserting straight after the call
     * that caused it is exactly what made this class pass alone and fail inside the full suite once.
     */
    private suspend fun awaitGateFlag(
        flag: MutableState<Boolean>,
        expected: Boolean,
    ) {
        withTimeout(5_000) { while (flag.value != expected) delay(10) }
    }

    /**
     * The session opening READS the shared preference rows.
     *
     * THE BUG THIS PINS, measured 2026-10-02: `DesktopPreferencesSource.reload()` had no production caller,
     * so the row map stayed empty and every stored preference showed as its default. The visible symptom was
     * the startup gate: `focusModeOnboardingCompleted` was permanently false, so a freshly imported database
     * whose Android install HAD completed focus onboarding was still asked to pick a focus preset, while
     * Android asks nothing. The row written below is exactly the one Android writes when that step is done,
     * so without the read this test waits for a value that never arrives.
     *
     * `runBlocking` rather than `runTest`, deliberately: the read hops to `Dispatchers.IO` (JDBC is blocking)
     * and the repository's flow is a poller, so the test dispatcher cannot drive it and a virtual-time wait
     * would run its clock past the real work instead of waiting for it.
     */
    @Test
    fun `opening a session reads the shared preference rows, so a completed step is not asked again`() =
        runBlocking {
            writeSetting(AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED, "true")
            val controller = controller()
            val source = preferencesSource()
            val gateFlag = mutableStateOf(false)
            val follower = launch { followDesktopSessionOpenedState(controller, gateFlag, source) }
            try {
                // Before any session: the absent-row default, which is what the desktop used to show forever.
                assertThat(source.preferences.value.focusModeOnboardingCompleted).isFalse()

                controller.openAndInstall(passphrase.toCharArray())

                val loaded = withTimeout(5_000) { source.preferences.first { it.focusModeOnboardingCompleted } }
                assertThat(loaded.focusModeOnboardingCompleted).isTrue()
            } finally {
                follower.cancel()
            }
        }
}
