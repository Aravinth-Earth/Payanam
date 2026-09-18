//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.assistant

/**
 * Compile-time defaults for the in-app AI assistant (BYOK via OpenCode Go).
 *
 * The values mirror the verified desktop POC (`poc/llm-spike/`): base URL, model
 * allow-list rules, token budget, row caps and the agentic-SQL round budget.
 */
object AssistantDefaults {
    const val BASE_URL = "https://opencode.ai/zen/go/v1"
    const val USER_AGENT = "Payanam-Android/1.0 (assistant)"
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
    fun targetLines(): Int = when (this) {
        SHORT -> 5
        BALANCED -> 20
        ELABORATED -> 50
    }

    companion object {
        /** Parses a stored preset name, falling back to [BALANCED] for unknown values. */
        fun fromName(value: String?): AssistantLength =
            entries.firstOrNull { it.name == value } ?: BALANCED
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

    private fun fields(): List<Pair<String, String>> = listOf(
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
    fun sanitized(): AssistantSettings = copy(
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
    data class Querying(val round: Int, val max: Int) : AssistantBusy

    /** The model is writing the final answer. */
    data object Finalizing : AssistantBusy
}

/**
 * True once the assistant is fully configured — a stored key AND a user-picked model. The chat
 * surface is reachable only in this state, so the predicate lives in one place instead of being
 * re-derived by every gate. Deterministic despite a possibly stale model row: the assistant
 * ViewModel blanks the model during refresh while a reconfigure is pending, so this never
 * reports configured mid-reconfigure.
 */
val AssistantUiState.isConfigured: Boolean
    get() = hasKey && settings.model.isNotEmpty()

/** Failure classes the UI can present with a dedicated message. */
enum class AssistantErrorKind {
    KEY_REJECTED,
    NETWORK,
    SERVER,
    DB_LOCKED,
    NO_MODEL,
}
