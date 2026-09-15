//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.assistant

/**
 * Pure helpers for the agentic-SQL message protocol (POC port).
 *
 * The model replies either with exactly one JSON object `{"sql": "..."}` to request a
 * query, or with plain text as the final answer. These helpers keep that parsing
 * testable without touching the network or the database.
 */
object AssistantProtocol {
    /** Matches a single flat JSON object containing a "sql" key (DOTALL). */
    private val sqlJson = Regex("\\{[^{}]*\"sql\"[^{}]*\\}", RegexOption.DOT_MATCHES_ALL)

    /** Extracts the SQL string value from a `"sql": "..."` fragment, with JSON unescaping. */
    private val sqlValue = Regex("\"sql\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * Returns the SQL statement when [reply] carries a complete `{"sql": ...}` object,
     * or null when the reply is a final answer / malformed JSON.
     */
    fun extractSql(reply: String): String? {
        if (!sqlJson.containsMatchIn(reply)) return null
        val match = sqlValue.find(reply) ?: return null
        return unescapeJson(match.groupValues[1]).trim().takeIf { it.isNotEmpty() }
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

    /** Unescapes the JSON string escapes the protocol can encounter. */
    internal fun unescapeJson(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n' -> builder.append('\n')
                    't' -> builder.append('\t')
                    'r' -> builder.append('\r')
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    '/' -> builder.append('/')
                    else -> builder.append(next)
                }
                index += 2
            } else {
                builder.append(char)
                index += 1
            }
        }
        return builder.toString()
    }
}
