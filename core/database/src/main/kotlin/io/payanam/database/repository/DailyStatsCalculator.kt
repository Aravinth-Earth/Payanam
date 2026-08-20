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

    /**
     * Calculate daily focus averages.
     */
    fun calculateDailyFocusAverages(entries: List<TimeEntry>): List<DailyFocusStat> {
        /** Segments. */
        val segments = splitToDaySegments(entries)
        /** If. */
        if (segments.isEmpty()) {
            logger?.d(
                "DailyStatsCalculator.calculateDailyFocusAverages",
                "No completed entries; returning empty stats",
                /** Map of. */
                mapOf("inputCount" to entries.size),
            )
            return emptyList()
        }
        /** Grouped. */
        val grouped = segments.groupBy { it.dayKey }
        /** Result. */
        val result =
            /** All days between. */
            allDaysBetween(segments.minOf { it.dayKey }, segments.maxOf { it.dayKey }).map { dayKey ->
                /** Avg. */
                val avg = grouped[dayKey]?.mapNotNull { it.focusRating }?.takeIf { it.isNotEmpty() }?.average()
                /** Daily focus stat. */
                DailyFocusStat(dayKey = dayKey, avgFocus = avg)
            }
        logger?.d(
            "DailyStatsCalculator.calculateDailyFocusAverages",
            "Calculated focus averages",
            /** Map of. */
            mapOf(
                "inputEntries" to entries.size,
                "outputDays" to result.size,
                "daysWithFocus" to result.count { it.avgFocus != null },
            ),
        )
        return result
    }

    /**
     * Calculate daily tracked time stats.
     */
    fun calculateDailyTrackedTimeStats(entries: List<TimeEntry>): List<DailyTrackedTimeStat> {
        /** Segments. */
        val segments = splitToDaySegments(entries)
        /** If. */
        if (segments.isEmpty()) {
            logger?.d(
                "DailyStatsCalculator.calculateDailyTrackedTimeStats",
                "No completed entries; returning empty stats",
                /** Map of. */
                mapOf("inputCount" to entries.size),
            )
            return emptyList()
        }
        /** Grouped. */
        val grouped = segments.groupBy { it.dayKey }
        /** Result. */
        val result =
            /** All days between. */
            allDaysBetween(segments.minOf { it.dayKey }, segments.maxOf { it.dayKey }).map { dayKey ->
                /** Tracked minutes. */
                val trackedMinutes = grouped[dayKey]?.sumOf { it.minutes } ?: 0L
                /** Tracked percent. */
                val trackedPercent = ((trackedMinutes.toDouble() / MINUTES_PER_DAY) * 100.0).coerceIn(0.0, 100.0)
                /** Daily tracked time stat. */
                DailyTrackedTimeStat(dayKey = dayKey, trackedPercent = trackedPercent)
            }
        logger?.d(
            "DailyStatsCalculator.calculateDailyTrackedTimeStats",
            "Calculated tracked time stats",
            /** Map of. */
            mapOf(
                "inputEntries" to entries.size,
                "outputDays" to result.size,
            ),
        )
        return result
    }

    /**
     * Calculate daily focused hours stats.
     */
    fun calculateDailyFocusedHoursStats(entries: List<TimeEntry>): List<DailyFocusedHoursStat> {
        /** Segments. */
        val segments = splitToDaySegments(entries)
        /** If. */
        if (segments.isEmpty()) {
            logger?.d(
                "DailyStatsCalculator.calculateDailyFocusedHoursStats",
                "No completed entries; returning empty stats",
                /** Map of. */
                mapOf("inputCount" to entries.size),
            )
            return emptyList()
        }
        /** Grouped. */
        val grouped = segments.groupBy { it.dayKey }
        /** Result. */
        val result =
            /** All days between. */
            allDaysBetween(segments.minOf { it.dayKey }, segments.maxOf { it.dayKey }).map { dayKey ->
                /** Focused minutes. */
                val focusedMinutes =
                    grouped[dayKey]?.sumOf { seg ->
                        (seg.minutes * (seg.focusRating ?: 0.0)).toLong()
                    } ?: 0L
                /** Focused hours. */
                val focusedHours = (focusedMinutes.toDouble() / 60.0).coerceIn(0.0, 24.0)
                /** Daily focused hours stat. */
                DailyFocusedHoursStat(dayKey = dayKey, focusedHours = focusedHours)
            }
        logger?.d(
            "DailyStatsCalculator.calculateDailyFocusedHoursStats",
            "Calculated focused hours stats",
            /** Map of. */
            mapOf(
                "inputEntries" to entries.size,
                "outputDays" to result.size,
            ),
        )
        return result
    }

    /**
     * Calculate average daily time table.
     */
    fun calculateAverageDailyTimeTable(
        entries: List<TimeEntry>,
        now: LocalDateTime = LocalDateTime.now(),
    ): AverageDailyTimeTableData? {
        /** If. */
        if (entries.isEmpty()) {
            logger?.d(
                "DailyStatsCalculator.calculateAverageDailyTimeTable",
                "No time entries available; returning null",
                /** Map of. */
                mapOf("inputCount" to entries.size),
            )
            return null
        }

        /** First tracked date. */
        val firstTrackedDate = entries.minOfOrNull { it.startedAt.toLocalDate() } ?: return null
        /** As of date. */
        val asOfDate = now.toLocalDate()
        /** If. */
        if (asOfDate.isBefore(firstTrackedDate)) {
            logger?.d(
                "DailyStatsCalculator.calculateAverageDailyTimeTable",
                "Average table has no eligible days",
                /** Map of. */
                mapOf(
                    "firstTrackedDate" to firstTrackedDate.toString(),
                    "asOfDate" to asOfDate.toString(),
                ),
            )
            return null
        }

        /** Total calendar days. */
        val totalCalendarDays = ChronoUnit.DAYS.between(firstTrackedDate, asOfDate).toInt() + 1
        /** Visible windows. */
        val visibleWindows = AverageDailyTimeWindow.entries.filter { totalCalendarDays >= it.minCalendarDays }
        /** All days. */
        val allDays = generateSequence(firstTrackedDate) { it.plusDays(1) }
            .take(totalCalendarDays)
            .toList()
        /** Tracking. */
        val tracking = buildAverageDailyTimeTracking(entries, firstTrackedDate, asOfDate, now)
        /** Rows. */
        val rows = buildAverageDailyTimeRows(
            visibleWindows = visibleWindows,
            allDays = allDays,
            trackedByDay = tracking.trackedByDay,
            untrackedByDay = tracking.untrackedByDay,
            orderedDimensionIds = tracking.orderedDimensionIds,
            sawUnassigned = tracking.sawUnassigned,
        )

        /** Result. */
        val result =
            /** Average daily time table data. */
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
            /** Map of. */
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
        /** Tracked by day. */
        val trackedByDay: Map<LocalDate, Map<String?, Int>>,
        /** Untracked by day. */
        val untrackedByDay: Map<LocalDate, Int>,
        /** Ordered dimension ids. */
        val orderedDimensionIds: List<String>,
        /** Saw unassigned. */
        val sawUnassigned: Boolean,
    )

    private fun buildAverageDailyTimeTracking(
        entries: List<TimeEntry>,
        /** First tracked date. */
        firstTrackedDate: LocalDate,
        /** As of date. */
        asOfDate: LocalDate,
        /** Now. */
        now: LocalDateTime,
    ): AverageDailyTimeTracking {
        /** Tracked by day. */
        val trackedByDay = mutableMapOf<LocalDate, MutableMap<String?, Int>>()
        /** Seen dimension ids. */
        val seenDimensionIds = linkedSetOf<String>()
        /** Saw unassigned. */
        var sawUnassigned = false

        entries.forEach { entry ->
            /** Dimension key. */
            val dimensionKey = normalizedDimensionKey(entry.dimensionId)
            /** If. */
            if (dimensionKey == null) {
                sawUnassigned = true
            } else {
                seenDimensionIds.add(dimensionKey)
            }
            /** Entry end. */
            val entryEnd = entry.endedAt ?: now
            /** Cursor. */
            var cursor = entry.startedAt
            /** While. */
            while (cursor.isBefore(entryEnd)) {
                /** Day. */
                val day = cursor.toLocalDate()
                /** Segment end. */
                val segmentEnd =
                    /** If. */
                    if (day == entryEnd.toLocalDate()) {
                        /** Entry end. */
                        entryEnd
                    } else {
                        day.plusDays(1).atStartOfDay()
                    }
                /** Minutes. */
                val minutes = Duration.between(cursor, segmentEnd).toMinutes().toInt()
                /** If. */
                if (minutes > 0 && !day.isBefore(firstTrackedDate) && !day.isAfter(asOfDate)) {
                    /** Day totals. */
                    val dayTotals = trackedByDay.getOrPut(day) { mutableMapOf() }
                    dayTotals[dimensionKey] = (dayTotals[dimensionKey] ?: 0) + minutes
                }
                cursor = segmentEnd
            }
        }

        /** Ordered dimension ids. */
        val orderedDimensionIds =
            seenDimensionIds.sortedWith(
                compareBy<String> {
                    DimensionTaxonomyCatalog.fromCanonicalId(it)?.sortOrder ?: Int.MAX_VALUE
                }.thenBy { it },
            )
        /** All days. */
        val allDays = generateSequence(firstTrackedDate) { it.plusDays(1) }
            .take(ChronoUnit.DAYS.between(firstTrackedDate, asOfDate).toInt() + 1)
            .toList()
        /** Window days. */
        val windowDays = allDays.associateWith { day -> dayPossibleMinutes(day, asOfDate, now) }
        /** Untracked by day. */
        val untrackedByDay =
            allDays.associateWith { day ->
                /** Tracked minutes. */
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
        /** Saw unassigned. */
        sawUnassigned: Boolean,
    ): List<AverageDailyTimeRow> {
        /** Rows. */
        val rows = mutableListOf<AverageDailyTimeRow>()
        orderedDimensionIds.forEach { dimensionId ->
            rows.add(
                /** Average daily time row. */
                AverageDailyTimeRow(
                    rowType = AverageDailyTimeRowType.DIMENSION,
                    dimensionId = dimensionId,
                    averageMinutesByWindow = visibleWindows.associateWith { window ->
                        /** Average minutes for window. */
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
        /** If. */
        if (sawUnassigned) {
            rows.add(
                /** Average daily time row. */
                AverageDailyTimeRow(
                    rowType = AverageDailyTimeRowType.UNASSIGNED,
                    averageMinutesByWindow = visibleWindows.associateWith { window ->
                        /** Average minutes for window. */
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
            /** Average daily time row. */
            AverageDailyTimeRow(
                rowType = AverageDailyTimeRowType.UNTRACKED,
                averageMinutesByWindow = visibleWindows.associateWith { window ->
                    /** Average minutes for window. */
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
        /** Day key. */
        val dayKey: String,
        /** Minutes. */
        val minutes: Long,
        /** Focus rating. */
        val focusRating: Double?,
    )

    private fun splitToDaySegments(entries: List<TimeEntry>): List<DaySegment> {
        /** Result. */
        val result = mutableListOf<DaySegment>()
        /** For. */
        for (entry in entries) {
            /** End. */
            val end = entry.endedAt ?: continue
            /** Current. */
            var current = entry.startedAt
            /** While. */
            while (current.isBefore(end)) {
                /** Day key. */
                val dayKey = current.toLocalDate().toString()
                /** Segment end. */
                val segmentEnd =
                    /** If. */
                    if (current.toLocalDate() == end.toLocalDate()) {
                        /** End. */
                        end
                    } else {
                        current.toLocalDate().plusDays(1).atStartOfDay()
                    }
                /** Minutes. */
                val minutes = Duration.between(current, segmentEnd).toMinutes()
                /** If. */
                if (minutes > 0L) {
                    result.add(DaySegment(dayKey = dayKey, minutes = minutes, focusRating = entry.focusRating))
                }
                current = segmentEnd
            }
        }
        return result
    }

    /**
     * Calculate dimension split.
     */
    fun calculateDimensionSplit(
        entries: List<TimeEntry>,
        /** Start. */
        start: LocalDate,
        /** End. */
        end: LocalDate,
    ): Map<String?, Int> {
        /** Result. */
        val result = mutableMapOf<String?, Int>()
        /** For. */
        for (entry in entries) {
            /** Entry end. */
            val entryEnd = entry.endedAt ?: continue
            /** Current. */
            var current = entry.startedAt
            /** While. */
            while (current.isBefore(entryEnd)) {
                /** Day. */
                val day = current.toLocalDate()
                /** Segment end. */
                val segmentEnd =
                    /** If. */
                    if (day == entryEnd.toLocalDate()) {
                        /** Entry end. */
                        entryEnd
                    } else {
                        day.plusDays(1).atStartOfDay()
                    }
                /** Minutes. */
                val minutes = Duration.between(current, segmentEnd).toMinutes()
                /** If. */
                if (minutes > 0L && !day.isBefore(start) && !day.isAfter(end)) {
                    result[entry.dimensionId] = (result[entry.dimensionId] ?: 0) + minutes.toInt()
                }
                current = segmentEnd
            }
        }
        logger?.d(
            "DailyStatsCalculator.calculateDimensionSplit",
            "Calculated dimension split",
            /** Map of. */
            mapOf("inputEntries" to entries.size, "start" to start.toString(), "end" to end.toString(), "dimensions" to result.size),
        )
        return result
    }

    private fun allDaysBetween(
        /** Start key. */
        startKey: String,
        /** End key. */
        endKey: String,
    ): List<String> {
        /** Days. */
        val days = mutableListOf<String>()
        /** Current. */
        var current = LocalDate.parse(startKey)
        /** End. */
        val end = LocalDate.parse(endKey)
        /** While. */
        while (!current.isAfter(end)) {
            days.add(current.toString())
            current = current.plusDays(1)
        }
        return days
    }

    private fun normalizedDimensionKey(dimensionId: String?): String? {
        /** Normalized. */
        val normalized = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DimensionTaxonomyCatalog.fromCanonicalId(normalized)?.id ?: normalized
    }

    private fun dayPossibleMinutes(
        /** Day. */
        day: LocalDate,
        /** As of date. */
        asOfDate: LocalDate,
        /** Now. */
        now: LocalDateTime,
    ): Int = if (day.isBefore(asOfDate)) {
        /** Minutes per day. */
        MINUTES_PER_DAY
    } else {
        Duration.between(day.atStartOfDay(), now).toMinutes().toInt().coerceIn(0, MINUTES_PER_DAY)
    }

    private fun averageMinutesForWindow(
        /** Window. */
        window: AverageDailyTimeWindow,
        allDays: List<LocalDate>,
        trackedByDay: Map<LocalDate, Map<String?, Int>>,
        untrackedByDay: Map<LocalDate, Int>,
        dimensionId: String?,
        /** Row type. */
        rowType: AverageDailyTimeRowType,
    ): Double {
        /** Window days. */
        val windowDays =
            /** When. */
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
        /** If. */
        if (windowDays.isEmpty()) {
            return 0.0
        }
        /** Total minutes. */
        val totalMinutes =
            /** When. */
            when (rowType) {
                AverageDailyTimeRowType.DIMENSION -> windowDays.sumOf { day -> trackedByDay[day]?.get(dimensionId) ?: 0 }
                AverageDailyTimeRowType.UNASSIGNED -> windowDays.sumOf { day -> trackedByDay[day]?.get(null) ?: 0 }
                AverageDailyTimeRowType.UNTRACKED -> windowDays.sumOf { day -> untrackedByDay[day] ?: 0 }
            }
        return totalMinutes.toDouble() / windowDays.size.toDouble()
    }
}

private const val MINUTES_PER_DAY = 24 * 60
