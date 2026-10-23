//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.domain.model.TaskReschedule
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import java.time.format.DateTimeFormatter

@Composable
fun RescheduleHistorySection(
    reschedules: List<TaskReschedule>,
    isLoading: Boolean,
) {
    val strings = LocalUiStrings.current
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
                    text = strings.locRescheduleHistory,
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
                    text = strings.locNoReschedulesRecorded,
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
                        text = strings.locShowingLast10OfReschedules.format(reschedules.size,),
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
    val strings = LocalUiStrings.current
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
            text = strings.locRescheduledAt.format(rescheduledAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reschedule.wasOverdue) {
            Text(
                text = strings.locWasOverdue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
