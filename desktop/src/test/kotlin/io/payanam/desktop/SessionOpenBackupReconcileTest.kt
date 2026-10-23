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
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the onOpened callback of [followDesktopSessionOpenedState].
 *
 * The shared unlock screen opens via DesktopSessionControllerOpenerAdapter and never fires the shell's
 * onSessionEstablished, so the backup schedule was never armed after unlock (zero 'Auto-backup schedule
 * applied from shared rows' log lines in every real run, 2026-10-08). The fix adds an onOpened callback
 * that fires on every session-open transition. These tests pin that behavior: the callback fires exactly
 * once per open (not at collection start, not on close), and the default (no callback) does not disturb
 * the gate mirror.
 */
class SessionOpenBackupReconcileTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "mirror-test"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

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
        io.payanam.desktop.UnifiedLogger
            .resetForTests()
        io.payanam.desktop.UnifiedLogger.initialize(
            logsDirectory =
                java.nio.file.Files
                    .createTempDirectory("payanam-desktop-test-logs"),
        )
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

    private fun preferencesSource() = DesktopPreferencesSource(sessionLogger = silentLogger, systemLanguageTag = null)

    /**
     * onOpened fires exactly once per session-open transition.
     *
     * The session starts closed, so the collector's initial emission (isOpen = false) must NOT fire
     * onOpened. After openAndInstall, onOpened fires once. After closeAndUninstall, it does NOT fire
     * again. After a second openAndInstall, it fires a second time.
     */
    @Test
    fun `opening a session through the controller fires onOpened exactly once`() =
        runBlocking {
            val controller = controller()
            val gateFlag = mutableStateOf(false)
            val onOpenedCount = AtomicInteger(0)
            val onOpened: suspend () -> Unit = { onOpenedCount.incrementAndGet() }
            val follower = launch { followDesktopSessionOpenedState(controller, gateFlag, preferencesSource(), onOpened) }
            try {
                // No spurious fire at collection start (session starts closed).
                assertThat(onOpenedCount.get()).isEqualTo(0)

                controller.openAndInstall(passphrase.toCharArray())
                awaitGateFlag(gateFlag, expected = true)
                assertThat(onOpenedCount.get()).isEqualTo(1)

                controller.closeAndUninstall()
                awaitGateFlag(gateFlag, expected = false)
                assertThat(onOpenedCount.get()).isEqualTo(1)

                controller.openAndInstall(passphrase.toCharArray())
                awaitGateFlag(gateFlag, expected = true)
                assertThat(onOpenedCount.get()).isEqualTo(2)
            } finally {
                follower.cancel()
            }
        }

    /**
     * The default onOpened (no 4th argument) does not disturb the gate mirror.
     *
     * Calls followDesktopSessionOpenedState with the positional-3 form, exactly as the three existing
     * test call sites do, and asserts that gateFlag still follows open/close exactly like
     * DesktopSessionOpenMirrorTest expects.
     */
    @Test
    fun `the default onOpened does not disturb the mirror`() =
        runBlocking {
            val controller = controller()
            val gateFlag = mutableStateOf(false)
            val follower = launch { followDesktopSessionOpenedState(controller, gateFlag, preferencesSource()) }
            try {
                assertThat(gateFlag.value).isFalse()

                controller.openAndInstall(passphrase.toCharArray())
                awaitGateFlag(gateFlag, expected = true)
                assertThat(gateFlag.value).isTrue()

                controller.closeAndUninstall()
                awaitGateFlag(gateFlag, expected = false)
                assertThat(gateFlag.value).isFalse()
            } finally {
                follower.cancel()
            }
        }

    /**
     * Waits for the follower to publish [expected], in REAL time.
     *
     * Not `runTest`/`runCurrent`: the collector's own body suspends into `Dispatchers.IO` for the row read,
     * so the value can land after any number of test-dispatcher turns. Asserting straight after the call
     * that caused it is exactly what made the mirror class pass alone and fail inside the full suite once.
     */
    private suspend fun awaitGateFlag(
        flag: MutableState<Boolean>,
        expected: Boolean,
    ) {
        withTimeout(5_000) { while (flag.value != expected) delay(10) }
    }
}
