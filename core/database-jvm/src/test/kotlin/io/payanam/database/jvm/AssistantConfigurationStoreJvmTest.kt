//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.assistant.AssistantAboutMe
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantLength
import io.payanam.domain.assistant.AssistantProvider
import io.payanam.domain.assistant.AssistantSettings
import io.payanam.shared.logging.AppLogger
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
 * Tests for [AssistantConfigurationStoreJvm], the desktop half of the assistant's stored configuration.
 *
 * Every member of `AssistantConfigurationStore` is covered, and every assertion is made against the
 * DATABASE ROWS rather than against the value the writer returned: this store's whole job is what it
 * leaves in storage, and a `save` that silently wrote nothing would still hand back the settings it was
 * given.
 *
 * Two things this class deliberately pins, because both fail silently:
 *
 *  - **The stored key set.** The keys are re-declared in the implementation (see its KDoc), so the
 *    literals below are asserted by name: a renamed key reads as an absent row, which looks like a
 *    first run rather than like a bug.
 *  - **The rows `save` is allowed to touch.** The single-writer discipline is the reason the port
 *    exists, and it is only observable on the rows.
 *
 * The `app_settings` DDL is copied verbatim from the Room schema export at schema version 22
 * (`core/database/schemas/io.payanam.database.PayanamDatabase/22.json`), with the export's
 * `${TABLE_NAME}` token substituted for `app_settings` exactly as this module's schema-DDL generator
 * does, and including the unique index. A hand-invented column set would let a mismatch between the
 * export and the writer pass unnoticed, which is the mismatch these tests exist to catch.
 *
 * Fixture and session follow [JdbcQueriesTest]: a generated SQLCipher file in a per-class temp directory,
 * reached through the sanctioned `forGeneratedFixture` factory. A real export is never used — it holds
 * the user's personal data.
 */
class AssistantConfigurationStoreJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "assistant-config-store-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var recordingLogger: RecordingLogger

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("assistant-config.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS app_settings (
                    `key` TEXT NOT NULL,
                    `value` TEXT,
                    `updatedAt` TEXT NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_app_settings_key` ON app_settings (`key`)",
            )
        }
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 22)
        recordingLogger = RecordingLogger()
        AppLogger.install(recordingLogger)
    }

    @After
    fun tearDown() {
        AppLogger.resetForTests()
        session.close()
    }

    private fun store() = AssistantConfigurationStoreJvm(session)

    /** Every row in `app_settings`, keyed by its literal storage key. */
    private fun rows(): Map<String, String?> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT `key`, value FROM app_settings").use { resultSet ->
                buildMap {
                    while (resultSet.next()) put(resultSet.getString(1), resultSet.getString(2))
                }
            }
        }

    /** One stored value, by literal key. Null for an absent row AND for a stored SQL NULL. */
    private fun raw(key: String): String? = rows()[key]

    private fun hasRow(key: String): Boolean = rows().containsKey(key)

    /** Writes a row straight through SQL, bypassing the store — how an unreadable row is simulated. */
    private fun seedRaw(
        key: String,
        value: String,
    ) {
        connection
            .prepareStatement(
                "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, key)
                statement.setString(2, value)
                statement.setString(3, "2026-01-01T00:00:00Z")
                statement.executeUpdate()
            }
    }

    /** Writes a row that EXISTS but holds SQL NULL — a third case, distinct from an absent row. */
    private fun seedRawNull(key: String) {
        connection
            .prepareStatement(
                "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES (?, NULL, ?)",
            ).use { statement ->
                statement.setString(1, key)
                statement.setString(2, "2026-01-01T00:00:00Z")
                statement.executeUpdate()
            }
    }

    /** The all-defaults settings value, spelled out so a changed domain default fails here too. */
    private fun defaults() =
        AssistantSettings(
            model = "",
            length = AssistantLength.BALANCED,
            maxRows = AssistantDefaults.MAX_ROWS,
            rounds = AssistantDefaults.ROUNDS,
            historyDepth = AssistantDefaults.HISTORY_DEPTH,
            showMethod = true,
            chipsEnabled = true,
            promptAdditions = "",
            aboutMe = AssistantAboutMe(),
            noticeShown = false,
        )

    // --- loadConfig -------------------------------------------------------------------------------

    @Test
    fun `every absent key loads the domain default and an empty key`() {
        runBlocking {
            val config = store().loadConfig()

            assertThat(config.apiKey).isEmpty()
            assertThat(config.setupRequired).isFalse()
            assertThat(config.settings).isEqualTo(defaults())
            // A first run must leave storage untouched: this reader never writes.
            assertThat(rows()).isEmpty()
        }
    }

    @Test
    fun `loadConfig reads the key the settings and the flag as one snapshot`() {
        runBlocking {
            store().saveAll("secret-key", defaults().copy(model = "gpt-x", length = AssistantLength.SHORT))

            store().beginReconfigure()
            val config = store().loadConfig()

            assertThat(config.apiKey).isEqualTo("secret-key")
            assertThat(config.settings.model).isEqualTo("gpt-x")
            assertThat(config.settings.length).isEqualTo(AssistantLength.SHORT)
            // The raw model comes back while a reconfigure is pending; the pending flag is what tells
            // the caller to show the setup surface.
            assertThat(config.setupRequired).isTrue()
        }
    }

    // --- beginReconfigure ------------------------------------------------------------------------

    @Test
    fun `beginReconfigure sets the flag and leaves the key and model rows untouched`() {
        runBlocking {
            store().saveAll("secret-key", defaults().copy(model = "gpt-x"))
            store().beginReconfigure()

            assertThat(raw("assistant.setup_required")).isEqualTo("true")
            assertThat(raw("assistant.api_key")).isEqualTo("secret-key")
            assertThat(raw("assistant.model")).isEqualTo("gpt-x")
        }
    }

    // --- save ------------------------------------------------------------------------------------

    @Test
    fun `save writes exactly the thirteen neutral rows`() {
        runBlocking {
            store().save(
                defaults().copy(
                    length = AssistantLength.ELABORATED,
                    maxRows = 250,
                    rounds = 4,
                    historyDepth = 12,
                    showMethod = false,
                    chipsEnabled = false,
                    promptAdditions = "Answer in Tamil script.",
                    aboutMe =
                        AssistantAboutMe(
                            goals = "G",
                            wants = "W",
                            needs = "N",
                            strengths = "S",
                            focusAreas = "F",
                            note = "Note",
                        ),
                    noticeShown = true,
                ),
            )

            // The whole key → value map, one entry per line: a pair-per-line list would be an
            // argument-wrapping violation, and a Map comparison does not depend on row order.
            val expected =
                mapOf(
                    "assistant.length" to "ELABORATED",
                    "assistant.max_rows" to "250",
                    "assistant.rounds" to "4",
                    "assistant.history_depth" to "12",
                    "assistant.show_method" to "false",
                    "assistant.chips" to "false",
                    "assistant.prompt_additions" to "Answer in Tamil script.",
                    "assistant.about_goals" to "G",
                    "assistant.about_wants" to "W",
                    "assistant.about_needs" to "N",
                    "assistant.about_strengths" to "S",
                    "assistant.about_focus" to "F",
                    "assistant.about_note" to "Note",
                )

            assertThat(rows()).isEqualTo(expected)
        }
    }

    @Test
    fun `a neutral save never writes the key the model row or the flag`() {
        runBlocking {
            store().saveAll("secret-key", defaults().copy(model = "gpt-x"))
            store().beginReconfigure()
            assertThat(raw("assistant.setup_required")).isEqualTo("true")

            store().save(defaults().copy(model = "gpt-y", maxRows = 300))

            // The single-writer discipline: a neutral writer can neither resurrect a model nor clear a
            // pending flag, and it did not touch the key.
            assertThat(raw("assistant.model")).isEqualTo("gpt-x")
            assertThat(raw("assistant.setup_required")).isEqualTo("true")
            assertThat(raw("assistant.api_key")).isEqualTo("secret-key")
            // ... and it did write the rows it does own.
            assertThat(raw("assistant.max_rows")).isEqualTo("300")
        }
    }

    @Test
    fun `save sanitizes the numbers it stores`() {
        runBlocking {
            store().save(defaults().copy(maxRows = 99_999, rounds = 0, historyDepth = -4))

            // Clamped into range by AssistantSettings.sanitized(), not stored raw.
            assertThat(raw("assistant.max_rows")).isEqualTo(AssistantDefaults.HARD_MAX_ROWS.toString())
            assertThat(raw("assistant.rounds")).isEqualTo(AssistantDefaults.MIN_ROUNDS.toString())
            assertThat(raw("assistant.history_depth")).isEqualTo(AssistantDefaults.MIN_HISTORY.toString())
        }
    }

    @Test
    fun `a second save overwrites the same rows rather than duplicating them`() {
        runBlocking {
            store().save(defaults().copy(promptAdditions = "first"))
            store().save(defaults().copy(promptAdditions = "second"))

            assertThat(rows().size).isEqualTo(13)
            assertThat(raw("assistant.prompt_additions")).isEqualTo("second")
        }
    }

    // --- saveAll ----------------------------------------------------------------------------------

    @Test
    fun `saveAll stores the key the model and the settings together and clears the flag`() {
        runBlocking {
            store().beginReconfigure()
            store().saveAll("secret-key", defaults().copy(model = "gpt-x", maxRows = 400))

            assertThat(raw("assistant.api_key")).isEqualTo("secret-key")
            assertThat(raw("assistant.model")).isEqualTo("gpt-x")
            assertThat(raw("assistant.max_rows")).isEqualTo("400")
            assertThat(raw("assistant.setup_required")).isEqualTo("false")
            // Thirteen neutral rows plus the three single-writer rows.
            assertThat(rows().size).isEqualTo(16)
        }
    }

    @Test
    fun `a null key keeps the stored key and still clears the flag`() {
        runBlocking {
            store().saveAll("secret-key", defaults().copy(model = "gpt-x"))
            store().beginReconfigure()

            // The reconfigure-with-the-same-key path: no key argument, but the flag MUST still clear.
            store().saveAll(null, defaults().copy(model = "gpt-y"))

            assertThat(raw("assistant.api_key")).isEqualTo("secret-key")
            assertThat(raw("assistant.model")).isEqualTo("gpt-y")
            assertThat(raw("assistant.setup_required")).isEqualTo("false")
        }
    }

    @Test
    fun `a failed saveAll leaves the earlier statements unwritten`() {
        runBlocking {
            store().saveAll("old-key", defaults().copy(model = "old-model", maxRows = 111))
            store().beginReconfigure()

            // A trigger that rejects one stored value makes a LATER statement of the transaction fail.
            // Without a transaction the fourteen neutral rows written before it would already be
            // committed, so this is the assertion that proves saveAll is all-or-nothing.
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TRIGGER reject_model BEFORE INSERT ON app_settings " +
                        "WHEN NEW.`key` = 'assistant.model' AND NEW.value = 'poison-model' " +
                        "BEGIN SELECT RAISE(ABORT, 'rejected model row'); END",
                )
            }

            val failure =
                runCatching {
                    store().saveAll("new-key", defaults().copy(model = "poison-model", maxRows = 999))
                }.exceptionOrNull()

            assertThat(failure).isNotNull()
            // Not one row of the failed write landed: not the key, not the model, not any of the neutral
            // rows, and not the flag clearing. Every value is still the one from BEFORE the failed write.
            assertThat(raw("assistant.api_key")).isEqualTo("old-key")
            assertThat(raw("assistant.model")).isEqualTo("old-model")
            assertThat(raw("assistant.max_rows")).isEqualTo("111")
            assertThat(raw("assistant.setup_required")).isEqualTo("true")
            assertThat(rows().size).isEqualTo(16)
        }
    }

    // --- saveConnection ---------------------------------------------------------------------------

    @Test
    fun `an install with neither endpoint row loads the gateway it already used`() {
        runBlocking {
            val connection = store().loadConfig().connection

            // THE UPGRADE GUARANTEE, the one case that must never regress: the endpoint rows did not
            // exist in the builds before them, so an install holding neither must read back as exactly
            // the endpoint those builds used.
            assertThat(connection.provider).isEqualTo(AssistantProvider.OPENCODE)
            assertThat(connection.baseUrl).isEqualTo(AssistantDefaults.BASE_URL)
            // Absence is benign — not an unreadable row — and the reader wrote nothing back.
            assertThat(recordingLogger.warnings).isEmpty()
            assertThat(rows()).isEmpty()
        }
    }

    @Test
    fun `a saved provider and address load back as stored`() {
        runBlocking {
            store().saveConnection(AssistantProvider.OPENROUTER, "https://openrouter.ai/api/v1")

            val connection = store().loadConfig().connection

            assertThat(connection.provider).isEqualTo(AssistantProvider.OPENROUTER)
            assertThat(connection.baseUrl).isEqualTo("https://openrouter.ai/api/v1")
            // The row holds the enum NAME, which is the storage format both platforms share.
            assertThat(raw("assistant.provider")).isEqualTo("OPENROUTER")
            assertThat(raw("assistant.base_url")).isEqualTo("https://openrouter.ai/api/v1")
        }
    }

    @Test
    fun `a custom address is kept verbatim and not replaced by a provider default`() {
        runBlocking {
            // A trailing slash AND a non-default port: neither is normalised, and the provider has no
            // default of its own that could stand in for it.
            store().saveConnection(AssistantProvider.CUSTOM_LOCAL, "http://192.168.1.20:8080/v1/")

            val connection = store().loadConfig().connection

            assertThat(connection.provider).isEqualTo(AssistantProvider.CUSTOM_LOCAL)
            assertThat(connection.baseUrl).isEqualTo("http://192.168.1.20:8080/v1/")
            assertThat(raw("assistant.base_url")).isEqualTo("http://192.168.1.20:8080/v1/")
        }
    }

    @Test
    fun `an unrecognised stored provider falls back to the gateway and is reported`() {
        runBlocking {
            seedRaw("assistant.provider", "GEMINI")

            val config = store().loadConfig()

            // Unmatched is not absent: the fallback is the same endpoint, but it must be LOUD, because
            // a name this build does not know means a row it cannot honour.
            assertThat(config.connection.provider).isEqualTo(AssistantProvider.OPENCODE)
            assertThat(config.connection.baseUrl).isEqualTo(AssistantDefaults.BASE_URL)
            val warnedKeys = recordingLogger.warnings.map { it.data["key"]?.toString() }
            assertThat(warnedKeys).containsExactly("assistant.provider")
            // Left in storage: the stored text is the only evidence of what was chosen.
            assertThat(raw("assistant.provider")).isEqualTo("GEMINI")
            assertThat(recordingLogger.text).doesNotContain("GEMINI")
        }
    }

    @Test
    fun `saveConnection writes only the provider and address rows`() {
        runBlocking {
            store().saveAll("secret-key", defaults().copy(model = "gpt-x", maxRows = 250))
            store().beginReconfigure()

            store().saveConnection(AssistantProvider.CUSTOM_CLOUD, "https://example.invalid/v1")

            // Sixteen rows from saveAll + beginReconfigure, two more from the connection write.
            assertThat(rows().size).isEqualTo(18)
            assertThat(raw("assistant.provider")).isEqualTo("CUSTOM_CLOUD")
            assertThat(raw("assistant.base_url")).isEqualTo("https://example.invalid/v1")
            // The rows other writers own are untouched: not the key, not the model, not any limit,
            // and not the pending flag.
            assertThat(raw("assistant.api_key")).isEqualTo("secret-key")
            assertThat(raw("assistant.model")).isEqualTo("gpt-x")
            assertThat(raw("assistant.max_rows")).isEqualTo("250")
            assertThat(raw("assistant.setup_required")).isEqualTo("true")
        }
    }

    // --- markNoticeShown --------------------------------------------------------------------------

    @Test
    fun `markNoticeShown records the notice without touching the key or the model`() {
        runBlocking {
            store().saveAll("secret-key", defaults().copy(model = "gpt-x"))
            store().beginReconfigure()

            store().markNoticeShown()

            assertThat(raw("assistant.notice_shown")).isEqualTo("true")
            assertThat(raw("assistant.api_key")).isEqualTo("secret-key")
            assertThat(raw("assistant.model")).isEqualTo("gpt-x")
            assertThat(raw("assistant.setup_required")).isEqualTo("true")
        }
    }

    // --- clearProviderConfiguration ---------------------------------------------------------------

    @Test
    fun `clearProviderConfiguration removes the key the model the endpoint and the flag and keeps the neutral rows`() {
        runBlocking {
            store().saveAll("secret-key", defaults().copy(model = "gpt-x", promptAdditions = "keep me"))
            store().beginReconfigure()
            store().saveConnection(AssistantProvider.OPENROUTER, "https://openrouter.ai/api/v1")

            store().clearProviderConfiguration()

            assertThat(hasRow("assistant.api_key")).isFalse()
            assertThat(hasRow("assistant.model")).isFalse()
            assertThat(hasRow("assistant.setup_required")).isFalse()
            // The endpoint rows go as a PAIR: leaving either behind would hand the next configuration
            // a host the user had already removed.
            assertThat(hasRow("assistant.provider")).isFalse()
            assertThat(hasRow("assistant.base_url")).isFalse()
            // The neutral settings survive a key change: the user's own text is not the provider's.
            assertThat(raw("assistant.prompt_additions")).isEqualTo("keep me")
            assertThat(rows().size).isEqualTo(13)
        }
    }

    // --- fingerprint -----------------------------------------------------------------------------

    @Test
    fun `fingerprint is short stable and one-way, and an empty value says so`() {
        val store = store()

        val fingerprint = store.fingerprint("secret-key")

        // Four bytes of SHA-256 rendered as hex: what a trace correlation carries instead of the key.
        assertThat(fingerprint).matches("[0-9a-f]{8}")
        assertThat(fingerprint).isEqualTo(store.fingerprint("secret-key"))
        assertThat(fingerprint).isNotEqualTo(store.fingerprint("other-key"))
        assertThat(fingerprint).doesNotContain("secret-key")
        assertThat(store.fingerprint("")).isEqualTo("-")
    }

    // --- parsing edges ---------------------------------------------------------------------------

    @Test
    fun `a malformed number loads its default and the stored row survives the load`() {
        runBlocking {
            seedRaw("assistant.max_rows", "not-a-number")

            val config = store().loadConfig()

            assertThat(config.settings.maxRows).isEqualTo(AssistantDefaults.MAX_ROWS)
            // The silent-wrong case: the reader must not rewrite the row, because the stored text is the
            // only record of what the user chose.
            assertThat(raw("assistant.max_rows")).isEqualTo("not-a-number")
        }
    }

    @Test
    fun `an empty stored string is unreadable for a number and for a boolean`() {
        runBlocking {
            seedRaw("assistant.rounds", "")
            seedRaw("assistant.show_method", "")

            val config = store().loadConfig()

            // `toIntOrNull()` and `toBooleanStrictOrNull()` both reject "", so both take their default
            // rather than collapsing to 0 or false.
            assertThat(config.settings.rounds).isEqualTo(AssistantDefaults.ROUNDS)
            assertThat(config.settings.showMethod).isTrue()
            assertThat(raw("assistant.rounds")).isEqualTo("")
            assertThat(raw("assistant.show_method")).isEqualTo("")
        }
    }

    @Test
    fun `an unknown length name and a non-strict boolean take their default and are reported by key`() {
        runBlocking {
            seedRaw("assistant.length", "GIGANTIC")
            seedRaw("assistant.chips", "TRUE")

            val config = store().loadConfig()

            assertThat(config.settings.length).isEqualTo(AssistantLength.BALANCED)
            // Strict on purpose: "TRUE" and "1" are not silently accepted as true.
            assertThat(config.settings.chipsEnabled).isTrue()

            // Reported in the order the fields are resolved, so a field added to the reader cannot
            // reorder this unnoticed: chips is resolved before length.
            val warnedKeys = recordingLogger.warnings.mapNotNull { it.data["key"]?.toString() }
            assertThat(warnedKeys).containsExactly("assistant.chips", "assistant.length").inOrder()
            // The report names the KEY; the stored VALUE must never reach the log.
            assertThat(recordingLogger.text).doesNotContain("GIGANTIC")
            assertThat(recordingLogger.text).doesNotContain("TRUE")
        }
    }

    @Test
    fun `a missing row is not reported as unreadable and a null value reads as its default`() {
        runBlocking {
            val fresh = store().loadConfig()

            assertThat(fresh.settings.maxRows).isEqualTo(AssistantDefaults.MAX_ROWS)
            assertThat(recordingLogger.warnings).isEmpty()

            // A row that EXISTS holding SQL NULL is not an absent row, and reading it must not throw.
            seedRawNull("assistant.history_depth")

            val loaded = store().loadConfig()

            assertThat(loaded.settings.historyDepth).isEqualTo(AssistantDefaults.HISTORY_DEPTH)
        }
    }

    @Test
    fun `an unreadable setup-required flag fails CLOSED to true and is reported`() {
        runBlocking {
            seedRaw("assistant.setup_required", "yes")

            // Fail-closed: an unparseable flag loads as true (into the setup surface), not false.
            // The flag is read through AssistantStoredValue.boolean with fallback = false (absent row
            // = no reconfigure needed), then the unreadable case is overridden to true.
            assertThat(store().loadConfig().setupRequired).isTrue()
            assertThat(recordingLogger.warnings).isNotEmpty()
            assertThat(recordingLogger.warnings.first().data["key"]).isEqualTo("assistant.setup_required")
        }
    }

    @Test
    fun `save does NOT write the notice row, so markNoticeShown remains the sole writer`() {
        runBlocking {
            store().markNoticeShown()
            assertThat(raw("assistant.notice_shown")).isEqualTo("true")

            // Single-writer: save must not touch the notice row. Saving noticeShown = false after
            // the notice was acknowledged must NOT clear it.
            store().save(defaults())

            assertThat(raw("assistant.notice_shown")).isEqualTo("true")
            assertThat(store().loadConfig().settings.noticeShown).isTrue()
        }
    }

    /** Captures log entries so the unreadable-row report can be asserted rather than assumed. */
    private class RecordingLogger : UnifiedLogger {
        private val entries = mutableListOf<Entry>()

        val warnings: List<Entry>
            get() = entries.filter { it.level == "W" }

        val text: String
            get() = entries.joinToString("\n") { "${it.level} ${it.source} ${it.message} ${it.data}" }

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            entries += Entry("D", source, message, data)
        }

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            entries += Entry("I", source, message, data)
        }

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            entries += Entry("W", source, message, data)
        }

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            entries += Entry("E", source, message, data)
        }

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            entries += Entry("E", source, message, data)
        }

        data class Entry(
            val level: String,
            val source: String,
            val message: String,
            val data: Map<String, Any?>,
        )
    }
}
