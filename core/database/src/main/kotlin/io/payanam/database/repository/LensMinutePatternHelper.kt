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
    /** Logger. */
    logger: UnifiedLogger,
): MinutePatternData {
    /** Completed entries. */
    val completedEntries = allEntries.filter { it.endedAt != null }
    logger.d(
        "LensMinutePatternHelper.buildMinutePatternData",
        "Building minute pattern data",
        /** Map of. */
        mapOf("totalEntries" to allEntries.size, "completedEntries" to completedEntries.size, "excludeEmptyDays" to excludeEmptyDays),
    )

    /** If. */
    if (completedEntries.isEmpty()) {
        logger.d("LensMinutePatternHelper.buildMinutePatternData", "No completed entries — returning empty minute pattern")
        return MinutePatternData(emptyList())
    }

    // Layer 0: Calendar spine — count DOW occurrences from firstTrackedDate to today.
    // When excludeEmptyDays=true, days with zero tracked entries are excluded from counts.
    /** First tracked date. */
    val firstTrackedDate = completedEntries.minOf { it.startedAt.toLocalDate() }
    /** Today. */
    val today = LocalDate.now()
    /** Tracked dates. */
    val trackedDates: Set<LocalDate> =
        /** If. */
        if (excludeEmptyDays) {
            /** Dates. */
            val dates = mutableSetOf<LocalDate>()
            completedEntries.forEach { entry ->
                /** D. */
                var d = entry.startedAt.toLocalDate()
                /** End. */
                val end = entry.endedAt!!.toLocalDate()
                /** While. */
                while (!d.isAfter(end)) {
                    dates.add(d)
                    d = d.plusDays(1)
                }
            }
            /** Dates. */
            dates
        } else {
            /** Empty set. */
            emptySet()
        }
    /** Dow counts. */
    val dowCounts = countDowOccurrences(firstTrackedDate, today, if (excludeEmptyDays) trackedDates else null)

    logger.d(
        "LensMinutePatternHelper.buildMinutePatternData",
        "DOW occurrence counts computed",
        /** Map of. */
        mapOf(
            "firstTrackedDate" to firstTrackedDate.toString(),
            "today" to today.toString(),
            "dowCounts" to dowCounts.map { "${it.key}=${it.value}" }.joinToString(","),
        ),
    )

    // Layer 1: Pre-allocate accumulator: DOW -> Array<MutableMap<String?, Int>> (1440 minutes)
    /** Accumulator. */
    val accumulator: Map<DayOfWeek, Array<MutableMap<String?, Int>>> =
        DayOfWeek.values().associateWith {
            /** Array. */
            Array(MINUTES_PER_DAY_MP) { mutableMapOf() }
        }

    completedEntries.forEach { entry ->
        /** Entry end. */
        val entryEnd = entry.endedAt!!
        /** Normalized dim id. */
        val normalizedDimId = normalizeMinuteDimId(entry.dimensionId)

        // Split across day boundaries
        /** Day cursor. */
        var dayCursor = entry.startedAt.toLocalDate()
        /** End day. */
        val endDay = entryEnd.toLocalDate()
        /** While. */
        while (!dayCursor.isAfter(endDay)) {
            /** Day start. */
            val dayStart = dayCursor.atStartOfDay()
            /** Day end exclusive. */
            val dayEndExclusive = dayStart.plusDays(1)
            /** Seg start. */
            val segStart = if (entry.startedAt.isBefore(dayStart)) dayStart else entry.startedAt
            /** Seg end. */
            val segEnd = if (entryEnd.isAfter(dayEndExclusive)) dayEndExclusive else entryEnd

            /** If. */
            if (segEnd.isAfter(segStart)) {
                /** Start min. */
                val startMin =
                    /** Duration. */
                    Duration
                        .between(dayStart, segStart)
                        .toMinutes()
                        .toInt()
                        .coerceIn(0, MINUTES_PER_DAY_MP)
                /** End min. */
                val endMin =
                    /** Duration. */
                    Duration
                        .between(dayStart, segEnd)
                        .toMinutes()
                        .toInt()
                        .coerceIn(0, MINUTES_PER_DAY_MP)
                /** Dow. */
                val dow = dayCursor.dayOfWeek
                /** Minute array. */
                val minuteArray = accumulator[dow]!!
                /** For. */
                for (m in startMin until endMin) {
                    /** Dim map. */
                    val dimMap = minuteArray[m]
                    dimMap[normalizedDimId] = (dimMap[normalizedDimId] ?: 0) + 1
                }
            }
            dayCursor = dayCursor.plusDays(1)
        }
    }

    // Build orderedDows: today's DOW first, going back 6 days (same as WeekGrid)
    /** Today dow. */
    val todayDow = today.dayOfWeek
    /** Ordered dows. */
    val orderedDows =
        (0 until 7).map { i ->
            DayOfWeek.of(((todayDow.value - 1 - i + 70) % 7) + 1)
        }

    // Layer 2 + 3: Untracked injection + argmax per minute
    /** Days. */
    val days =
        orderedDows.map { dow ->
            /** Occ count. */
            val occCount = dowCounts[dow] ?: 0
            /** If. */
            if (occCount == 0) {
                // No data for this DOW — all minutes are untracked
                return@map MinutePatternDay(
                    dayOfWeek = dow,
                    minuteWinners = List(MINUTES_PER_DAY_MP) { MINUTE_UNTRACKED_SENTINEL },
                )
            }
            /** Minute array. */
            val minuteArray = accumulator[dow]!!
            /** Winners. */
            val winners =
                /** List. */
                List(MINUTES_PER_DAY_MP) { m ->
                    /** Dim map. */
                    val dimMap = minuteArray[m]
                    /** Tracked total. */
                    val trackedTotal = dimMap.values.sum()
                    /** Untracked count. */
                    val untrackedCount = (occCount - trackedTotal).coerceAtLeast(0)

                    // Argmax: find tracked dim with highest count; ties: tracked beats untracked
                    /** Best tracked key. */
                    var bestTrackedKey: String? = null // use a sentinel-absent key
                    /** Best tracked found. */
                    var bestTrackedFound = false
                    /** Best tracked count. */
                    var bestTrackedCount = 0
                    /** For. */
                    for ((key, count) in dimMap) {
                        /** If. */
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
            /** Minute pattern day. */
            MinutePatternDay(dayOfWeek = dow, minuteWinners = winners)
        }

    /** Distinct winners. */
    val distinctWinners = days.flatMap { it.minuteWinners }.toSet().size
    logger.d(
        "LensMinutePatternHelper.buildMinutePatternData",
        "Minute pattern built",
        /** Map of. */
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
    /** First date. */
    firstDate: LocalDate,
    /** Today. */
    today: LocalDate,
    allowedDates: Set<LocalDate>? = null,
): Map<DayOfWeek, Int> {
    /** Counts. */
    val counts = mutableMapOf<DayOfWeek, Int>()
    /** Cursor. */
    var cursor = firstDate
    /** While. */
    while (!cursor.isAfter(today)) {
        /** If. */
        if (allowedDates == null || cursor in allowedDates) {
            /** Dow. */
            val dow = cursor.dayOfWeek
            counts[dow] = (counts[dow] ?: 0) + 1
        }
        cursor = cursor.plusDays(1)
    }
    return counts
}

private fun normalizeMinuteDimId(dimensionId: String?): String? {
    /** If. */
    if (dimensionId == null) return null
    /** T. */
    val t = dimensionId.trim()
    return if (t.isBlank() || t.lowercase() == "unassigned") null else dimensionId
}
