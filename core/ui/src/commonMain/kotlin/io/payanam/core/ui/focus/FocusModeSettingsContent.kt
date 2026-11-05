//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.focus

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.settings.FocusModePreset

/**
 * Focus Mode settings section composable.
 * Allows users to select preset tab configurations and toggle individual tab visibility.
 */
@Composable
fun focusModeSettingsContent(
    prefsState: AppPreferencesState,
    onSetActivePreset: (FocusModePreset) -> Unit,
    onSetTabVisibility: (String, Boolean) -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = AppLogger.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Active Preset Selector
        Text(
            text = strings.activePreset,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            FocusModePreset.entries.forEachIndexed { index, preset ->
                SegmentedButton(
                    shape =
                        SegmentedButtonDefaults.itemShape(
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
                    val presetLabel =
                        when (preset) {
                            FocusModePreset.SIMPLE_TIME_HABITS -> strings.presetSimpleTimeHabits
                            FocusModePreset.SIMPLE_JOURNAL -> strings.presetSimpleJournal
                            FocusModePreset.SIMPLE_TASKS -> strings.presetSimpleTasks
                            FocusModePreset.FULL_SUITE -> strings.presetFullSuite
                        }
                    Text(
                        text = presetLabel,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Individual Tab Visibility Toggles
        Text(
            text = strings.tabVisibility,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = strings.tabVisibilityWarning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Tab toggles
        val tabs =
            listOf(
                "tasks" to strings.settingsDatabaseTasks,
                "habits" to strings.locHabits,
                "time" to strings.locTime,
                "journal" to strings.locJournal,
                "notes" to strings.settingsDatabaseNotes,
                "lenses" to strings.locLenses,
                "settings" to strings.settingsTitle,
            )

        tabs.forEach { (tabRoute, label) ->
            val tabLabel = label
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = tabLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (tabRoute == "settings") {
                    // Settings tab is always visible (disabled switch)
                    Switch(
                        checked = true,
                        onCheckedChange = null, // Disabled
                        enabled = false,
                        // Label lives in the semantics tree only (same pattern as ChartToggleRow):
                        // without it the control is unlabelled for screen readers and invisible to
                        // UI tests, which can only reach a control through its accessibility node.
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = tabLabel },
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
                        // The row's label is a separate Text; merging it onto the switch node keeps
                        // the label and the toggle state on one node so the control is announced
                        // with its name and reachable by label-driven UI tests.
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = tabLabel },
                    )
                }
            }
        }

        // Settings always visible note
        Text(
            text = strings.settingsAlwaysVisible,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
