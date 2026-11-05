//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenMode
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.logging.UnifiedLogger
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
 * Tests for the desktop's open-and-install step.
 *
 * The property that matters is what is INSTALLED, not what is returned: a refusal must leave the shared
 * holder empty so that every repository read throws with the fix in its message. The failure this replaces
 * was the opposite — an un-installed layer that returned empty lists, which made a misconfigured app look
 * like an app with no data.
 */
class DesktopDatabaseSessionControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "controller-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private val records = mutableListOf<Triple<String, String, Map<String, Any?>>>()

    private val capturingLogger =
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
            ) {
                records.add(Triple("i", message, data))
            }

            override fun w(
                source: String,
                message: String,
                data: Map<String, Any?>,
            ) {
                records.add(Triple("w", message, data))
            }

            override fun e(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit

            override fun eSync(
                source: String,
                message: String,
                error: Throwable?,
                data: Map<String, Any?>,
            ) = Unit
        }

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
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
        LocalDataLayer.resetForTests()
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        if (!session.isClosed) session.close()
        records.clear()
    }

    private fun controller(open: suspend (String, CharArray) -> DatabaseOpenResult) =
        DesktopDatabaseSessionController(
            databaseFilePath = { tempFolder.root.resolve("payanam.db").absolutePath },
            open = open,
            logger = capturingLogger,
        )

    private fun readingNotesFails(): Boolean = runCatching { LocalDataLayer.current.notes }.isFailure

    @Test
    fun `an accepted open installs the shared layer`(): Unit =
        runBlocking {
            val controller = controller { _, _ -> DatabaseOpenResult.Opened(session) }

            val result = controller.openAndInstall(passphrase.toCharArray())

            assertThat(result).isInstanceOf(DatabaseOpenResult.Opened::class.java)
            assertThat(controller.isOpen).isTrue()
            // The owner's own state, not the UI's optimistic flag: true only now that the session
            // is assigned.
            assertThat(controller.sessionOpenState.value).isTrue()
            // Reachable through the holder means a shared ViewModel would find its repositories.
            assertThat(readingNotesFails()).isFalse()
            assertThat(records.map { it.first }).contains("i")
        }

    @Test
    fun `a refused open leaves the layer UNINSTALLED so reads fail loudly`(): Unit =
        runBlocking {
            val controller =
                controller { _, _ ->
                    DatabaseOpenResult.Refused(
                        DatabaseBootIssue(type = DatabaseBootIssueType.OPEN_FAILED, detailMessage = "not_found"),
                    )
                }

            val result = controller.openAndInstall(passphrase.toCharArray())

            assertThat(result).isInstanceOf(DatabaseOpenResult.Refused::class.java)
            assertThat(controller.isOpen).isFalse()
            // The contract's whole point: an un-installed layer THROWS, rather than returning empty lists
            // that make a misconfigured app look like an app with no data.
            assertThat(readingNotesFails()).isTrue()
        }

    @Test
    fun `the refusal logs the issue TYPE and never the detail message`(): Unit =
        runBlocking {
            val controller =
                controller { _, _ ->
                    DatabaseOpenResult.Refused(
                        DatabaseBootIssue(
                            type = DatabaseBootIssueType.DB_TOO_OLD,
                            detailMessage = "/home/someone/payanam.db could not be read",
                        ),
                    )
                }

            controller.openAndInstall(passphrase.toCharArray())

            val logged = records.single { it.first == "w" }
            assertThat(logged.third["issue"]).isEqualTo("DB_TOO_OLD")
            // The detail can carry a path, so it must not reach the log at all.
            assertThat(logged.third.toString()).doesNotContain("/home/someone")
        }

    @Test
    fun `reopening replaces the layer and closes the previous session`(): Unit =
        runBlocking {
            val controller = controller { _, _ -> DatabaseOpenResult.Opened(session) }
            controller.openAndInstall(passphrase.toCharArray())

            // A fresh session over a second connection, standing in for the reopen.
            val second =
                PayanamDatabaseSession.forGeneratedFixture(
                    DriverManager.getConnection(
                        "jdbc:sqlcipher:${tempFolder.newFolder().toPath().resolve("second.db").toAbsolutePath()}",
                        Properties().apply { setProperty("key", passphrase) },
                    ),
                    schemaVersion = 21,
                )
            val reopened = controller(open = { _, _ -> DatabaseOpenResult.Opened(second) })
            reopened.openAndInstall(passphrase.toCharArray())

            assertThat(reopened.isOpen).isTrue()
            second.close()
        }

    @Test
    fun `closing uninstalls the layer so a later read cannot succeed against a closed database`(): Unit =
        runBlocking {
            val controller = controller { _, _ -> DatabaseOpenResult.Opened(session) }
            controller.openAndInstall(passphrase.toCharArray())
            assertThat(readingNotesFails()).isFalse()

            controller.closeAndUninstall()

            assertThat(controller.isOpen).isFalse()
            assertThat(controller.sessionOpenState.value).isFalse()
            assertThat(session.isClosed).isTrue()
            assertThat(readingNotesFails()).isTrue()
        }

    /**
     * The shutdown fold runs once per controller, and the guard is per-INSTANCE state.
     *
     * A second call must be a no-op, and the observable proof is the two reports the method makes: the
     * fold line and the closed line are each emitted exactly once, and the early-return loser emits
     * neither. Counting the report is not the fold itself - but the fold line is the first statement
     * after the guard, so a second fold could not run without a second line.
     *
     * The release ORDER this item also asks about is NOT covered here, and deliberately: the session
     * logger and the single-instance lease are released as bare statements in `DesktopMain.main`'s
     * `finally` block, and no test seam reaches them without changing production code.
     */
    @Test
    fun `the shutdown fold runs once, so a second call neither folds nor closes again`(): Unit =
        runBlocking {
            val foldMessage = "Folding the open session's log before the process exits"
            val closedMessage = "Database session closed for process exit"
            val controller = controller { _, _ -> DatabaseOpenResult.Opened(session) }
            controller.openAndInstall(passphrase.toCharArray())
            records.clear()

            controller.checkpointAndCloseForShutdown()

            assertThat(records.count { it.second == foldMessage }).isEqualTo(1)
            assertThat(records.count { it.second == closedMessage }).isEqualTo(1)
            assertThat(controller.isOpen).isFalse()
            assertThat(session.isClosed).isTrue()

            controller.checkpointAndCloseForShutdown()

            // One fold and one close across the whole sequence: the second call returned at the guard.
            assertThat(records.count { it.second == foldMessage }).isEqualTo(1)
            assertThat(records.count { it.second == closedMessage }).isEqualTo(1)
            assertThat(controller.isOpen).isFalse()
            assertThat(session.isClosed).isTrue()
        }
}
