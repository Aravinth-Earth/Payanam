//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

import java.util.Locale

/**
 * Sanitizes structured log messages and data before persistence, so a log handed over for
 * diagnosis never carries absolute paths, credentials, or user content.
 *
 * Shared by both loggers: `io.payanam.common.logging.UnifiedLogger` (Android) and
 * `io.payanam.desktop.UnifiedLogger` (desktop). Keep the rules here rather than in either
 * implementation so the two platforms cannot drift.
 *
 * This is a **log-only** utility. Its output is meant for log files, not for display:
 * `sanitizeMessage`/`sanitizeData` replace paths with `<path>` and secrets with `<redacted>`, which
 * is the right trade for a log and the wrong one for a user-facing message.
 *
 * **Known limitations**
 * - In free text a path is matched up to the first space, so the tail of a path that itself contains
 *   spaces is not redacted. Data *values* are unaffected: a value that looks like a path is replaced
 *   wholesale. Extending the match across spaces would swallow the words that follow the path and
 *   destroy the surrounding diagnostic text, which is the worse trade for a log.
 * - A credential whose value contains whitespace is only partially masked, for the same reason.
 * - Classification is a denylist by key name. A caller that logs user data under an unrecognised key
 *   is not protected — use the established keys.
 */
object LogSanitizer {
    private const val MAX_VALUE_LENGTH = 64
    private const val REDACTED_VALUE = "<redacted>"
    private const val REDACTED_PATH_VALUE = "<path>"
    private const val NON_UUID_VALUE = "<non-uuid>"

    private val uuidRegex =
        Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
    private val windowsPathRegex =
        Regex(
            """[A-Za-z]:\\[^\s\]]+""",
            RegexOption.IGNORE_CASE,
        )
    private val uncPathRegex =
        Regex(
            """\\\\[^\s\\\]]+\\[^\s\]]+""",
        )
    private val lineBreakRegex = Regex("""[\r\n]+""")

    /**
     * Message shapes that carry a credential inline, e.g. `passphrase=hunter2`,
     * `authorization: Bearer <jwt>`, or JSON `"password":"…"`.
     *
     * Group 1 is the key, group 2 the separator (optionally quoted, so JSON separators match),
     * group 3 an optional auth-scheme word, group 4 the value that must not survive. The scheme is
     * preserved: knowing the credential was a `Bearer` token is useful, the token itself is not.
     */
    private val inlineCredentialRegex =
        Regex(
            """(?i)\b(?<key>password|passphrase|secret|token|otp|apikey|api[_-]?key|authorization|credential)""" +
                """(?<separator>\s*["']?\s*[=:]\s*["']?)(?<scheme>(?:bearer|basic)\s+)?(?<value>\S+)""",
        )
    private val allowedEmptyIdentifierTokens = setOf("none", "null", "n/a", "unknown")

    /**
     * Credential-bearing keys. A match here redacts **regardless of value type**, so a numeric OTP
     * or PIN cannot slip through the type gate that preserves numeric aggregates.
     */
    private val secretClassTokens =
        setOf(
            "password",
            "passphrase",
            "secret",
            "token",
            "otp",
            "apikey",
            "api_key",
            "authorization",
            "credential",
        ).map(::normalizeToken).toSet()

    /**
     * User-content keys. A match here redacts only non-numeric values — counts and aggregates
     * (`taskCount`, `noteCount`, `responseLength`) must survive, since a log without numbers is a
     * log you cannot diagnose with.
     *
     * Tokens are additive-only and evidence-backed. `salt` and `hash` are deliberately excluded:
     * `DatabaseEncryptionManager` logs `saltBytes`/`hashBytes` as integer byte counts and
     * `hasVerifierHash`/`hasVerifierSalt` as booleans, so adding them would redact real diagnostics
     * while protecting no secret value.
     */
    private val contentClassTokens =
        setOf(
            "task",
            "habit",
            "journal",
            "title",
            "name",
            "description",
            "note",
            "content",
            "text",
            "message",
            "value",
            "label",
            "path",
            "uri",
            "body",
            "prompt",
            "response",
            "answer",
            "dimension",
            "category",
        ).map(::normalizeToken).toSet()

    /**
     * Path roots that identify a value as a filesystem path rather than a route string.
     *
     * Deliberately a root list and not a bare "starts with a slash" rule: `/api/v1/tasks` is a
     * route, not a path, and redacting routes would destroy the usefulness of route-level logs.
     */
    private val pathPrefixes =
        listOf(
            // POSIX: covers Linux and macOS user, temp, system, mount and snap locations.
            "/home/",
            "/Users/",
            "/private/",
            "/tmp/",
            "/var/",
            "/opt/",
            "/usr/",
            "/srv/",
            "/snap/",
            "/mnt/",
            "/media/",
            "/run/media/",
            "~/",
            // Android shapes.
            "/storage/",
            "/documents/",
            "/logs/",
            // Android app-internal storage, e.g. /data/data/<pkg>/databases/tasks.db in an
            // exception message. The trailing slash keeps this from matching "/database".
            "/data/",
        )

    /** Hyphens/underscores/spaces are stripped so `api-key`, `api_key` and `apikey` match alike. */
    private fun normalizeToken(token: String): String = token.filter { character -> character.isLetterOrDigit() }.lowercase(Locale.US)

    /**
     * Matches a POSIX path anywhere in a value or message, built from [pathPrefixes] so message
     * redaction and value redaction cannot diverge. Trailing characters stop at whitespace, quotes
     * and brackets, which is where a path ends in prose.
     *
     * The alternation must be grouped: without `(?:…)` the trailing quantifier binds to the LAST
     * prefix only, so `/home/someone/data.db` would be reduced to `<path>someone/data.db`.
     */
    private val posixPathRegex: Regex =
        Regex(
            "(?:" +
                pathPrefixes.joinToString(separator = "|") { prefix -> Regex.escape(prefix) } +
                """)[^\s\]"',;]*""",
            // Case-insensitive to preserve the previous Android matching behaviour for
            // /storage/, /documents/ and /logs/ rather than silently tightening it.
            RegexOption.IGNORE_CASE,
        )

    /**
     * Keys whose values are diagnostics the logger itself builds. Sourced from [ErrorPayload] so a
     * rename there cannot silently stop this matching them.
     *
     * They are exempt from [MAX_VALUE_LENGTH] — a stack cut to 64 characters cannot be read, which
     * would make the crash-imminent path useless — but they are **not** exempt from redaction:
     * exception messages routinely carry absolute paths and, occasionally, credentials.
     */
    private val diagnosticKeys: Set<String> =
        ErrorPayload.diagnosticKeys.map { key -> key.lowercase(Locale.US) }.toSet()

    /**
     * Recursively walks a structured [data] map and redacts sensitive values while preserving the
     * map shape, so logs never leak paths, credentials or user content. Returns an empty map when
     * [data] is null/empty.
     */
    fun sanitizeData(data: Map<String, Any?>?): Map<String, Any?> {
        if (data.isNullOrEmpty()) {
            return emptyMap()
        }
        return data.mapValues { (key, value) ->
            sanitizeDataValue(key, value)
        }
    }

    /**
     * Single-line-izes [message], redacts filesystem paths, and masks credentials written inline as
     * `key=value` or `key: value`.
     */
    fun sanitizeMessage(message: String): String = redactInlineCredentials(replacePaths(message.replace(lineBreakRegex, " ").trim()))

    /**
     * Masks a logger-built diagnostic. Paths and inline credentials are still redacted — an
     * exception message can carry either — but the value is not length-capped, because a stack cut
     * to 64 characters cannot be read.
     */
    private fun sanitizeDiagnostic(value: String): String = redactInlineCredentials(replacePaths(value))

    private fun redactInlineCredentials(value: String): String =
        value.replace(inlineCredentialRegex) { match ->
            // Preserve key, separator and auth scheme; replace only the credential value.
            match.groups["key"]?.value.orEmpty() +
                match.groups["separator"]?.value.orEmpty() +
                match.groups["scheme"]?.value.orEmpty() +
                REDACTED_VALUE
        }

    private fun replacePaths(value: String): String =
        value
            .replace(windowsPathRegex, REDACTED_PATH_VALUE)
            .replace(uncPathRegex, REDACTED_PATH_VALUE)
            .replace(posixPathRegex, REDACTED_PATH_VALUE)

    private fun sanitizeDataValue(
        key: String,
        value: Any?,
    ): Any? {
        if (value == null) {
            return null
        }

        val normalizedKey = key.lowercase(Locale.US)
        // Separator-insensitive view of the key, so hyphen/underscore/space variants
        // ("api-key", "api_key", "api key") hit the same entry.
        val comparableKey = normalizeToken(key)

        if (isIdentifierKey(normalizedKey)) {
            val idValue = value.toString()
            if (isAllowedEmptyIdentifier(idValue)) {
                return idValue
            }
            return if (uuidRegex.matches(idValue)) idValue else NON_UUID_VALUE
        }

        // Logger-built diagnostics: full length (a truncated stack cannot be read), but still
        // path- and credential-redacted — an exception message can carry either.
        if (value is String && normalizedKey in diagnosticKeys) {
            return sanitizeDiagnostic(value)
        }

        // Credentials redact by key alone: a numeric OTP or PIN must not survive the type gate.
        if (secretClassTokens.any { comparableKey.contains(it) }) {
            return REDACTED_VALUE
        }

        if (contentClassTokens.any { comparableKey.contains(it) } && shouldRedactValue(value)) {
            return REDACTED_VALUE
        }

        if (value is Collection<*>) {
            return "[${value.size} items]"
        }

        if (value is Map<*, *>) {
            return "{${value.size} entries}"
        }

        if (value is Number || value is Boolean) {
            return value
        }

        return renderPlainValue(value.toString())
    }

    /** A non-classified value: redact it if it looks like a path, otherwise bound its length. */
    private fun renderPlainValue(text: String): String {
        if (isPathLike(text)) {
            return REDACTED_PATH_VALUE
        }
        return if (text.length > MAX_VALUE_LENGTH) {
            "${text.take(MAX_VALUE_LENGTH)}..."
        } else {
            text
        }
    }

    /** Numbers and booleans survive for content keys — they are counts, not content. */
    private fun shouldRedactValue(value: Any): Boolean =
        when (value) {
            is Number -> false
            is Boolean -> false
            else -> true
        }

    private fun isIdentifierKey(normalizedKey: String): Boolean =
        normalizedKey == "id" ||
            normalizedKey.endsWith("id") ||
            normalizedKey.endsWith("_id") ||
            normalizedKey.contains("uuid")

    private fun isAllowedEmptyIdentifier(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.US)
        return normalized.isEmpty() || normalized in allowedEmptyIdentifierTokens
    }

    private fun isPathLike(value: String): Boolean =
        windowsPathRegex.containsMatchIn(value) ||
            uncPathRegex.containsMatchIn(value) ||
            posixPathRegex.containsMatchIn(value)
}
