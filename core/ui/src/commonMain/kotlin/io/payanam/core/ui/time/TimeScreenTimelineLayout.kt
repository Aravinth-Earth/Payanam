//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.shared.logging.AppLogger

internal sealed class TimelineItem {
    abstract val id: String
    abstract val startMinutes: Int
    abstract val endMinutes: Int

    /**
     * A tracked time-entry block on the timeline.
     */
    data class Entry(
        val entry: TimeEntry,
        override val startMinutes: Int,
        override val endMinutes: Int,
    ) : TimelineItem() {
        override val id: String = "entry_${entry.id}"
    }

    /**
     * A planned (not-yet-completed) task block on the timeline.
     */
    data class Planned(
        val task: Task,
        val dueDate: java.time.LocalDateTime,
        override val startMinutes: Int,
        override val endMinutes: Int,
    ) : TimelineItem() {
        override val id: String = "planned_${task.id}"
    }

    /**
     * A completed/skipped task occurrence block on the timeline.
     */
    data class Occurrence(
        val occurrence: TaskOccurrence,
        val task: Task?,
        override val startMinutes: Int,
        override val endMinutes: Int,
    ) : TimelineItem() {
        override val id: String = "occurrence_${occurrence.id}"
    }
}

/**
 * One laid-out timeline item: its lane index and the total lane count of its
 * overlap group (drives column width).
 */
data class LaneLayoutEntry<T>(
    val item: T,
    val laneIndex: Int,
    val laneCount: Int,
)

/**
 * Assigns each block on the timeline a lane (column) so blocks that share time sit side by side
 * instead of on top of each other.
 *
 * [items] are swept in the order [getStart] gives them, so callers may pass them unsorted. An item is
 * treated as no longer occupying a lane once it has ended: an item ending exactly when the next begins
 * frees its lane, so back-to-back blocks share a column rather than being needlessly split. Each item
 * then takes the lowest lane no still-active item holds, which keeps a lone block in lane 0 no matter
 * what came before it.
 *
 * Lanes are grouped: a run of items that overlap each other, directly or through a chain, is one
 * group, and every item in a group reports the group's [LaneLayoutEntry.laneCount] so the group can be
 * drawn full width - a 2-wide group and a 4-wide group each split their own space, and a block alone is
 * always `laneCount` 1 and so always full width. The count is at least 1, so callers can divide by it
 * without guarding.
 *
 * Only the geometry is decided here; [LaneLayoutEntry.item] is handed back untouched, so callers that
 * need to identify a block do it by their own key rather than by position in the result. The result is
 * in the swept (start) order, not the input order, and has exactly one entry per input item - items are
 * never dropped, not even a zero-length one, which occupies a lane for the instant it is considered.
 *
 * @param getStart the item's start, in minutes from midnight, used both to order the sweep and to
 *   decide when a lane frees up.
 * @param getEnd the item's end in the same units. Only compared against later starts, so a reversed
 *   or zero-length end leaves the item occupying its lane until the next item's start rather than
 *   failing.
 * @return one [LaneLayoutEntry] per item, or an empty list when [items] is empty.
 */
fun <T> calculateLaneLayout(
    items: List<T>,
    getStart: (T) -> Int,
    getEnd: (T) -> Int,
): List<LaneLayoutEntry<T>> {
    if (items.isEmpty()) return emptyList()

    /**
     * An item currently overlapping others, with its assigned lane.
     */
    data class ActiveItem<T>(
        val item: T,
        val laneIndex: Int,
        val endMinutes: Int,
    )

    /**
     * An item in the not-yet-emitted overlap group.
     */
    data class GroupItem<T>(
        val item: T,
        val laneIndex: Int,
    )
    val logger = AppLogger.current
    val sorted = items.sortedBy { getStart(it) }
    val active = mutableListOf<ActiveItem<T>>()
    var groupItems = mutableListOf<GroupItem<T>>()
    var groupMaxLanes = 0
    val result = mutableListOf<LaneLayoutEntry<T>>()

    /**
     * Flushes any pending overlap group into results with its lane count.
     */
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
