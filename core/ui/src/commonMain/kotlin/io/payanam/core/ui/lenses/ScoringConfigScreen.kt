//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.lenses

import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.shared.logging.AppLogger
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.presentation.lenses.ScoringConfigViewModel

private val logger get() = AppLogger.current

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoringConfigScreen(
    viewModel: ScoringConfigViewModel,
    onNavigateBack: () -> Unit = {},
) {
    val strings = LocalUiStrings.current
    val uiState by viewModel.uiState.collectAsState()
    val prefs = LocalAppPreferences.current
    // Nothing on this screen may be edited or written until the stored config has been read: while a read is
    // in flight, or after one failed, the values shown are defaults rather than the user's weights.
    val editingEnabled = !uiState.isLoading && !uiState.loadFailed

    logger.d("ScoringConfigScreen", "Rendering", mapOf("hasChanges" to uiState.hasChanges))
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.settingsScoringTitle) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.locBack,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.resetToDefaults() },
                        // Reset persists defaults, so it would delete a configuration this screen never
                        // managed to read. The ViewModel refuses it too; this is the visible half.
                        enabled = editingEnabled,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = strings.locResetToDefaults,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(strings.locLoadingConfiguration)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // A failed read leaves defaults on screen; saving those would replace the stored weights. The
                // notice sits above the sections so it is visible without scrolling to the end of the weights.
                if (uiState.loadFailed) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = strings.locUnknownErrorOccurred,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.reloadConfig() }) {
                            Text(strings.settingsUpdateRetryButton)
                        }
                    }
                }

                Text(
                    text = strings.locAdjustScoringFactors,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Factor Weights Section
                SectionCard(title = strings.locFactorWeights) {
                    WeightSlider(
                        label = strings.locDimension,
                        value = uiState.config.dimensionWeight,
                        onValueChange = { viewModel.setDimensionWeight(it) },
                        range = 0.1f..3.0f,
                        enabled = editingEnabled,
                    )
                    WeightSlider(
                        label = strings.locImpact,
                        value = uiState.config.impactWeight,
                        onValueChange = { viewModel.setImpactWeight(it) },
                        range = 0.1f..3.0f,
                        enabled = editingEnabled,
                    )
                    WeightSlider(
                        label = strings.locGoalAlignment,
                        value = uiState.config.alignmentWeight,
                        onValueChange = { viewModel.setAlignmentWeight(it) },
                        range = 0.1f..3.0f,
                        enabled = editingEnabled,
                    )
                    WeightSlider(
                        label = strings.locEnergyRequired,
                        value = uiState.config.energyWeight,
                        onValueChange = { viewModel.setEnergyWeight(it) },
                        range = 0.1f..3.0f,
                        enabled = editingEnabled,
                    )
                    WeightSlider(
                        label = strings.locControlLevel,
                        value = uiState.config.controlWeight,
                        onValueChange = { viewModel.setControlWeight(it) },
                        range = 0.1f..3.0f,
                        enabled = editingEnabled,
                    )
                    WeightSlider(
                        label = strings.locDuration,
                        value = uiState.config.durationWeight,
                        onValueChange = { viewModel.setDurationWeight(it) },
                        range = 0.1f..3.0f,
                        enabled = editingEnabled,
                    )
                }

                // Impact Level Values
                SectionCard(title = strings.locImpactLevels) {
                    uiState.config.impactLevelWeights.forEach { (level, value) ->
                        WeightSlider(
                            label = level,
                            value = value,
                            onValueChange = { viewModel.setImpactLevelValue(level, it) },
                            range = 0.0f..1.0f,
                            enabled = editingEnabled,
                        )
                    }
                }

                // Goal Alignment Values
                SectionCard(title = strings.locGoalAlignmentLevels) {
                    uiState.config.alignmentWeights.forEach { (level, value) ->
                        WeightSlider(
                            label = level,
                            value = value,
                            onValueChange = { viewModel.setAlignmentValue(level, it) },
                            range = 0.0f..1.0f,
                            enabled = editingEnabled,
                        )
                    }
                }

                // Energy Level Values
                SectionCard(title = strings.locEnergyLevels) {
                    uiState.config.energyLevelWeights.forEach { (level, value) ->
                        WeightSlider(
                            label = level,
                            value = value,
                            onValueChange = { viewModel.setEnergyLevelValue(level, it) },
                            range = 0.0f..1.0f,
                            enabled = editingEnabled,
                        )
                    }
                }

                // Control Level Values
                SectionCard(title = strings.locControlLevels) {
                    uiState.config.controlLevelWeights.forEach { (level, value) ->
                        WeightSlider(
                            label = level.split(" ").first(), // Shortened
                            value = value,
                            onValueChange = { viewModel.setControlLevelValue(level, it) },
                            range = 0.0f..1.0f,
                            enabled = editingEnabled,
                        )
                    }
                }

                // Life Dimension Weights
                SectionCard(title = strings.locLifeDimensionWeights) {
                    prefs.dimensionPreferences.forEach { dimension ->
                        val value = uiState.config.dimensionWeightsById[dimension.id] ?: 0.5
                        WeightSlider(
                            label = prefs.labelForDimensionId(dimension.id) ?: dimension.label,
                            value = value,
                            onValueChange = { viewModel.setDimensionValue(dimension.id, it) },
                            range = 0.0f..1.0f,
                            enabled = editingEnabled,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.discardChanges()
                            onNavigateBack()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isSaving,
                    ) {
                        Text(strings.settingsActionCancel)
                    }
                    Button(
                        onClick = {
                            // Double-gated with `editingEnabled` rather than `loadFailed` alone: a retry
                            // in flight is a read that has not succeeded yet either.
                            if (editingEnabled) {
                                viewModel.saveConfig()
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.hasChanges && !uiState.isSaving && editingEnabled,
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(strings.locSave)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun WeightSlider(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    // A slider that cannot be driven is the visible half of the ViewModel's refusal to edit before a
    // successful read; the ViewModel refuses the same callbacks, so neither alone is the guard.
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = String.format("%.2f", value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat().coerceIn(range),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .testTag("scoring_config_slider"),
        )
    }
}
