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

    data class Entry(
        val entry: TimeEntry,
        override val startMinutes: Int,
        override val endMinutes: Int,
    ) : TimelineItem() {
        override val id: String = "entry_${entry.id}"
    }

    data class Planned(
        val task: Task,
        val dueDate: java.time.LocalDateTime,
        override val startMinutes: Int,
        override val endMinutes: Int,
    ) : TimelineItem() {
        override val id: String = "planned_${task.id}"
    }

    data class Occurrence(
        val occurrence: TaskOccurrence,
        val task: Task?,
        override val startMinutes: Int,
        override val endMinutes: Int,
    ) : TimelineItem() {
        override val id: String = "occurrence_${occurrence.id}"
    }
}

internal data class LaneLayoutEntry<T>(
    val item: T,
    val laneIndex: Int,
    val laneCount: Int,
)

internal fun <T> calculateLaneLayout(
    items: List<T>,
    getStart: (T) -> Int,
    getEnd: (T) -> Int,
): List<LaneLayoutEntry<T>> {
    if (items.isEmpty()) return emptyList()
    data class ActiveItem<T>(val item: T, val laneIndex: Int, val endMinutes: Int)
    data class GroupItem<T>(val item: T, val laneIndex: Int)

    val logger = UnifiedLogger.getInstance()
    val sorted = items.sortedBy { getStart(it) }
    val active = mutableListOf<ActiveItem<T>>()
    var groupItems = mutableListOf<GroupItem<T>>()
    var groupMaxLanes = 0
    val result = mutableListOf<LaneLayoutEntry<T>>()

    fun finalizeGroup() {
        if (groupItems.isEmpty()) return
        groupItems.forEach { entry ->
            result.add(LaneLayoutEntry(entry.item, entry.laneIndex, groupMaxLanes.coerceAtLeast(1)))
        }
        groupItems = mutableListOf()
        groupMaxLanes = 0
    }

    for (item in sorted) {
        val start = getStart(item)
        val end = getEnd(item)
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().endMinutes <= start) {
                iterator.remove()
            }
        }
        if (active.isEmpty()) {
            finalizeGroup()
        }
        val usedLanes = active.map { it.laneIndex }.toSet()
        var laneIndex = 0
        while (usedLanes.contains(laneIndex)) {
            laneIndex++
        }
        active.add(ActiveItem(item, laneIndex, end))
        groupItems.add(GroupItem(item, laneIndex))
        groupMaxLanes = maxOf(groupMaxLanes, active.size, laneIndex + 1)
    }
    finalizeGroup()
    logger.d(
        "TimeScreenTimelineLayout.calculateLaneLayout",
        "Resolved lane layout",
        mapOf("itemCount" to items.size.toString(), "resultCount" to result.size.toString()),
    )
    return result
}
