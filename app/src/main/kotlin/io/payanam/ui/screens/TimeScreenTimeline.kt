//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.ui.components.DimensionIconCascadeLayer
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.model.DimensionIconOption
import io.payanam.ui.theme.rememberInsightsVisualTokens
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.colorFor
import io.payanam.ui.viewmodel.colorForDimensionId
import io.payanam.ui.viewmodel.iconOptionForDimensionId
import io.payanam.ui.viewmodel.labelForDimensionId
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
private const val TOTAL_HOURS = 24
private const val PLANNED_OVERLAY_MINUTES = 20
private const val MIN_HOUR_HEIGHT_DP = MIN_TIME_HOUR_HEIGHT_DP
private const val MAX_HOUR_HEIGHT_DP = MAX_TIME_HOUR_HEIGHT_DP

private fun gridStepMinutesForHourHeight(hourHeightDp: Float): Int = nearestTimeScalePreset(hourHeightDp).slotMinutes

internal fun formatCompactFocusValue(focusValue: Double?): String? {
    /** Normalized. */
    val normalized = focusValue?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: return null
    return String.format(Locale.US, "F: %.1f", normalized)
}

internal fun buildTimeBlockCompactLabel(
    /** Dimension label. */
    dimensionLabel: String,
    taskLabel: String?,
    /** Start label. */
    startLabel: String,
    /** End label. */
    endLabel: String,
    /** Duration label. */
    durationLabel: String,
    focusValueLabel: String?,
): String = buildList {
    /** Add. */
    add(dimensionLabel)
    taskLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
    /** Add. */
    add("$startLabel - $endLabel")
    /** Add. */
    add(durationLabel)
    focusValueLabel?.let { add(it) }
}.joinToString(" · ")

@Composable
internal fun TimeCalendarView(
    /** Selected date. */
    selectedDate: LocalDate,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    plannedTasks: List<Task>,
    pastOccurrences: List<TaskOccurrence>,
    taskLookup: Map<String, Task>,
    /** Hour height dp. */
    hourHeightDp: Float,
    /** Current time. */
    currentTime: LocalDateTime,
    scrollState: androidx.compose.foundation.ScrollState,
    /** Use24hour. */
    use24Hour: Boolean,
    highlightedDimensionId: String?,
    /** Preferences. */
    preferences: AppPreferencesState,
    onEntryClick: (TimeEntry) -> Unit,
    onPlannedTaskClick: (Task) -> Unit,
    onPastOccurrenceClick: (TaskOccurrence, Task?) -> Unit,
    onGapClick: (LocalTime, LocalTime) -> Unit = { _, _ -> },
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Tokens. */
    val tokens = rememberInsightsVisualTokens()
    /** Hour height. */
    val hourHeight = hourHeightDp.coerceIn(MIN_HOUR_HEIGHT_DP, MAX_HOUR_HEIGHT_DP).dp
    /** Minute height. */
    val minuteHeight = hourHeight / 60f
    /** Grid step minutes. */
    val gridStepMinutes = gridStepMinutesForHourHeight(hourHeightDp)
    /** Day start. */
    val dayStart = selectedDate.atStartOfDay()
    /** Gaps. */
    val gaps = remember(entries, activeEntry, selectedDate, currentTime) {
        /** Compute time gaps. */
        computeTimeGaps(selectedDate, entries, activeEntry, currentTime)
    }
    /** Overlaps. */
    val overlaps = remember(entries, activeEntry, selectedDate, currentTime) {
        /** Compute time overlaps. */
        computeTimeOverlaps(selectedDate, entries, activeEntry, currentTime)
    }
    /** Slots per day. */
    val slotsPerDay = (TOTAL_HOURS * 60) / gridStepMinutes
    /** Slot height. */
    val slotHeight = minuteHeight * gridStepMinutes
    /** Row. */
    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        /** Column. */
        Column(modifier = Modifier.width(56.dp)) {
            /** Repeat. */
            repeat(slotsPerDay) { slotIndex ->
                /** Slot minutes. */
                val slotMinutes = slotIndex * gridStepMinutes
                /** Box. */
                Box(
                    modifier = Modifier
                        .height(slotHeight)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    /** Text. */
                    Text(
                        text = formatSlotLabel(slotMinutes, use24Hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .offset(y = (-6).dp),
                    )
                }
            }
        }
        /** Box. */
        Box(
            modifier = Modifier
                .weight(1f)
                .height(hourHeight * TOTAL_HOURS),
        ) {
            /** Column. */
            Column(modifier = Modifier.fillMaxSize()) {
                /** Repeat. */
                repeat(slotsPerDay) {
                    /** Box. */
                    Box(
                        modifier = Modifier
                            .height(slotHeight)
                            .fillMaxWidth()
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(0.dp),
                            ),
                    )
                }
            }
            gaps.forEach { gap ->
                /** Gap block. */
                GapBlock(
                    gap = gap,
                    minuteHeight = minuteHeight,
                    onClick = {
                        /** Start time. */
                        val startTime = LocalTime.of(gap.startMinutes / 60, gap.startMinutes % 60)
                        /** End time. */
                        val endTime = LocalTime.of(gap.endMinutes / 60, gap.endMinutes % 60)
                        /** On gap click. */
                        onGapClick(startTime, endTime)
                    },
                )
            }
            /** If. */
            if (!FeatureFlags.minimalModeEnabled) {
                overlaps.forEach { overlap ->
                    /** Overlap marker. */
                    OverlapMarker(
                        overlap = overlap,
                        minuteHeight = minuteHeight,
                        color = tokens.qualityOverlap,
                    )
                }
            }
            /** Past occurrences for day. */
            val pastOccurrencesForDay = pastOccurrences
            /** Planned for day. */
            val plannedForDay = resolvePlannedTasksForTimeline(
                selectedDate = selectedDate,
                plannedTasks = plannedTasks,
                entries = entries,
                activeEntry = activeEntry,
                pastOccurrences = pastOccurrencesForDay,
            )
            /** Timeline layouts. */
            val timelineLayouts = remember(
                /** Entries. */
                entries,
                /** Active entry. */
                activeEntry,
                /** Planned for day. */
                plannedForDay,
                /** Past occurrences for day. */
                pastOccurrencesForDay,
                /** Task lookup. */
                taskLookup,
                /** Selected date. */
                selectedDate,
                /** Current time. */
                currentTime,
            ) {
                /** Timeline items. */
                val timelineItems = mutableListOf<TimelineItem>()
                /** Timeline start. */
                val timelineStart = selectedDate.atStartOfDay()
                /** All entries. */
                val allEntries = entries.toMutableList()
                /** If. */
                if (activeEntry != null && allEntries.none { it.id == activeEntry.id }) {
                    allEntries.add(activeEntry)
                }
                allEntries.forEach { entry ->
                    /** Start minutes. */
                    val startMinutes = Duration.between(timelineStart, entry.startedAt).toMinutes()
                        .toInt().coerceIn(0, 1440)
                    /** End minutes. */
                    val endMinutes = entry.endedAt?.let {
                        Duration.between(timelineStart, it).toMinutes().toInt().coerceIn(0, 1440)
                    } ?: Duration.between(timelineStart, currentTime).toMinutes().toInt().coerceIn(0, 1440)
                    timelineItems.add(TimelineItem.Entry(entry, startMinutes, endMinutes))
                }
                plannedForDay.forEach { task ->
                    task.dueDate?.let { due ->
                        /** Task duration. */
                        val taskDuration = if (task.durationMinutes > 0) {
                            task.durationMinutes
                        } else {
                            /** Planned overlay minutes. */
                            PLANNED_OVERLAY_MINUTES
                        }
                        /** Due minutes. */
                        val dueMinutes = Duration.between(timelineStart, due).toMinutes().toInt()
                            .coerceIn(0, 1440)
                        /** Start minutes. */
                        val startMinutes = dueMinutes
                        /** End minutes. */
                        val endMinutes = (startMinutes + taskDuration).coerceAtMost(1440)
                        timelineItems.add(TimelineItem.Planned(task, due, startMinutes, endMinutes))
                    }
                }
                pastOccurrencesForDay.forEachIndexed { index, occ ->
                    /** Task. */
                    val task = taskLookup[occ.taskId]
                    /** Window. */
                    val window = resolveOccurrenceWindowMinutes(
                        selectedDate = selectedDate,
                        occurrence = occ,
                        task = task,
                        fallbackIndex = index,
                        fallbackTotal = pastOccurrencesForDay.size,
                        defaultDurationMinutes = PLANNED_OVERLAY_MINUTES,
                    )
                    timelineItems.add(
                        TimelineItem.Occurrence(
                            occurrence = occ,
                            task = task,
                            startMinutes = window.startMinutes,
                            endMinutes = window.endMinutes,
                        ),
                    )
                }
                /** Calculate lane layout. */
                calculateLaneLayout(
                    items = timelineItems,
                    getStart = { it.startMinutes },
                    getEnd = { it.endMinutes },
                ).associateBy { it.item.id }
            }
            plannedForDay.forEach { task ->
                task.dueDate?.let { due ->
                    /** Layout. */
                    val layout = timelineLayouts["planned_${task.id}"]
                    /** Color. */
                    val color = preferences.colorForDimensionId(task.dimensionId)
                        ?: MaterialTheme.colorScheme.primary
                    /** Planned task block. */
                    PlannedTaskBlock(
                        task = task,
                        dueDate = due,
                        minuteHeight = minuteHeight,
                        color = toMutedPastelColor(
                            baseColor = color,
                            surfaceColor = MaterialTheme.colorScheme.surface,
                        ),
                        laneIndex = layout?.laneIndex ?: 0,
                        laneCount = layout?.laneCount ?: 1,
                        onClick = { onPlannedTaskClick(task) },
                    )
                }
            }
            entries.forEach { entry ->
                /** Layout. */
                val layout = timelineLayouts["entry_${entry.id}"]
                /** Item. */
                val item = layout?.item as? TimelineItem.Entry
                /** Start minutes. */
                val startMinutes = item?.startMinutes ?: 0
                /** End minutes. */
                val endMinutes = item?.endMinutes ?: 0
                /** Display start. */
                val displayStart = dayStart.plusMinutes(startMinutes.toLong())
                /** Display end. */
                val displayEnd = dayStart.plusMinutes(endMinutes.toLong())
                /** Task title. */
                val taskTitle = entry.taskId?.let { taskLookup[it]?.title }
                /** Dimension id. */
                val dimensionId = entry.dimensionId
                    ?: entry.taskId?.let { taskLookup[it]?.dimensionId }
                /** Dimension label. */
                val dimensionLabel = preferences.labelForDimensionId(dimensionId)
                    ?: ""
                /** Focus value label. */
                val focusValueLabel = formatCompactFocusValue(entry.focusRating)
                /** Dimension icon option. */
                val dimensionIconOption = preferences.iconOptionForDimensionId(dimensionId)
                    ?: DimensionIconCatalog.resolve(null, dimensionId)
                /** Dimension color. */
                val dimensionColor = toMutedPastelColor(
                    baseColor = preferences.colorForDimensionId(dimensionId)
                        ?: MaterialTheme.colorScheme.primary,
                    surfaceColor = MaterialTheme.colorScheme.surface,
                )
                /** Time entry block. */
                TimeEntryBlock(
                    entry = entry,
                    displayStart = displayStart,
                    displayEnd = displayEnd,
                    startMinutes = startMinutes,
                    endMinutes = endMinutes,
                    taskTitle = taskTitle,
                    dimensionLabel = dimensionLabel,
                    focusValueLabel = focusValueLabel,
                    dimensionIconOption = dimensionIconOption,
                    isActive = entry.id == activeEntry?.id,
                    minuteHeight = minuteHeight,
                    color = dimensionColor,
                    isDimmed = highlightedDimensionId != null && dimensionId != highlightedDimensionId,
                    use24Hour = use24Hour,
                    laneIndex = layout?.laneIndex ?: 0,
                    laneCount = layout?.laneCount ?: 1,
                    onClick = { onEntryClick(entry) },
                )
            }
            pastOccurrencesForDay.forEach { occ ->
                /** Layout. */
                val layout = timelineLayouts["occurrence_${occ.id}"]
                /** Item. */
                val item = layout?.item as? TimelineItem.Occurrence
                /** Task. */
                val task = taskLookup[occ.taskId]
                /** Color. */
                val color = toMutedPastelColor(
                    baseColor = task?.let { preferences.colorForDimensionId(it.dimensionId) ?: preferences.colorFor(it.lifeIntentionCategory) }
                        ?: MaterialTheme.colorScheme.outline,
                    surfaceColor = MaterialTheme.colorScheme.surface,
                )
                /** Dimension icon option. */
                val dimensionIconOption = preferences.iconOptionForDimensionId(task?.dimensionId)
                    ?: DimensionIconCatalog.resolve(null, task?.dimensionId)
                /** Past occurrence block. */
                PastOccurrenceBlock(
                    occurrence = occ,
                    task = task,
                    minuteHeight = minuteHeight,
                    startMinutes = item?.startMinutes ?: 0,
                    endMinutes = item?.endMinutes ?: PLANNED_OVERLAY_MINUTES,
                    use24Hour = use24Hour,
                    color = color,
                    dimensionIconOption = dimensionIconOption,
                    laneIndex = layout?.laneIndex ?: 0,
                    laneCount = layout?.laneCount ?: 1,
                    onClick = { onPastOccurrenceClick(occ, task) },
                )
            }
            /** If. */
            if (selectedDate == currentTime.toLocalDate()) {
                /** Current minutes. */
                val currentMinutes = currentTime.hour * 60 + currentTime.minute
                /** Top offset. */
                val topOffset = minuteHeight * currentMinutes
                /** Box. */
                Box(
                    modifier = Modifier
                        .offset(y = topOffset)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.Red),
                )
                /** Box. */
                Box(
                    modifier = Modifier
                        .offset(y = topOffset - 4.dp)
                        .size(8.dp)
                        .background(Color.Red, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun TimeEntryBlock(
    /** Entry. */
    entry: TimeEntry,
    /** Display start. */
    displayStart: LocalDateTime,
    /** Display end. */
    displayEnd: LocalDateTime,
    /** Start minutes. */
    startMinutes: Int,
    /** End minutes. */
    endMinutes: Int,
    taskTitle: String?,
    /** Dimension label. */
    dimensionLabel: String,
    focusValueLabel: String?,
    /** Dimension icon option. */
    dimensionIconOption: DimensionIconOption,
    /** Is active. */
    isActive: Boolean,
    minuteHeight: androidx.compose.ui.unit.Dp,
    /** Color. */
    color: Color,
    isDimmed: Boolean = false,
    /** Use24hour. */
    use24Hour: Boolean,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    onClick: () -> Unit,
) {
    /** Top offset. */
    val topOffset = minuteHeight * startMinutes
    /** Height. */
    val height = minuteHeight * (endMinutes - startMinutes)
    /** Time formatter. */
    val timeFormatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a")
    /** Duration minutes. */
    val durationMinutes = (endMinutes - startMinutes).coerceAtLeast(0)
    /** Duration label. */
    val durationLabel = if (durationMinutes >= 60) {
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration_hours_minutes_compact, durationMinutes / 60, durationMinutes % 60)
    } else {
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration_minutes_compact, durationMinutes)
    }
    /** Horizontal padding. */
    val horizontalPadding = 2.dp
    /** Box with constraints. */
    BoxWithConstraints(
        modifier = Modifier
            .offset(y = topOffset)
            .fillMaxWidth(),
    ) {
        /** Available width. */
        val availableWidth = maxWidth - (horizontalPadding * 2)
        /** Lane width. */
        val laneWidth = availableWidth / laneCount
        /** Left offset. */
        val leftOffset = horizontalPadding + (laneWidth * laneIndex)
        /** Show compact row. */
        val showCompactRow = height >= 24.dp && laneWidth >= 92.dp
        /** Start label. */
        val startLabel = displayStart.format(timeFormatter)
        /** End label. */
        val endLabel = if (isActive) {
            androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_now)
        } else {
            displayEnd.format(timeFormatter)
        }
        /** Compact label. */
        val compactLabel = buildTimeBlockCompactLabel(
            dimensionLabel = dimensionLabel,
            taskLabel = taskTitle,
            startLabel = startLabel,
            endLabel = endLabel,
            durationLabel = durationLabel,
            focusValueLabel = focusValueLabel,
        )
        /** Fallback metadata. */
        val fallbackMetadata = buildList {
            /** Add. */
            add(durationLabel)
            focusValueLabel?.let { add(it) }
        }.joinToString(" · ")
        /** Box. */
        Box(
            modifier = Modifier
                .offset(x = leftOffset)
                .width(laneWidth - horizontalPadding)
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = if (isDimmed) 0.25f else 0.85f))
                .border(
                    width = if (isActive) 2.dp else 0.5.dp,
                    color = if (isActive) MaterialTheme.colorScheme.primary else color.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable { onClick() }
                .padding(4.dp),
        ) {
            /** Dimension icon cascade layer. */
            DimensionIconCascadeLayer(
                iconOption = dimensionIconOption,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                seedKey = "${entry.id}_${dimensionLabel}_${taskTitle ?: "entry"}",
                iconCount = if (height >= 96.dp && laneWidth >= 92.dp) 14 else 10,
                minIconSize = 8.dp,
                maxIconSize = 16.dp,
                alphaRange = if (isDimmed) 0.08f..0.16f else 0.14f..0.28f,
                animated = true,
            )
            /** Content color. */
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            /** If. */
            if (showCompactRow) {
                /** Row. */
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    /** Icon. */
                    Icon(
                        imageVector = dimensionIconOption.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = contentColor.copy(alpha = 0.92f),
                    )
                    /** Text. */
                    Text(
                        text = compactLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Column {
                    /** Row. */
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        /** Icon. */
                        Icon(
                            imageVector = dimensionIconOption.imageVector,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = contentColor.copy(alpha = 0.92f),
                        )
                        /** Text. */
                        Text(
                            text = taskTitle ?: dimensionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    /** Text. */
                    Text(
                        text = "$startLabel - $endLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                    /** Text. */
                    Text(
                        text = fallbackMetadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.78f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal fun resolvePlannedTasksForTimeline(
    /** Selected date. */
    selectedDate: LocalDate,
    plannedTasks: List<Task>,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    pastOccurrences: List<TaskOccurrence>,
): List<Task> {
    /** Actualized task ids. */
    val actualizedTaskIds = buildSet {
        entries.mapNotNullTo(this) { it.taskId }
        activeEntry?.taskId?.let { add(it) }
        pastOccurrences.mapTo(this) { it.taskId }
    }
    return plannedTasks.filter { task ->
        task.dueDate?.toLocalDate() == selectedDate && task.id !in actualizedTaskIds
    }
}

private fun formatSlotLabel(totalMinutes: Int, use24Hour: Boolean): String {
    /** Safe minutes. */
    val safeMinutes = totalMinutes.coerceIn(0, TOTAL_HOURS * 60)
    /** Hour. */
    val hour = safeMinutes / 60
    /** Minute. */
    val minute = safeMinutes % 60
    return if (use24Hour) {
        String.format(Locale.US, "%02d:%02d", hour, minute)
    } else {
        LocalTime.of(hour % 24, minute).format(DateTimeFormatter.ofPattern("h:mm a"))
    }
}

@Composable
private fun GapBlock(
    /** Gap. */
    gap: TimeGap,
    minuteHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit = {},
) {
    /** Height. */
    val height = (minuteHeight * gap.minutes).coerceAtLeast(8.dp)
    /** Top offset. */
    val topOffset = minuteHeight * gap.startMinutes
    /** Gap color. */
    val gapColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    /** Border color. */
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    /** Box. */
    Box(
        modifier = Modifier
            .offset(y = topOffset)
            .padding(horizontal = 6.dp)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(gapColor)
            .border(0.5.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() },
    ) {
        /** Column. */
        Column(modifier = Modifier.padding(6.dp)) {
            /** If. */
            if (height > 24.dp) {
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_untracked),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            /** If. */
            if (height > 40.dp) {
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        id = io.payanam.R.string.loc_untracked_tap_to_assign,
                        /** Format duration. */
                        formatDuration(gap.minutes.toLong()),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun OverlapMarker(
    /** Overlap. */
    overlap: TimeOverlap,
    minuteHeight: androidx.compose.ui.unit.Dp,
    /** Color. */
    color: Color,
) {
    /** Height. */
    val height = (minuteHeight * overlap.minutes).coerceAtLeast(4.dp)
    /** Top offset. */
    val topOffset = minuteHeight * overlap.startMinutes
    /** Box. */
    Box(
        modifier = Modifier
            .offset(y = topOffset)
            .padding(start = 2.dp)
            .width(4.dp)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = 0.85f)),
    )
}

@Composable
private fun PlannedTaskBlock(
    /** Task. */
    task: Task,
    /** Due date. */
    dueDate: LocalDateTime,
    minuteHeight: androidx.compose.ui.unit.Dp,
    /** Color. */
    color: Color,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    onClick: () -> Unit = {},
) {
    /** Task duration minutes. */
    val taskDurationMinutes = if (task.durationMinutes > 0) task.durationMinutes else PLANNED_OVERLAY_MINUTES
    /** Due minutes. */
    val dueMinutes = dueDate.hour * 60 + dueDate.minute
    /** Start minutes. */
    val startMinutes = dueMinutes
    /** Top offset. */
    val topOffset = minuteHeight * startMinutes
    /** Height. */
    val height = minuteHeight * taskDurationMinutes
    /** Focus value label. */
    val focusValueLabel = formatCompactFocusValue(task.focusRequired)
    /** Time formatter. */
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    /** Start label. */
    val startLabel = dueDate.toLocalTime().format(timeFormatter)
    /** End label. */
    val endLabel = dueDate.toLocalTime().plusMinutes(taskDurationMinutes.toLong()).format(timeFormatter)
    /** Duration label. */
    val durationLabel = if (taskDurationMinutes >= 60) {
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration_hours_minutes_compact, taskDurationMinutes / 60, taskDurationMinutes % 60)
    } else {
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration_minutes_compact, taskDurationMinutes)
    }
    /** Compact label. */
    val compactLabel = buildTimeBlockCompactLabel(
        dimensionLabel = task.lifeIntentionCategory,
        taskLabel = task.title,
        startLabel = startLabel,
        endLabel = endLabel,
        durationLabel = durationLabel,
        focusValueLabel = focusValueLabel,
    )
    /** Pattern brush. */
    val patternBrush = Brush.linearGradient(
        colors = listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.1f)),
        start = Offset.Zero,
        end = Offset(16f, 16f),
        tileMode = TileMode.Repeated,
    )
    /** Horizontal padding. */
    val horizontalPadding = 2.dp
    /** Border style. */
    val borderStyle = if (task.recurrenceEnabled) {
        Modifier.border(
            width = 1.5.dp,
            color = color.copy(alpha = 0.7f),
            shape = RoundedCornerShape(4.dp),
        )
    } else {
        Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
    }
    /** Box with constraints. */
    BoxWithConstraints(
        modifier = Modifier
            .offset(y = topOffset)
            .fillMaxWidth(),
    ) {
        /** Available width. */
        val availableWidth = maxWidth - (horizontalPadding * 2)
        /** Lane width. */
        val laneWidth = availableWidth / laneCount
        /** Left offset. */
        val leftOffset = horizontalPadding + (laneWidth * laneIndex)
        /** Show compact row. */
        val showCompactRow = height >= 24.dp && laneWidth >= 92.dp
        /** Box. */
        Box(
            modifier = Modifier
                .offset(x = leftOffset)
                .width(laneWidth - horizontalPadding)
                .height(height)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onClick() }
                .background(patternBrush)
                .then(borderStyle)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            /** Row. */
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                /** If. */
                if (task.recurrenceEnabled) {
                    /** Icon. */
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_recurring),
                        modifier = Modifier.size(12.dp),
                        tint = color.copy(alpha = 0.8f),
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(3.dp))
                }
                /** Text. */
                Text(
                    text = if (showCompactRow) compactLabel else task.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
