//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.util

import io.payanam.common.logging.UnifiedLogger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Central contract for persisted app-local timestamps.
 *
 * Canonical format for new rows is ISO local date-time without zone data.
 * Legacy values with a trailing Z are treated as local wall-clock values to
 * preserve existing user-visible behavior instead of shifting historical data.
 */
object PersistedDateTime {
    private val logger = UnifiedLogger.getInstance()
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** Formats a [LocalDateTime] to the canonical ISO local string. */
    fun format(value: LocalDateTime): String = value.format(formatter)

    /** Returns the date portion of [value] as a stable day-key string. */
    fun dayKey(value: LocalDateTime): String = value.toLocalDate().toString()

    /**
     * Parses a persisted date-time [value] (stripping a legacy trailing 'Z'
     * so it is read as local wall-clock). Logs and rethrows on failure.
     */
    fun parse(value: String): LocalDateTime {
        val normalized = normalize(value)
        return try {
            LocalDateTime.parse(normalized, formatter)
        } catch (error: DateTimeParseException) {
            logger.e(
                "PersistedDateTime.parse",
                "Failed to parse persisted local datetime",
                error,
                mapOf("value" to value),
            )
            throw error
        }
    }

    /**
     * Parses [value] if non-blank, otherwise returns null. delegates to [parse].
     */
    fun parseOrNull(value: String?): LocalDateTime? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parse(candidate)
    }

    /**
     * Parses [value] as a full date-time; on failure, falls back to parsing it
     * as a bare date at start-of-day. Returns null if [value] is blank.
     */
    fun parseOrDateStart(value: String?): LocalDateTime? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            parse(candidate)
        } catch (_: DateTimeParseException) {
            val normalized = normalize(candidate)
            try {
                LocalDate.parse(normalized).atStartOfDay()
            } catch (dateError: DateTimeParseException) {
                logger.e(
                    "PersistedDateTime.parseOrDateStart",
                    "Failed to parse persisted date or datetime",
                    dateError,
                    mapOf("value" to value),
                )
                throw dateError
            }
        }
    }

    private fun normalize(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.endsWith("Z")) trimmed.dropLast(1) else trimmed
    }
}
