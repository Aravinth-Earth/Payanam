//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.feature.assistant

import androidx.room.withTransaction
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.AppSettingEntity
import io.payanam.database.session.DatabaseSessionManager
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

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
    ) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Reads the key + settings + reconfigure flag in ONE transaction (one consistent
         * snapshot; the flag tells the caller to show the setup surface).
         */
        suspend fun loadConfig(): StoredAssistantConfig =
            sessionManager.requireDatabase().withTransaction {
                StoredAssistantConfig(
                    apiKey = read(KEY_API_KEY) ?: "",
                    settings = loadSettings(),
                    setupRequired = read(KEY_SETUP_REQUIRED)?.toBooleanStrictOrNull() ?: false,
                )
            }.also { config ->
                logger.d(
                    "AssistantSettingsStore.loadConfig",
                    "Assistant config loaded",
                    mapOf("hasKey" to config.apiKey.isNotEmpty(), "setupRequired" to config.setupRequired),
                )
            }

        /** Loads the non-secret settings, applying defaults for missing rows. */
        private suspend fun loadSettings(): AssistantSettings {
            val settings = AssistantSettings(
                model = read(KEY_MODEL) ?: "",
                length = AssistantLength.fromName(read(KEY_LENGTH)),
                maxRows = read(KEY_MAX_ROWS)?.toIntOrNull() ?: AssistantDefaults.MAX_ROWS,
                rounds = read(KEY_ROUNDS)?.toIntOrNull() ?: AssistantDefaults.ROUNDS,
                historyDepth = read(KEY_HISTORY)?.toIntOrNull() ?: AssistantDefaults.HISTORY_DEPTH,
                showMethod = read(KEY_SHOW_METHOD)?.toBooleanStrictOrNull() ?: true,
                chipsEnabled = read(KEY_CHIPS)?.toBooleanStrictOrNull() ?: true,
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
            return settings
        }

        /**
         * Flags that the provider must be reconfigured: until [saveAll] (or a key removal)
         * clears it, the assistant destination resolves to the setup surface.
         */
        suspend fun beginReconfigure() {
            writeSetupRequired(true)
            logger.i("AssistantSettingsStore.beginReconfigure", "Reconfigure requested; assistant disabled until Save & start")
        }

        /**
         * Stores the neutral (non-secret, non-model) setting rows in one transaction.
         * The model row is owned by [saveAll]; the flag by [beginReconfigure]/[saveAll].
         */
        suspend fun save(settings: AssistantSettings) {
            val clean = settings.sanitized()
            sessionManager.requireDatabase().withTransaction { writeAll(clean) }
            logger.d("AssistantSettingsStore.save", "Assistant settings saved", mapOf("fields" to STORED_SETTING_ROWS))
        }

        /**
         * Stores the API key and the settings in ONE transaction — used when both change
         * together (the setup "Save & start" step). A partial write here would leave a stored
         * key behind with a stale/empty model across a restart. [apiKey] null means "keep the
         * stored key as it is".
         */
        suspend fun saveAll(apiKey: String?, settings: AssistantSettings) {
            val clean = settings.sanitized()
            sessionManager.requireDatabase().withTransaction {
                writeAll(clean)
                write(KEY_MODEL, clean.model)
                if (apiKey != null) write(KEY_API_KEY, apiKey)
                // Unconditional: the reconfigure-with-same-key path (apiKey == null) must clear it too.
                writeSetupRequired(false)
            }
            logger.i(
                "AssistantSettingsStore.saveAll",
                "Key + settings saved in one transaction",
                mapOf("fields" to STORED_SETTING_ROWS, "keyWritten" to (apiKey != null), "setupRequired" to false),
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
            write(KEY_NOTICE_SHOWN, clean.noticeShown.toString())
        }

        /** Marks the first-run disclaimer as seen. */
        suspend fun markNoticeShown() {
            write(KEY_NOTICE_SHOWN, "true")
            logger.i("AssistantSettingsStore.markNoticeShown", "First-run notice acknowledged")
        }

        /**
         * Removes the stored provider configuration — key, model and the reconfigure flag —
         * in ONE transaction (used by "Remove key" / replacing the provider).
         */
        suspend fun clearProviderConfiguration() {
            sessionManager.requireDatabase().withTransaction {
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
        internal fun fingerprint(value: String): String {
            if (value.isEmpty()) return "-"
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.take(FINGERPRINT_BYTES).joinToString("") { byte -> "%02x".format(byte) }
        }

        private suspend fun read(key: String): String? =
            sessionManager.requireDatabase().appSettingsDao().getSetting(key)?.value

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
            private const val KEY_API_KEY = "assistant.api_key"
            private const val KEY_MODEL = "assistant.model"
            private const val KEY_LENGTH = "assistant.length"
            private const val KEY_MAX_ROWS = "assistant.max_rows"
            private const val KEY_ROUNDS = "assistant.rounds"
            private const val KEY_HISTORY = "assistant.history_depth"
            private const val KEY_SHOW_METHOD = "assistant.show_method"
            private const val KEY_CHIPS = "assistant.chips"
            private const val KEY_PROMPT_ADDITIONS = "assistant.prompt_additions"
            private const val KEY_ABOUT_GOALS = "assistant.about_goals"
            private const val KEY_ABOUT_WANTS = "assistant.about_wants"
            private const val KEY_ABOUT_NEEDS = "assistant.about_needs"
            private const val KEY_ABOUT_STRENGTHS = "assistant.about_strengths"
            private const val KEY_ABOUT_FOCUS = "assistant.about_focus"
            private const val KEY_ABOUT_NOTE = "assistant.about_note"
            private const val KEY_NOTICE_SHOWN = "assistant.notice_shown"
            private const val KEY_SETUP_REQUIRED = "assistant.setup_required"

            /** Rows [writeAll] persists; the model row and the flag are written separately. */
            private const val STORED_SETTING_ROWS = 14
        }
    }

/**
 * One consistent snapshot of the assistant configuration: the stored key (empty when
 * unset), the non-secret settings, and the reconfigure flag. [toString] never emits the key.
 */
data class StoredAssistantConfig(
    val apiKey: String,
    val settings: AssistantSettings,
    val setupRequired: Boolean,
) {
    override fun toString(): String =
        "StoredAssistantConfig(apiKey=<redacted>, setupRequired=$setupRequired)"
}
