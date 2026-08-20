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
/**
 * Scoring config screen.
 */
fun ScoringConfigScreen(
    viewModel: ScoringConfigViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    /** Prefs. */
    val prefs = LocalAppPreferences.current

    logger.d("ScoringConfigScreen", "Rendering", mapOf("hasChanges" to uiState.hasChanges))

    /** Scaffold. */
    Scaffold(
        topBar = {
            /** Top app bar. */
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_scoring_title)) },
                navigationIcon = {
                    /** Icon button. */
                    IconButton(onClick = onNavigateBack) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_back),
                        )
                    }
                },
                actions = {
                    /** Icon button. */
                    IconButton(
                        onClick = { viewModel.resetToDefaults() },
                    ) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reset_to_defaults),
                        )
                    }
                },
            )
        },
    ) { padding ->
        /** If. */
        if (uiState.isLoading) {
            /** Column. */
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                /** Circular progress indicator. */
                CircularProgressIndicator()
                /** Spacer. */
                Spacer(modifier = Modifier.height(16.dp))
                /** Text. */
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_loading_configuration))
            }
        } else {
            /** Column. */
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_adjust_scoring_factors),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Factor Weights Section
                /** Section card. */
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_factor_weights)) {
                    /** Weight slider. */
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_dimension),
                        value = uiState.config.dimensionWeight,
                        onValueChange = { viewModel.setDimensionWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    /** Weight slider. */
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_impact),
                        value = uiState.config.impactWeight,
                        onValueChange = { viewModel.setImpactWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    /** Weight slider. */
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_goal_alignment),
                        value = uiState.config.alignmentWeight,
                        onValueChange = { viewModel.setAlignmentWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    /** Weight slider. */
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_energy_required),
                        value = uiState.config.energyWeight,
                        onValueChange = { viewModel.setEnergyWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    /** Weight slider. */
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_control_level),
                        value = uiState.config.controlWeight,
                        onValueChange = { viewModel.setControlWeight(it) },
                        range = 0.1f..3.0f,
                    )
                    /** Weight slider. */
                    WeightSlider(
                        label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration),
                        value = uiState.config.durationWeight,
                        onValueChange = { viewModel.setDurationWeight(it) },
                        range = 0.1f..3.0f,
                    )
                }

                // Impact Level Values
                /** Section card. */
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_impact_levels)) {
                    uiState.config.impactLevelWeights.forEach { (level, value) ->
                        /** Weight slider. */
                        WeightSlider(
                            label = level,
                            value = value,
                            onValueChange = { viewModel.setImpactLevelValue(level, it) },
                            range = 0.0f..1.0f,
                        )
                    }
                }

                // Goal Alignment Values
                /** Section card. */
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_goal_alignment_levels)) {
                    uiState.config.alignmentWeights.forEach { (level, value) ->
                        /** Weight slider. */
                        WeightSlider(
                            label = level,
                            value = value,
                            onValueChange = { viewModel.setAlignmentValue(level, it) },
                            range = 0.0f..1.0f,
                        )
                    }
                }

                // Energy Level Values
                /** Section card. */
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_energy_levels)) {
                    uiState.config.energyLevelWeights.forEach { (level, value) ->
                        /** Weight slider. */
                        WeightSlider(
                            label = level,
                            value = value,
                            onValueChange = { viewModel.setEnergyLevelValue(level, it) },
                            range = 0.0f..1.0f,
                        )
                    }
                }

                // Control Level Values
                /** Section card. */
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_control_levels)) {
                    uiState.config.controlLevelWeights.forEach { (level, value) ->
                        /** Weight slider. */
                        WeightSlider(
                            label = level.split(" ").first(), // Shortened
                            value = value,
                            onValueChange = { viewModel.setControlLevelValue(level, it) },
                            range = 0.0f..1.0f,
                        )
                    }
                }

                // Life Dimension Weights
                /** Section card. */
                SectionCard(title = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_life_dimension_weights)) {
                    prefs.dimensionPreferences.forEach { dimension ->
                        /** Value. */
                        val value = uiState.config.dimensionWeightsById[dimension.id] ?: 0.5
                        /** Weight slider. */
                        WeightSlider(
                            label = prefs.labelForDimensionId(dimension.id) ?: dimension.label,
                            value = value,
                            onValueChange = { viewModel.setDimensionValue(dimension.id, it) },
                            range = 0.0f..1.0f,
                        )
                    }
                }

                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))

                // Action Buttons
                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    /** Outlined button. */
                    OutlinedButton(
                        onClick = {
                            viewModel.discardChanges()
                            /** On navigate back. */
                            onNavigateBack()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isSaving,
                    ) {
                        /** Text. */
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                    }

                    /** Button. */
                    Button(
                        onClick = {
                            viewModel.saveConfig()
                            /** On navigate back. */
                            onNavigateBack()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.hasChanges && !uiState.isSaving,
                    ) {
                        /** If. */
                        if (uiState.isSaving) {
                            /** Circular progress indicator. */
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            /** Icon. */
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            /** Text. */
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_save))
                        }
                    }
                }

                /** Spacer. */
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(
    /** Title. */
    title: String,
    content: @Composable () -> Unit,
) {
    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        /** Column. */
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /** Text. */
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            /** Content. */
            content()
        }
    }
}

@Composable
private fun WeightSlider(
    /** Label. */
    label: String,
    /** Value. */
    value: Double,
    onValueChange: (Double) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    /** Column. */
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        /** Row. */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /** Text. */
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            /** Text. */
            Text(
                text = String.format("%.2f", value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        /** Slider. */
        Slider(
            value = value.toFloat().coerceIn(range),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
