//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.ui.components.DimensionIconCascadeLayer
import io.payanam.ui.model.DimensionIconOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
internal fun PastOccurrenceBlock(
    occurrence: TaskOccurrence,
    task: Task?,
    minuteHeight: androidx.compose.ui.unit.Dp,
    startMinutes: Int,
    endMinutes: Int,
    use24Hour: Boolean,
    color: Color,
    dimensionIconOption: DimensionIconOption,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    onClick: () -> Unit,
) {
    val logger = remember { UnifiedLogger.getInstance() }
    val topOffset = minuteHeight * startMinutes
    val height = minuteHeight * (endMinutes - startMinutes)
    val (bgColor, statusIcon) = when (occurrence.status) {
        "completed" -> color.copy(alpha = 0.15f) to Icons.Filled.Check
        "skipped" -> Color.Gray.copy(alpha = 0.15f) to Icons.Filled.Close
        "missed" -> Color.Red.copy(alpha = 0.15f) to Icons.Filled.Close
        else -> color.copy(alpha = 0.1f) to null
    }
    val borderColor = when (occurrence.status) {
        "completed" -> color.copy(alpha = 0.4f)
        "skipped" -> Color.Gray.copy(alpha = 0.4f)
        "missed" -> Color.Red.copy(alpha = 0.4f)
        else -> color.copy(alpha = 0.3f)
    }
    val timeFormatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a")
    val displayStart = LocalTime.of(startMinutes / 60, startMinutes % 60).format(timeFormatter)
    val displayEnd = LocalTime.of((endMinutes.coerceAtMost(1439)) / 60, (endMinutes.coerceAtMost(1439)) % 60).format(timeFormatter)
    val durationMinutes = (endMinutes - startMinutes).coerceAtLeast(0)
    val focusValueLabel = formatCompactFocusValue(task?.focusRequired)
    val durationLabel = if (durationMinutes >= 60) {
        androidx.compose.ui.res.stringResource(id = R.string.loc_duration_hours_minutes_compact, durationMinutes / 60, durationMinutes % 60)
    } else {
        androidx.compose.ui.res.stringResource(id = R.string.loc_duration_minutes_compact, durationMinutes)
    }
    val dimensionLabel = task?.lifeIntentionCategory?.takeIf { it.isNotBlank() }
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
        val compactLabel = buildTimeBlockCompactLabel(
            dimensionLabel = task?.lifeIntentionCategory ?: androidx.compose.ui.res.stringResource(id = R.string.loc_unknown_task),
            taskLabel = task?.title,
            startLabel = displayStart,
            endLabel = displayEnd,
            durationLabel = durationLabel,
            focusValueLabel = focusValueLabel,
        )
        Box(
            modifier = Modifier
                .offset(x = leftOffset)
                .width(laneWidth - horizontalPadding)
                .height(height)
                .background(bgColor, RoundedCornerShape(8.dp))
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .clickable {
                    logger.d("TimeScreenTimelineOccurrenceBlock.PastOccurrenceBlock", "Opening past occurrence block", mapOf("occurrenceId" to occurrence.id))
                    onClick()
                }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            DimensionIconCascadeLayer(
                iconOption = dimensionIconOption,
                tint = borderColor,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                seedKey = "${occurrence.id}_${task?.id ?: "occurrence"}",
                iconCount = if (height >= 96.dp && laneWidth >= 92.dp) 13 else 9,
                minIconSize = 8.dp,
                maxIconSize = 16.dp,
                alphaRange = 0.12f..0.24f,
                animated = true,
            )
            if (showCompactRow) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    statusIcon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = when (occurrence.status) {
                                "completed" -> androidx.compose.ui.res.stringResource(id = R.string.loc_completed)
                                "skipped" -> androidx.compose.ui.res.stringResource(id = R.string.loc_skipped)
                                "missed" -> androidx.compose.ui.res.stringResource(id = R.string.loc_missed)
                                else -> androidx.compose.ui.res.stringResource(id = R.string.loc_status)
                            },
                            modifier = Modifier.size(12.dp),
                            tint = borderColor,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = androidx.compose.ui.res.stringResource(id = R.string.loc_recurring),
                        modifier = Modifier.size(10.dp),
                        tint = borderColor.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = dimensionIconOption.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = borderColor.copy(alpha = 0.92f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = compactLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = borderColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        statusIcon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = when (occurrence.status) {
                                    "completed" -> androidx.compose.ui.res.stringResource(id = R.string.loc_completed)
                                    "skipped" -> androidx.compose.ui.res.stringResource(id = R.string.loc_skipped)
                                    "missed" -> androidx.compose.ui.res.stringResource(id = R.string.loc_missed)
                                    else -> androidx.compose.ui.res.stringResource(id = R.string.loc_status)
                                },
                                modifier = Modifier.size(12.dp),
                                tint = borderColor,
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Icon(
                            imageVector = Icons.Filled.Repeat,
                            contentDescription = androidx.compose.ui.res.stringResource(id = R.string.loc_recurring),
                            modifier = Modifier.size(10.dp),
                            tint = borderColor.copy(alpha = 0.6f),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = dimensionIconOption.imageVector,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = borderColor.copy(alpha = 0.92f),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = task?.title ?: androidx.compose.ui.res.stringResource(id = R.string.loc_unknown_task),
                            style = MaterialTheme.typography.labelSmall,
                            color = borderColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = R.string.loc_time_range_with_now, displayStart, displayEnd),
                        style = MaterialTheme.typography.labelSmall,
                        color = borderColor.copy(alpha = 0.86f),
                        maxLines = 1,
                    )
                    Text(
                        text = buildList {
                            add(durationLabel)
                            focusValueLabel?.let { add(it) }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = borderColor.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
