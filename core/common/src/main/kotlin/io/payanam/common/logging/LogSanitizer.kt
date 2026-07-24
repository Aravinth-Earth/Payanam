//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import java.util.Locale
import kotlin.text.RegexOption

/**
 * Sanitizes structured log messages and data before persistence.
 */
internal object LogSanitizer {
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
            "[A-Za-z]:\\\\[^\\s\\]]+",
            RegexOption.IGNORE_CASE,
        )
    private val lineBreakRegex = Regex("[\\r\\n]+")
    private val allowedEmptyIdentifierTokens = setOf("none", "null", "n/a", "unknown")
    private val sensitiveKeyTokens =
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
            "password",
            "passphrase",
            "secret",
            "token",
            "otp",
            "prompt",
            "response",
            "answer",
            "dimension",
            "category",
        )

    fun sanitizeData(data: Map<String, Any?>?): Map<String, Any?> {
        if (data.isNullOrEmpty()) {
            return emptyMap()
        }
        return data.mapValues { (key, value) ->
            sanitizeDataValue(key, value)
        }
    }

    fun sanitizeMessage(message: String): String {
        val singleLine = message.replace(lineBreakRegex, " ").trim()
        return singleLine.replace(windowsPathRegex, REDACTED_PATH_VALUE)
    }

    private fun sanitizeDataValue(
        key: String,
        value: Any?,
    ): Any? {
        if (value == null) {
            return null
        }

        val normalizedKey = key.lowercase(Locale.US)

        if (isIdentifierKey(normalizedKey)) {
            val idValue = value.toString()
            if (isAllowedEmptyIdentifier(idValue)) {
                return idValue
            }
            return if (uuidRegex.matches(idValue)) idValue else NON_UUID_VALUE
        }

        if (sensitiveKeyTokens.any { normalizedKey.contains(it) } && shouldRedactValue(value)) {
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

        val text = value.toString()
        if (isPathLike(text)) {
            return REDACTED_PATH_VALUE
        }

        return if (text.length > MAX_VALUE_LENGTH) {
            "${text.take(MAX_VALUE_LENGTH)}..."
        } else {
            text
        }
    }

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
            value.contains("/storage/", ignoreCase = true) ||
            value.contains("/documents/", ignoreCase = true) ||
            value.contains("/logs/", ignoreCase = true)
}
