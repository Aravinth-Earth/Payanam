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
    /** Window days. */
    windowDays: Int,
    /** Logger. */
    logger: UnifiedLogger,
): List<DimensionTrendBlock> {
    /** Today. */
    val today = LocalDate.now()
    /** Completed entries. */
    val completedEntries = allEntries.filter { it.endedAt != null }
    /** First tracked date. */
    val firstTrackedDate = completedEntries.minOfOrNull { it.startedAt.toLocalDate() } ?: today
    /** Total days. */
    val totalDays = ChronoUnit.DAYS.between(firstTrackedDate, today).toInt() + 1
    /** Block count. */
    val blockCount = ceil(totalDays.toDouble() / windowDays).toInt().coerceAtLeast(1)
    logger.d(
        "LensVisualsHelper.buildDimensionTrendBlocks",
        "Building dimension trend blocks",
        /** Map of. */
        mapOf(
            "windowDays" to windowDays,
            "blockCount" to blockCount,
            "firstTrackedDate" to firstTrackedDate.toString(),
            "totalDays" to totalDays,
        ),
    )
    /** Result. */
    val result = mutableListOf<DimensionTrendBlock>()
    /** For. */
    for (blockIndex in 0 until blockCount) {
        /** End date. */
        val endDate = today.minusDays((blockIndex * windowDays).toLong())
        /** Start date. */
        val startDate = today.minusDays(((blockIndex + 1) * windowDays - 1).toLong())
        /** Total possible minutes. */
        val totalPossibleMinutes = windowDays * VISUALS_MINUTES_PER_DAY
        /** Window start. */
        val windowStart = startDate.atStartOfDay()
        /** Window end. */
        val windowEnd = endDate.atStartOfDay().plusDays(1)
        /** By dimension. */
        val byDimension = mutableMapOf<String?, Int>()
        completedEntries.forEach { entry ->
            /** Minutes. */
            val minutes = clippedMinutes(entry.startedAt, entry.endedAt!!, windowStart, windowEnd)
            /** If. */
            if (minutes > 0) {
                /** Dim id. */
                val dimId = normalizeDimensionId(entry.dimensionId)
                byDimension[dimId] = (byDimension[dimId] ?: 0) + minutes
            }
        }
        result.add(
            /** Dimension trend block. */
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
        /** Map of. */
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
    /** Logger. */
    logger: UnifiedLogger,
): List<HeatmapDayData> {
    logger.d("LensVisualsHelper.buildHeatmapDays", "Building heatmap days")
    /** Iso formatter. */
    val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    /** Today. */
    val today = LocalDate.now()
    /** Completed entries. */
    val completedEntries = allEntries.filter { it.endedAt != null }
    /** First tracked date. */
    val firstTrackedDate = completedEntries.minOfOrNull { it.startedAt.toLocalDate() } ?: today

    // Build segment map from entries
    /** Day map. */
    val dayMap = mutableMapOf<String, MutableList<HeatmapEntrySegment>>()
    completedEntries.forEach { entry ->
        /** Entry end. */
        val entryEnd = entry.endedAt!!
        /** Entry start. */
        val entryStart = entry.startedAt
        /** Normalized dim id. */
        val normalizedDimId = normalizeDimensionId(entry.dimensionId)
        /** Cursor. */
        var cursor = entryStart.toLocalDate()
        /** End day. */
        val endDay = entryEnd.toLocalDate()
        /** While. */
        while (!cursor.isAfter(endDay)) {
            /** Day start. */
            val dayStart = cursor.atStartOfDay()
            /** Day end exclusive. */
            val dayEndExclusive = dayStart.plusDays(1)
            /** Seg start. */
            val segStart = if (entryStart.isBefore(dayStart)) dayStart else entryStart
            /** Seg end. */
            val segEnd = if (entryEnd.isAfter(dayEndExclusive)) dayEndExclusive else entryEnd
            /** If. */
            if (segEnd.isAfter(segStart)) {
                /** Start minute. */
                val startMinute =
                    java.time.Duration
                        .between(dayStart, segStart)
                        .toMinutes()
                        .toInt()
                        .coerceAtLeast(0)
                /** Duration minutes. */
                val durationMinutes =
                    java.time.Duration
                        .between(segStart, segEnd)
                        .toMinutes()
                        .toInt()
                        .coerceAtLeast(0)
                /** If. */
                if (durationMinutes > 0) {
                    /** Day key. */
                    val dayKey = cursor.format(isoFormatter)
                    dayMap.getOrPut(dayKey) { mutableListOf() }.add(
                        /** Heatmap entry segment. */
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
    /** Total days. */
    val totalDays = ChronoUnit.DAYS.between(firstTrackedDate, today).toInt() + 1
    /** All days. */
    val allDays =
        /** Generate sequence. */
        generateSequence(today) { it.minusDays(1) }
            .take(totalDays)
            .map { date ->
                /** Day key. */
                val dayKey = date.format(isoFormatter)
                /** Heatmap day data. */
                HeatmapDayData(dayKey = dayKey, segments = dayMap[dayKey] ?: emptyList())
            }.toList()

    logger.d(
        "LensVisualsHelper.buildHeatmapDays",
        "Heatmap days built",
        /** Map of. */
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
    /** Entry start. */
    entryStart: LocalDateTime,
    /** Entry end. */
    entryEnd: LocalDateTime,
    /** Window start. */
    windowStart: LocalDateTime,
    /** Window end. */
    windowEnd: LocalDateTime,
): Int {
    /** Clipped start. */
    val clippedStart = if (entryStart.isBefore(windowStart)) windowStart else entryStart
    /** Clipped end. */
    val clippedEnd = if (entryEnd.isAfter(windowEnd)) windowEnd else entryEnd
    /** If. */
    if (!clippedEnd.isAfter(clippedStart)) return 0
    return java.time.Duration
        .between(clippedStart, clippedEnd)
        .toMinutes()
        .toInt()
        .coerceAtLeast(0)
}

private fun normalizeDimensionId(dimensionId: String?): String? {
    /** If. */
    if (dimensionId == null) return null
    /** Trimmed. */
    val trimmed = dimensionId.trim()
    return if (trimmed.isBlank() || trimmed.lowercase() == "unassigned") null else dimensionId
}
