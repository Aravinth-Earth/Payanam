//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length")

package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.DimensionTrendBlock
import io.payanam.domain.repository.HeatmapDayData
import io.payanam.domain.repository.HeatmapEntrySegment
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

private const val VISUALS_MINUTES_PER_DAY = 24 * 60

/**
 * Builds stacked proportional bar blocks for the Dimension Trend chart.
 * Most recent block is index 0. Each block spans windowDays days.
 * Block count is computed dynamically from firstTrackedDate so all history is covered.
 */
internal fun buildDimensionTrendBlocks(
    allEntries: List<TimeEntry>,
    windowDays: Int,
    logger: UnifiedLogger,
): List<DimensionTrendBlock> {
    val today = LocalDate.now()
    val completedEntries = allEntries.filter { it.endedAt != null }
    val firstTrackedDate = completedEntries.minOfOrNull { it.startedAt.toLocalDate() } ?: today
    val totalDays = ChronoUnit.DAYS.between(firstTrackedDate, today).toInt() + 1
    val blockCount = ceil(totalDays.toDouble() / windowDays).toInt().coerceAtLeast(1)
    logger.d(
        "LensVisualsHelper.buildDimensionTrendBlocks",
        "Building dimension trend blocks",
        mapOf(
            "windowDays" to windowDays,
            "blockCount" to blockCount,
            "firstTrackedDate" to firstTrackedDate.toString(),
            "totalDays" to totalDays,
        ),
    )
    val result = mutableListOf<DimensionTrendBlock>()
    for (blockIndex in 0 until blockCount) {
        val endDate = today.minusDays((blockIndex * windowDays).toLong())
        val startDate = today.minusDays(((blockIndex + 1) * windowDays - 1).toLong())
        val totalPossibleMinutes = windowDays * VISUALS_MINUTES_PER_DAY
        val windowStart = startDate.atStartOfDay()
        val windowEnd = endDate.atStartOfDay().plusDays(1)
        val byDimension = mutableMapOf<String?, Int>()
        completedEntries.forEach { entry ->
            val minutes = clippedMinutes(entry.startedAt, entry.endedAt!!, windowStart, windowEnd)
            if (minutes > 0) {
                val dimId = normalizeDimensionId(entry.dimensionId)
                byDimension[dimId] = (byDimension[dimId] ?: 0) + minutes
            }
        }
        result.add(
            DimensionTrendBlock(
                startDate = startDate,
                endDate = endDate,
                byDimension = byDimension,
                totalPossibleMinutes = totalPossibleMinutes,
            ),
        )
    }
    logger.d(
        "LensVisualsHelper.buildDimensionTrendBlocks",
        "Dimension trend blocks built",
        mapOf("blockCount" to result.size),
    )
    return result
}

/**
 * Builds heatmap day data from all time entries, splitting entries at midnight.
 * Fills every calendar date from firstTrackedDate to today — days with no entries
 * appear as empty segment lists (rendered as fully-untracked columns).
 * Result is sorted descending (most recent day first).
 */
internal fun buildHeatmapDays(
    allEntries: List<TimeEntry>,
    logger: UnifiedLogger,
): List<HeatmapDayData> {
    logger.d("LensVisualsHelper.buildHeatmapDays", "Building heatmap days")
    val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    val today = LocalDate.now()
    val completedEntries = allEntries.filter { it.endedAt != null }
    val firstTrackedDate = completedEntries.minOfOrNull { it.startedAt.toLocalDate() } ?: today

    // Build segment map from entries
    val dayMap = mutableMapOf<String, MutableList<HeatmapEntrySegment>>()
    completedEntries.forEach { entry ->
        val entryEnd = entry.endedAt!!
        val entryStart = entry.startedAt
        val normalizedDimId = normalizeDimensionId(entry.dimensionId)
        var cursor = entryStart.toLocalDate()
        val endDay = entryEnd.toLocalDate()
        while (!cursor.isAfter(endDay)) {
            val dayStart = cursor.atStartOfDay()
            val dayEndExclusive = dayStart.plusDays(1)
            val segStart = if (entryStart.isBefore(dayStart)) dayStart else entryStart
            val segEnd = if (entryEnd.isAfter(dayEndExclusive)) dayEndExclusive else entryEnd
            if (segEnd.isAfter(segStart)) {
                val startMinute =
                    java.time.Duration
                        .between(dayStart, segStart)
                        .toMinutes()
                        .toInt()
                        .coerceAtLeast(0)
                val durationMinutes =
                    java.time.Duration
                        .between(segStart, segEnd)
                        .toMinutes()
                        .toInt()
                        .coerceAtLeast(0)
                if (durationMinutes > 0) {
                    val dayKey = cursor.format(isoFormatter)
                    dayMap.getOrPut(dayKey) { mutableListOf() }.add(
                        HeatmapEntrySegment(
                            startMinute = startMinute,
                            durationMinutes = durationMinutes,
                            dimensionId = normalizedDimId,
                        ),
                    )
                }
            }
            cursor = cursor.plusDays(1)
        }
    }

    // Generate every calendar date from firstTrackedDate to today (most recent first).
    // Days with no entries get an empty segment list — they render as fully-untracked columns.
    val totalDays = ChronoUnit.DAYS.between(firstTrackedDate, today).toInt() + 1
    val allDays =
        generateSequence(today) { it.minusDays(1) }
            .take(totalDays)
            .map { date ->
                val dayKey = date.format(isoFormatter)
                HeatmapDayData(dayKey = dayKey, segments = dayMap[dayKey] ?: emptyList())
            }.toList()

    logger.d(
        "LensVisualsHelper.buildHeatmapDays",
        "Heatmap days built",
        mapOf(
            "totalDays" to allDays.size,
            "daysWithEntries" to dayMap.size,
            "totalSegments" to dayMap.values.sumOf { it.size },
            "firstTrackedDate" to firstTrackedDate.toString(),
        ),
    )
    return allDays
}

private fun clippedMinutes(
    entryStart: LocalDateTime,
    entryEnd: LocalDateTime,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
): Int {
    val clippedStart = if (entryStart.isBefore(windowStart)) windowStart else entryStart
    val clippedEnd = if (entryEnd.isAfter(windowEnd)) windowEnd else entryEnd
    if (!clippedEnd.isAfter(clippedStart)) return 0
    return java.time.Duration
        .between(clippedStart, clippedEnd)
        .toMinutes()
        .toInt()
        .coerceAtLeast(0)
}

private fun normalizeDimensionId(dimensionId: String?): String? {
    if (dimensionId == null) return null
    val trimmed = dimensionId.trim()
    return if (trimmed.isBlank() || trimmed.lowercase() == "unassigned") null else dimensionId
}
