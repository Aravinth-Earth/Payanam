//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.domain.model.RecurrenceConfig
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TaskReschedule
import io.payanam.scoring.CompletionStats
import io.payanam.ui.theme.scoreColor
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.colorFor
import io.payanam.ui.viewmodel.labelFor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun RescheduleHistorySection(
    reschedules: List<TaskReschedule>,
    isLoading: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reschedule_history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (reschedules.isEmpty()) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_reschedules_recorded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val recentReschedules = reschedules
                    .sortedByDescending { it.rescheduledAt }
                    .take(10)

                recentReschedules.forEachIndexed { index, reschedule ->
                    RescheduleRow(reschedule)
                    if (index < recentReschedules.lastIndex) {
                        HorizontalDivider()
                    }
                }

                if (reschedules.size > 10) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            id = io.payanam.R.string.loc_showing_last_10_of_reschedules,
                            reschedules.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RescheduleRow(reschedule: TaskReschedule) {
    val prefs = LocalAppPreferences.current
    val pattern = if (prefs.timeFormat.use24Hour) "MMM d, yyyy HH:mm" else "MMM d, yyyy h:mm a"
    val dateTimeFormatter = DateTimeFormatter.ofPattern(pattern)
    val fromText = reschedule.previousDueDate.format(dateTimeFormatter)
    val toText = reschedule.newDueDate.format(dateTimeFormatter)
    val rescheduledAt = reschedule.rescheduledAt.format(dateTimeFormatter)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$fromText → $toText",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_rescheduled_at, rescheduledAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reschedule.wasOverdue) {
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_was_overdue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun RecurrenceScoreCard(
    currentScore: Double,
    completionStats: CompletionStats?,
    occurrenceHistory: List<TaskOccurrence>,
    recurrenceRule: String?,
    latestL1: io.payanam.domain.model.HabitL1Summary? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.TrendingUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_habit_health),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Current Score with visual indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_current_score),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${(currentScore * 100).toInt()}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                currentScore >= 0.8 -> MaterialTheme.colorScheme.primary
                                currentScore >= 0.5 -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            text = "%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Score ring visual
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(60.dp),
                ) {
                    CircularProgressIndicator(
                        progress = { currentScore.toFloat() },
                        modifier = Modifier.size(60.dp),
                        strokeWidth = 6.dp,
                        color = when {
                            currentScore >= 0.8 -> MaterialTheme.colorScheme.primary
                            currentScore >= 0.5 -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    val emoji = when {
                        currentScore >= 0.9 -> "🔥"
                        currentScore >= 0.7 -> "💪"
                        currentScore >= 0.5 -> "👍"
                        currentScore >= 0.3 -> "⚠️"
                        else -> "😬"
                    }
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }

            // Completion Stats
            completionStats?.let { stats ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatColumn(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_7_days), "${(stats.completionRate7Days * 100).toInt()}%")
                    StatColumn(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_30_days), "${(stats.completionRate30Days * 100).toInt()}%")
                    StatColumn(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_90_days), "${(stats.completionRate90Days * 100).toInt()}%")
                }
            }

            // Score roll-up metrics (Inc 4) — the 6 self-gov metrics
            latestL1?.let { l1 ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatColumn(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_metric_running_avg), "${(l1.runningAvg * 100).toInt()}%")
                    StatColumn(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_lens_time_progress_label), formatProgress(l1.progress))
                    StatColumn(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_lens_time_streak_label), "${l1.streakPos}d")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatColumn(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_metric_net_streak), "${l1.streakNet}d")
                    StatColumn(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_metric_consistency), "${l1.posContinue}d")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            HabitCalendarSection(
                occurrences = occurrenceHistory,
                recurrenceRule = recurrenceRule,
            )
        }
    }
}

@Composable
private fun formatProgress(progress: Double): String {
    val pct = (progress * 100).toInt()
    return if (pct > 0) "+$pct%" else "$pct%"
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HabitCalendarSection(
    occurrences: List<TaskOccurrence>,
    recurrenceRule: String?,
) {
    val endDate = LocalDate.now()
    val startDate = endDate.minusDays(83)
    val allDates = rememberDates(startDate, endDate)
    val weeks = allDates.chunked(7)
    val recurrenceConfig = RecurrenceConfig.parse(recurrenceRule)
    val scheduledDates = recurrenceConfig.getScheduledDatesInRange(startDate, endDate).toSet()
    val occurrenceByDate = occurrences
        .asSequence()
        .mapNotNull { occurrence ->
            runCatching { LocalDate.parse(occurrence.occurrenceDate.take(10)) }
                .getOrNull()
                ?.let { parsedDate -> parsedDate to occurrence.status }
        }
        .toMap()

    Text(
        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_habit_calendar),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_last_12_weeks),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(6.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val weekdayLabels = listOf(
            io.payanam.R.string.loc_weekday_m,
            io.payanam.R.string.loc_weekday_tu,
            io.payanam.R.string.loc_weekday_w,
            io.payanam.R.string.loc_weekday_th,
            io.payanam.R.string.loc_weekday_f,
            io.payanam.R.string.loc_weekday_sa,
            io.payanam.R.string.loc_weekday_su,
        )
        weekdayLabels.forEach { labelRes ->
            Text(
                text = androidx.compose.ui.res.stringResource(id = labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        weeks.forEach { week ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                week.forEach { date ->
                    val isScheduled = date in scheduledDates
                    val status = occurrenceByDate[date]
                    val cellColor = when {
                        !isScheduled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                        status == "completed" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        status == "skipped" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
                        status == "missed" -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(cellColor),
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CalendarLegendItem(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_done),
        )
        CalendarLegendItem(
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
            label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.task_notification_action_skip),
        )
        CalendarLegendItem(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_missed),
        )
        CalendarLegendItem(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_scheduled_not_marked),
        )
    }
}

@Composable
private fun CalendarLegendItem(
    color: androidx.compose.ui.graphics.Color,
    label: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun rememberDates(startDate: LocalDate, endDate: LocalDate): List<LocalDate> {
    val days = mutableListOf<LocalDate>()
    var cursor = startDate
    while (!cursor.isAfter(endDate)) {
        days.add(cursor)
        cursor = cursor.plusDays(1)
    }
    return days
}
