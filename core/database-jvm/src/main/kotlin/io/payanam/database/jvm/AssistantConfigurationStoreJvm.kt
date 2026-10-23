//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.domain.assistant.AssistantAboutMe
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantSettings
import io.payanam.domain.assistant.AssistantStoredValue
import io.payanam.domain.port.AssistantConfigurationStore
import io.payanam.domain.port.StoredAssistantConfig
import io.payanam.shared.logging.AppLogger
import java.security.MessageDigest
import java.time.Instant

/**
 * JDBC implementation of [AssistantConfigurationStore] over the encrypted database — the desktop's half
 * of the assistant's stored configuration, in the same `app_settings` rows the Android implementation
 * writes, so one database holds one configuration whichever app wrote it.
 *
 * Ported from `AssistantSettingsStore` (the app module's implementation) as the truth for behaviour: the
 * same storage keys, the same parse-and-default rules, the same rows each member owns. The one platform
 * coupling there — Room's `withTransaction` — is [JdbcQueries.transaction] here, which also joins a
 * nested call instead of committing early, so [saveAll] and [clearProviderConfiguration] keep the
 * all-or-nothing property the Android versions have.
 *
 * **THE SINGLE-WRITER DISCIPLINE IS PRESERVED, not approximated.** [save] writes only the fourteen
 * neutral rows (NEUTRAL_SETTING_ROWS): never the key, never the model row, never the pending-setup
 * flag. So a neutral writer cannot resurrect a model the user removed or silently clear a pending
 * reconfigure — the failure the port's KDoc names as the reason this interface exists.
 *
 * ## The storage keys are re-declared here, and that is the drift-prone choice
 *
 * [AssistantConfigurationStore] names the rows but not their string values, and the Android
 * implementation keeps its `KEY_*` constants private to its own companion object. The alternative —
 * moving them onto the shared type beside the port, so both implementations read one declaration — is
 * the choice that stops the two implementations drifting apart, and it is the one this port should
 * eventually get. **It is not done here**: the Android file would have to be edited to read the shared
 * constants, and this port may only add files. Re-declaring the seventeen literals is therefore a
 * deliberate, bounded duplication: it is cheap here (a constant either matches or the store reads
 * nothing) and it is caught the moment it does not, because
 * `AssistantConfigurationStoreJvmTest` asserts the complete stored key set against literal strings and
 * every read in these tests is asserted by literal key. The drift risk is the row it shares with the
 * Android file, and the test is the detector, not the design. Moving the constants onto the port is a
 * follow-up in one place: `AssistantSettingsStore`'s companion plus this one.
 *
 * ## Two places the port's wording and the Android behaviour do not agree
 *
 * Neither is smoothed over here; both are reported to the layer above, and the Android behaviour is
 * what this port implements because the shared database and the existing rows are the truth:
 *
 *  - **[markNoticeShown] is not the only writer of the notice row.** The port presents it as *the*
 *    notice operation and names exactly two single-writer rows (the key to [saveAll], the flag to
 *    [beginReconfigure] and [saveAll]); [save] also writes `assistant.notice_shown`, because it is a
 *    field of the settings being saved. So a neutral save of a settings value whose `noticeShown` is
 *    stale-false un-records the first-run notice that [markNoticeShown] recorded. The port does not
 *    document that writer, and the fix — whether [save] should stop writing the row or the port should
 *    name it — is a contract decision, not a porting decision.
 *  - **An unreadable pending-setup flag fails OPEN and is not reported.** Every numeric and boolean
 *    setting goes through [AssistantStoredValue], which distinguishes an absent row from an unreadable
 *    one and reports the unreadable key. The flag does not: an unparseable `assistant.setup_required`
 *    reads as `false`, i.e. "no reconfigure needed", with no warning. Every other field in the same
 *    row-set fails the safe way (default for this load, row left alone), and the port nowhere
 *    authorises this one to do the opposite. Reported, not repaired: changing the Android behaviour
 *    would change what an already-corrupt database means on the phone.
 *
 * `updatedAt` is stamped with [Instant] because that is what the Android assistant store writes into
 * this column for these rows. The sibling `AppSettingsRepositoryJvm` stamps local date-time instead; the
 * two forms are both TEXT and nothing in the assistant path parses the column, so the Android form is
 * the one used here to keep the rows this port writes indistinguishable from the rows Android writes.
 *
 * @param session an already-open session owned by the caller.
 */
class AssistantConfigurationStoreJvm(
    private val session: PayanamDatabaseSession,
) : AssistantConfigurationStore {
    private val logger = AppLogger.current

    /**
     * Reads the key, the settings and the pending-setup flag in ONE transaction, so the returned
     * [StoredAssistantConfig] is a single consistent snapshot rather than three independent reads.
     *
     * An absent key row and an empty key row both load as `""`, which is what the snapshot's non-null
     * key promises. The flag's `?: false` is the fail-open case named in this class's KDoc.
     */
    override suspend fun loadConfig(): StoredAssistantConfig =
        JdbcFlows.onIo {
            val config =
                JdbcQueries.transaction(session) {
                    StoredAssistantConfig(
                        apiKey = read(KEY_API_KEY) ?: "",
                        settings = loadSettings(),
                        setupRequired = read(KEY_SETUP_REQUIRED)?.toBooleanStrictOrNull() ?: false,
                    )
                }
            logger.d(
                SOURCE,
                "Assistant configuration loaded",
                mapOf("hasKey" to config.apiKey.isNotEmpty(), "setupRequired" to config.setupRequired),
            )
            config
        }

    /**
     * Flags that the provider must be reconfigured, leaving the key and the model row untouched: the
     * stored key is what keeps "Load models" usable while the setup surface is showing.
     */
    override suspend fun beginReconfigure() {
        JdbcFlows.onIo {
            writeSetupRequired(true)
            logger.i(SOURCE, "Reconfigure requested; assistant disabled until save")
        }
    }

    /**
     * Stores the neutral setting rows in one transaction — and, by NEUTRAL_SETTING_ROWS, nothing else:
     * no key, no model row, no pending-setup flag.
     */
    override suspend fun save(settings: AssistantSettings) {
        JdbcFlows.onIo {
            val clean = settings.sanitized()
            JdbcQueries.transaction(session) { writeNeutralSettings(clean) }
            logger.d(SOURCE, "Assistant settings saved", mapOf("rows" to NEUTRAL_SETTING_ROWS))
        }
    }

    /**
     * Stores the key, the model row, the neutral settings and the cleared pending-setup flag in ONE
     * transaction. A partial write here would leave a stored key behind with a stale or empty model
     * across a restart, which is exactly what the transaction exists to prevent.
     *
     * A null [apiKey] means "keep the stored key" — the reconfigure-with-the-same-key path — and the
     * flag is cleared on it too.
     */
    override suspend fun saveAll(
        apiKey: String?,
        settings: AssistantSettings,
    ) {
        JdbcFlows.onIo {
            val clean = settings.sanitized()
            JdbcQueries.transaction(session) {
                writeNeutralSettings(clean)
                write(KEY_MODEL, clean.model)
                if (apiKey != null) write(KEY_API_KEY, apiKey)
                // Unconditional: the reconfigure-with-the-same-key path (apiKey == null) must clear it too.
                writeSetupRequired(false)
            }
            logger.i(
                SOURCE,
                "Assistant key and settings saved in one transaction",
                mapOf("rows" to NEUTRAL_SETTING_ROWS, "keyWritten" to (apiKey != null), "setupRequired" to false),
            )
        }
    }

    /**
     * Records that the first-run notice has been seen. Note that [save] writes the same row from the
     * settings it is handed; see this class's KDoc.
     */
    override suspend fun markNoticeShown() {
        JdbcFlows.onIo {
            write(KEY_NOTICE_SHOWN, true.toString())
            logger.i(SOURCE, "First-run notice acknowledged")
        }
    }

    /**
     * Removes the stored provider configuration — key, pending-setup flag and model row — in ONE
     * transaction, leaving the neutral settings alone so the user keeps their prompt additions and
     * "about me" text across a key change.
     */
    override suspend fun clearProviderConfiguration() {
        JdbcFlows.onIo {
            JdbcQueries.transaction(session) {
                delete(KEY_API_KEY)
                delete(KEY_SETUP_REQUIRED)
                delete(KEY_MODEL)
            }
            logger.i(SOURCE, "Provider configuration cleared (key, model, reconfigure flag)")
        }
    }

    /** Short non-reversible fingerprint used for trace correlation; `"-"` for an empty value. */
    override fun fingerprint(value: String): String {
        if (value.isEmpty()) return "-"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(FINGERPRINT_BYTES).joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Loads the non-secret settings. A MISSING row takes its default; a row that EXISTS but cannot be
     * parsed is reported and then left in storage untouched. This reader never writes — defaulting an
     * unreadable row AND writing that default back on the next save would destroy the stored value with
     * nothing to show for it.
     */
    private fun loadSettings(): AssistantSettings {
        val maxRows = AssistantStoredValue.int(KEY_MAX_ROWS, read(KEY_MAX_ROWS), AssistantDefaults.MAX_ROWS)
        val rounds = AssistantStoredValue.int(KEY_ROUNDS, read(KEY_ROUNDS), AssistantDefaults.ROUNDS)
        val history = AssistantStoredValue.int(KEY_HISTORY, read(KEY_HISTORY), AssistantDefaults.HISTORY_DEPTH)
        val showMethod = AssistantStoredValue.boolean(KEY_SHOW_METHOD, read(KEY_SHOW_METHOD), fallback = true)
        val chips = AssistantStoredValue.boolean(KEY_CHIPS, read(KEY_CHIPS), fallback = true)
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
            aboutMe =
                AssistantAboutMe(
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
     * Says WHICH stored row could not be read. The value itself is never logged, and the row is not
     * rewritten: the stored text is the only record of what the user chose.
     */
    private fun reportUnreadableStoredRow(key: String) {
        logger.w(
            SOURCE,
            "Stored assistant setting is unreadable; using the default for this load and leaving the row unchanged",
            mapOf("key" to key),
        )
    }

    /**
     * Writes the neutral settings rows; the caller must already hold the transaction.
     *
     * Deliberately EXCLUDES the model row (single-writer: [saveAll]) and the pending-setup flag
     * ([writeSetupRequired]) so a neutral writer can never resurrect a model or clear the flag.
     */
    private fun writeNeutralSettings(clean: AssistantSettings) {
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

    private fun read(key: String): String? = JdbcQueries.readScalar(session, SETTING_BY_KEY_SQL, arrayOf(key))

    private fun write(
        key: String,
        value: String,
    ) {
        JdbcQueries.write(session, UPSERT_SETTING_SQL, arrayOf(key, value, Instant.now().toString()))
    }

    private fun delete(key: String) {
        JdbcQueries.write(session, DELETE_SETTING_SQL, arrayOf(key))
    }

    /**
     * Writes the pending-setup flag. Called ONLY by [beginReconfigure] and [saveAll] — deliberately not
     * from [writeNeutralSettings], so a neutral writer can never clear it.
     */
    private fun writeSetupRequired(value: Boolean) {
        write(KEY_SETUP_REQUIRED, value.toString())
    }

    private companion object {
        /** Log-source tag. */
        private const val SOURCE = "AssistantConfigurationStoreJvm"

        private const val FINGERPRINT_BYTES = 4

        /**
         * Rows [save] writes, asserted against this writer by `AssistantConfigurationStoreJvmTest`: the
         * number in the log line must not be able to report rows the writer stopped writing.
         */
        private const val NEUTRAL_SETTING_ROWS = 14

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

        /** Verbatim from `AppSettingsDao.getSetting`; `key` is a SQL keyword, so it is backticked. */
        const val SETTING_BY_KEY_SQL = "SELECT value FROM app_settings WHERE `key` = ?"

        /** `AppSettingsDao.insertSetting` is `@Insert(REPLACE)`; the primary key is the key. */
        const val UPSERT_SETTING_SQL =
            "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES (?, ?, ?)"

        /** Verbatim from `AppSettingsDao.deleteSetting`. */
        const val DELETE_SETTING_SQL = "DELETE FROM app_settings WHERE `key` = ?"
    }
}
