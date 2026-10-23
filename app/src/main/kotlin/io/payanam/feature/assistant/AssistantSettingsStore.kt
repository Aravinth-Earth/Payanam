//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.AppSettingEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.assistant.AssistantAboutMe
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantLength
import io.payanam.domain.assistant.AssistantSettings
import io.payanam.domain.assistant.AssistantStoredValue
import io.payanam.domain.port.AssistantConfigurationStore
import io.payanam.domain.port.StoredAssistantConfig
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rows one `save` writes into `app_settings`.
 *
 * The number is asserted against the writer against a REAL database by
 * `AssistantSettingsWriteCountTest`, because the log line that quotes it must not be able to
 * report rows the writer stopped writing.
 */
internal const val STORED_SETTING_ROWS = 13

/**
 * Persists assistant configuration inside the app's **encrypted** database
 * (`app_settings` rows) — the API key therefore lives behind SQLCipher, exactly like
 * the rest of the user's data, and is unavailable while the DB session is closed.
 *
 * The key value is never logged: only its length and a short SHA-256 fingerprint.
 *
 * Two rows follow a single-writer discipline: the model row is written only by [saveAll],
 * and the reconfigure flag (`KEY_SETUP_REQUIRED`) only by [beginReconfigure] (set) and
 * [saveAll]/[clearProviderConfiguration] (clear). Neutral writers ([save] and dispose/
 * onCleared flushes) write neither, so they can never resurrect a model or clear a pending
 * reconfigure. A pending flag rides database backups like any other row; recovery is the
 * normal setup path (the stored key keeps "Load models" usable).
 */
@Singleton
class AssistantSettingsStore
@Inject
constructor(
    private val sessionManager: DatabaseSessionManager,
) : AssistantConfigurationStore {
    private val logger = UnifiedLogger.getInstance()

    /**
     * Reads the key + settings + reconfigure flag in ONE transaction (one consistent
     * snapshot; the flag tells the caller to show the setup surface).
     */
    override suspend fun loadConfig(): StoredAssistantConfig = sessionManager.inTransaction {
        val setupRequiredField =
            AssistantStoredValue.boolean(KEY_SETUP_REQUIRED, read(KEY_SETUP_REQUIRED), fallback = false)
        // Fail-closed: an unreadable row loads as true (into the setup surface), not false.
        val setupRequired = if (setupRequiredField.usable) setupRequiredField.value else true
        if (!setupRequiredField.usable) reportUnreadableStoredRow(KEY_SETUP_REQUIRED)
        StoredAssistantConfig(
            apiKey = read(KEY_API_KEY) ?: "",
            settings = loadSettings(),
            setupRequired = setupRequired,
        )
    }.also { config ->
        logger.d(
            "AssistantSettingsStore.loadConfig",
            "Assistant config loaded",
            mapOf("hasKey" to config.apiKey.isNotEmpty(), "setupRequired" to config.setupRequired),
        )
    }

    /**
     * Loads the non-secret settings. A MISSING row takes its default; a row that EXISTS but
     * cannot be parsed is reported and then left in storage untouched — see
     * [AssistantStoredValue]. This reader never writes: the old `?:` chain defaulted an
     * unreadable stored row AND the next save wrote that default back, destroying the stored
     * value with nothing to show for it.
     */
    private suspend fun loadSettings(): AssistantSettings {
        val maxRows = AssistantStoredValue.int(KEY_MAX_ROWS, read(KEY_MAX_ROWS), AssistantDefaults.MAX_ROWS)
        val rounds = AssistantStoredValue.int(KEY_ROUNDS, read(KEY_ROUNDS), AssistantDefaults.ROUNDS)
        val history =
            AssistantStoredValue.int(KEY_HISTORY, read(KEY_HISTORY), AssistantDefaults.HISTORY_DEPTH)
        val showMethod =
            AssistantStoredValue.boolean(KEY_SHOW_METHOD, read(KEY_SHOW_METHOD), fallback = true)
        val chips =
            AssistantStoredValue.boolean(KEY_CHIPS, read(KEY_CHIPS), fallback = true)
        val length = AssistantStoredValue.length(KEY_LENGTH, read(KEY_LENGTH))
        listOf(maxRows, rounds, history, showMethod, chips, length)
            .forEach { field -> if (!field.usable) reportUnreadableStoredRow(field.key) }
        return AssistantSettings(
            model = read(KEY_MODEL) ?: "",
            length = length.value,
            maxRows = maxRows.value,
            rounds = rounds.value,
            historyDepth = history.value,
            showMethod = showMethod.value,
            chipsEnabled = chips.value,
            promptAdditions = read(KEY_PROMPT_ADDITIONS) ?: "",
            aboutMe = AssistantAboutMe(
                goals = read(KEY_ABOUT_GOALS) ?: "",
                wants = read(KEY_ABOUT_WANTS) ?: "",
                needs = read(KEY_ABOUT_NEEDS) ?: "",
                strengths = read(KEY_ABOUT_STRENGTHS) ?: "",
                focusAreas = read(KEY_ABOUT_FOCUS) ?: "",
                note = read(KEY_ABOUT_NOTE) ?: "",
            ),
            noticeShown = read(KEY_NOTICE_SHOWN)?.toBooleanStrictOrNull() ?: false,
        ).sanitized()
    }

    /**
     * Says WHICH stored row could not be read. The value itself is never logged, and the row is
     * not rewritten: the stored text is the only evidence of what the user chose.
     */
    private fun reportUnreadableStoredRow(key: String) {
        logger.w(
            "AssistantSettingsStore.loadSettings",
            "Stored assistant setting is unreadable; using the default for this load and leaving the row unchanged",
            mapOf("key" to key),
        )
    }

    /**
     * Flags that the provider must be reconfigured: until [saveAll] (or a key removal)
     * clears it, the assistant destination resolves to the setup surface.
     */
    override suspend fun beginReconfigure() {
        writeSetupRequired(true)
        logger.i("AssistantSettingsStore.beginReconfigure", "Reconfigure requested; assistant disabled until Save & start")
    }

    /**
     * Stores the neutral (non-secret, non-model) setting rows in one transaction.
     * The model row is owned by [saveAll]; the flag by [beginReconfigure]/[saveAll].
     */
    override suspend fun save(settings: AssistantSettings) {
        val clean = settings.sanitized()
        sessionManager.inTransaction { writeAll(clean) }
        logger.d("AssistantSettingsStore.save", "Assistant settings saved", mapOf("rows" to STORED_SETTING_ROWS))
    }

    /**
     * Stores the API key and the settings in ONE transaction — used when both change
     * together (the setup "Save & start" step). A partial write here would leave a stored
     * key behind with a stale/empty model across a restart. [apiKey] null means "keep the
     * stored key as it is".
     */
    override suspend fun saveAll(apiKey: String?, settings: AssistantSettings) {
        val clean = settings.sanitized()
        sessionManager.inTransaction {
            writeAll(clean)
            write(KEY_MODEL, clean.model)
            if (apiKey != null) write(KEY_API_KEY, apiKey)
            // Unconditional: the reconfigure-with-same-key path (apiKey == null) must clear it too.
            writeSetupRequired(false)
        }
        logger.i(
            "AssistantSettingsStore.saveAll",
            "Key + settings saved in one transaction",
            mapOf("rows" to STORED_SETTING_ROWS, "keyWritten" to (apiKey != null), "setupRequired" to false),
        )
    }

    /**
     * Writes the neutral settings rows; callers must already hold the transaction.
     * Deliberately EXCLUDES the model row (single-writer: [saveAll]) and the reconfigure
     * flag ([writeSetupRequired]) so neutral writers (debounced saves, dispose/onCleared
     * flushes) can never resurrect a model or clear the flag.
     */
    private suspend fun writeAll(clean: AssistantSettings) {
        write(KEY_LENGTH, clean.length.name)
        write(KEY_MAX_ROWS, clean.maxRows.toString())
        write(KEY_ROUNDS, clean.rounds.toString())
        write(KEY_HISTORY, clean.historyDepth.toString())
        write(KEY_SHOW_METHOD, clean.showMethod.toString())
        write(KEY_CHIPS, clean.chipsEnabled.toString())
        write(KEY_PROMPT_ADDITIONS, clean.promptAdditions)
        write(KEY_ABOUT_GOALS, clean.aboutMe.goals)
        write(KEY_ABOUT_WANTS, clean.aboutMe.wants)
        write(KEY_ABOUT_NEEDS, clean.aboutMe.needs)
        write(KEY_ABOUT_STRENGTHS, clean.aboutMe.strengths)
        write(KEY_ABOUT_FOCUS, clean.aboutMe.focusAreas)
        write(KEY_ABOUT_NOTE, clean.aboutMe.note)
    }

    /** Marks the first-run disclaimer as seen. */
    override suspend fun markNoticeShown() {
        write(KEY_NOTICE_SHOWN, "true")
        logger.i("AssistantSettingsStore.markNoticeShown", "First-run notice acknowledged")
    }

    /**
     * Removes the stored provider configuration — key, model and the reconfigure flag —
     * in ONE transaction (used by "Remove key" / replacing the provider).
     */
    override suspend fun clearProviderConfiguration() {
        sessionManager.inTransaction {
            delete(KEY_API_KEY)
            delete(KEY_SETUP_REQUIRED)
            delete(KEY_MODEL)
        }
        logger.i(
            "AssistantSettingsStore.clearProviderConfiguration",
            "Provider configuration cleared (key, model, reconfigure flag)",
        )
    }

    /** Short non-reversible fingerprint used for trace correlation. */
    override fun fingerprint(value: String): String {
        if (value.isEmpty()) return "-"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(FINGERPRINT_BYTES).joinToString("") { byte -> "%02x".format(byte) }
    }

    private suspend fun read(key: String): String? = sessionManager.requireDatabase().appSettingsDao().getSetting(key)?.value

    private suspend fun write(key: String, value: String?) {
        sessionManager.requireDatabase().appSettingsDao().insertSetting(
            AppSettingEntity(key = key, value = value, updatedAt = Instant.now().toString()),
        )
    }

    private suspend fun delete(key: String) {
        sessionManager.requireDatabase().appSettingsDao().deleteSetting(key)
    }

    /**
     * Writes the reconfigure flag. Called ONLY by [beginReconfigure] and [saveAll] —
     * deliberately not from [writeAll], so neutral writers can never clear it.
     */
    private suspend fun writeSetupRequired(value: Boolean) {
        write(KEY_SETUP_REQUIRED, value.toString())
    }

    private companion object {
        private const val FINGERPRINT_BYTES = 4
        private const val KEY_API_KEY = AssistantConfigurationStore.KEY_API_KEY
        private const val KEY_MODEL = AssistantConfigurationStore.KEY_MODEL
        private const val KEY_LENGTH = AssistantConfigurationStore.KEY_LENGTH
        private const val KEY_MAX_ROWS = AssistantConfigurationStore.KEY_MAX_ROWS
        private const val KEY_ROUNDS = AssistantConfigurationStore.KEY_ROUNDS
        private const val KEY_HISTORY = AssistantConfigurationStore.KEY_HISTORY
        private const val KEY_SHOW_METHOD = AssistantConfigurationStore.KEY_SHOW_METHOD
        private const val KEY_CHIPS = AssistantConfigurationStore.KEY_CHIPS
        private const val KEY_PROMPT_ADDITIONS = AssistantConfigurationStore.KEY_PROMPT_ADDITIONS
        private const val KEY_ABOUT_GOALS = AssistantConfigurationStore.KEY_ABOUT_GOALS
        private const val KEY_ABOUT_WANTS = AssistantConfigurationStore.KEY_ABOUT_WANTS
        private const val KEY_ABOUT_NEEDS = AssistantConfigurationStore.KEY_ABOUT_NEEDS
        private const val KEY_ABOUT_STRENGTHS = AssistantConfigurationStore.KEY_ABOUT_STRENGTHS
        private const val KEY_ABOUT_FOCUS = AssistantConfigurationStore.KEY_ABOUT_FOCUS
        private const val KEY_ABOUT_NOTE = AssistantConfigurationStore.KEY_ABOUT_NOTE
        private const val KEY_NOTICE_SHOWN = AssistantConfigurationStore.KEY_NOTICE_SHOWN
        private const val KEY_SETUP_REQUIRED = AssistantConfigurationStore.KEY_SETUP_REQUIRED
    }
}
