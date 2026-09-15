//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.assistant

/** Outcome of [ReadOnlySqlGuard.sanitize]. */
sealed interface GuardResult {
    /** The statement is a single read-only SELECT/WITH and is safe to execute. */
    data class Ok(val sql: String) : GuardResult

    /** The statement was refused; [reason] is sent back to the model as a retry hint. */
    data class Rejected(val reason: String) : GuardResult
}

/**
 * Code-level read-only enforcement for model-generated SQL — the POC guard ported
 * (poc/llm-spike/assistant_harness.py `guard()`) and hardened.
 *
 * Safety never depends on the prompt: this object is the only path from a model reply to
 * the database, and it refuses anything that is not a single SELECT/WITH statement.
 * Scans run on a copy of the statement whose string-literal contents are blanked out, so
 * user data values inside quotes (`LIKE '%delete%'`, `'a -- b'`) can neither trip a false
 * rejection nor hide a real keyword. A second variant unwraps identifier quoting
 * ([unwrapQuotedIdentifiers]) so quoted spellings of blocked names cannot dodge the rules.
 * A LIMIT is appended when the statement has none; the
 * app-side row cap in [DbQueryTool] remains the hard bound on rows returned.
 */
object ReadOnlySqlGuard {
    private const val MAX_SQL_CHARS = 4000

    private val forbidden = Regex(
        // Trailing \b everywhere so identifiers that merely START with a keyword (created_at,
        // attachments) stay queryable; the pragma/sqlite_* families get their own prefix rules
        // because their table-valued spellings (pragma_table_info, sqlite_dbpage) are read
        // paths the keyword list would miss.
        "\\b(insert|update|delete|drop|alter|attach|detach|vacuum|reindex|create|replace|trigger)\\b" +
            "|\\bpragma\\w*\\b" +
            "|\\bsqlite_(master|schema|dbpage|dbdata)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val selectStart = Regex("^(select|with)\\b", RegexOption.IGNORE_CASE)
    private val hasLimit = Regex("\\blimit\\b", RegexOption.IGNORE_CASE)

    /**
     * The settings table holds app-internal values, above all the assistant's own API key row
     * (`assistant.api_key`). It is refused on the RAW statement text so quoted or bracketed
     * spellings cannot slip past the literal-stripping scan.
     */
    private val forbiddenTable = Regex("app_settings", RegexOption.IGNORE_CASE)

    /**
     * Validates [raw] and returns the executable statement (LIMIT added when missing).
     */
    fun sanitize(raw: String, maxRows: Int): GuardResult {
        val statement = raw.trim().trimEnd(';').trim()
        if (statement.isEmpty()) return GuardResult.Rejected("empty statement")
        if (statement.length > MAX_SQL_CHARS) return GuardResult.Rejected("statement too long")
        if (forbiddenTable.containsMatchIn(statement)) {
            return GuardResult.Rejected("table app_settings is not queryable")
        }
        val scan = stripStringLiterals(statement)
        if (scan.count { it == '\'' || it == '"' } % 2 != 0) {
            return GuardResult.Rejected("unterminated string literal")
        }
        if (scan.contains(';')) return GuardResult.Rejected("multi-statement not allowed")
        if (scan.contains("--") || scan.contains("/*")) {
            return GuardResult.Rejected("comments not allowed")
        }
        if (!selectStart.containsMatchIn(scan)) {
            return GuardResult.Rejected("only SELECT/WITH statements are allowed")
        }
        if (forbidden.containsMatchIn(scan) ||
            forbidden.containsMatchIn(unwrapQuotedIdentifiers(statement))
        ) {
            return GuardResult.Rejected("forbidden keyword detected")
        }
        val capped =
            if (hasLimit.containsMatchIn(scan)) {
                statement
            } else {
                "$statement LIMIT $maxRows"
            }
        return GuardResult.Ok(capped)
    }

    /**
     * Second scan variant: identifier quoting is removed and its contents kept, so a quoted
     * spelling of a blocked name ("sqlite_master", "main"."sqlite_master", [sqlite_master],
     * `sqlite_master`, "pragma_table_info") still hits the keyword rules. Single-quoted string
     * literals are still blanked (their content is data, never structure) and each literal is
     * replaced by one space, so two tokens cannot be glued together across a value.
     */
    internal fun unwrapQuotedIdentifiers(sql: String): String {
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            val ch = sql[index]
            if (ch != '\'' && ch != '"' && ch != '`' && ch != '[') {
                out.append(ch)
                index++
                continue
            }
            val closer = if (ch == '[') ']' else ch
            val keepContent = ch != '\''
            index++
            while (index < sql.length) {
                val inner = sql[index]
                if (inner == closer) {
                    val doubled = ch != '[' && index + 1 < sql.length && sql[index + 1] == closer
                    if (doubled) {
                        if (keepContent) out.append(closer)
                        index += 2
                        continue
                    }
                    index++
                    break
                }
                if (keepContent) out.append(inner) else out.append(' ')
                index++
            }
            if (!keepContent) out.append(' ')
        }
        return out.toString()
    }

    /**
     * Replaces the contents of single-quoted string literals and double-quoted identifiers
     * with spaces (keeping the delimiters), so structural scans see the SQL, not the data
     * values. Handles SQL-style doubled quotes (`''` inside a literal).
     */
    internal fun stripStringLiterals(sql: String): String {
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            val ch = sql[index]
            if (ch != '\'' && ch != '"') {
                out.append(ch)
                index++
                continue
            }
            out.append(ch)
            index++
            while (index < sql.length) {
                val inner = sql[index]
                if (inner == ch) {
                    val doubled = index + 1 < sql.length && sql[index + 1] == ch
                    if (doubled) {
                        out.append("  ")
                        index += 2
                        continue
                    }
                    out.append(ch)
                    index++
                    break
                }
                out.append(' ')
                index++
            }
        }
        return out.toString()
    }
}
