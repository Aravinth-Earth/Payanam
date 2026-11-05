//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
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
 * Tests for the desktop's session PORT: the shared shape a platform-neutral caller sees.
 *
 * What matters is that every accessor reports the HONEST state — true only after a session was
 * actually assigned — and that a refusal's failure message carries the issue TYPE and never the
 * detail, which can hold a filesystem path a user might paste into a public bug report.
 *
 * Fixture and fakes follow [DesktopDatabaseSessionControllerTest]: a generated SQLCipher file in a
 * per-class temp folder, the injected `open` lambda driving both outcomes, and the same capturing
 * logger.
 */
class DesktopDatabaseSessionPortTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "port-test"
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

    private fun port(open: suspend (String, CharArray) -> DatabaseOpenResult) =
        DesktopDatabaseSessionPort(
            DesktopDatabaseSessionController(
                databaseFilePath = { tempFolder.root.resolve("payanam.db").absolutePath },
                open = open,
                logger = capturingLogger,
            ),
        )

    @Test
    fun `an accepted open reports the session open through both accessors`(): Unit =
        runBlocking {
            val port = port { _, _ -> DatabaseOpenResult.Opened(session) }
            assertThat(port.isSessionOpen()).isFalse()
            assertThat(port.sessionOpenState.value).isFalse()

            val result = port.openDatabase(passphrase)

            assertThat(result.isSuccess).isTrue()
            assertThat(port.isSessionOpen()).isTrue()
            // The flow's value IS what a collector receives: StateFlow conflates to the current state.
            assertThat(port.sessionOpenState.value).isTrue()
        }

    @Test
    fun `a refused open fails with the issue TYPE and never the detail`(): Unit =
        runBlocking {
            val port =
                port { _, _ ->
                    DatabaseOpenResult.Refused(
                        DatabaseBootIssue(
                            type = DatabaseBootIssueType.DB_TOO_OLD,
                            detailMessage = "/home/someone/payanam.db could not be read",
                        ),
                    )
                }

            val result = port.openDatabase(passphrase)

            assertThat(result.isFailure).isTrue()
            val message = result.exceptionOrNull()?.message
            // Which class of database problem occurred — enough for a log or a screen, whatever the
            // detail said.
            assertThat(message).contains("DB_TOO_OLD")
            // The detail can carry a path; it must not reach a caller that might log or show it.
            assertThat(message).doesNotContain("/home/someone")
            assertThat(message).doesNotContain("could not be read")
            // A refusal installed nothing, so both accessors stay honestly closed.
            assertThat(port.isSessionOpen()).isFalse()
            assertThat(port.sessionOpenState.value).isFalse()
        }

    @Test
    fun `closeDatabase reports the session closed through both accessors`(): Unit =
        runBlocking {
            val port = port { _, _ -> DatabaseOpenResult.Opened(session) }
            assertThat(port.openDatabase(passphrase).isSuccess).isTrue()

            port.closeDatabase()

            assertThat(port.isSessionOpen()).isFalse()
            assertThat(port.sessionOpenState.value).isFalse()
            assertThat(session.isClosed).isTrue()
        }

    @Test
    fun `requireOpenPassphrase throws while closed and answers once open`(): Unit =
        runBlocking {
            val port = port { _, _ -> DatabaseOpenResult.Opened(session) }

            val whileClosed = runCatching { port.requireOpenPassphrase() }
            assertThat(whileClosed.isFailure).isTrue()
            assertThat(whileClosed.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)

            assertThat(port.openDatabase(passphrase).isSuccess).isTrue()
            assertThat(port.requireOpenPassphrase()).isEqualTo(passphrase)

            // Closing clears the retained passphrase too, so it cannot outlive the unlock.
            port.closeDatabase()
            assertThat(runCatching { port.requireOpenPassphrase() }.isFailure).isTrue()
        }
}
