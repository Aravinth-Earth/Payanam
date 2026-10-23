//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.desktop.platform.DesktopDebugFlags
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * The debug-logging toggle, end to end: the shared `debug_logging_enabled` row reaches the desktop logger's
 * own gate, and the gate is what the `d()` call sites consult.
 *
 * The first test is the seam on its own; the second is the wiring, because a toggle that records a value
 * without moving the logger is exactly the shape this change exists to remove.
 */
class DesktopDebugLoggingToggleTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var logger: UnifiedLogger
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val logsDirectory: Path = tempFolder.newFolder("logs").toPath()
        // The logger is a process-wide singleton; without the reset a later class would inherit this gate.
        UnifiedLogger.resetForTests()
        logger = UnifiedLogger.initialize(logsDirectory = logsDirectory, debugEnabled = false)

        val path = tempFolder.newFolder("db").toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", "desktop-debug-toggle-test") }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
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
        LocalDataLayer.resetForTests()
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        runCatching { session.close() }
        UnifiedLogger.resetForTests()
    }

    @Test
    fun `the toggle moves the session logger's debug gate`() {
        val flags = DesktopDebugFlags()

        // The gate starts where the build type starts it; the logger below is pinned off at construction,
        // so the first d() call is withheld whatever that starting value is.
        assertThat(flags.debugLoggingEnabled).isEqualTo(DesktopBuildInfo.IS_DEBUG_BUILD)
        logger.d("DesktopDebugLoggingToggleTest", "before the toggle", emptyMap())
        assertThat(debugLines()).isEmpty()

        assertThat(flags.setDebugLoggingEnabled(true)).isTrue()
        logger.d("DesktopDebugLoggingToggleTest", "after the toggle", emptyMap())
        assertThat(debugLines()).hasSize(1)

        assertThat(flags.setDebugLoggingEnabled(false)).isFalse()
        logger.d("DesktopDebugLoggingToggleTest", "after turning it back off", emptyMap())
        assertThat(debugLines()).hasSize(1)
    }

    @Test
    fun `the debug_logging_enabled row drives the gate, and pre-unlock leaves it alone`(): Unit =
        runBlocking {
            val flags = DesktopDebugFlags()
            val source =
                DesktopPreferencesSource(
                    sessionLogger = NoOpUnifiedLogger,
                    systemLanguageTag = "en",
                    onDebugLoggingChanged = { enabled -> flags.setDebugLoggingEnabled(enabled) },
                )

            // No session: there are no rows to follow, so the gate keeps its own value.
            flags.setDebugLoggingEnabled(true)
            source.reportNoSession()
            assertThat(flags.debugLoggingEnabled).isTrue()

            // With a session open, the row is the source of truth - on a read AND on a write.
            LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))
            writeRow(AppPreferenceKeys.KEY_DEBUG_LOGGING_ENABLED, "false")
            source.reload()
            assertThat(flags.debugLoggingEnabled).isFalse()

            source.setSetting(AppPreferenceKeys.KEY_DEBUG_LOGGING_ENABLED, "true")
            assertThat(flags.debugLoggingEnabled).isTrue()
            logger.d("DesktopDebugLoggingToggleTest", "enabled by the row", emptyMap())
            assertThat(debugLines()).hasSize(1)
        }

    private fun debugLines(): List<String> =
        Files
            .readAllLines(logger.getLogPath())
            .filter { line -> line.contains(" | DEBUG | ") }

    private fun writeRow(
        key: String,
        value: String,
    ) {
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES ('$key', '$value', '2026-09-23T00:00:00')",
            )
        }
    }
}
