//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length")

package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.AverageDailyTimeRow
import io.payanam.domain.repository.AverageDailyTimeRowType
import io.payanam.domain.repository.AverageDailyTimeTableData
import io.payanam.domain.repository.AverageDailyTimeWindow
import io.payanam.domain.repository.DailyFocusStat
import io.payanam.domain.repository.DailyFocusedHoursStat
import io.payanam.domain.repository.DailyTrackedTimeStat
import io.payanam.domain.model.DimensionTaxonomyCatalog
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

internal object DailyStatsCalculator {
    private val logger get() = if (UnifiedLogger.isInitialized()) UnifiedLogger.getInstance() else null

    fun calculateDailyFocusAverages(entries: List<TimeEntry>): List<DailyFocusStat> {
        val segments = splitToDaySegments(entries)
        if (segments.isEmpty()) {
            logger?.d(
                "DailyStatsCalculator.calculateDailyFocusAverages",
                "No completed entries; returning empty stats",
                mapOf("inputCount" to entries.size),
            )
            return emptyList()
        }
        val grouped = segments.groupBy { it.dayKey }
        val result =
            allDaysBetween(segments.minOf { it.dayKey }, segments.maxOf { it.dayKey }).map { dayKey ->
                val avg = grouped[dayKey]?.mapNotNull { it.focusRating }?.takeIf { it.isNotEmpty() }?.average()
                DailyFocusStat(dayKey = dayKey, avgFocus = avg)
            }
        logger?.d(
            "DailyStatsCalculator.calculateDailyFocusAverages",
            "Calculated focus averages",
            mapOf(
                "inputEntries" to entries.size,
                "outputDays" to result.size,
                "daysWithFocus" to result.count { it.avgFocus != null },
            ),
        )
        return result
    }

    fun calculateDailyTrackedTimeStats(entries: List<TimeEntry>): List<DailyTrackedTimeStat> {
        val segments = splitToDaySegments(entries)
        if (segments.isEmpty()) {
            logger?.d(
                "DailyStatsCalculator.calculateDailyTrackedTimeStats",
                "No completed entries; returning empty stats",
                mapOf("inputCount" to entries.size),
            )
            return emptyList()
        }
        val grouped = segments.groupBy { it.dayKey }
        val result =
            allDaysBetween(segments.minOf { it.dayKey }, segments.maxOf { it.dayKey }).map { dayKey ->
                val trackedMinutes = grouped[dayKey]?.sumOf { it.minutes } ?: 0L
                val trackedPercent = ((trackedMinutes.toDouble() / MINUTES_PER_DAY) * 100.0).coerceIn(0.0, 100.0)
                DailyTrackedTimeStat(dayKey = dayKey, trackedPercent = trackedPercent)
            }
        logger?.d(
            "DailyStatsCalculator.calculateDailyTrackedTimeStats",
            "Calculated tracked time stats",
            mapOf(
                "inputEntries" to entries.size,
                "outputDays" to result.size,
            ),
        )
        return result
    }

    fun calculateDailyFocusedHoursStats(entries: List<TimeEntry>): List<DailyFocusedHoursStat> {
        val segments = splitToDaySegments(entries)
        if (segments.isEmpty()) {
            logger?.d(
                "DailyStatsCalculator.calculateDailyFocusedHoursStats",
                "No completed entries; returning empty stats",
                mapOf("inputCount" to entries.size),
            )
            return emptyList()
        }
        val grouped = segments.groupBy { it.dayKey }
        val result =
            allDaysBetween(segments.minOf { it.dayKey }, segments.maxOf { it.dayKey }).map { dayKey ->
                val focusedMinutes =
                    grouped[dayKey]?.sumOf { seg ->
                        (seg.minutes * (seg.focusRating ?: 0.0)).toLong()
                    } ?: 0L
                val focusedHours = (focusedMinutes.toDouble() / 60.0).coerceIn(0.0, 24.0)
                DailyFocusedHoursStat(dayKey = dayKey, focusedHours = focusedHours)
            }
        logger?.d(
            "DailyStatsCalculator.calculateDailyFocusedHoursStats",
            "Calculated focused hours stats",
            mapOf(
                "inputEntries" to entries.size,
                "outputDays" to result.size,
            ),
        )
        return result
    }

    fun calculateAverageDailyTimeTable(
        entries: List<TimeEntry>,
        now: LocalDateTime = LocalDateTime.now(),
    ): AverageDailyTimeTableData? {
        if (entries.isEmpty()) {
            logger?.d(
                "DailyStatsCalculator.calculateAverageDailyTimeTable",
                "No time entries available; returning null",
                mapOf("inputCount" to entries.size),
            )
            return null
        }

        val firstTrackedDate = entries.minOfOrNull { it.startedAt.toLocalDate() } ?: return null
        val asOfDate = now.toLocalDate()
        if (asOfDate.isBefore(firstTrackedDate)) {
            logger?.d(
                "DailyStatsCalculator.calculateAverageDailyTimeTable",
                "Average table has no eligible days",
                mapOf(
                    "firstTrackedDate" to firstTrackedDate.toString(),
                    "asOfDate" to asOfDate.toString(),
                ),
            )
            return null
        }

        val totalCalendarDays = ChronoUnit.DAYS.between(firstTrackedDate, asOfDate).toInt() + 1
        val visibleWindows = AverageDailyTimeWindow.entries.filter { totalCalendarDays >= it.minCalendarDays }
        val allDays = generateSequence(firstTrackedDate) { it.plusDays(1) }
            .take(totalCalendarDays)
            .toList()
        val tracking = buildAverageDailyTimeTracking(entries, firstTrackedDate, asOfDate, now)
        val rows = buildAverageDailyTimeRows(
            visibleWindows = visibleWindows,
            allDays = allDays,
            trackedByDay = tracking.trackedByDay,
            untrackedByDay = tracking.untrackedByDay,
            orderedDimensionIds = tracking.orderedDimensionIds,
            sawUnassigned = tracking.sawUnassigned,
        )

        val result =
            AverageDailyTimeTableData(
                firstTrackedDate = firstTrackedDate,
                asOfDate = asOfDate,
                totalCalendarDays = totalCalendarDays,
                visibleWindows = visibleWindows,
                rows = rows,
            )
        logger?.d(
            "DailyStatsCalculator.calculateAverageDailyTimeTable",
            "Calculated average daily time table",
            mapOf(
                "inputEntries" to entries.size,
                "firstTrackedDate" to firstTrackedDate.toString(),
                "asOfDate" to asOfDate.toString(),
                "days" to totalCalendarDays,
                "visibleWindows" to visibleWindows.size,
                "rows" to rows.size,
            ),
        )
        return result
    }

    private data class AverageDailyTimeTracking(
        val trackedByDay: Map<LocalDate, Map<String?, Int>>,
        val untrackedByDay: Map<LocalDate, Int>,
        val orderedDimensionIds: List<String>,
        val sawUnassigned: Boolean,
    )

    private fun buildAverageDailyTimeTracking(
        entries: List<TimeEntry>,
        firstTrackedDate: LocalDate,
        asOfDate: LocalDate,
        now: LocalDateTime,
    ): AverageDailyTimeTracking {
        val trackedByDay = mutableMapOf<LocalDate, MutableMap<String?, Int>>()
        val seenDimensionIds = linkedSetOf<String>()
        var sawUnassigned = false

        entries.forEach { entry ->
            val dimensionKey = normalizedDimensionKey(entry.dimensionId)
            if (dimensionKey == null) {
                sawUnassigned = true
            } else {
                seenDimensionIds.add(dimensionKey)
            }
            val entryEnd = entry.endedAt ?: now
            var cursor = entry.startedAt
            while (cursor.isBefore(entryEnd)) {
                val day = cursor.toLocalDate()
                val segmentEnd =
                    if (day == entryEnd.toLocalDate()) {
                        entryEnd
                    } else {
                        day.plusDays(1).atStartOfDay()
                    }
                val minutes = Duration.between(cursor, segmentEnd).toMinutes().toInt()
                if (minutes > 0 && !day.isBefore(firstTrackedDate) && !day.isAfter(asOfDate)) {
                    val dayTotals = trackedByDay.getOrPut(day) { mutableMapOf() }
                    dayTotals[dimensionKey] = (dayTotals[dimensionKey] ?: 0) + minutes
                }
                cursor = segmentEnd
            }
        }

        val orderedDimensionIds =
            seenDimensionIds.sortedWith(
                compareBy<String> {
                    DimensionTaxonomyCatalog.fromCanonicalId(it)?.sortOrder ?: Int.MAX_VALUE
                }.thenBy { it },
            )
        val allDays = generateSequence(firstTrackedDate) { it.plusDays(1) }
            .take(ChronoUnit.DAYS.between(firstTrackedDate, asOfDate).toInt() + 1)
            .toList()
        val windowDays = allDays.associateWith { day -> dayPossibleMinutes(day, asOfDate, now) }
        val untrackedByDay =
            allDays.associateWith { day ->
                val trackedMinutes = trackedByDay[day]?.values?.sum() ?: 0
                (windowDays.getValue(day) - trackedMinutes).coerceAtLeast(0)
            }

        return AverageDailyTimeTracking(
            trackedByDay = trackedByDay,
            untrackedByDay = untrackedByDay,
            orderedDimensionIds = orderedDimensionIds,
            sawUnassigned = sawUnassigned,
        )
    }

    private fun buildAverageDailyTimeRows(
        visibleWindows: List<AverageDailyTimeWindow>,
        allDays: List<LocalDate>,
        trackedByDay: Map<LocalDate, Map<String?, Int>>,
        untrackedByDay: Map<LocalDate, Int>,
        orderedDimensionIds: List<String>,
        sawUnassigned: Boolean,
    ): List<AverageDailyTimeRow> {
        val rows = mutableListOf<AverageDailyTimeRow>()
        orderedDimensionIds.forEach { dimensionId ->
            rows.add(
                AverageDailyTimeRow(
                    rowType = AverageDailyTimeRowType.DIMENSION,
                    dimensionId = dimensionId,
                    averageMinutesByWindow = visibleWindows.associateWith { window ->
                        averageMinutesForWindow(
                            window = window,
                            allDays = allDays,
                            trackedByDay = trackedByDay,
                            untrackedByDay = untrackedByDay,
                            dimensionId = dimensionId,
                            rowType = AverageDailyTimeRowType.DIMENSION,
                        )
                    },
                ),
            )
        }
        if (sawUnassigned) {
            rows.add(
                AverageDailyTimeRow(
                    rowType = AverageDailyTimeRowType.UNASSIGNED,
                    averageMinutesByWindow = visibleWindows.associateWith { window ->
                        averageMinutesForWindow(
                            window = window,
                            allDays = allDays,
                            trackedByDay = trackedByDay,
                            untrackedByDay = untrackedByDay,
                            dimensionId = null,
                            rowType = AverageDailyTimeRowType.UNASSIGNED,
                        )
                    },
                ),
            )
        }
        rows.add(
            AverageDailyTimeRow(
                rowType = AverageDailyTimeRowType.UNTRACKED,
                averageMinutesByWindow = visibleWindows.associateWith { window ->
                    averageMinutesForWindow(
                        window = window,
                        allDays = allDays,
                        trackedByDay = trackedByDay,
                        untrackedByDay = untrackedByDay,
                        dimensionId = null,
                        rowType = AverageDailyTimeRowType.UNTRACKED,
                    )
                },
            ),
        )
        return rows
    }

    private data class DaySegment(
        val dayKey: String,
        val minutes: Long,
        val focusRating: Double?,
    )

    private fun splitToDaySegments(entries: List<TimeEntry>): List<DaySegment> {
        val result = mutableListOf<DaySegment>()
        for (entry in entries) {
            val end = entry.endedAt ?: continue
            var current = entry.startedAt
            while (current.isBefore(end)) {
                val dayKey = current.toLocalDate().toString()
                val segmentEnd =
                    if (current.toLocalDate() == end.toLocalDate()) {
                        end
                    } else {
                        current.toLocalDate().plusDays(1).atStartOfDay()
                    }
                val minutes = Duration.between(current, segmentEnd).toMinutes()
                if (minutes > 0L) {
                    result.add(DaySegment(dayKey = dayKey, minutes = minutes, focusRating = entry.focusRating))
                }
                current = segmentEnd
            }
        }
        return result
    }

    fun calculateDimensionSplit(
        entries: List<TimeEntry>,
        start: LocalDate,
        end: LocalDate,
    ): Map<String?, Int> {
        val result = mutableMapOf<String?, Int>()
        for (entry in entries) {
            val entryEnd = entry.endedAt ?: continue
            var current = entry.startedAt
            while (current.isBefore(entryEnd)) {
                val day = current.toLocalDate()
                val segmentEnd =
                    if (day == entryEnd.toLocalDate()) {
                        entryEnd
                    } else {
                        day.plusDays(1).atStartOfDay()
                    }
                val minutes = Duration.between(current, segmentEnd).toMinutes()
                if (minutes > 0L && !day.isBefore(start) && !day.isAfter(end)) {
                    result[entry.dimensionId] = (result[entry.dimensionId] ?: 0) + minutes.toInt()
                }
                current = segmentEnd
            }
        }
        logger?.d(
            "DailyStatsCalculator.calculateDimensionSplit",
            "Calculated dimension split",
            mapOf("inputEntries" to entries.size, "start" to start.toString(), "end" to end.toString(), "dimensions" to result.size),
        )
        return result
    }

    private fun allDaysBetween(
        startKey: String,
        endKey: String,
    ): List<String> {
        val days = mutableListOf<String>()
        var current = LocalDate.parse(startKey)
        val end = LocalDate.parse(endKey)
        while (!current.isAfter(end)) {
            days.add(current.toString())
            current = current.plusDays(1)
        }
        return days
    }

    private fun normalizedDimensionKey(dimensionId: String?): String? {
        val normalized = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DimensionTaxonomyCatalog.fromCanonicalId(normalized)?.id ?: normalized
    }

    private fun dayPossibleMinutes(
        day: LocalDate,
        asOfDate: LocalDate,
        now: LocalDateTime,
    ): Int = if (day.isBefore(asOfDate)) {
        MINUTES_PER_DAY
    } else {
        Duration.between(day.atStartOfDay(), now).toMinutes().toInt().coerceIn(0, MINUTES_PER_DAY)
    }

    private fun averageMinutesForWindow(
        window: AverageDailyTimeWindow,
        allDays: List<LocalDate>,
        trackedByDay: Map<LocalDate, Map<String?, Int>>,
        untrackedByDay: Map<LocalDate, Int>,
        dimensionId: String?,
        rowType: AverageDailyTimeRowType,
    ): Double {
        val windowDays =
            when (window) {
                AverageDailyTimeWindow.TODAY_SO_FAR -> listOf(allDays.last())
                AverageDailyTimeWindow.YESTERDAY -> listOf(allDays.last().minusDays(1))
                AverageDailyTimeWindow.LAST_7_DAYS -> allDays.takeLast(7)
                AverageDailyTimeWindow.LAST_30_DAYS -> allDays.takeLast(30)
                AverageDailyTimeWindow.LAST_90_DAYS -> allDays.takeLast(90)
                AverageDailyTimeWindow.LAST_180_DAYS -> allDays.takeLast(180)
                AverageDailyTimeWindow.LAST_365_DAYS -> allDays.takeLast(365)
                AverageDailyTimeWindow.ALL_DAYS -> allDays
            }
        if (windowDays.isEmpty()) {
            return 0.0
        }
        val totalMinutes =
            when (rowType) {
                AverageDailyTimeRowType.DIMENSION -> windowDays.sumOf { day -> trackedByDay[day]?.get(dimensionId) ?: 0 }
                AverageDailyTimeRowType.UNASSIGNED -> windowDays.sumOf { day -> trackedByDay[day]?.get(null) ?: 0 }
                AverageDailyTimeRowType.UNTRACKED -> windowDays.sumOf { day -> untrackedByDay[day] ?: 0 }
            }
        return totalMinutes.toDouble() / windowDays.size.toDouble()
    }
}

private const val MINUTES_PER_DAY = 24 * 60
