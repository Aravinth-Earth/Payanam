//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry

internal sealed class TimelineItem {
    abstract val id: String
    abstract val startMinutes: Int
    abstract val endMinutes: Int

    /**
     * Entry.
     */
    data class Entry(
        /** Entry. */
        val entry: TimeEntry,
        override val startMinutes: Int,
        override val endMinutes: Int,
    ) : TimelineItem() {
        override val id: String = "entry_${entry.id}"
    }

    /**
     * Planned.
     */
    data class Planned(
        /** Task. */
        val task: Task,
        /** Due date. */
        val dueDate: java.time.LocalDateTime,
        override val startMinutes: Int,
        override val endMinutes: Int,
    ) : TimelineItem() {
        override val id: String = "planned_${task.id}"
    }

    /**
     * Occurrence.
     */
    data class Occurrence(
        /** Occurrence. */
        val occurrence: TaskOccurrence,
        /** Task. */
        val task: Task?,
        override val startMinutes: Int,
        override val endMinutes: Int,
    ) : TimelineItem() {
        override val id: String = "occurrence_${occurrence.id}"
    }
}

internal data class LaneLayoutEntry<T>(
    /** Item. */
    val item: T,
    /** Lane index. */
    val laneIndex: Int,
    /** Lane count. */
    val laneCount: Int,
)

internal fun <T> calculateLaneLayout(
    items: List<T>,
    getStart: (T) -> Int,
    getEnd: (T) -> Int,
): List<LaneLayoutEntry<T>> {
    /** If. */
    if (items.isEmpty()) return emptyList()
    /**
     * ActiveItem.
     */
    data class ActiveItem<T>(val item: T, val laneIndex: Int, val endMinutes: Int)
    /**
     * GroupItem.
     */
    data class GroupItem<T>(val item: T, val laneIndex: Int)

    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Sorted. */
    val sorted = items.sortedBy { getStart(it) }
    /** Active. */
    val active = mutableListOf<ActiveItem<T>>()
    /** Group items. */
    var groupItems = mutableListOf<GroupItem<T>>()
    /** Group max lanes. */
    var groupMaxLanes = 0
    /** Result. */
    val result = mutableListOf<LaneLayoutEntry<T>>()

    /**
     * Finalize group.
     */
    fun finalizeGroup() {
        /** If. */
        if (groupItems.isEmpty()) return
        groupItems.forEach { entry ->
            result.add(LaneLayoutEntry(entry.item, entry.laneIndex, groupMaxLanes.coerceAtLeast(1)))
        }
        groupItems = mutableListOf()
        groupMaxLanes = 0
    }

    /** For. */
    for (item in sorted) {
        /** Start. */
        val start = getStart(item)
        /** End. */
        val end = getEnd(item)
        /** Iterator. */
        val iterator = active.iterator()
        /** While. */
        while (iterator.hasNext()) {
            /** If. */
            if (iterator.next().endMinutes <= start) {
                iterator.remove()
            }
        }
        /** If. */
        if (active.isEmpty()) {
            /** Finalize group. */
            finalizeGroup()
        }
        /** Used lanes. */
        val usedLanes = active.map { it.laneIndex }.toSet()
        /** Lane index. */
        var laneIndex = 0
        /** While. */
        while (usedLanes.contains(laneIndex)) {
            laneIndex++
        }
        active.add(ActiveItem(item, laneIndex, end))
        groupItems.add(GroupItem(item, laneIndex))
        groupMaxLanes = maxOf(groupMaxLanes, active.size, laneIndex + 1)
    }
    /** Finalize group. */
    finalizeGroup()
    logger.d(
        "TimeScreenTimelineLayout.calculateLaneLayout",
        "Resolved lane layout",
        /** Map of. */
        mapOf("itemCount" to items.size.toString(), "resultCount" to result.size.toString()),
    )
    return result
}
