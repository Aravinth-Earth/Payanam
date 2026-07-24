//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.ScoringConfigViewModel
import io.payanam.ui.viewmodel.labelForDimensionId

private val logger = UnifiedLogger.getInstance()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoringConfigScreen(
    viewModel: ScoringConfigViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val prefs = LocalAppPreferences.current

    logger.d("ScoringConfigScreen", "Rendering", mapOf("hasChanges" to uiState.hasChanges))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_scoring_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.resetToDefaults() },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reset_to_defaults),
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
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_loading_configuration))
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
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_adjust_scoring_factors),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Factor Weights Section
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_factor_weights)) {
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_dimension),
                        value = uiState.config.dimensionWeight,
                        onValueChange = { viewModel.setDimensionWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_impact),
                        value = uiState.config.impactWeight,
                        onValueChange = { viewModel.setImpactWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_goal_alignment),
                        value = uiState.config.alignmentWeight,
                        onValueChange = { viewModel.setAlignmentWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_energy_required),
                        value = uiState.config.energyWeight,
                        onValueChange = { viewModel.setEnergyWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_control_level),
                        value = uiState.config.controlWeight,
                        onValueChange = { viewModel.setControlWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration),
                        value = uiState.config.durationWeight,
                        onValueChange = { viewModel.setDurationWeight(it) },
                        range = 0.1f..3.0f,
                    )
                }

                // Impact Level Values
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_impact_levels)) {
                    uiState.config.impactLevelWeights.forEach { (level, value) ->
                        WeightSlider(
                            label = level,
                            value = value,
                            onValueChange = { viewModel.setImpactLevelValue(level, it) },
                            range = 0.0f..1.0f,
                        )
                    }
                }

                // Goal Alignment Values
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_goal_alignment_levels)) {
                    uiState.config.alignmentWeights.forEach { (level, value) ->
                        WeightSlider(
                            label = level,
                            value = value,
                            onValueChange = { viewModel.setAlignmentValue(level, it) },
                            range = 0.0f..1.0f,
                        )
                    }
                }

                // Energy Level Values
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_energy_levels)) {
                    uiState.config.energyLevelWeights.forEach { (level, value) ->
                        WeightSlider(
                            label = level,
                            value = value,
                            onValueChange = { viewModel.setEnergyLevelValue(level, it) },
                            range = 0.0f..1.0f,
                        )
                    }
                }

                // Control Level Values
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_control_levels)) {
                    uiState.config.controlLevelWeights.forEach { (level, value) ->
                        WeightSlider(
                            label = level.split(" ").first(), // Shortened
                            value = value,
                            onValueChange = { viewModel.setControlLevelValue(level, it) },
                            range = 0.0f..1.0f,
                        )
                    }
                }

                // Life Dimension Weights
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_life_dimension_weights)) {
                    prefs.dimensionPreferences.forEach { dimension ->
                        val value = uiState.config.dimensionWeightsById[dimension.id] ?: 0.5
                        WeightSlider(
                            label = prefs.labelForDimensionId(dimension.id) ?: dimension.label,
                            value = value,
                            onValueChange = { viewModel.setDimensionValue(dimension.id, it) },
                            range = 0.0f..1.0f,
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
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                    }

                    Button(
                        onClick = {
                            viewModel.saveConfig()
                            onNavigateBack()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.hasChanges && !uiState.isSaving,
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
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_save))
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
