//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.domain.assistant

/**
 * Compile-time defaults for the in-app AI assistant (BYOK via OpenCode Go).
 *
 * The values mirror the verified desktop POC (`poc/llm-spike/`): base URL, model
 * allow-list rules, token budget, row caps and the agentic-SQL round budget.
 */
object AssistantDefaults {
    const val BASE_URL = "https://opencode.ai/zen/go/v1"

    /**
     * The `User-Agent` sent to the provider on every call.
     *
     * It names the PRODUCT and not the platform. A per-platform value ("Payanam-Android",
     * "Payanam-Linux") would need a platform seam for a string with no functional effect, and a
     * build-name suffix would be a user-visible claim the shared module cannot verify: this
     * constant is compiled into the shared library, which knows neither the OS it runs on nor the
     * build number. The provider needs one stable, distinct agent per app, and "Payanam/1.0" is
     * that — matching the sibling update checker's `Payanam/$currentBuildNumber`, which resolves
     * its build number on the platform side where it actually knows it.
     */
    const val USER_AGENT = "Payanam/1.0 (assistant)"
    const val TEMPERATURE = 0.2
    const val MAX_TOKENS = 6000
    const val MAX_ROWS = 500
    const val MIN_ROWS = 100
    const val HARD_MAX_ROWS = 2000
    const val ROUNDS = 8
    const val MIN_ROUNDS = 2
    const val MAX_ROUNDS_LIMIT = 12
    const val HISTORY_DEPTH = 10
    const val MIN_HISTORY = 2
    const val MAX_HISTORY = 30
    const val CELL_CLIP = 200
    const val MAX_TOOL_RESULT_CHARS = 40_000
    const val MAX_PROMPT_ADDITIONS = 4000
    const val PREVIEW_ROWS = 3
    const val PREVIEW_CELL_CLIP = 80
    const val PREVIEW_TOTAL_CLIP = 600

    /** Model ids whose names start with these prefixes are never offered (train-on-data families). */
    val BLOCKED_MODEL_PREFIXES = listOf("muse")
}

/** Response-length presets; the numeric target is injected into the system prompt per question. */
enum class AssistantLength {
    SHORT,
    BALANCED,
    ELABORATED,
    ;

    /** The line-count target injected into the system prompt (plan: ~5 / ~20 / ~50 lines). */
    fun targetLines(): Int =
        when (this) {
            SHORT -> 5
            BALANCED -> 20
            ELABORATED -> 50
        }

    companion object {
        /** Parses a stored preset name, falling back to [BALANCED] for unknown values. */
        fun fromName(value: String?): AssistantLength = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}

/** Structured "About me" notes, kept as a labelled list in the prompt — never comma-joined. */
data class AssistantAboutMe(
    val goals: String = "",
    val wants: String = "",
    val needs: String = "",
    val strengths: String = "",
    val focusAreas: String = "",
    val note: String = "",
) {
    /** True when every field is blank. */
    fun isEmpty(): Boolean = fields().all { it.second.isBlank() }

    /**
     * Renders a clean labelled list for the system prompt, skipping blank fields.
     */
    fun renderLabeledList(): String =
        fields()
            .filter { it.second.isNotBlank() }
            .joinToString("\n") { (label, value) -> "- $label: ${value.trim()}" }

    private fun fields(): List<Pair<String, String>> =
        listOf(
            "Goals" to goals,
            "Wants" to wants,
            "Needs" to needs,
            "Strengths" to strengths,
            "Focus areas" to focusAreas,
            "Note" to note,
        )
}

/** Every assistant setting; persisted field-by-field in the encrypted DB (`app_settings`). */
data class AssistantSettings(
    /** No model is preselected: the model is empty until the user validates a key and picks one. */
    val model: String = "",
    val length: AssistantLength = AssistantLength.BALANCED,
    val maxRows: Int = AssistantDefaults.MAX_ROWS,
    val rounds: Int = AssistantDefaults.ROUNDS,
    val historyDepth: Int = AssistantDefaults.HISTORY_DEPTH,
    val showMethod: Boolean = true,
    val chipsEnabled: Boolean = true,
    val promptAdditions: String = "",
    val aboutMe: AssistantAboutMe = AssistantAboutMe(),
    val noticeShown: Boolean = false,
) {
    /** Clamps every numeric/limited field into its supported range. */
    fun sanitized(): AssistantSettings =
        copy(
            maxRows = maxRows.coerceIn(AssistantDefaults.MIN_ROWS, AssistantDefaults.HARD_MAX_ROWS),
            rounds = rounds.coerceIn(AssistantDefaults.MIN_ROUNDS, AssistantDefaults.MAX_ROUNDS_LIMIT),
            historyDepth = historyDepth.coerceIn(AssistantDefaults.MIN_HISTORY, AssistantDefaults.MAX_HISTORY),
            promptAdditions = promptAdditions.take(AssistantDefaults.MAX_PROMPT_ADDITIONS),
        )
}

/** A chat bubble. Assistant bubbles carry their receipt (model + query/row counts). */
data class AssistantChatMessage(
    val role: String,
    val text: String,
    val model: String? = null,
    val queryCount: Int = 0,
    val rowCount: Int = 0,
    val id: Long = 0,
) {
    /** True for the local-only error bubble. */
    val isError: Boolean get() = role == ROLE_ERROR

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_ERROR = "error"
    }
}

/** Live progress of the current turn, mapped to localized labels in the UI. */
sealed interface AssistantBusy {
    /** Preparing prompt + history. */
    data object Preparing : AssistantBusy

    /** Waiting on a model reply (no pending SQL round). */
    data object Thinking : AssistantBusy

    /** A guarded SQL query is executing (round N of max). */
    data class Querying(
        val round: Int,
        val max: Int,
    ) : AssistantBusy

    /** The model is writing the final answer. */
    data object Finalizing : AssistantBusy
}

/** Failure classes the UI can present with a dedicated message. */
enum class AssistantErrorKind {
    KEY_REJECTED,
    NETWORK,
    SERVER,
    DB_LOCKED,
    NO_MODEL,
}

/**
 * A stored `app_settings` value resolved for one load, together with whether the stored text was
 * actually usable.
 *
 * A MISSING row and an UNPARSEABLE row are different findings and are kept apart on purpose: a
 * missing row is a first run and its default is correct, while an unparseable row is a value the
 * user or an earlier build wrote that this code cannot read. The old reader collapsed both into
 * `?: default`, so a corrupted or superseded stored choice was silently replaced by the default
 * and then written back by the next save — the user's stored value destroyed with nothing to show
 * for it.
 */
data class AssistantStoredField<T>(
    /** The `app_settings` key this value came from, for the unreadable-row report. */
    val key: String,
    /** The value to use for this load: the parsed text, or [fallback] when there was none to parse. */
    val value: T,
    /**
     * False when the row was absent (a benign first run) or parsed cleanly. True when a stored
     * value existed and could not be read — the caller reports it and leaves the row alone.
     */
    val usable: Boolean,
)

/**
 * The one rule for reading a stored assistant setting, so no caller re-derives "default it and
 * move on".
 *
 * `resolve` never throws and never rewrites storage. An unusable stored value yields [fallback]
 * for this load AND `usable = false`, which is the signal a caller must act on: report which key
 * is unreadable and keep the stored text, because a default is a guess about intent and
 * overwriting the row turns a guess into the record.
 */
object AssistantStoredValue {
    /** Resolves [stored] with [parse], distinguishing absent from present-but-unreadable. */
    fun <T> resolve(
        key: String,
        stored: String?,
        fallback: T,
        parse: (String) -> T?,
    ): AssistantStoredField<T> =
        when {
            stored == null -> {
                AssistantStoredField(key, fallback, usable = true)
            }

            else -> {
                parse(stored)?.let { parsed -> AssistantStoredField(key, parsed, usable = true) }
                    ?: AssistantStoredField(key, fallback, usable = false)
            }
        }

    /** [resolve] for the numeric limits, which are the four a bad write can corrupt. */
    fun int(
        key: String,
        stored: String?,
        fallback: Int,
    ): AssistantStoredField<Int> = resolve(key, stored, fallback) { it.toIntOrNull() }

    /** [resolve] for the boolean flags, parsed strictly so "TRUE"/"1" are not silently accepted. */
    fun boolean(
        key: String,
        stored: String?,
        fallback: Boolean,
    ): AssistantStoredField<Boolean> = resolve(key, stored, fallback) { it.toBooleanStrictOrNull() }

    /** [resolve] for the response-length preset, which falls back to BALANCED when unreadable. */
    fun length(
        key: String,
        stored: String?,
    ): AssistantStoredField<AssistantLength> =
        resolve(key, stored, AssistantLength.BALANCED) { value ->
            AssistantLength.entries.firstOrNull { it.name == value }
        }
}
