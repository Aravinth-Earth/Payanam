//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.tasks

import io.payanam.core.ui.components.HabitActivityDetailSection
import io.payanam.core.ui.text.formatArgs
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.flags.LocalFeatureFlags
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.colorFor
import io.payanam.core.ui.preferences.colorForDimensionId
import io.payanam.core.ui.preferences.labelFor
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.core.ui.theme.scoreColor
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TaskReschedule
import io.payanam.scoring.CompletionStats
import io.payanam.core.ui.components.DimensionIdentityRow
import io.payanam.core.ui.components.RescheduleHistorySection
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun TaskDetailContent(
    task: Task,
    recurrenceRule: String?,
    occurrenceHistory: List<TaskOccurrence>,
    isLoadingOccurrences: Boolean,
    rescheduleHistory: List<TaskReschedule>,
    isLoadingReschedules: Boolean,
    completionStats: CompletionStats?,
    latestL1: io.payanam.domain.model.HabitL1Summary? = null,
    windowSizeDays: Int = 7,
    windowEnd: java.time.LocalDate = java.time.LocalDate.now(),
    windowRows: List<io.payanam.domain.model.HabitL1Summary> = emptyList(),
    windowOccurrences: Map<String, io.payanam.domain.model.TaskOccurrence> = emptyMap(),
    isLoadingWindow: Boolean = false,
    showChartView: Boolean = true,
    onWindowSizeChange: (Int) -> Unit = {},
    onWindowBack: () -> Unit = {},
    onWindowForward: () -> Unit = {},
    onWindowToday: () -> Unit = {},
    onChartViewChange: (Boolean) -> Unit = {},
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onMiss: () -> Unit,
    onReschedule: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val prefs = LocalAppPreferences.current
    val dateTimePattern = if (prefs.timeFormat.use24Hour) "EEE, MMM d 'at' HH:mm" else "EEE, MMM d 'at' h:mm a"
    val dateTimeFormatter = DateTimeFormatter.ofPattern(dateTimePattern)
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    val dimensionLabel = prefs.labelForDimensionId(task.dimensionId) ?: prefs.labelFor(task.lifeIntentionCategory)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header with score and dimension
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Life Dimension Badge
            val dimensionColor = prefs.colorForDimensionId(task.dimensionId) ?: prefs.colorFor(task.lifeIntentionCategory)
            DimensionIdentityRow(
                prefs = prefs,
                dimensionId = task.dimensionId,
                fallbackLabel = dimensionLabel,
                fallbackColor = dimensionColor,
                iconTint = dimensionColor,
                labelColor = dimensionColor,
                iconSize = 18.dp,
                dotSize = 10.dp,
                showLabel = false,
            )

            // Score Badge
            task.taskScore?.let { score ->
                Box(
                    modifier = Modifier
                        .background(
                            scoreColor(score.toFloat()),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "${(score * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // Title
        Text(
            text = task.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        // Description
        task.description?.let { desc ->
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Due Date
        task.dueDate?.let { due ->
            val canReschedule = task.status == "pending" || task.status == "active"
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = strings.locDueDate,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = due.format(dateTimeFormatter),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    TextButton(onClick = onReschedule, enabled = canReschedule) {
                        Text(strings.locReschedule)
                    }
                }
            }
        }

        // Scoring Parameters
        Text(
            text = strings.locTaskProperties,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PropertyRow(strings.locImpact, task.impactLevel)
                PropertyRow(strings.locGoalAlignment, task.goalAlignment)
                PropertyRow(strings.locEnergyRequired, task.energyLevel)
                PropertyRow(strings.locControlLevel, task.controlLevel)
                PropertyRow(
                    strings.locDuration,
                    strings.locDurationMinutesPlain.formatArgs(task.durationMinutes),
                )
            }
        }

        // Status
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PropertyRow(strings.locStatus, task.status.replaceFirstChar { it.uppercase() })
                PropertyRow(strings.locCreated, task.createdAt.format(dateFormatter))
                task.completedAt?.let {
                    PropertyRow(strings.locCompleted, it.format(dateFormatter))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        if (task.status != "completed" && task.status != "archived") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Complete Button
                Button(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.taskNotificationActionComplete)
                }

                // Skip Button - only for recurring tasks
                if (task.recurrenceEnabled) {
                    FilledTonalButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.taskNotificationActionSkip)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Miss Button - only for recurring tasks
                if (LocalFeatureFlags.current.recurringTasksEnabled && task.recurrenceEnabled) {
                    OutlinedButton(
                        onClick = onMiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.locMiss)
                    }
                }

                // Archive Button
                OutlinedButton(
                    onClick = onArchive,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.locArchive)
                }
            }
        }

        // Activity detail (Part C): window nav + range + charts/table — replaces
        // the old score card + calendar + occurrence history for recurring tasks.
        if (task.recurrenceEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            if (LocalFeatureFlags.current.scoringEnabled) {
                HabitActivityDetailSection(
                    windowSizeDays = windowSizeDays,
                    windowEnd = windowEnd,
                    rows = windowRows,
                    occurrences = windowOccurrences,
                    isLoading = isLoadingWindow,
                    showChartView = showChartView,
                    onWindowSizeChange = onWindowSizeChange,
                    onWindowBack = onWindowBack,
                    onWindowForward = onWindowForward,
                    onWindowToday = onWindowToday,
                    onChartViewChange = onChartViewChange,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (rescheduleHistory.isNotEmpty() || isLoadingReschedules) {
            Spacer(modifier = Modifier.height(16.dp))
            RescheduleHistorySection(
                reschedules = rescheduleHistory,
                isLoading = isLoadingReschedules,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

