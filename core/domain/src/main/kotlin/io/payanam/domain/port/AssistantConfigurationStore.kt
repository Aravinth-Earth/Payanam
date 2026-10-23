//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.domain.assistant.AssistantSettings

/**
 * The assistant's stored configuration: the provider key, the settings, and whether the user still has to
 * finish setting the provider up.
 *
 * WHY THIS EXISTS: the assistant screen's state cannot be shared while the thing that STORES that state
 * cannot be named by shared code. The store is an Android type today - the Android platform's transaction helper, an
 * `app_settings` DAO, the Keyguard-closed session handle - and all three of those are the platform's, not
 * the screen's. The screen needs six operations and one value from it; this interface is exactly those,
 * so a platform that cannot store a key must say so by refusing rather than by pretending a session began.
 *
 * THE SINGLE-WRITER DISCIPLINE IS PART OF THE CONTRACT, not of an implementation: the key row belongs to
 * [saveAll], the pending-setup flag to [beginReconfigure] and [saveAll], and the notice row to
 * [markNoticeShown], so the neutral writer [save] can never resurrect a model, silently clear a flag,
 * or record the notice as shown. An implementation that cannot keep that promise cannot satisfy this
 * interface, and the assistant screen is the only caller that depends on it.
 */
interface AssistantConfigurationStore {
    /** The key, the settings and the pending-setup flag as ONE consistent snapshot. */
    suspend fun loadConfig(): StoredAssistantConfig

    /** Flags that the provider must be reconfigured before the chat surface is reachable again. */
    suspend fun beginReconfigure()

    /** Stores the neutral settings. Writes neither the key, nor the model row, nor the pending-setup flag, nor the notice row. */
    suspend fun save(settings: AssistantSettings)

    /**
     * Stores the key and the settings in one write, and clears the pending-setup flag.
     *
     * A null [apiKey] means "keep the stored key", which is the reconfigure-with-the-same-key path and must
     * still clear the flag.
     */
    suspend fun saveAll(apiKey: String?, settings: AssistantSettings)

    /** Records that the first-run notice has been seen. This is the only writer of the notice row. */
    suspend fun markNoticeShown()

    /** Removes the key, the model row and the pending-setup flag in one write. */
    suspend fun clearProviderConfiguration()

    /**
     * A short non-reversible fingerprint of [value], for trace correlation.
     *
     * Callers log this instead of the key, so an implementation that cannot produce one has to return
     * something that says "no fingerprint" rather than the value itself.
     */
    fun fingerprint(value: String): String

    companion object {
        /**
         * The `app_settings` rows this store owns, declared ONCE so both implementations read the
         * same names.
         *
         * These used to be re-declared privately in each implementation, with the row keyed by a
         * string in both - the drift-prone arrangement, where a typo in one reader fails silently as
         * a missing row rather than as a compile error. The values are the storage format: they are
         * the same literals on both platforms and must never be renamed without a migration.
         */
        const val KEY_API_KEY = "assistant.api_key"
        const val KEY_MODEL = "assistant.model"
        const val KEY_LENGTH = "assistant.length"
        const val KEY_MAX_ROWS = "assistant.max_rows"
        const val KEY_ROUNDS = "assistant.rounds"
        const val KEY_HISTORY = "assistant.history_depth"
        const val KEY_SHOW_METHOD = "assistant.show_method"
        const val KEY_CHIPS = "assistant.chips"
        const val KEY_PROMPT_ADDITIONS = "assistant.prompt_additions"
        const val KEY_ABOUT_GOALS = "assistant.about_goals"
        const val KEY_ABOUT_WANTS = "assistant.about_wants"
        const val KEY_ABOUT_NEEDS = "assistant.about_needs"
        const val KEY_ABOUT_STRENGTHS = "assistant.about_strengths"
        const val KEY_ABOUT_FOCUS = "assistant.about_focus"
        const val KEY_ABOUT_NOTE = "assistant.about_note"
        const val KEY_NOTICE_SHOWN = "assistant.notice_shown"
        const val KEY_SETUP_REQUIRED = "assistant.setup_required"
    }
}

/**
 * One loaded configuration, read inside a single transaction.
 *
 * [toString] is overridden because this value can be logged by a caller that has not been careful: it
 * carries the provider key, so the rendered form leaves the key out entirely rather than shortening it.
 */
data class StoredAssistantConfig(
    val apiKey: String,
    val settings: AssistantSettings,
    val setupRequired: Boolean,
) {
    override fun toString(): String =
        "StoredAssistantConfig(apiKey=<redacted>, settings=$settings, setupRequired=$setupRequired)"
}
