//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.SlotEntry
import io.payanam.domain.repository.WeekGridData
import io.payanam.domain.repository.WeekGridDay
import io.payanam.domain.repository.WeekGridSlot
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

internal const val UNTRACKED_SENTINEL = "__untracked__"
private const val SLOTS_PER_DAY = 48
private const val SLOT_MINUTES = 30
private const val MINUTES_PER_DAY = 24 * 60

/**
 * Builds a 7-column × 48-row weekly behavioral pattern grid from all completed time entries.
 * Each slot covers a 30-minute window; rows aggregate across ALL history.
 */
@Suppress("LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")
internal fun buildWeekGridData(
    allEntries: List<TimeEntry>,
    excludeEmptyDays: Boolean = false,
    logger: UnifiedLogger,
): WeekGridData {
    val completedEntries = allEntries.filter { it.endedAt != null }
    logger.d(
        "LensWeekGridHelper.buildWeekGridData",
        "Building week grid data",
        mapOf("totalEntries" to allEntries.size, "completedEntries" to completedEntries.size, "excludeEmptyDays" to excludeEmptyDays),
    )

    if (completedEntries.isEmpty()) {
        logger.d("LensWeekGridHelper.buildWeekGridData", "No completed entries — returning empty grid")
        return WeekGridData(emptyList())
    }

    // Layer 0: Calendar spine — count dayOfWeek occurrences from firstTrackedDate to today
    // When excludeEmptyDays=true, only count dates that have at least one tracked entry.
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
    val dowOccurrences = mutableMapOf<DayOfWeek, Long>()
    var cursor = firstTrackedDate
    while (!cursor.isAfter(today)) {
        if (!excludeEmptyDays || cursor in trackedDates) {
            val dow = cursor.dayOfWeek
            dowOccurrences[dow] = (dowOccurrences[dow] ?: 0L) + 1L
        }
        cursor = cursor.plusDays(1)
    }
    logger.d(
        "LensWeekGridHelper.buildWeekGridData",
        "Day-of-week occurrence counts computed",
        mapOf(
            "firstTrackedDate" to firstTrackedDate.toString(),
            "today" to today.toString(),
            "dowCounts" to dowOccurrences.map { "${it.key}=${it.value}" }.joinToString(","),
        ),
    )

    // Layer 1: Raw accumulation — Triple<DayOfWeek, slotIndex, dimensionId?> -> Long (minutes)
    // dimensionId key: null = unassigned tracked time, UNTRACKED_SENTINEL = synthetic untracked bucket
    val accumulator = mutableMapOf<Triple<DayOfWeek, Int, String?>, Long>()

    completedEntries.forEach { entry ->
        val entryEnd = entry.endedAt!!
        val rawDimId = entry.dimensionId
        val normalizedDimId: String? =
            if (rawDimId == null) {
                null
            } else {
                val trimmed = rawDimId.trim()
                if (trimmed.isBlank() || trimmed.lowercase() == "unassigned") null else rawDimId
            }

        // Split entry across days it spans
        var dayCursor = entry.startedAt.toLocalDate()
        val endDay = entryEnd.toLocalDate()
        while (!dayCursor.isAfter(endDay)) {
            val dayStart = dayCursor.atStartOfDay()
            val dayEndExclusive = dayStart.plusDays(1)
            val segStart = if (entry.startedAt.isBefore(dayStart)) dayStart else entry.startedAt
            val segEnd = if (entryEnd.isAfter(dayEndExclusive)) dayEndExclusive else entryEnd

            if (segEnd.isAfter(segStart)) {
                // Convert to minutes-of-day
                val entryStartMinute =
                    Duration
                        .between(dayStart, segStart)
                        .toMinutes()
                        .toInt()
                        .coerceIn(0, MINUTES_PER_DAY)
                val entryEndMinute =
                    Duration
                        .between(dayStart, segEnd)
                        .toMinutes()
                        .toInt()
                        .coerceIn(0, MINUTES_PER_DAY)
                val dow = dayCursor.dayOfWeek

                // Distribute across 30-min slots
                for (slotIndex in 0 until SLOTS_PER_DAY) {
                    val slotStartMinute = slotIndex * SLOT_MINUTES
                    val slotEndMinute = slotStartMinute + SLOT_MINUTES
                    val clippedStart = maxOf(entryStartMinute, slotStartMinute)
                    val clippedEnd = minOf(entryEndMinute, slotEndMinute)
                    val minutes = maxOf(0, clippedEnd - clippedStart).toLong()
                    if (minutes > 0) {
                        val key = Triple(dow, slotIndex, normalizedDimId)
                        accumulator[key] = (accumulator[key] ?: 0L) + minutes
                    }
                }
            }
            dayCursor = dayCursor.plusDays(1)
        }
    }

    // Layer 2: Untracked injection
    DayOfWeek.values().forEach { dow ->
        val occurrences = dowOccurrences[dow] ?: 0L
        val totalPossible = occurrences * SLOT_MINUTES
        for (slotIndex in 0 until SLOTS_PER_DAY) {
            val trackedTotal =
                accumulator.entries
                    .filter { (k, _) -> k.first == dow && k.second == slotIndex && k.third != UNTRACKED_SENTINEL }
                    .sumOf { (_, v) -> v }
            val untrackedMinutes = maxOf(0L, totalPossible - trackedTotal)
            val untrackedKey = Triple(dow, slotIndex, UNTRACKED_SENTINEL)
            accumulator[untrackedKey] = untrackedMinutes
        }
    }

    // Layer 3: Rank + proportion per (dayOfWeek, slotIndex)
    // Build grid: dayOfWeek -> slotIndex -> WeekGridSlot
    val gridByDow = mutableMapOf<DayOfWeek, MutableList<WeekGridSlot>>()
    DayOfWeek.values().forEach { dow ->
        val slots = mutableListOf<WeekGridSlot>()
        for (slotIndex in 0 until SLOTS_PER_DAY) {
            // Collect all (dimensionId, minutes) for this (dow, slotIndex)
            val candidates =
                accumulator.entries
                    .filter { (k, _) -> k.first == dow && k.second == slotIndex }
                    .map { (k, v) -> k.third to v }
                    .sortedByDescending { (_, minutes) -> minutes }
                    .take(3)

            val totalTop3 = candidates.sumOf { (_, m) -> m }
            if (totalTop3 <= 0L) {
                slots.add(WeekGridSlot.EMPTY)
            } else {
                val rank1 =
                    candidates.getOrNull(0)?.let { (dimId, m) ->
                        SlotEntry(dimensionId = dimId, proportion = m.toFloat() / totalTop3)
                    }
                val rank2 =
                    candidates.getOrNull(1)?.let { (dimId, m) ->
                        SlotEntry(dimensionId = dimId, proportion = m.toFloat() / totalTop3)
                    }
                val rank3 =
                    candidates.getOrNull(2)?.let { (dimId, m) ->
                        SlotEntry(dimensionId = dimId, proportion = m.toFloat() / totalTop3)
                    }
                slots.add(WeekGridSlot(rank1 = rank1, rank2 = rank2, rank3 = rank3))
            }
        }
        gridByDow[dow] = slots
    }

    // Build 7 WeekGridDay objects: today's dayOfWeek first (index 0), then going back
    val todayDow = today.dayOfWeek
    val orderedDays =
        (0 until 7).map { i ->
            // i=0 -> today's dow, i=1 -> yesterday's dow, etc.
            val dow = DayOfWeek.of(((todayDow.value - 1 - i + 70) % 7) + 1)
            WeekGridDay(
                dayOfWeek = dow,
                slots = gridByDow[dow] ?: List(SLOTS_PER_DAY) { WeekGridSlot.EMPTY },
            )
        }

    logger.d(
        "LensWeekGridHelper.buildWeekGridData",
        "Week grid built",
        mapOf("days" to orderedDays.size, "slotsPerDay" to SLOTS_PER_DAY),
    )
    return WeekGridData(days = orderedDays)
}
