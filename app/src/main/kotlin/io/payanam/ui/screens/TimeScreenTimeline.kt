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
    val normalized = focusValue?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: return null
    return String.format(Locale.US, "F: %.1f", normalized)
}

internal fun buildTimeBlockCompactLabel(
    dimensionLabel: String,
    taskLabel: String?,
    startLabel: String,
    endLabel: String,
    durationLabel: String,
    focusValueLabel: String?,
): String = buildList {
    add(dimensionLabel)
    taskLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
    add("$startLabel - $endLabel")
    add(durationLabel)
    focusValueLabel?.let { add(it) }
}.joinToString(" · ")

@Composable
internal fun TimeCalendarView(
    selectedDate: LocalDate,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    plannedTasks: List<Task>,
    pastOccurrences: List<TaskOccurrence>,
    taskLookup: Map<String, Task>,
    hourHeightDp: Float,
    currentTime: LocalDateTime,
    scrollState: androidx.compose.foundation.ScrollState,
    use24Hour: Boolean,
    highlightedDimensionId: String?,
    preferences: AppPreferencesState,
    onEntryClick: (TimeEntry) -> Unit,
    onPlannedTaskClick: (Task) -> Unit,
    onPastOccurrenceClick: (TaskOccurrence, Task?) -> Unit,
    onGapClick: (LocalTime, LocalTime) -> Unit = { _, _ -> },
) {
    val logger = remember { UnifiedLogger.getInstance() }
    val tokens = rememberInsightsVisualTokens()
    val hourHeight = hourHeightDp.coerceIn(MIN_HOUR_HEIGHT_DP, MAX_HOUR_HEIGHT_DP).dp
    val minuteHeight = hourHeight / 60f
    val gridStepMinutes = gridStepMinutesForHourHeight(hourHeightDp)
    val dayStart = selectedDate.atStartOfDay()
    val gaps = remember(entries, activeEntry, selectedDate, currentTime) {
        computeTimeGaps(selectedDate, entries, activeEntry, currentTime)
    }
    val overlaps = remember(entries, activeEntry, selectedDate, currentTime) {
        computeTimeOverlaps(selectedDate, entries, activeEntry, currentTime)
    }
    val slotsPerDay = (TOTAL_HOURS * 60) / gridStepMinutes
    val slotHeight = minuteHeight * gridStepMinutes
    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Column(modifier = Modifier.width(56.dp)) {
            repeat(slotsPerDay) { slotIndex ->
                val slotMinutes = slotIndex * gridStepMinutes
                Box(
                    modifier = Modifier
                        .height(slotHeight)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopEnd,
                ) {
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
        Box(
            modifier = Modifier
                .weight(1f)
                .height(hourHeight * TOTAL_HOURS),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                repeat(slotsPerDay) {
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
                GapBlock(
                    gap = gap,
                    minuteHeight = minuteHeight,
                    onClick = {
                        val startTime = LocalTime.of(gap.startMinutes / 60, gap.startMinutes % 60)
                        val endTime = LocalTime.of(gap.endMinutes / 60, gap.endMinutes % 60)
                        onGapClick(startTime, endTime)
                    },
                )
            }
            if (!FeatureFlags.minimalModeEnabled) {
                overlaps.forEach { overlap ->
                    OverlapMarker(
                        overlap = overlap,
                        minuteHeight = minuteHeight,
                        color = tokens.qualityOverlap,
                    )
                }
            }
            val pastOccurrencesForDay = pastOccurrences
            val plannedForDay = resolvePlannedTasksForTimeline(
                selectedDate = selectedDate,
                plannedTasks = plannedTasks,
                entries = entries,
                activeEntry = activeEntry,
                pastOccurrences = pastOccurrencesForDay,
            )
            val timelineLayouts = remember(
                entries,
                activeEntry,
                plannedForDay,
                pastOccurrencesForDay,
                taskLookup,
                selectedDate,
                currentTime,
            ) {
                val timelineItems = mutableListOf<TimelineItem>()
                val timelineStart = selectedDate.atStartOfDay()
                val allEntries = entries.toMutableList()
                if (activeEntry != null && allEntries.none { it.id == activeEntry.id }) {
                    allEntries.add(activeEntry)
                }
                allEntries.forEach { entry ->
                    val startMinutes = Duration.between(timelineStart, entry.startedAt).toMinutes()
                        .toInt().coerceIn(0, 1440)
                    val endMinutes = entry.endedAt?.let {
                        Duration.between(timelineStart, it).toMinutes().toInt().coerceIn(0, 1440)
                    } ?: Duration.between(timelineStart, currentTime).toMinutes().toInt().coerceIn(0, 1440)
                    timelineItems.add(TimelineItem.Entry(entry, startMinutes, endMinutes))
                }
                plannedForDay.forEach { task ->
                    task.dueDate?.let { due ->
                        val taskDuration = if (task.durationMinutes > 0) {
                            task.durationMinutes
                        } else {
                            PLANNED_OVERLAY_MINUTES
                        }
                        val dueMinutes = Duration.between(timelineStart, due).toMinutes().toInt()
                            .coerceIn(0, 1440)
                        val startMinutes = dueMinutes
                        val endMinutes = (startMinutes + taskDuration).coerceAtMost(1440)
                        timelineItems.add(TimelineItem.Planned(task, due, startMinutes, endMinutes))
                    }
                }
                pastOccurrencesForDay.forEachIndexed { index, occ ->
                    val task = taskLookup[occ.taskId]
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
                calculateLaneLayout(
                    items = timelineItems,
                    getStart = { it.startMinutes },
                    getEnd = { it.endMinutes },
                ).associateBy { it.item.id }
            }
            plannedForDay.forEach { task ->
                task.dueDate?.let { due ->
                    val layout = timelineLayouts["planned_${task.id}"]
                    val color = preferences.colorForDimensionId(task.dimensionId)
                        ?: MaterialTheme.colorScheme.primary
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
                val layout = timelineLayouts["entry_${entry.id}"]
                val item = layout?.item as? TimelineItem.Entry
                val startMinutes = item?.startMinutes ?: 0
                val endMinutes = item?.endMinutes ?: 0
                val displayStart = dayStart.plusMinutes(startMinutes.toLong())
                val displayEnd = dayStart.plusMinutes(endMinutes.toLong())
                val taskTitle = entry.taskId?.let { taskLookup[it]?.title }
                val dimensionId = entry.dimensionId
                    ?: entry.taskId?.let { taskLookup[it]?.dimensionId }
                val dimensionLabel = preferences.labelForDimensionId(dimensionId)
                    ?: ""
                val focusValueLabel = formatCompactFocusValue(entry.focusRating)
                val dimensionIconOption = preferences.iconOptionForDimensionId(dimensionId)
                    ?: DimensionIconCatalog.resolve(null, dimensionId)
                val dimensionColor = toMutedPastelColor(
                    baseColor = preferences.colorForDimensionId(dimensionId)
                        ?: MaterialTheme.colorScheme.primary,
                    surfaceColor = MaterialTheme.colorScheme.surface,
                )
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
                val layout = timelineLayouts["occurrence_${occ.id}"]
                val item = layout?.item as? TimelineItem.Occurrence
                val task = taskLookup[occ.taskId]
                val color = toMutedPastelColor(
                    baseColor = task?.let { preferences.colorForDimensionId(it.dimensionId) ?: preferences.colorFor(it.lifeIntentionCategory) }
                        ?: MaterialTheme.colorScheme.outline,
                    surfaceColor = MaterialTheme.colorScheme.surface,
                )
                val dimensionIconOption = preferences.iconOptionForDimensionId(task?.dimensionId)
                    ?: DimensionIconCatalog.resolve(null, task?.dimensionId)
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
            if (selectedDate == currentTime.toLocalDate()) {
                val currentMinutes = currentTime.hour * 60 + currentTime.minute
                val topOffset = minuteHeight * currentMinutes
                Box(
                    modifier = Modifier
                        .offset(y = topOffset)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.Red),
                )
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
    entry: TimeEntry,
    displayStart: LocalDateTime,
    displayEnd: LocalDateTime,
    startMinutes: Int,
    endMinutes: Int,
    taskTitle: String?,
    dimensionLabel: String,
    focusValueLabel: String?,
    dimensionIconOption: DimensionIconOption,
    isActive: Boolean,
    minuteHeight: androidx.compose.ui.unit.Dp,
    color: Color,
    isDimmed: Boolean = false,
    use24Hour: Boolean,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    onClick: () -> Unit,
) {
    val topOffset = minuteHeight * startMinutes
    val height = minuteHeight * (endMinutes - startMinutes)
    val timeFormatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a")
    val durationMinutes = (endMinutes - startMinutes).coerceAtLeast(0)
    val durationLabel = if (durationMinutes >= 60) {
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration_hours_minutes_compact, durationMinutes / 60, durationMinutes % 60)
    } else {
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration_minutes_compact, durationMinutes)
    }
    val horizontalPadding = 2.dp
    BoxWithConstraints(
        modifier = Modifier
            .offset(y = topOffset)
            .fillMaxWidth(),
    ) {
        val availableWidth = maxWidth - (horizontalPadding * 2)
        val laneWidth = availableWidth / laneCount
        val leftOffset = horizontalPadding + (laneWidth * laneIndex)
        val showCompactRow = height >= 24.dp && laneWidth >= 92.dp
        val startLabel = displayStart.format(timeFormatter)
        val endLabel = if (isActive) {
            androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_now)
        } else {
            displayEnd.format(timeFormatter)
        }
        val compactLabel = buildTimeBlockCompactLabel(
            dimensionLabel = dimensionLabel,
            taskLabel = taskTitle,
            startLabel = startLabel,
            endLabel = endLabel,
            durationLabel = durationLabel,
            focusValueLabel = focusValueLabel,
        )
        val fallbackMetadata = buildList {
            add(durationLabel)
            focusValueLabel?.let { add(it) }
        }.joinToString(" · ")
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
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            if (showCompactRow) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = dimensionIconOption.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = contentColor.copy(alpha = 0.92f),
                    )
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = dimensionIconOption.imageVector,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = contentColor.copy(alpha = 0.92f),
                        )
                        Text(
                            text = taskTitle ?: dimensionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "$startLabel - $endLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
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
    selectedDate: LocalDate,
    plannedTasks: List<Task>,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    pastOccurrences: List<TaskOccurrence>,
): List<Task> {
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
    val safeMinutes = totalMinutes.coerceIn(0, TOTAL_HOURS * 60)
    val hour = safeMinutes / 60
    val minute = safeMinutes % 60
    return if (use24Hour) {
        String.format(Locale.US, "%02d:%02d", hour, minute)
    } else {
        LocalTime.of(hour % 24, minute).format(DateTimeFormatter.ofPattern("h:mm a"))
    }
}

@Composable
private fun GapBlock(
    gap: TimeGap,
    minuteHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit = {},
) {
    val height = (minuteHeight * gap.minutes).coerceAtLeast(8.dp)
    val topOffset = minuteHeight * gap.startMinutes
    val gapColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
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
        Column(modifier = Modifier.padding(6.dp)) {
            if (height > 24.dp) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_untracked),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (height > 40.dp) {
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        id = io.payanam.R.string.loc_untracked_tap_to_assign,
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
    overlap: TimeOverlap,
    minuteHeight: androidx.compose.ui.unit.Dp,
    color: Color,
) {
    val height = (minuteHeight * overlap.minutes).coerceAtLeast(4.dp)
    val topOffset = minuteHeight * overlap.startMinutes
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
    task: Task,
    dueDate: LocalDateTime,
    minuteHeight: androidx.compose.ui.unit.Dp,
    color: Color,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    onClick: () -> Unit = {},
) {
    val taskDurationMinutes = if (task.durationMinutes > 0) task.durationMinutes else PLANNED_OVERLAY_MINUTES
    val dueMinutes = dueDate.hour * 60 + dueDate.minute
    val startMinutes = dueMinutes
    val topOffset = minuteHeight * startMinutes
    val height = minuteHeight * taskDurationMinutes
    val focusValueLabel = formatCompactFocusValue(task.focusRequired)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val startLabel = dueDate.toLocalTime().format(timeFormatter)
    val endLabel = dueDate.toLocalTime().plusMinutes(taskDurationMinutes.toLong()).format(timeFormatter)
    val durationLabel = if (taskDurationMinutes >= 60) {
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration_hours_minutes_compact, taskDurationMinutes / 60, taskDurationMinutes % 60)
    } else {
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration_minutes_compact, taskDurationMinutes)
    }
    val compactLabel = buildTimeBlockCompactLabel(
        dimensionLabel = task.lifeIntentionCategory,
        taskLabel = task.title,
        startLabel = startLabel,
        endLabel = endLabel,
        durationLabel = durationLabel,
        focusValueLabel = focusValueLabel,
    )
    val patternBrush = Brush.linearGradient(
        colors = listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.1f)),
        start = Offset.Zero,
        end = Offset(16f, 16f),
        tileMode = TileMode.Repeated,
    )
    val horizontalPadding = 2.dp
    val borderStyle = if (task.recurrenceEnabled) {
        Modifier.border(
            width = 1.5.dp,
            color = color.copy(alpha = 0.7f),
            shape = RoundedCornerShape(4.dp),
        )
    } else {
        Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
    }
    BoxWithConstraints(
        modifier = Modifier
            .offset(y = topOffset)
            .fillMaxWidth(),
    ) {
        val availableWidth = maxWidth - (horizontalPadding * 2)
        val laneWidth = availableWidth / laneCount
        val leftOffset = horizontalPadding + (laneWidth * laneIndex)
        val showCompactRow = height >= 24.dp && laneWidth >= 92.dp
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (task.recurrenceEnabled) {
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_recurring),
                        modifier = Modifier.size(12.dp),
                        tint = color.copy(alpha = 0.8f),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
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
