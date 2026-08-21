//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.settings.FocusModePreset
import io.payanam.ui.viewmodel.AppPreferencesState

/**
 * Focus Mode settings section composable.
 * Allows users to select preset tab configurations and toggle individual tab visibility.
 */
@Composable
/**
 * Performs the focus mode settings content.
 */
fun focusModeSettingsContent(
    prefsState: AppPreferencesState,
    onSetActivePreset: (FocusModePreset) -> Unit,
    onSetTabVisibility: (String, Boolean) -> Unit,
) {
    val logger = UnifiedLogger.getInstance()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Active Preset Selector
        Text(
            text = stringResource(id = R.string.active_preset),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            FocusModePreset.entries.forEachIndexed { index, preset ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = FocusModePreset.entries.size,
                    ),
                    onClick = {
                        onSetActivePreset(preset)
                        logger.d(
                            "FocusModeSettings",
                            "Focus mode preset changed",
                            mapOf("preset" to preset.presetId),
                        )
                    },
                    selected = prefsState.activePreset == preset,
                ) {
                    val labelRes = when (preset) {
                        FocusModePreset.SIMPLE_TIME_HABITS -> R.string.preset_simple_time_habits
                        FocusModePreset.SIMPLE_JOURNAL -> R.string.preset_simple_journal
                        FocusModePreset.SIMPLE_TASKS -> R.string.preset_simple_tasks
                        FocusModePreset.FULL_SUITE -> R.string.preset_full_suite
                    }
                    Text(
                        text = stringResource(id = labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Individual Tab Visibility Toggles
        Text(
            text = stringResource(id = R.string.tab_visibility),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(id = R.string.tab_visibility_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Tab toggles
        val tabs = listOf(
            "tasks" to R.string.settings_database_tasks,
            "habits" to R.string.loc_habits,
            "time" to R.string.loc_time,
            "journal" to R.string.loc_journal,
            "notes" to R.string.settings_database_notes,
            "lenses" to R.string.loc_lenses,
            "settings" to R.string.settings_title,
        )

        tabs.forEach { (tabRoute, labelRes) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(id = labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (tabRoute == "settings") {
                    // Settings tab is always visible (disabled switch)
                    Switch(
                        checked = true,
                        onCheckedChange = null, // Disabled
                        enabled = false,
                    )
                } else {
                    Switch(
                        checked = prefsState.tabVisibility[tabRoute] != false,
                        onCheckedChange = { visible ->
                            onSetTabVisibility(tabRoute, visible)
                            logger.d(
                                "FocusModeSettings",
                                "Tab visibility changed",
                                mapOf("tab" to tabRoute, "visible" to visible),
                            )
                        },
                    )
                }
            }
        }

        // Settings always visible note
        Text(
            text = stringResource(id = R.string.settings_always_visible),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
