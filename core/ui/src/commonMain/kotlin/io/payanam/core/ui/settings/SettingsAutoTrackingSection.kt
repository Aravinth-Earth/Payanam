//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.autoTrackEnabledForDimensionId
import io.payanam.core.ui.preferences.visibleDimensionOptions
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.shared.logging.AppLogger

/**
 * Auto-tracking habit time settings section.
 *
 * WHY THE WRITES ARE PARAMETERS: this section has no platform behaviour in it at all - it reads the
 * shared [AppPreferencesState] and hands each toggle's outcome to a callback, so the same rendering
 * serves Android and the desktop. The writes themselves are bound by the caller, which owns the
 * repositories; taking an Android ViewModel here instead would put the platform back in the file for no
 * gain, and is the arrangement already used by the Time screen (`onTimeHourHeightDpChange`) and
 * `DayPlanTemplateScreen.onNameChange`.
 *
 * The global toggle and the per-dimension toggles are separate callbacks rather than one, because they
 * are different rows: the global row is `auto_track_habit_time_global`, a per-dimension row is the
 * dimension's own preference. Collapsing them would move that distinction into a parameter.
 */
@Composable
fun SettingsAutoTrackingSection(
    prefsState: AppPreferencesState,
    onAutoTrackHabitTimeGlobalChange: (Boolean) -> Unit,
    onAutoTrackDimensionChange: (dimensionId: String, enabled: Boolean) -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = strings.settingsAutoTrackHabitTimeDescription,
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
                    text = strings.settingsAutoTrackHabitTimeGlobal,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = strings.settingsAutoTrackHabitTimeGlobalHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = prefsState.autoTrackHabitTimeGlobal,
                // The global title is a SIBLING of this toggle, so the merged node carried the on/off state
                // with nothing naming what it governed. The description is that same visible title from the
                // copy table, and there is deliberately no merge: a merge boundary moves the toggle out of
                // the unmerged semantics tree this screen is tested through.
                modifier =
                    Modifier.semantics {
                        contentDescription = strings.settingsAutoTrackHabitTimeGlobal
                    },
                onCheckedChange = { enabled ->
                    onAutoTrackHabitTimeGlobalChange(enabled)
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
                text = strings.settingsAutoTrackPerDimension,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = strings.settingsAutoTrackPerDimensionHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            prefsState.visibleDimensionOptions().forEach { dimension ->
                Row(
                    modifier =
                        Modifier
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
                            modifier =
                                Modifier
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
                        // The per-dimension name beside this toggle is the only thing that tells a screen
                        // reader WHICH life area it governs - two rows in this section differ by exactly that
                        // label and nothing else. The description is that same visible label, read from the
                        // preference the row is already drawn from, so the two cannot disagree. It is data
                        // rather than a copy-table key by nature: the dimension's name is the user's own, not
                        // a phrase this module ships.
                        modifier =
                            Modifier.semantics {
                                contentDescription = dimension.label
                            },
                        onCheckedChange = { enabled ->
                            onAutoTrackDimensionChange(dimension.id, enabled)
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
