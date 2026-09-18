//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure helpers for the agentic-SQL message protocol (POC port).
 *
 * The model replies either with exactly one JSON object `{"sql": "..."}` to request a
 * query, or with plain text as the final answer. These helpers keep that parsing
 * testable without touching the network or the database.
 */
object AssistantProtocol {
    /** Matches a single flat JSON object containing a "sql" key (DOTALL). */
    private val sqlJson = Regex("\\{[^{}]*\\\"sql\\\"[^{}]*\\}", RegexOption.DOT_MATCHES_ALL)

    /**
     * Returns the SQL statement when [reply] carries a complete `{"sql": ...}` object,
     * or null when the reply is a final answer / malformed JSON.
     */
    fun extractSql(reply: String): String? {
        var match: MatchResult? = sqlJson.find(reply) ?: return null
        while (match != null) {
            val candidate = match
            val root = runCatching {
                Json.parseToJsonElement(candidate.value) as? JsonObject
            }.getOrNull()
            val value = root?.get("sql") as? JsonPrimitive
            if (value != null && value.isString) {
                return value.content.trim().takeIf { it.isNotEmpty() }
            }
            match = sqlJson.find(reply, candidate.range.first + 1)
        }
        return null
    }

    /** True when the reply mentions a sql key at all — used to detect truncated JSON. */
    fun mentionsSqlKey(reply: String): Boolean = reply.contains("\"sql\"")

    /** True when the reply is blank (provider glitch) — caller re-asks once. */
    fun isEmptyReply(reply: String): Boolean = reply.isBlank()

    /** The retry nudge sent after an empty reply. */
    const val EMPTY_REPLY_PROMPT =
        "EMPTY_REPLY: reply again - either one complete {\"sql\": ...} object or the final answer."

    /** The retry nudge sent after malformed/truncated SQL JSON. */
    const val INVALID_SQL_JSON_PROMPT =
        "SQL_JSON_INVALID: your JSON was truncated or malformed. " +
            "Reply again with a complete, compact {\"sql\": ...} object."

    /** The retry nudge sent after a guard rejection. */
    fun guardRejectionPrompt(reason: String): String = "SQL_REJECTED: $reason. Retry with a single SELECT."

    /** The continuation nudge sent when the answer hit the token cap mid-sentence. */
    const val CONTINUE_PROMPT =
        "CONTINUE: your reply was cut off at the token limit. " +
            "Continue from exactly where it stopped - no repetition, no restart."

    /** The final nudge when the round budget is exhausted. */
    const val ROUND_LIMIT_PROMPT =
        "ROUND_LIMIT: no more SQL allowed. Give your final answer now, using the data you already have."
}
