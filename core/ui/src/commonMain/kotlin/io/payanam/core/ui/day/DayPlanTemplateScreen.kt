//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.labelForDimension
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.core.ui.preferences.visibleDimensions
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.core.ui.time.DurationMinutesPickerField
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.presentation.day.DayPlanViewModel
import io.payanam.shared.logging.AppLogger

/**
 * The day-plan template manager: list, create, edit and delete the templates a day can be planned from.
 *
 * Shared by Android and the desktop. The [DayPlanViewModel] is a required parameter because obtaining it
 * differs per platform - Android retains it across configuration changes (`retainSharedViewModel`), the
 * desktop holds it in a `remember` - and a screen that fetched its own would have to know which platform it
 * is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPlanTemplateScreen(
    viewModel: DayPlanViewModel,
    onNavigateBack: () -> Unit = {},
) {
    val logger = AppLogger.current
    val uiState by viewModel.uiState.collectAsState()
    val strings = LocalUiStrings.current
    val appPrefs = LocalAppPreferences.current
    val dimensionOptions = appPrefs.visibleDimensions()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.locTemplateManagement) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.locBack,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!uiState.isEditingTemplate && uiState.templateCount < uiState.maxTemplates) {
                FloatingActionButton(onClick = { viewModel.startNewTemplate() }) {
                    Icon(Icons.Default.Add, contentDescription = strings.locNewTemplate)
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
                            text = strings.locNoTemplates,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    if (uiState.templateCount >= uiState.maxTemplates) {
                        Text(
                            text = strings.locMaxTemplatesReached.formatArgs(uiState.maxTemplates),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp),
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
    val strings = LocalUiStrings.current
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
                        Icon(Icons.Default.Edit, contentDescription = strings.locEditTemplate)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = strings.locDeleteTemplate,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                text = strings.locTemplateTotalPlanned.formatArgs(formatMinutesShort(budget.totalMinutes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (budget.isExcess) {
                Text(
                    text = strings.locTemplateTimeExcess.formatArgs(formatMinutesShort(budget.excessMinutes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = strings.locTemplateTimeRemaining.formatArgs(formatMinutesShort(budget.remainingMinutes)),
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
    dimensionOptions: List<DimensionPreference>,
    isNew: Boolean,
    errorMessage: String?,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onAllocationChange: (String, Int?) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val strings = LocalUiStrings.current
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
            text = if (isNew) strings.locNewTemplate else strings.locEditTemplate,
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
            label = { Text(strings.locTemplateName) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = templateDescription,
            onValueChange = onDescriptionChange,
            label = { Text(strings.locTemplateDescription) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.locPlannedMinutes,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = strings.locTemplateTotalPlanned.formatArgs(formatMinutesShort(budget.totalMinutes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (budget.isExcess) {
            Text(
                text = strings.locTemplateTimeExcess.formatArgs(formatMinutesShort(budget.excessMinutes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (budget.remainingMinutes > 0) {
            Text(
                text = strings.locTemplateTimeRemaining.formatArgs(formatMinutesShort(budget.remainingMinutes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = strings.locTemplateTimeFullyPlanned,
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
                Text(strings.settingsActionCancel)
            }
            TextButton(
                onClick = onSave,
                enabled = canSave,
            ) {
                Text(strings.locSave)
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
