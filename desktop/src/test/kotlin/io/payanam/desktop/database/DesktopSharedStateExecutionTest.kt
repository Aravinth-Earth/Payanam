//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenMode
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.notes.notesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * The proof D4 asks for: a SHARED class loading and executing from the shared classpath on the desktop.
 *
 * The shared `LocalDataLayer` holder is installed with [DesktopPayanamDataLayer] over a real
 * SQLCipher-encrypted database, and then the shared `NotesViewModel` from `:core:presentation` is driven
 * against it — reads, a write, and the write read back. Nothing in that path is desktop-specific, which is
 * the property under test: the desktop is not rendering an empty screen over a mock, it is running the same
 * state layer Android runs, over repositories that speak to the same schema.
 *
 * The session is constructed directly rather than through `PayanamDatabaseOpener`. That is not a shortcut
 * around the guard: the driver cannot write `PRAGMA user_version` (a measured fact), so a GENERATED fixture
 * is always version 0 and the opener correctly refuses it. The opener's reject path is covered by its own
 * tests and its accept path was proven against a real Android export during the milestone spike; what this
 * test isolates is the layer above it. A real export is never used here — it is the user's personal data.
 */
class DesktopSharedStateExecutionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "desktop-state-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private var viewModelScope: CoroutineScope? = null

    @Before
    fun setUp() {
        // The desktop logger is process-wide, and this class constructs collaborators that reach for it:
        // initialize it HERE (fully qualified, because several siblings import the SHARED UnifiedLogger under
        // the same name) rather than relying on a sibling class's leak - the shared-JVM class order is not a
        // contract, and a solo run of this class must work. The directory outlives the class on purpose.
        io.payanam.desktop.UnifiedLogger
            .resetForTests()
        io.payanam.desktop.UnifiedLogger.initialize(
            logsDirectory =
                java.nio.file.Files
                    .createTempDirectory("payanam-desktop-test-logs"),
        )
        // A per-test database: a shared path would let one test's rows satisfy another's assertion.
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE journal_notes (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    details TEXT,
                    lifeIntentionCategory TEXT NOT NULL,
                    dimension_id TEXT,
                    day_key TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE notes (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    details TEXT,
                    lifeIntentionCategory TEXT NOT NULL,
                    dimension_id TEXT,
                    day_key TEXT,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE tags (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    usage_count INTEGER NOT NULL DEFAULT 0,
                    last_used_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("CREATE TABLE note_tags (note_id TEXT, tag_id TEXT, created_at TEXT)")
            statement.execute("CREATE TABLE task_tags (task_id TEXT, tag_id TEXT, created_at TEXT)")
            statement.execute(
                "CREATE TABLE time_entry_tags (time_entry_id TEXT, tag_id TEXT, created_at TEXT)",
            )
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        // The holder is process-wide, so a leaked install would let a later class pass on this one's state.
        LocalDataLayer.resetForTests()
    }

    @After
    fun tearDown() {
        // WHY THE JOIN, and why it comes FIRST: cancelling the scope is not WAITING for it. The shared
        // ViewModel's collectors never complete on their own, so one still inside the session when the
        // session closes throws for real - not a cancellation - and with no CoroutineExceptionHandler on
        // this scope that failure reaches the JVM-global exception collector, which re-throws it as
        // UncaughtExceptionsBeforeTest in whichever LATER test opens a TestScope. Joining here is what
        // keeps this test's failure from being charged to an unrelated one.
        runBlocking { viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
        LocalDataLayer.resetForTests()
        session.close()
    }

    private fun newViewModelScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { viewModelScope = it }

    private suspend fun awaitNotes(
        viewModel: io.payanam.presentation.notes.NotesViewModel,
        count: Int,
    ) {
        repeat(200) {
            if (viewModel.uiState.value.notes.size == count && !viewModel.uiState.value.isLoading) return
            delay(20)
        }
        throw AssertionError(
            "timed out waiting for $count notes; saw ${viewModel.uiState.value.notes.size}",
        )
    }

    @Test
    fun `the shared NotesViewModel executes against the desktop's encrypted database`(): Unit =
        runBlocking {
            // The holder throws when nothing is installed, so this is also the assertion that installing is
            // what makes the repositories reachable.
            LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))

            val viewModel = notesViewModel(newViewModelScope())

            // A write through the SHARED state layer, into the encrypted file.
            viewModel.createNote(
                title = "Written by the shared layer",
                details = "on the desktop",
                dimensionId = "dim_learning_growth",
                dimensionLabel = "Learning & Growth",
                tags = emptyList(),
            )
            awaitNotes(viewModel, 1)

            assertThat(
                viewModel.uiState.value.notes
                    .single()
                    .title,
            ).isEqualTo("Written by the shared layer")
            // And the row is really in the database, not merely in a state cache: read it back over a
            // separate connection path.
            val rows =
                session.requireConnection().createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM journal_notes").use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                }
            assertThat(rows).isEqualTo(1)
        }

    @Test
    fun `the installed layer serves the shared repositories the contract promises`() {
        LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))

        // Reading through the holder is what a shared ViewModel does; a missing entry throws, so this also
        // proves the desktop's layer is complete for the ViewModels that have moved.
        assertThat(LocalDataLayer.current.notes).isNotNull()
        assertThat(LocalDataLayer.current.tags).isNotNull()
    }

    @Test
    fun `a delete through the shared layer removes the row from the encrypted database`(): Unit =
        runBlocking {
            LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))
            val viewModel = notesViewModel(newViewModelScope())
            viewModel.createNote(
                title = "Temporary",
                details = null,
                dimensionId = "dim_learning_growth",
                dimensionLabel = "Learning & Growth",
                tags = emptyList(),
            )
            awaitNotes(viewModel, 1)

            viewModel.deleteNote(
                viewModel.uiState.value.notes
                    .single()
                    .id,
            )
            awaitNotes(viewModel, 0)

            val rows =
                session.requireConnection().createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM journal_notes").use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                }
            assertThat(rows).isEqualTo(0)
        }
}
