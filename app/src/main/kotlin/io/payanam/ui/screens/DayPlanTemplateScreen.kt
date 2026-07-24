//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.ui.viewmodel.DayPlanViewModel
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.labelFor
import io.payanam.ui.viewmodel.labelForDimension
import io.payanam.ui.viewmodel.labelForDimensionId
import io.payanam.ui.viewmodel.visibleDimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPlanTemplateScreen(
    viewModel: DayPlanViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val logger = remember { UnifiedLogger.getInstance() }
    val uiState by viewModel.uiState.collectAsState()
    val appPrefs = LocalAppPreferences.current
    val dimensionOptions = appPrefs.visibleDimensions()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.loc_template_management)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.loc_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!uiState.isEditingTemplate && uiState.templateCount < uiState.maxTemplates) {
                FloatingActionButton(onClick = { viewModel.startNewTemplate() }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(id = R.string.loc_new_template))
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (uiState.isEditingTemplate) {
                // Template editor
                TemplateEditor(
                    templateName = uiState.templateName,
                    templateDescription = uiState.templateDescription,
                    templateAllocations = uiState.templateAllocations,
                    dimensionOptions = dimensionOptions,
                    isNew = uiState.isCreatingNew,
                    errorMessage = uiState.errorMessage,
                    onNameChange = { viewModel.setTemplateName(it) },
                    onDescriptionChange = { viewModel.setTemplateDescription(it) },
                    onAllocationChange = { dimId, minutes ->
                        viewModel.setTemplateAllocation(dimId, minutes)
                        logger.d(
                            "DayPlanTemplateScreen",
                            "Template allocation duration changed",
                            mapOf("dimensionId" to dimId, "minutes" to (minutes?.toString() ?: "none")),
                        )
                    },
                    onSave = { viewModel.saveTemplate() },
                    onCancel = { viewModel.cancelEditing() },
                )
            } else {
                // Template list
                if (uiState.templates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(id = R.string.loc_no_templates),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    if (uiState.templateCount >= uiState.maxTemplates) {
                        Text(
                            text = stringResource(id = R.string.loc_max_templates_reached, uiState.maxTemplates),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    ) {
                        items(uiState.templates, key = { it.id }) { template ->
                            TemplateCard(
                                name = template.name,
                                description = template.description,
                                allocationSummary = template.allocations.mapNotNull { alloc ->
                                    val label = appPrefs.labelForDimensionId(alloc.dimensionId)
                                        ?: DimensionTaxonomyCatalog.fromCanonicalId(alloc.dimensionId)?.fallbackLabel?.let { fallbackLabel ->
                                            appPrefs.labelForDimension(alloc.dimensionId, fallbackLabel)
                                        }
                                    label?.let { it to alloc.plannedMinutes }
                                },
                                onEdit = { viewModel.startEditTemplate(template.id) },
                                onDelete = { viewModel.deleteTemplate(template.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    name: String,
    description: String?,
    allocationSummary: List<Pair<String, Int>>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val budget = computeTemplatePlanBudget(allocationSummary.sumOf { it.second })
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!description.isNullOrBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(id = R.string.loc_edit_template))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(id = R.string.loc_delete_template),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                text = stringResource(id = R.string.loc_template_total_planned, formatMinutesShort(budget.totalMinutes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (budget.isExcess) {
                Text(
                    text = stringResource(id = R.string.loc_template_time_excess, formatMinutesShort(budget.excessMinutes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(id = R.string.loc_template_time_remaining, formatMinutesShort(budget.remainingMinutes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            if (allocationSummary.isNotEmpty()) {
                allocationSummary.forEach { (label, minutes) ->
                    Text(
                        text = "$label: ${formatMinutesShort(minutes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateEditor(
    templateName: String,
    templateDescription: String,
    templateAllocations: Map<String, Int>,
    dimensionOptions: List<io.payanam.ui.viewmodel.DimensionPreference>,
    isNew: Boolean,
    errorMessage: String?,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onAllocationChange: (String, Int?) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val budget = computeTemplatePlanBudget(templateAllocations.values.sum())
    val canSave = !budget.isExcess && templateName.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (isNew) stringResource(id = R.string.loc_new_template) else stringResource(id = R.string.loc_edit_template),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = templateName,
            onValueChange = onNameChange,
            label = { Text(stringResource(id = R.string.loc_template_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = templateDescription,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(id = R.string.loc_template_description)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(id = R.string.loc_planned_minutes),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(id = R.string.loc_template_total_planned, formatMinutesShort(budget.totalMinutes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (budget.isExcess) {
            Text(
                text = stringResource(id = R.string.loc_template_time_excess, formatMinutesShort(budget.excessMinutes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (budget.remainingMinutes > 0) {
            Text(
                text = stringResource(id = R.string.loc_template_time_remaining, formatMinutesShort(budget.remainingMinutes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(id = R.string.loc_template_time_fully_planned),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        dimensionOptions.forEach { option ->
            DurationMinutesPickerField(
                label = option.label,
                minutes = templateAllocations[option.id],
                enabled = true,
            ) { minutes ->
                onAllocationChange(option.id, minutes)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(id = R.string.settings_action_cancel))
            }
            TextButton(
                onClick = onSave,
                enabled = canSave,
            ) {
                Text(stringResource(id = R.string.loc_save))
            }
        }
    }
}

internal data class TemplatePlanBudget(
    val totalMinutes: Int,
    val remainingMinutes: Int,
    val excessMinutes: Int,
) {
    val isExcess: Boolean
        get() = excessMinutes > 0
}

internal fun computeTemplatePlanBudget(totalMinutes: Int): TemplatePlanBudget {
    val cap = TEMPLATE_DAY_LIMIT_MINUTES
    return if (totalMinutes > cap) {
        TemplatePlanBudget(totalMinutes, remainingMinutes = 0, excessMinutes = totalMinutes - cap)
    } else {
        TemplatePlanBudget(totalMinutes, remainingMinutes = cap - totalMinutes, excessMinutes = 0)
    }
}

private fun formatMinutesShort(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

private const val TEMPLATE_DAY_LIMIT_MINUTES = 24 * 60
