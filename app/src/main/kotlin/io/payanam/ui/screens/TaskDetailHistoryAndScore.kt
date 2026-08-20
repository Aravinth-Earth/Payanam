//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

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
import io.payanam.domain.model.TaskReschedule
import io.payanam.ui.viewmodel.LocalAppPreferences
import java.time.format.DateTimeFormatter

@Composable
internal fun RescheduleHistorySection(
    reschedules: List<TaskReschedule>,
    /** Is loading. */
    isLoading: Boolean,
) {
    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        /** Column. */
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /** Row. */
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                /** Icon. */
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reschedule_history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            /** If. */
            if (isLoading) {
                /** Box. */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    /** Circular progress indicator. */
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (reschedules.isEmpty()) {
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_reschedules_recorded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                /** Recent reschedules. */
                val recentReschedules = reschedules
                    .sortedByDescending { it.rescheduledAt }
                    .take(10)

                recentReschedules.forEachIndexed { index, reschedule ->
                    /** Reschedule row. */
                    RescheduleRow(reschedule)
                    /** If. */
                    if (index < recentReschedules.lastIndex) {
                        /** Horizontal divider. */
                        HorizontalDivider()
                    }
                }

                /** If. */
                if (reschedules.size > 10) {
                    /** Text. */
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
    /** Prefs. */
    val prefs = LocalAppPreferences.current
    /** Pattern. */
    val pattern = if (prefs.timeFormat.use24Hour) "MMM d, yyyy HH:mm" else "MMM d, yyyy h:mm a"
    /** Date time formatter. */
    val dateTimeFormatter = DateTimeFormatter.ofPattern(pattern)
    /** From text. */
    val fromText = reschedule.previousDueDate.format(dateTimeFormatter)
    /** To text. */
    val toText = reschedule.newDueDate.format(dateTimeFormatter)
    /** Rescheduled at. */
    val rescheduledAt = reschedule.rescheduledAt.format(dateTimeFormatter)

    /** Column. */
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        /** Text. */
        Text(
            text = "$fromText → $toText",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        /** Text. */
        Text(
            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_rescheduled_at, rescheduledAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** If. */
        if (reschedule.wasOverdue) {
            /** Text. */
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_was_overdue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
