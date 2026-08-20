//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.settings.FocusModePreset

/**
 * Focus Mode Selection Screen - shown during onboarding to help users
 * choose an initial preset that matches their workflow.
 *
 * @param onPresetSelected Callback invoked when user selects a preset and continues
 */
@Composable
/**
 * Focus mode selection screen.
 */
fun FocusModeSelectionScreen(
    onPresetSelected: (FocusModePreset) -> Unit,
) {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    var selectedPreset by remember { mutableStateOf(FocusModePreset.FULL_SUITE) }

    /** Surface. */
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        /** Column. */
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            /** Spacer. */
            Spacer(modifier = Modifier.height(32.dp))

            // Icon
            /** Icon. */
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = stringResource(R.string.focus_mode_title),
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            /** Spacer. */
            Spacer(modifier = Modifier.height(24.dp))

            // Title
            /** Text. */
            Text(
                text = stringResource(R.string.choose_your_focus),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            /** Spacer. */
            Spacer(modifier = Modifier.height(12.dp))

            // Explanation
            /** Text. */
            Text(
                text = stringResource(R.string.focus_mode_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            /** Spacer. */
            Spacer(modifier = Modifier.height(32.dp))

            // Preset Cards
            FocusModePreset.entries.forEach { preset ->
                /** Preset card. */
                PresetCard(
                    preset = preset,
                    isSelected = selectedPreset == preset,
                    onSelected = { selectedPreset = preset },
                )
                /** Spacer. */
                Spacer(modifier = Modifier.height(12.dp))
            }

            /** Spacer. */
            Spacer(modifier = Modifier.height(24.dp))

            // Continue Button
            /** Button. */
            Button(
                onClick = {
                    logger.i(
                        "FocusModeSelectionScreen",
                        "User selected preset",
                        /** Map of. */
                        mapOf("preset" to selectedPreset.presetId),
                    )
                    /** On preset selected. */
                    onPresetSelected(selectedPreset)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                /** Text. */
                Text(
                    text = stringResource(R.string.loc_continue),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            /** Spacer. */
            Spacer(modifier = Modifier.height(12.dp))

            // Skip link
            /** Text button. */
            TextButton(
                onClick = {
                    logger.i(
                        "FocusModeSelectionScreen",
                        "User skipped preset selection",
                        /** Map of. */
                        mapOf("defaultPreset" to FocusModePreset.FULL_SUITE.presetId),
                    )
                    /** On preset selected. */
                    onPresetSelected(FocusModePreset.FULL_SUITE)
                },
            ) {
                /** Text. */
                Text(
                    text = stringResource(R.string.skip_focus_mode),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            /** Spacer. */
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PresetCard(
    /** Preset. */
    preset: FocusModePreset,
    /** Is selected. */
    isSelected: Boolean,
    onSelected: () -> Unit,
) {
    /** Card. */
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected() }
            .then(
                /** If. */
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    /** Modifier. */
                    Modifier
                },
            ),
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
        shape = RoundedCornerShape(12.dp),
    ) {
        /** Row. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Radio button
            /** Radio button. */
            RadioButton(
                selected = isSelected,
                onClick = { onSelected() },
            )

            /** Spacer. */
            Spacer(modifier = Modifier.width(12.dp))

            // Text content
            /** Column. */
            Column(modifier = Modifier.weight(1f)) {
                /** Text. */
                Text(
                    text = stringResource(
                        id = when (preset) {
                            FocusModePreset.SIMPLE_TIME_HABITS -> R.string.preset_simple_time_habits
                            FocusModePreset.SIMPLE_JOURNAL -> R.string.preset_simple_journal
                            FocusModePreset.SIMPLE_TASKS -> R.string.preset_simple_tasks
                            FocusModePreset.FULL_SUITE -> R.string.preset_full_suite
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )

                /** Spacer. */
                Spacer(modifier = Modifier.height(4.dp))

                /** Text. */
                Text(
                    text = stringResource(
                        id = when (preset) {
                            FocusModePreset.SIMPLE_TIME_HABITS -> R.string.preset_simple_time_habits_desc
                            FocusModePreset.SIMPLE_JOURNAL -> R.string.preset_simple_journal_desc
                            FocusModePreset.SIMPLE_TASKS -> R.string.preset_simple_tasks_desc
                            FocusModePreset.FULL_SUITE -> R.string.preset_full_suite_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                // Show visible tabs count
                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))
                /** Row. */
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /** Icon. */
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    /** Text. */
                    Text(
                        text = "${preset.visibleTabs.size} tabs visible",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // Selected indicator
            /** If. */
            if (isSelected) {
                /** Icon. */
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
