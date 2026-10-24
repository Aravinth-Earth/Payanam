//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.domain.assistant.AssistantAboutMe
import io.payanam.domain.assistant.AssistantConnection
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantProvider
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
 * coupling there — the Android platform's transaction helper — is [JdbcQueries.transaction] here, which also joins a
 * nested call instead of committing early, so [saveAll] and [clearProviderConfiguration] keep the
 * all-or-nothing property the Android versions have.
 *
 * **THE SINGLE-WRITER DISCIPLINE IS PRESERVED, not approximated.** [save] writes only the thirteen
 * neutral rows (NEUTRAL_SETTING_ROWS): never the key, never the model row, never the endpoint rows,
 * never the pending-setup flag. So a neutral writer cannot resurrect a model the user removed, drop
 * the provider they chose, or silently clear a pending reconfigure — the failure the port's KDoc names
 * as the reason this interface exists. [saveConnection] is the only writer of `KEY_PROVIDER` and
 * `KEY_BASE_URL`, and [markNoticeShown] of the notice row.
 *
 * ## The storage keys
 *
 * The literals live on [AssistantConfigurationStore]'s companion, so both platforms read one
 * declaration; each implementation re-declares them as private aliases. That only shortens the names —
 * a mismatched alias reads as an absent row, which looks like a first run rather than like a bug, so
 * `AssistantConfigurationStoreJvmTest` asserts the stored key set against literal strings and every
 * read in those tests is asserted by literal key. The test is the detector, not the design.
 *
 * ## Single-writer discipline
 *
 * [markNoticeShown] is the ONLY writer of the notice row. [save] does not write it, so a neutral
 * save of a settings value whose `noticeShown` is stale-false cannot un-record the first-run notice.
 *
 * ## Fail-closed pending-setup flag
 *
 * An unparseable `assistant.setup_required` row loads as `true` (fail-closed: into the setup
 * surface) and is reported via [reportUnreadableStoredRow]. The flag is read through
 * [AssistantStoredValue.boolean] with `fallback = false` (absent row = no reconfigure needed),
 * then the unreadable case is overridden to `true` so a corrupt row cannot silently disable the
 * setup surface.
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
     * Reads the key, the settings, the endpoint and the pending-setup flag in ONE transaction, so the
     * returned [StoredAssistantConfig] is a single consistent snapshot rather than four independent reads.
     *
     * An absent key row and an empty key row both load as `""`, which is what the snapshot's non-null
     * key promises. The flag's `?: false` is the fail-open case named in this class's KDoc.
     */
    override suspend fun loadConfig(): StoredAssistantConfig =
        JdbcFlows.onIo {
            val config =
                JdbcQueries.transaction(session) {
                    val setupRequiredField =
                        AssistantStoredValue.boolean(KEY_SETUP_REQUIRED, read(KEY_SETUP_REQUIRED), fallback = false)
                    // Fail-closed: an unreadable row loads as true (into the setup surface), not false.
                    val setupRequired = if (setupRequiredField.usable) setupRequiredField.value else true
                    if (!setupRequiredField.usable) reportUnreadableStoredRow(KEY_SETUP_REQUIRED)
                    val settings = loadSettings()
                    when (val endpoint = loadConnection(settings.model)) {
                        is StoredEndpoint.Unrecognised -> {
                            // The stored provider names no provider we know, so none of this
                            // configuration can be trusted: the stored address may belong to a host
                            // the stored key was never entered for. Load the state a not-yet-set-up
                            // install has instead of guessing, and leave every row in storage
                            // untouched - the stored text is the only evidence of what the user chose.
                            StoredAssistantConfig(
                                // Not deleted, but never offered as usable for a host we cannot name.
                                apiKey = "",
                                settings = settings,
                                // Forced, whatever the stored flag says: the user has to choose again.
                                setupRequired = true,
                                // No provider and no address: which host the stored address described
                                // is unknown, so substituting a default is the same guess in another shape.
                                connection = AssistantConnection(provider = null, baseUrl = "", model = settings.model),
                            )
                        }

                        is StoredEndpoint.Resolved -> {
                            StoredAssistantConfig(
                                apiKey = read(KEY_API_KEY) ?: "",
                                settings = settings,
                                setupRequired = setupRequired,
                                connection = endpoint.connection,
                            )
                        }
                    }
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
     * no key, no model row, no endpoint rows, no pending-setup flag.
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
     * Stores the provider and its address in one transaction, and writes nothing else: no key, no
     * model row, no pending-setup flag.
     *
     * The two rows are written together because a provider without an address — or an address with
     * no provider — describes no endpoint. The address is not logged: it can name a host on the
     * user's own network, so only whether one was stored is recorded.
     */
    override suspend fun saveConnection(
        provider: AssistantProvider,
        baseUrl: String,
    ) {
        JdbcFlows.onIo {
            JdbcQueries.transaction(session) {
                write(KEY_PROVIDER, provider.name)
                write(KEY_BASE_URL, baseUrl)
            }
            logger.i(
                SOURCE,
                "Assistant provider and endpoint saved",
                mapOf("provider" to provider.name, "addressStored" to baseUrl.isNotBlank()),
            )
        }
    }

    /**
     * Records that the first-run notice has been seen. This is the ONLY writer of the notice row;
     * [save] does not write it.
     */
    override suspend fun markNoticeShown() {
        JdbcFlows.onIo {
            write(KEY_NOTICE_SHOWN, true.toString())
            logger.i(SOURCE, "First-run notice acknowledged")
        }
    }

    /**
     * Removes the stored provider configuration — key, pending-setup flag, model row, and the
     * provider and address rows — in ONE transaction, leaving the neutral settings alone so the user
     * keeps their prompt additions and "about me" text across a key change.
     *
     * The endpoint rows go too, not only the key and model: a "clear" that left a stale address
     * behind would hand the next configuration a host the user had already removed.
     */
    override suspend fun clearProviderConfiguration() {
        JdbcFlows.onIo {
            JdbcQueries.transaction(session) {
                delete(KEY_API_KEY)
                delete(KEY_SETUP_REQUIRED)
                delete(KEY_MODEL)
                delete(KEY_PROVIDER)
                delete(KEY_BASE_URL)
            }
            logger.i(SOURCE, "Provider configuration cleared (key, model, endpoint, reconfigure flag)")
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
     * Reads the stored endpoint, telling the three cases apart that `KEY_PROVIDER` can be in. Identical in
     * shape and behaviour to the Android store's reader.
     *
     * THE UPGRADE RULE (absent row): an install written by a build that predates the provider list has
     * NEITHER row, and that user must keep exactly the endpoint they already had — [AssistantProvider.OPENCODE]
     * at its own default address, the gateway those builds used. Nothing changes for them, no migration is
     * written, and nothing is reported: this is not a fault.
     *
     * THE ANOMALY (row present, name unrecognised): a newer build wrote it, or the row is corrupt. Nothing
     * can be inferred from it — not the provider, and so not which host the stored address belonged to — so
     * this returns [StoredEndpoint.Unrecognised] and no connection at all, rather than pairing a name with
     * an address. It is reported through [reportUnreadableStoredRow] with the key only, never the stored
     * value, and the rows are left exactly as they are.
     *
     * A blank stored address is treated as absent; the two custom providers have a null default, so this
     * never invents one.
     */
    private fun loadConnection(model: String): StoredEndpoint {
        val storedProvider = read(KEY_PROVIDER)
        if (storedProvider == null) {
            // ABSENT and PRESENT-BUT-UNRECOGNISED are different states, and the branches below are kept
            // separate on purpose. Do NOT collapse them into one `?: AssistantProvider.OPENCODE`: absent
            // means "the endpoint those builds used, and the stored key belongs to it"; unrecognised
            // means "we cannot say which host that key was for".
            val storedBaseUrl = read(KEY_BASE_URL)
            val provider = AssistantProvider.OPENCODE
            return StoredEndpoint.Resolved(
                AssistantConnection(
                    provider = provider,
                    baseUrl = if (storedBaseUrl.isNullOrBlank()) provider.defaultBaseUrl.orEmpty() else storedBaseUrl,
                    model = model,
                ),
            )
        }

        val matched = AssistantProvider.entries.firstOrNull { it.name == storedProvider }
        if (matched == null) {
            // The address row is deliberately NOT read here: without a provider we cannot tell which
            // host it named, and returning it would be the guess this branch exists to refuse.
            reportUnreadableStoredRow(KEY_PROVIDER)
            return StoredEndpoint.Unrecognised
        }

        val storedBaseUrl = read(KEY_BASE_URL)
        return StoredEndpoint.Resolved(
            AssistantConnection(
                provider = matched,
                baseUrl = if (storedBaseUrl.isNullOrBlank()) matched.defaultBaseUrl.orEmpty() else storedBaseUrl,
                model = model,
            ),
        )
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
     * Deliberately EXCLUDES the model row (single-writer: [saveAll]), the endpoint rows (single-writer:
     * [saveConnection]) and the pending-setup flag ([writeSetupRequired]) so a neutral writer can never
     * resurrect a model, drop the chosen provider, or clear the flag.
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
        private const val NEUTRAL_SETTING_ROWS = 13

        private const val KEY_API_KEY = AssistantConfigurationStore.KEY_API_KEY
        private const val KEY_MODEL = AssistantConfigurationStore.KEY_MODEL
        private const val KEY_PROVIDER = AssistantConfigurationStore.KEY_PROVIDER
        private const val KEY_BASE_URL = AssistantConfigurationStore.KEY_BASE_URL
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

        /** Verbatim from `AppSettingsDao.getSetting`; `key` is a SQL keyword, so it is backticked. */
        const val SETTING_BY_KEY_SQL = "SELECT value FROM app_settings WHERE `key` = ?"

        /** `AppSettingsDao.insertSetting` is `@Insert(REPLACE)`; the primary key is the key. */
        const val UPSERT_SETTING_SQL =
            "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES (?, ?, ?)"

        /** Verbatim from `AppSettingsDao.deleteSetting`. */
        const val DELETE_SETTING_SQL = "DELETE FROM app_settings WHERE `key` = ?"
    }
}

/**
 * What reading `assistant.provider` + `assistant.base_url` produced — the three states this store has to
 * tell apart, made into a type so they cannot be collapsed back together.
 *
 * [Resolved] covers the two cases where an endpoint can honestly be named: the row names a recognised
 * [AssistantProvider], or the row is ABSENT (the ordinary upgrade from a build that predates the provider
 * list, where the stored key does belong to the default gateway).
 *
 * [Unrecognised] is the third case and carries NO connection on purpose: the name is PRESENT but matches
 * no entry, so which host the stored address described is unknowable, and handing back any provider — or
 * any address — would present the stored credential to a host it may never have been entered for. That is
 * the defect this type exists to make unrepresentable, so a caller is forced to decide what a
 * configuration that cannot be trusted looks like instead of inheriting a plausible default.
 */
private sealed interface StoredEndpoint {
    /** A provider that can be named, with the address that belongs to it. */
    class Resolved(
        val connection: AssistantConnection,
    ) : StoredEndpoint

    /** The provider row is PRESENT but names no [AssistantProvider] entry: an anomaly, never an upgrade. */
    data object Unrecognised : StoredEndpoint
}
