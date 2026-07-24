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

    fun format(value: LocalDateTime): String = value.format(formatter)

    fun dayKey(value: LocalDateTime): String = value.toLocalDate().toString()

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

    fun parseOrNull(value: String?): LocalDateTime? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parse(candidate)
    }

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
