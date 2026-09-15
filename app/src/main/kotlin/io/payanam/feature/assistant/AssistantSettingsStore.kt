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
 */
@Singleton
class AssistantSettingsStore
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) {
        private val logger = UnifiedLogger.getInstance()

        /** Loads the stored API key (empty string when unset). */
        suspend fun loadApiKey(): String = read(KEY_API_KEY) ?: ""

        /** Stores the API key; logs only length + fingerprint. */
        suspend fun saveApiKey(value: String) {
            write(KEY_API_KEY, value)
            logger.i(
                "AssistantSettingsStore.saveApiKey",
                "API key stored",
                mapOf("length" to value.length, "fingerprint" to fingerprint(value)),
            )
        }

        /** Loads every non-secret setting, applying defaults for missing rows. */
        suspend fun load(): AssistantSettings {
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
            logger.d(
                "AssistantSettingsStore.load",
                "Assistant settings loaded",
                mapOf(
                    "model" to settings.model,
                    "length" to settings.length.name,
                    "maxRows" to settings.maxRows,
                    "rounds" to settings.rounds,
                    "historyDepth" to settings.historyDepth,
                    "showMethod" to settings.showMethod,
                    "chipsEnabled" to settings.chipsEnabled,
                    "promptAdditionsChars" to settings.promptAdditions.length,
                    "aboutMeEmpty" to settings.aboutMe.isEmpty(),
                    "noticeShown" to settings.noticeShown,
                ),
            )
            return settings
        }

        /** Stores every non-secret setting field atomically (single transaction). */
        suspend fun save(settings: AssistantSettings) {
            val clean = settings.sanitized()
            sessionManager.requireDatabase().withTransaction { writeAll(clean) }
            logger.d("AssistantSettingsStore.save", "Assistant settings saved", mapOf("fields" to FIELD_COUNT))
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
                if (apiKey != null) write(KEY_API_KEY, apiKey)
            }
            logger.d(
                "AssistantSettingsStore.saveAll",
                "Key + settings saved in one transaction",
                mapOf("fields" to FIELD_COUNT, "keyWritten" to (apiKey != null)),
            )
        }

        /** Writes the key + every settings row; callers must already hold the transaction. */
        private suspend fun writeAll(clean: AssistantSettings) {
            write(KEY_MODEL, clean.model)
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

        /** Deletes the stored key (used when the user replaces it). */
        suspend fun clearApiKey() {
            sessionManager.requireDatabase().appSettingsDao().deleteSetting(KEY_API_KEY)
            logger.i("AssistantSettingsStore.clearApiKey", "API key cleared")
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
            private const val FIELD_COUNT = 15
        }
    }
