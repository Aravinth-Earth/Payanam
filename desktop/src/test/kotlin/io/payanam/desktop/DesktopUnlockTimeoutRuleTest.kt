//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.preferences.AppPreferenceKeys
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
 * Android's derive-and-clamp rule for `session_timeout_minutes`, pinned where the rule now lives.
 *
 * These two cases were guards on `DesktopSettingsStore`, which is deleted; the rule moved to
 * `DesktopPreferencesSource.resolveUnlockTimeoutMinutes`, so the guards moved with it. A guard whose subject
 * is gone is coverage the migration quietly drops, and this is the rule whose two wrong readings the parity
 * doc already had to correct twice.
 *
 * The fixture is a generated encrypted database - the same pattern `DesktopPreferencesSourceTest` uses. A real
 * export is the user's personal data and is never used.
 */
class DesktopUnlockTimeoutRuleTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "desktop-timeout-rule-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var source: DesktopPreferencesSource

    @Before
    fun setUp() {
        // The desktop logger is process-wide, and this class constructs collaborators that reach for it:
        // initialize it HERE (fully qualified, because several siblings import the SHARED UnifiedLogger under
        // the same name) rather than relying on a sibling class's leak - the shared-JVM class order is not a
        // contract, and a solo run of this class must work. The directory outlives the class on purpose.
        io.payanam.desktop.UnifiedLogger.resetForTests()
        io.payanam.desktop.UnifiedLogger.initialize(
            logsDirectory = java.nio.file.Files.createTempDirectory("payanam-desktop-test-logs"),
        )
        val path = tempFolder.newFolder().toPath().resolve("payanam.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
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
        // The holder is process-wide, so a leaked install would let a later class pass on this one's state.
        LocalDataLayer.resetForTests()
        source = DesktopPreferencesSource(sessionLogger = noOpLogger(), systemLanguageTag = "en")
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
        runCatching { session.close() }
    }

    @Test
    fun `an absent timeout row is DERIVED as twice the backup interval, as Android derives it`() {
        // Android writes the derivation, not a constant: 2x the auto-backup interval, which with the default
        // 60m interval is 120. A reader that defaulted to the security preferences' 10 would disagree with
        // the phone for every user who never chose a timeout.
        runBlocking {
            installLayer()
            assertThat(reloadTimeoutMinutes()).isEqualTo(120)

            writeRow(AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL, "30m")
            assertThat(reloadTimeoutMinutes()).isEqualTo(60)
        }
    }

    @Test
    fun `a stored timeout is honoured, and one outside Android's bounds is clamped`() {
        // Android's manager clamps to 1..240, so a value outside that range is not merely unusual - it is a
        // value the phone would never lock on.
        runBlocking {
            installLayer()
            writeRow(AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES, "45")
            assertThat(reloadTimeoutMinutes()).isEqualTo(45)

            writeRow(AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES, "9999")
            writeRow(AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL, "60m")
            assertThat(reloadTimeoutMinutes()).isEqualTo(240)

            // Zero is not a timeout, it is a missing one, so it takes the derivation rather than clamping to 1.
            writeRow(AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES, "0")
            writeRow(AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL, "6h")
            assertThat(reloadTimeoutMinutes()).isEqualTo(240)
        }
    }

    /** The source reads the rows through the installed layer; without this every read throws by design. */
    private fun installLayer() {
        LocalDataLayer.install(DesktopPayanamDataLayer(session, pollIntervalMs = 10L))
    }

    private suspend fun reloadTimeoutMinutes(): Int {
        source.reload()
        return source.unlockTimeoutMinutes.value
    }

    private fun writeRow(
        key: String,
        value: String,
    ) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES ('$key', '$value', '2026-01-01T00:00:00')",
            )
        }
    }

    private fun noOpLogger(): UnifiedLogger =
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
            ) = Unit

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
}
