//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.tasks

import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.platform.elapsedMillis
import io.payanam.core.ui.flags.LocalFeatureFlags
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.colorFor
import io.payanam.core.ui.preferences.colorForDimensionId
import io.payanam.core.ui.preferences.labelFor
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.core.ui.theme.scoreColor
import io.payanam.domain.model.Task
import io.payanam.core.ui.components.DimensionIdentityRow
import java.time.format.DateTimeFormatter

private val taskDueFormatter24h: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
private val taskDueFormatter12h: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

@Composable
internal fun TaskListRow(
    task: Task,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    traceInteractionId: String? = null,
    traceTapMs: Long? = null,
    tracePosition: Int? = null,
) {
    val strings = LocalUiStrings.current
    val tracer = LocalDataLayer.current.tracer
    val prefs = LocalAppPreferences.current
    val dimensionColor = prefs.colorForDimensionId(task.dimensionId) ?: prefs.colorFor(task.lifeIntentionCategory)
    val dimensionLabel = prefs.labelForDimensionId(task.dimensionId) ?: prefs.labelFor(task.lifeIntentionCategory)
    val dueFormatter = remember(prefs.timeFormat.use24Hour) {
        if (prefs.timeFormat.use24Hour) taskDueFormatter24h else taskDueFormatter12h
    }
    var rowTraceSent by remember(traceInteractionId, task.id) { mutableStateOf(false) }
    var badgeTraceSent by remember(traceInteractionId, task.id) { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (LocalFeatureFlags.current.scoringEnabled) {
                task.taskScore?.let { score ->
                    val scoreLabel = remember(score) { String.format("%.0f", score * 100) }
                    Text(
                        text = scoreLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor(score.toFloat()),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DimensionIdentityRow(
                        prefs = prefs,
                        dimensionId = task.dimensionId,
                        fallbackLabel = dimensionLabel,
                        fallbackColor = dimensionColor,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        showLabel = false,
                        modifier = Modifier,
                    )

                    task.dueDate?.let { dueDate ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = dueDate.format(dueFormatter),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (task.status == "completed") {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = strings.locCompleted,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    SideEffect {
        if (traceInteractionId != null && traceTapMs != null && !rowTraceSent) {
            val elapsed = elapsedMillis() - traceTapMs
            tracer.markEvent(
                screen = "tasks",
                event = "filter_interaction_row_shell_composed",
                data = mapOf(
                    "interactionId" to traceInteractionId,
                    "taskId" to task.id,
                    "rowPosition" to tracePosition,
                    "elapsedSinceTapMs" to elapsed,
                ),
            )
            rowTraceSent = true
        }
        if (traceInteractionId != null && traceTapMs != null && !badgeTraceSent) {
            val elapsed = elapsedMillis() - traceTapMs
            tracer.markEvent(
                screen = "tasks",
                event = "filter_interaction_badge_composed",
                data = mapOf(
                    "interactionId" to traceInteractionId,
                    "taskId" to task.id,
                    "rowPosition" to tracePosition,
                    "elapsedSinceTapMs" to elapsed,
                    "hasDueDate" to (task.dueDate != null),
                ),
            )
            badgeTraceSent = true
        }
    }
}
