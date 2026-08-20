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
import io.payanam.FeatureFlags
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TaskReschedule
import io.payanam.scoring.CompletionStats
import io.payanam.ui.components.DimensionIdentityRow
import io.payanam.ui.theme.scoreColor
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.colorFor
import io.payanam.ui.viewmodel.colorForDimensionId
import io.payanam.ui.viewmodel.labelFor
import io.payanam.ui.viewmodel.labelForDimensionId
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun TaskDetailContent(
    /** Task. */
    task: Task,
    recurrenceRule: String?,
    occurrenceHistory: List<TaskOccurrence>,
    /** Is loading occurrences. */
    isLoadingOccurrences: Boolean,
    rescheduleHistory: List<TaskReschedule>,
    /** Is loading reschedules. */
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
    /** Prefs. */
    val prefs = LocalAppPreferences.current
    /** Date time pattern. */
    val dateTimePattern = if (prefs.timeFormat.use24Hour) "EEE, MMM d 'at' HH:mm" else "EEE, MMM d 'at' h:mm a"
    /** Date time formatter. */
    val dateTimeFormatter = DateTimeFormatter.ofPattern(dateTimePattern)
    /** Date formatter. */
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    /** Dimension label. */
    val dimensionLabel = prefs.labelForDimensionId(task.dimensionId) ?: prefs.labelFor(task.lifeIntentionCategory)

    /** Column. */
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header with score and dimension
        /** Row. */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Life Dimension Badge
            /** Dimension color. */
            val dimensionColor = prefs.colorForDimensionId(task.dimensionId) ?: prefs.colorFor(task.lifeIntentionCategory)
            /** Dimension identity row. */
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
                /** Box. */
                Box(
                    modifier = Modifier
                        .background(
                            /** Score color. */
                            scoreColor(score.toFloat()),
                            /** Rounded corner shape. */
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    /** Text. */
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
        /** Text. */
        Text(
            text = task.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        // Description
        task.description?.let { desc ->
            /** Text. */
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Due Date
        task.dueDate?.let { due ->
            /** Can reschedule. */
            val canReschedule = task.status == "pending" || task.status == "active"
            /** Card. */
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                /** Row. */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /** Row. */
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            /** Text. */
                            Text(
                                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_due_date),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            /** Text. */
                            Text(
                                text = due.format(dateTimeFormatter),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    /** Text button. */
                    TextButton(onClick = onReschedule, enabled = canReschedule) {
                        /** Text. */
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reschedule))
                    }
                }
            }
        }

        // Scoring Parameters
        /** Text. */
        Text(
            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_properties),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        /** Card. */
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            /** Column. */
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                /** Property row. */
                PropertyRow(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_impact), task.impactLevel)
                /** Property row. */
                PropertyRow(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_goal_alignment), task.goalAlignment)
                /** Property row. */
                PropertyRow(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_energy_required), task.energyLevel)
                /** Property row. */
                PropertyRow(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_control_level), task.controlLevel)
                /** Property row. */
                PropertyRow(
                    androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration),
                    androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration_minutes_plain, task.durationMinutes),
                )
            }
        }

        // Status
        /** Card. */
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            /** Column. */
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                /** Property row. */
                PropertyRow(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_status), task.status.replaceFirstChar { it.uppercase() })
                /** Property row. */
                PropertyRow(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_created), task.createdAt.format(dateFormatter))
                task.completedAt?.let {
                    /** Property row. */
                    PropertyRow(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_completed), it.format(dateFormatter))
                }
            }
        }

        /** Spacer. */
        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        /** If. */
        if (task.status != "completed" && task.status != "archived") {
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Complete Button
                /** Button. */
                Button(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    /** Icon. */
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(8.dp))
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.task_notification_action_complete))
                }

                // Skip Button - only for recurring tasks
                /** If. */
                if (task.recurrenceEnabled) {
                    /** Filled tonal button. */
                    FilledTonalButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f),
                    ) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        /** Spacer. */
                        Spacer(modifier = Modifier.width(8.dp))
                        /** Text. */
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.task_notification_action_skip))
                    }
                }
            }

            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Miss Button - only for recurring tasks
                /** If. */
                if (FeatureFlags.recurringTasksEnabled && task.recurrenceEnabled) {
                    /** Outlined button. */
                    OutlinedButton(
                        onClick = onMiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        /** Spacer. */
                        Spacer(modifier = Modifier.width(8.dp))
                        /** Text. */
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_miss))
                    }
                }

                // Archive Button
                /** Outlined button. */
                OutlinedButton(
                    onClick = onArchive,
                    modifier = Modifier.weight(1f),
                ) {
                    /** Icon. */
                    Icon(
                        imageVector = Icons.Default.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(8.dp))
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_archive))
                }
            }
        }

        // Activity detail (Part C): window nav + range + charts/table — replaces
        // the old score card + calendar + occurrence history for recurring tasks.
        /** If. */
        if (task.recurrenceEnabled) {
            /** Spacer. */
            Spacer(modifier = Modifier.height(16.dp))

            /** If. */
            if (FeatureFlags.scoringEnabled) {
                /** Habit activity detail section. */
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

            /** Spacer. */
            Spacer(modifier = Modifier.height(16.dp))
        }

        /** If. */
        if (rescheduleHistory.isNotEmpty() || isLoadingReschedules) {
            /** Spacer. */
            Spacer(modifier = Modifier.height(16.dp))
            /** Reschedule history section. */
            RescheduleHistorySection(
                reschedules = rescheduleHistory,
                isLoading = isLoadingReschedules,
            )
        }

        /** Spacer. */
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    /** Row. */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        /** Text. */
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Text. */
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

