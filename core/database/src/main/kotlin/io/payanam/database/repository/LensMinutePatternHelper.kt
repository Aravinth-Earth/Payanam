//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.MinutePatternData
import io.payanam.domain.repository.MinutePatternDay
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

internal const val MINUTE_UNTRACKED_SENTINEL = "__minute_untracked__"
private const val MINUTES_PER_DAY_MP = 24 * 60

/**
 * Builds a 7-column × 1440-row minute-level behavioral pattern grid from all completed time entries.
 * Each row = one minute-of-day; winner = top-1 dimension (or untracked) across all history for that DOW.
 */
@Suppress("LongMethod")
internal fun buildMinutePatternData(
    allEntries: List<TimeEntry>,
    excludeEmptyDays: Boolean = false,
    logger: UnifiedLogger,
): MinutePatternData {
    val completedEntries = allEntries.filter { it.endedAt != null }
    logger.d(
        "LensMinutePatternHelper.buildMinutePatternData",
        "Building minute pattern data",
        mapOf("totalEntries" to allEntries.size, "completedEntries" to completedEntries.size, "excludeEmptyDays" to excludeEmptyDays),
    )
    if (completedEntries.isEmpty()) {
        logger.d("LensMinutePatternHelper.buildMinutePatternData", "No completed entries — returning empty minute pattern")
        return MinutePatternData(emptyList())
    }

    // Layer 0: Calendar spine — count DOW occurrences from firstTrackedDate to today.
    // When excludeEmptyDays=true, days with zero tracked entries are excluded from counts.
    val firstTrackedDate = completedEntries.minOf { it.startedAt.toLocalDate() }
    val today = LocalDate.now()
    val trackedDates: Set<LocalDate> =
        if (excludeEmptyDays) {
            val dates = mutableSetOf<LocalDate>()
            completedEntries.forEach { entry ->
                var d = entry.startedAt.toLocalDate()
                val end = entry.endedAt!!.toLocalDate()
                while (!d.isAfter(end)) {
                    dates.add(d)
                    d = d.plusDays(1)
                }
            }
            dates
        } else {
            emptySet()
        }
    val dowCounts = countDowOccurrences(firstTrackedDate, today, if (excludeEmptyDays) trackedDates else null)

    logger.d(
        "LensMinutePatternHelper.buildMinutePatternData",
        "DOW occurrence counts computed",
        mapOf(
            "firstTrackedDate" to firstTrackedDate.toString(),
            "today" to today.toString(),
            "dowCounts" to dowCounts.map { "${it.key}=${it.value}" }.joinToString(","),
        ),
    )

    // Layer 1: Pre-allocate accumulator: DOW -> Array<MutableMap<String?, Int>> (1440 minutes)
    val accumulator: Map<DayOfWeek, Array<MutableMap<String?, Int>>> =
        DayOfWeek.values().associateWith {
            Array(MINUTES_PER_DAY_MP) { mutableMapOf() }
        }

    completedEntries.forEach { entry ->
        val entryEnd = entry.endedAt!!
        val normalizedDimId = normalizeMinuteDimId(entry.dimensionId)

        // Split across day boundaries
        var dayCursor = entry.startedAt.toLocalDate()
        val endDay = entryEnd.toLocalDate()
        while (!dayCursor.isAfter(endDay)) {
            val dayStart = dayCursor.atStartOfDay()
            val dayEndExclusive = dayStart.plusDays(1)
            val segStart = if (entry.startedAt.isBefore(dayStart)) dayStart else entry.startedAt
            val segEnd = if (entryEnd.isAfter(dayEndExclusive)) dayEndExclusive else entryEnd
            if (segEnd.isAfter(segStart)) {
                val startMin =
                    Duration
                        .between(dayStart, segStart)
                        .toMinutes()
                        .toInt()
                        .coerceIn(0, MINUTES_PER_DAY_MP)
                val endMin =
                    Duration
                        .between(dayStart, segEnd)
                        .toMinutes()
                        .toInt()
                        .coerceIn(0, MINUTES_PER_DAY_MP)
                val dow = dayCursor.dayOfWeek
                val minuteArray = accumulator[dow]!!
                for (m in startMin until endMin) {
                    val dimMap = minuteArray[m]
                    dimMap[normalizedDimId] = (dimMap[normalizedDimId] ?: 0) + 1
                }
            }
            dayCursor = dayCursor.plusDays(1)
        }
    }

    // Build orderedDows: today's DOW first, going back 6 days (same as WeekGrid)
    val todayDow = today.dayOfWeek
    val orderedDows =
        (0 until 7).map { i ->
            DayOfWeek.of(((todayDow.value - 1 - i + 70) % 7) + 1)
        }

    // Layer 2 + 3: Untracked injection + argmax per minute
    val days =
        orderedDows.map { dow ->
            val occCount = dowCounts[dow] ?: 0
            if (occCount == 0) {
                // No data for this DOW — all minutes are untracked
                return@map MinutePatternDay(
                    dayOfWeek = dow,
                    minuteWinners = List(MINUTES_PER_DAY_MP) { MINUTE_UNTRACKED_SENTINEL },
                )
            }
            val minuteArray = accumulator[dow]!!
            val winners =
                List(MINUTES_PER_DAY_MP) { m ->
                    val dimMap = minuteArray[m]
                    val trackedTotal = dimMap.values.sum()
                    val untrackedCount = (occCount - trackedTotal).coerceAtLeast(0)

                    // Argmax: find tracked dim with highest count; ties: tracked beats untracked
                    var bestTrackedKey: String? = null // use a sentinel-absent key
                    var bestTrackedFound = false
                    var bestTrackedCount = 0
                    for ((key, count) in dimMap) {
                        if (!bestTrackedFound || count > bestTrackedCount) {
                            bestTrackedKey = key
                            bestTrackedCount = count
                            bestTrackedFound = true
                        }
                    }

                    when {
                        !bestTrackedFound -> MINUTE_UNTRACKED_SENTINEL

                        bestTrackedCount >= untrackedCount -> bestTrackedKey

                        // tracked wins (ties go to tracked)
                        else -> MINUTE_UNTRACKED_SENTINEL
                    }
                }
            MinutePatternDay(dayOfWeek = dow, minuteWinners = winners)
        }
    val distinctWinners = days.flatMap { it.minuteWinners }.toSet().size
    logger.d(
        "LensMinutePatternHelper.buildMinutePatternData",
        "Minute pattern built",
        mapOf(
            "days" to days.size,
            "firstTrackedDate" to firstTrackedDate.toString(),
            "dowCounts" to dowCounts.map { "${it.key}=${it.value}" }.joinToString(","),
            "distinctWinnerValues" to distinctWinners,
        ),
    )
    return MinutePatternData(days = days)
}

private fun countDowOccurrences(
    firstDate: LocalDate,
    today: LocalDate,
    allowedDates: Set<LocalDate>? = null,
): Map<DayOfWeek, Int> {
    val counts = mutableMapOf<DayOfWeek, Int>()
    var cursor = firstDate
    while (!cursor.isAfter(today)) {
        if (allowedDates == null || cursor in allowedDates) {
            val dow = cursor.dayOfWeek
            counts[dow] = (counts[dow] ?: 0) + 1
        }
        cursor = cursor.plusDays(1)
    }
    return counts
}

private fun normalizeMinuteDimId(dimensionId: String?): String? {
    if (dimensionId == null) return null
    val t = dimensionId.trim()
    return if (t.isBlank() || t.lowercase() == "unassigned") null else dimensionId
}
