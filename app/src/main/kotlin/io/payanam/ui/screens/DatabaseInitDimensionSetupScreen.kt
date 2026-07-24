//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.components.DimensionBadgeLabelRow
import io.payanam.ui.components.DimensionColorHexOptions
import io.payanam.ui.components.DimensionColorPicker
import io.payanam.ui.components.DimensionIconPicker
import io.payanam.ui.components.colorFromHex
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.viewmodel.MAX_USER_DIMENSIONS
import io.payanam.ui.viewmodel.NewDatabaseDimensionInput
import io.payanam.ui.viewmodel.defaultNewDatabaseDimensionInputs
import java.util.Locale

@Composable
internal fun MandatoryDimensionSetupSection(
    isSaving: Boolean,
    onSave: (List<NewDatabaseDimensionInput>) -> Unit,
) {
    val logger = UnifiedLogger.getInstance()
    var validationErrorResId by rememberSaveable { mutableStateOf<Int?>(null) }
    var editTargetDimensionId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTargetIsAddMode by rememberSaveable { mutableStateOf(false) }
    var editLabel by rememberSaveable { mutableStateOf("") }
    var editColorHex by rememberSaveable { mutableStateOf(DimensionColorHexOptions.first()) }
    var editIconKey by rememberSaveable { mutableStateOf(DimensionIconCatalog.defaultIconKeyForDimensionId(null)) }
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicateNameIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var duplicateColorIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var duplicateIconIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val editableDimensions = remember {
        mutableStateListOf(
            *defaultNewDatabaseDimensionInputs(context)
                .map { input ->
                    DimensionSetupUiItem(
                        id = input.id,
                        label = input.label,
                        colorHex = input.colorHex,
                        isEnabled = input.isEnabled,
                        iconKey = input.iconKey,
                    )
                }
                .toTypedArray(),
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.db_init_dimension_setup_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = stringResource(id = R.string.db_init_dimension_setup_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.db_init_dimension_setup_customize_desc),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            TextButton(
                onClick = {
                    if (editableDimensions.size < MAX_USER_DIMENSIONS) {
                        editTargetDimensionId = null
                        editTargetIsAddMode = true
                        editLabel = ""
                        editColorHex = DimensionColorHexOptions.first()
                        editIconKey = DimensionIconCatalog.defaultIconKeyForDimensionId(null)
                        validationErrorResId = null
                    } else {
                        validationErrorResId = R.string.db_init_dimension_setup_error_no_slots_left
                    }
                },
                enabled = !isSaving,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(id = R.string.db_init_dimension_setup_add_new_action))
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(editableDimensions, key = { it.id }) { item ->
                DimensionSetupRow(
                    item = item,
                    hasDuplicateName = item.id in duplicateNameIds,
                    hasDuplicateColor = item.id in duplicateColorIds,
                    hasDuplicateIcon = item.id in duplicateIconIds,
                    onEdit = {
                        editTargetDimensionId = item.id
                        editTargetIsAddMode = false
                        editLabel = item.label
                        editColorHex = item.colorHex
                        editIconKey = item.iconKey
                    },
                    onToggleEnabled = {
                        val index = editableDimensions.indexOfFirst { existing -> existing.id == item.id }
                        if (index >= 0) {
                            val toggled = editableDimensions[index].copy(isEnabled = !editableDimensions[index].isEnabled)
                            editableDimensions[index] = toggled
                            validationErrorResId = null
                            duplicateNameIds = emptySet()
                            duplicateColorIds = emptySet()
                            duplicateIconIds = emptySet()
                        }
                    },
                )
            }
        }

        validationErrorResId?.let { errorResId ->
            Text(
                text = stringResource(id = errorResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Button(
            onClick = {
                val dimensionInputs = editableDimensions.map {
                    NewDatabaseDimensionInput(
                        id = it.id,
                        label = it.label.trim(),
                        colorHex = it.colorHex,
                        isEnabled = it.isEnabled,
                        iconKey = it.iconKey,
                    )
                }
                val validationResult = validateDimensionInputs(dimensionInputs)
                duplicateNameIds = validationResult.duplicateNameIds
                duplicateColorIds = validationResult.duplicateColorIds
                duplicateIconIds = validationResult.duplicateIconIds
                if (validationResult.errorResId != null) {
                    validationErrorResId = validationResult.errorResId
                    return@Button
                }
                validationErrorResId = null
                logger.i("DatabaseInitDimensionSetupScreen", "Submitting redesigned dimension setup")
                onSave(dimensionInputs)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isSaving,
            shape = MaterialTheme.shapes.large,
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(id = R.string.db_init_dimension_setup_continue_action),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        DatabaseInitLogExportActions(
            logger = logger,
            context = context,
            scope = scope,
            debugExportMessage = debugExportMessage,
            onDebugExportMessageChange = { debugExportMessage = it },
            showHint = false,
        )
    }

    if (editTargetDimensionId != null || editTargetIsAddMode) {
        DimensionEditDialog(
            isAddMode = editTargetIsAddMode,
            editingDimensionId = editTargetDimensionId,
            initialLabel = editLabel,
            initialColorHex = editColorHex,
            initialIconKey = editIconKey,
            existingDimensions = editableDimensions,
            onDismiss = {
                editTargetDimensionId = null
                editTargetIsAddMode = false
                editIconKey = DimensionIconCatalog.defaultIconKeyForDimensionId(null)
            },
            onSave = { label, colorHex, iconKey ->
                val trimmed = label.trim()
                if (editTargetIsAddMode) {
                    val generatedId = generateDimensionId(trimmed, editableDimensions)
                    editableDimensions.add(
                        DimensionSetupUiItem(
                            id = generatedId,
                            label = trimmed,
                            colorHex = colorHex,
                            isEnabled = true,
                            iconKey = iconKey,
                        ),
                    )
                    validationErrorResId = null
                    duplicateNameIds = emptySet()
                    duplicateColorIds = emptySet()
                    duplicateIconIds = emptySet()
                } else {
                    val targetId = editTargetDimensionId
                    if (targetId != null) {
                        val index = editableDimensions.indexOfFirst { it.id == targetId }
                        if (index >= 0) {
                            editableDimensions[index] = editableDimensions[index].copy(
                                label = trimmed,
                                colorHex = colorHex,
                                isEnabled = true,
                                iconKey = iconKey,
                            )
                            validationErrorResId = null
                            duplicateNameIds = emptySet()
                            duplicateColorIds = emptySet()
                            duplicateIconIds = emptySet()
                        }
                    }
                }
                editTargetDimensionId = null
                editTargetIsAddMode = false
                editIconKey = DimensionIconCatalog.defaultIconKeyForDimensionId(null)
            },
        )
    }
}

@Composable
private fun DimensionSetupRow(
    item: DimensionSetupUiItem,
    hasDuplicateName: Boolean,
    hasDuplicateColor: Boolean,
    hasDuplicateIcon: Boolean,
    onEdit: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (item.isEnabled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                DimensionBadgeLabelRow(
                    label = item.label.ifBlank {
                        stringResource(id = R.string.db_init_dimension_setup_name_placeholder)
                    },
                    color = colorFromHex(item.colorHex),
                    iconOption = DimensionIconCatalog.resolve(item.iconKey, item.id),
                    labelColor = if (item.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    badgeSize = 24.dp,
                )

                if (!item.isEnabled) {
                    Text(
                        text = stringResource(id = R.string.settings_disabled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (hasDuplicateName) {
                    Text(
                        text = stringResource(id = R.string.db_init_dimension_setup_error_row_duplicate_name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (hasDuplicateColor) {
                    Text(
                        text = stringResource(id = R.string.db_init_dimension_setup_error_row_duplicate_color),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (hasDuplicateIcon) {
                    Text(
                        text = stringResource(id = R.string.db_init_dimension_setup_error_row_duplicate_icon),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onEdit, enabled = item.isEnabled) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(id = R.string.loc_edit),
                        tint = if (item.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onToggleEnabled) {
                    Icon(
                        imageVector = if (item.isEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = if (item.isEnabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DimensionEditDialog(
    isAddMode: Boolean,
    editingDimensionId: String?,
    initialLabel: String,
    initialColorHex: String,
    initialIconKey: String,
    existingDimensions: List<DimensionSetupUiItem>,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var colorHex by remember { mutableStateOf(initialColorHex) }
    var iconKey by remember { mutableStateOf(initialIconKey) }
    var error by remember { mutableStateOf<String?>(null) }
    val requiredNameError = stringResource(id = R.string.db_init_dimension_setup_error_name_required)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    id = if (isAddMode) R.string.db_init_dimension_setup_add_dialog_title else R.string.db_init_dimension_setup_edit_dialog_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(id = R.string.db_init_dimension_setup_name_label)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )

                Text(
                    text = stringResource(id = R.string.db_init_dimension_setup_color_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )

                val usedActiveColors = existingDimensions
                    .filter { it.isEnabled }
                    .filterNot { !isAddMode && it.id == editingDimensionId }
                    .map { it.colorHex.trim().uppercase(Locale.ROOT) }
                    .toSet()

                DimensionColorPicker(
                    selectedColorHex = colorHex,
                    usedColorHexes = usedActiveColors,
                    onSelect = { colorHex = it },
                )
                val usedIconKeys = existingDimensions
                    .filterNot { !isAddMode && it.id == editingDimensionId }
                    .map { it.iconKey }
                    .toSet()
                DimensionIconPicker(
                    selectedIconKey = iconKey,
                    usedIconKeys = usedIconKeys,
                    onSelect = { iconKey = it },
                )

                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (label.trim().isBlank()) {
                        error = requiredNameError
                    } else {
                        onSave(label, colorHex, iconKey)
                    }
                },
            ) {
                Text(stringResource(id = R.string.loc_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.settings_action_cancel))
            }
        },
    )
}

private data class DimensionSetupValidationResult(
    val errorResId: Int?,
    val duplicateNameIds: Set<String> = emptySet(),
    val duplicateColorIds: Set<String> = emptySet(),
    val duplicateIconIds: Set<String> = emptySet(),
)

private fun validateDimensionInputs(dimensionInputs: List<NewDatabaseDimensionInput>): DimensionSetupValidationResult {
    val activeDimensions = dimensionInputs.filter { it.isEnabled }
    if (dimensionInputs.size > MAX_USER_DIMENSIONS) {
        return DimensionSetupValidationResult(errorResId = R.string.db_init_dimension_setup_error_no_slots_left)
    }
    if (activeDimensions.isEmpty()) {
        return DimensionSetupValidationResult(errorResId = R.string.db_init_dimension_setup_error_at_least_one)
    }
    if (activeDimensions.any { it.label.trim().isEmpty() }) {
        return DimensionSetupValidationResult(errorResId = R.string.db_init_dimension_setup_error_name_required)
    }
    val duplicateNameIds = findDuplicateIds(
        rows = activeDimensions,
        keySelector = { it.label.trim().lowercase(Locale.ROOT) },
    )
    if (duplicateNameIds.isNotEmpty()) {
        return DimensionSetupValidationResult(
            errorResId = R.string.db_init_dimension_setup_error_unique_names,
            duplicateNameIds = duplicateNameIds,
        )
    }
    val duplicateColorIds = findDuplicateIds(
        rows = activeDimensions,
        keySelector = { it.colorHex.trim().uppercase(Locale.ROOT) },
    )
    if (duplicateColorIds.isNotEmpty()) {
        return DimensionSetupValidationResult(
            errorResId = R.string.db_init_dimension_setup_error_unique_colors,
            duplicateColorIds = duplicateColorIds,
        )
    }
    val duplicateIconIds = findDuplicateIds(
        rows = dimensionInputs,
        keySelector = { it.iconKey.trim().ifEmpty { DimensionIconCatalog.defaultIconKeyForDimensionId(it.id) } },
    )
    if (duplicateIconIds.isNotEmpty()) {
        return DimensionSetupValidationResult(
            errorResId = R.string.db_init_dimension_setup_error_unique_icons,
            duplicateIconIds = duplicateIconIds,
        )
    }
    return DimensionSetupValidationResult(errorResId = null)
}

private fun findDuplicateIds(
    rows: List<NewDatabaseDimensionInput>,
    keySelector: (NewDatabaseDimensionInput) -> String,
): Set<String> {
    val idsByKey = linkedMapOf<String, MutableList<String>>()
    rows.forEach { row ->
        val key = keySelector(row)
        idsByKey.getOrPut(key) { mutableListOf() }.add(row.id)
    }
    return idsByKey.values
        .filter { it.size > 1 }
        .flatten()
        .toSet()
}

private data class DimensionSetupUiItem(
    val id: String,
    val label: String,
    val colorHex: String,
    val isEnabled: Boolean,
    val iconKey: String,
)

private fun generateDimensionId(label: String, existing: List<DimensionSetupUiItem>): String {
    val slug = label.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "custom" }
    val base = "dim_$slug"
    if (existing.none { it.id == base }) {
        return base
    }
    var suffix = 2
    while (existing.any { it.id == "${base}_$suffix" }) {
        suffix++
    }
    return "${base}_$suffix"
}
