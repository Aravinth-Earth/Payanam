//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.AppPreferencesViewModel
import io.payanam.ui.viewmodel.autoTrackEnabledForDimensionId
import io.payanam.ui.viewmodel.visibleDimensionOptions

/**
 * Auto-tracking habit time settings section.
 * Extracted from SettingsScreen to maintain file size limits.
 */
@Composable
/**
 * Performs the auto tracking section.
 */
fun AutoTrackingSection(
    prefsState: AppPreferencesState,
    prefsViewModel: AppPreferencesViewModel,
    logger: UnifiedLogger,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(id = R.string.settings_auto_track_habit_time_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Global toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.settings_auto_track_habit_time_global),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(id = R.string.settings_auto_track_habit_time_global_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = prefsState.autoTrackHabitTimeGlobal,
                onCheckedChange = { enabled ->
                    prefsViewModel.setAutoTrackHabitTimeGlobal(enabled)
                    logger.d(
                        "SettingsScreen.autoTrackHabitTime",
                        "Global auto-track toggled",
                        mapOf(
                            "enabled" to enabled,
                        ),
                    )
                },
            )
        }

        // Per-dimension toggles (only show when global is enabled)
        if (prefsState.autoTrackHabitTimeGlobal) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = stringResource(id = R.string.settings_auto_track_per_dimension),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(id = R.string.settings_auto_track_per_dimension_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            prefsState.visibleDimensionOptions().forEach { dimension ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(dimension.color),
                        )
                        Text(
                            text = dimension.label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = prefsState.autoTrackEnabledForDimensionId(dimension.id),
                        onCheckedChange = { enabled ->
                            prefsViewModel.setAutoTrackDimensionPreference(dimension.id, enabled)
                            logger.d(
                                "SettingsScreen.autoTrackDimension",
                                "Dimension auto-track toggled",
                                mapOf(
                                    "dimensionId" to dimension.id,
                                    "enabled" to enabled,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}
