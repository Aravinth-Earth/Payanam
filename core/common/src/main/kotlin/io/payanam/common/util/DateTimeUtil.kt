//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.util

import io.payanam.common.logging.UnifiedLogger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Date/time utility functions.
 * Equivalent to archive-v0.0.2/src/utils/timezone.ts
 */
object DateTimeUtil { // detekt:ignore:TooManyFunctions

    private const val MINUTES_PER_HOUR = 60

    private val logger = UnifiedLogger.getInstance()
    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * Get current local date/time.
     */
    fun now(): LocalDateTime = LocalDateTime.now()

    /**
     * Get current local date.
     */
    fun today(): LocalDate = LocalDate.now()

    /**
     * Get start of day (00:00:00).
     */
    fun startOfDay(date: LocalDate = today()): LocalDateTime = date.atStartOfDay()

    /**
     * Get end of day (23:59:59.999).
     */
    fun endOfDay(date: LocalDate = today()): LocalDateTime = date.atTime(LocalTime.MAX)

    /**
     * Check if a date/time is today.
     */
    fun isToday(dateTime: LocalDateTime): Boolean = dateTime.toLocalDate() == today()

    /**
     * Check if a task is overdue (due date in the past).
     */
    fun isOverdue(dueDate: LocalDateTime?): Boolean {
        if (dueDate == null) return false
        return dueDate.isBefore(now())
    }

    /**
     * Format date/time to ISO string.
     */
    fun toIsoString(dateTime: LocalDateTime): String = dateTime.format(isoFormatter)

    /**
     * Parses an ISO local date-time [isoString]. Logs and rethrows on failure.
     */
    fun parseIso(isoString: String): LocalDateTime =
        try {
            LocalDateTime.parse(isoString, isoFormatter)
        } catch (e: DateTimeParseException) {
            logger.e("DateTimeUtil.parseIso", "Failed to parse ISO string", e, mapOf("isoString" to isoString))
            throw e
        }

    /**
     * Format date to display string (e.g., "Jan 15, 2026").
     */
    fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

    /**
     * Format time to display string based on format preference.
     */
    fun formatTime(
        time: LocalTime,
        use24Hour: Boolean = false,
    ): String {
        val pattern = if (use24Hour) "HH:mm" else "h:mm a"
        return time.format(DateTimeFormatter.ofPattern(pattern))
    }

    /**
     * Format duration in minutes to readable string.
     */
    fun formatDuration(minutes: Long): String =
        when {
            minutes < MINUTES_PER_HOUR -> "${minutes}m"
            minutes % MINUTES_PER_HOUR == 0L -> "${minutes / MINUTES_PER_HOUR}h"
            else -> "${minutes / MINUTES_PER_HOUR}h ${minutes % MINUTES_PER_HOUR}m"
        }

    /**
     * Calculate minutes between two date/times.
     */
    fun minutesBetween(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Long = ChronoUnit.MINUTES.between(start, end)
}
