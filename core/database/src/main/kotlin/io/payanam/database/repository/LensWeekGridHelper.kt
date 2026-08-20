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
    /** Logger. */
    logger: UnifiedLogger,
): WeekGridData {
    /** Completed entries. */
    val completedEntries = allEntries.filter { it.endedAt != null }
    logger.d(
        "LensWeekGridHelper.buildWeekGridData",
        "Building week grid data",
        /** Map of. */
        mapOf("totalEntries" to allEntries.size, "completedEntries" to completedEntries.size, "excludeEmptyDays" to excludeEmptyDays),
    )

    /** If. */
    if (completedEntries.isEmpty()) {
        logger.d("LensWeekGridHelper.buildWeekGridData", "No completed entries — returning empty grid")
        return WeekGridData(emptyList())
    }

    // Layer 0: Calendar spine — count dayOfWeek occurrences from firstTrackedDate to today
    // When excludeEmptyDays=true, only count dates that have at least one tracked entry.
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
    /** Dow occurrences. */
    val dowOccurrences = mutableMapOf<DayOfWeek, Long>()
    /** Cursor. */
    var cursor = firstTrackedDate
    /** While. */
    while (!cursor.isAfter(today)) {
        /** If. */
        if (!excludeEmptyDays || cursor in trackedDates) {
            /** Dow. */
            val dow = cursor.dayOfWeek
            dowOccurrences[dow] = (dowOccurrences[dow] ?: 0L) + 1L
        }
        cursor = cursor.plusDays(1)
    }
    logger.d(
        "LensWeekGridHelper.buildWeekGridData",
        "Day-of-week occurrence counts computed",
        /** Map of. */
        mapOf(
            "firstTrackedDate" to firstTrackedDate.toString(),
            "today" to today.toString(),
            "dowCounts" to dowOccurrences.map { "${it.key}=${it.value}" }.joinToString(","),
        ),
    )

    // Layer 1: Raw accumulation — Triple<DayOfWeek, slotIndex, dimensionId?> -> Long (minutes)
    // dimensionId key: null = unassigned tracked time, UNTRACKED_SENTINEL = synthetic untracked bucket
    /** Accumulator. */
    val accumulator = mutableMapOf<Triple<DayOfWeek, Int, String?>, Long>()

    completedEntries.forEach { entry ->
        /** Entry end. */
        val entryEnd = entry.endedAt!!
        /** Raw dim id. */
        val rawDimId = entry.dimensionId
        /** Normalized dim id. */
        val normalizedDimId: String? =
            /** If. */
            if (rawDimId == null) {
                /** Null. */
                null
            } else {
                /** Trimmed. */
                val trimmed = rawDimId.trim()
                /** If. */
                if (trimmed.isBlank() || trimmed.lowercase() == "unassigned") null else rawDimId
            }

        // Split entry across days it spans
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
                // Convert to minutes-of-day
                /** Entry start minute. */
                val entryStartMinute =
                    /** Duration. */
                    Duration
                        .between(dayStart, segStart)
                        .toMinutes()
                        .toInt()
                        .coerceIn(0, MINUTES_PER_DAY)
                /** Entry end minute. */
                val entryEndMinute =
                    /** Duration. */
                    Duration
                        .between(dayStart, segEnd)
                        .toMinutes()
                        .toInt()
                        .coerceIn(0, MINUTES_PER_DAY)
                /** Dow. */
                val dow = dayCursor.dayOfWeek

                // Distribute across 30-min slots
                /** For. */
                for (slotIndex in 0 until SLOTS_PER_DAY) {
                    /** Slot start minute. */
                    val slotStartMinute = slotIndex * SLOT_MINUTES
                    /** Slot end minute. */
                    val slotEndMinute = slotStartMinute + SLOT_MINUTES
                    /** Clipped start. */
                    val clippedStart = maxOf(entryStartMinute, slotStartMinute)
                    /** Clipped end. */
                    val clippedEnd = minOf(entryEndMinute, slotEndMinute)
                    /** Minutes. */
                    val minutes = maxOf(0, clippedEnd - clippedStart).toLong()
                    /** If. */
                    if (minutes > 0) {
                        /** Key. */
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
        /** Occurrences. */
        val occurrences = dowOccurrences[dow] ?: 0L
        /** Total possible. */
        val totalPossible = occurrences * SLOT_MINUTES
        /** For. */
        for (slotIndex in 0 until SLOTS_PER_DAY) {
            /** Tracked total. */
            val trackedTotal =
                accumulator.entries
                    .filter { (k, _) -> k.first == dow && k.second == slotIndex && k.third != UNTRACKED_SENTINEL }
                    .sumOf { (_, v) -> v }
            /** Untracked minutes. */
            val untrackedMinutes = maxOf(0L, totalPossible - trackedTotal)
            /** Untracked key. */
            val untrackedKey = Triple(dow, slotIndex, UNTRACKED_SENTINEL)
            accumulator[untrackedKey] = untrackedMinutes
        }
    }

    // Layer 3: Rank + proportion per (dayOfWeek, slotIndex)
    // Build grid: dayOfWeek -> slotIndex -> WeekGridSlot
    /** Grid by dow. */
    val gridByDow = mutableMapOf<DayOfWeek, MutableList<WeekGridSlot>>()
    DayOfWeek.values().forEach { dow ->
        /** Slots. */
        val slots = mutableListOf<WeekGridSlot>()
        /** For. */
        for (slotIndex in 0 until SLOTS_PER_DAY) {
            // Collect all (dimensionId, minutes) for this (dow, slotIndex)
            /** Candidates. */
            val candidates =
                accumulator.entries
                    .filter { (k, _) -> k.first == dow && k.second == slotIndex }
                    .map { (k, v) -> k.third to v }
                    .sortedByDescending { (_, minutes) -> minutes }
                    .take(3)

            /** Total top3. */
            val totalTop3 = candidates.sumOf { (_, m) -> m }
            /** If. */
            if (totalTop3 <= 0L) {
                slots.add(WeekGridSlot.EMPTY)
            } else {
                /** Rank1. */
                val rank1 =
                    candidates.getOrNull(0)?.let { (dimId, m) ->
                        /** Slot entry. */
                        SlotEntry(dimensionId = dimId, proportion = m.toFloat() / totalTop3)
                    }
                /** Rank2. */
                val rank2 =
                    candidates.getOrNull(1)?.let { (dimId, m) ->
                        /** Slot entry. */
                        SlotEntry(dimensionId = dimId, proportion = m.toFloat() / totalTop3)
                    }
                /** Rank3. */
                val rank3 =
                    candidates.getOrNull(2)?.let { (dimId, m) ->
                        /** Slot entry. */
                        SlotEntry(dimensionId = dimId, proportion = m.toFloat() / totalTop3)
                    }
                slots.add(WeekGridSlot(rank1 = rank1, rank2 = rank2, rank3 = rank3))
            }
        }
        gridByDow[dow] = slots
    }

    // Build 7 WeekGridDay objects: today's dayOfWeek first (index 0), then going back
    /** Today dow. */
    val todayDow = today.dayOfWeek
    /** Ordered days. */
    val orderedDays =
        (0 until 7).map { i ->
            // i=0 -> today's dow, i=1 -> yesterday's dow, etc.
            /** Dow. */
            val dow = DayOfWeek.of(((todayDow.value - 1 - i + 70) % 7) + 1)
            /** Week grid day. */
            WeekGridDay(
                dayOfWeek = dow,
                slots = gridByDow[dow] ?: List(SLOTS_PER_DAY) { WeekGridSlot.EMPTY },
            )
        }

    logger.d(
        "LensWeekGridHelper.buildWeekGridData",
        "Week grid built",
        /** Map of. */
        mapOf("days" to orderedDays.size, "slotsPerDay" to SLOTS_PER_DAY),
    )
    return WeekGridData(days = orderedDays)
}
