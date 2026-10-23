//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.database

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.presentation.database.NewDatabaseDimensionInput
import io.payanam.core.ui.model.DimensionIconCatalog
import io.payanam.core.ui.components.DimensionBadgeLabelRow
import io.payanam.core.ui.components.DimensionColorHexOptions
import io.payanam.core.ui.components.DimensionColorPicker
import io.payanam.core.ui.components.DimensionIconPicker
import io.payanam.core.ui.components.colorFromHex
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import java.util.Locale

@Composable
fun MandatoryDimensionSetupSection(
    isSaving: Boolean,
    onSave: (List<NewDatabaseDimensionInput>) -> Unit,
    /**
     * The debug-log exporter, supplied by the platform.
     *
     * A slot rather than a call, because the exporter writes a log file to the platform's own storage and
     * reads its copy from Android's R class. A shared screen that called it would be unbuildable on the
     * desktop for no user-visible reason - the desktop passes a no-op slot instead of a parallel exporter.
     */
    debugLogExport: (@Composable () -> Unit)? = null,
) {
    val logger = AppLogger.current
    var validationError by remember { mutableStateOf<DimensionSetupError?>(null) }
    var editTargetDimensionId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTargetIsAddMode by rememberSaveable { mutableStateOf(false) }
    var editLabel by rememberSaveable { mutableStateOf("") }
    var editColorHex by rememberSaveable { mutableStateOf(DimensionColorHexOptions.first()) }
    var editIconKey by rememberSaveable { mutableStateOf(DimensionIconCatalog.defaultIconKeyForDimensionId(null)) }
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicateNameIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var duplicateColorIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var duplicateIconIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val strings = LocalUiStrings.current
    val scope = rememberCoroutineScope()
    val editableDimensions = remember {
        mutableStateListOf(
            *defaultNewDatabaseDimensionInputs(strings)
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
            text = strings.dbInitDimensionSetupTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = strings.dbInitDimensionSetupDesc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        // Two stacked rows on purpose: the description owns its full-width row and the button
        // owns its own row below it. In one shared band (the previous Box) long translations or
        // large font scales let the wrapping description run into the button, and even when the
        // glyphs happened to stay apart the button read as part of the description sentence.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = strings.dbInitDimensionSetupCustomizeDesc,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    if (editableDimensions.size < MAX_USER_DIMENSIONS) {
                        editTargetDimensionId = null
                        editTargetIsAddMode = true
                        editLabel = ""
                        editColorHex = DimensionColorHexOptions.first()
                        editIconKey = DimensionIconCatalog.defaultIconKeyForDimensionId(null)
                        validationError = null
                    } else {
                        validationError = DimensionSetupError.NO_SLOTS_LEFT
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(strings.dbInitDimensionSetupAddNewAction)
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
                            validationError = null
                            duplicateNameIds = emptySet()
                            duplicateColorIds = emptySet()
                            duplicateIconIds = emptySet()
                        }
                    },
                )
            }
        }

        validationError?.let { error ->
            Text(
                text = error.copy(strings),
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
                if (validationResult.error != null) {
                    validationError = validationResult.error
                    return@Button
                }
                validationError = null
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
                    text = strings.dbInitDimensionSetupContinueAction,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        debugLogExport?.invoke()
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
                    validationError = null
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
                            validationError = null
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
    val strings = LocalUiStrings.current
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
                        strings.dbInitDimensionSetupNamePlaceholder
                    },
                    color = colorFromHex(item.colorHex),
                    iconOption = DimensionIconCatalog.resolve(item.iconKey, item.id),
                    labelColor = if (item.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    badgeSize = 24.dp,
                )
                if (!item.isEnabled) {
                    Text(
                        text = strings.settingsDisabled,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (hasDuplicateName) {
                    Text(
                        text = strings.dbInitDimensionSetupErrorRowDuplicateName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (hasDuplicateColor) {
                    Text(
                        text = strings.dbInitDimensionSetupErrorRowDuplicateColor,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (hasDuplicateIcon) {
                    Text(
                        text = strings.dbInitDimensionSetupErrorRowDuplicateIcon,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onEdit, enabled = item.isEnabled) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = strings.locEdit,
                        tint = if (item.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleEnabled) {
                    Icon(
                        imageVector = if (item.isEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (item.isEnabled) {
                            strings.dbInitDimensionSetupDisableAction
                        } else {
                            strings.dbInitDimensionSetupEnableAction
                        },
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
    val strings = LocalUiStrings.current
    var label by remember { mutableStateOf(initialLabel) }
    var colorHex by remember { mutableStateOf(initialColorHex) }
    var iconKey by remember { mutableStateOf(initialIconKey) }
    var error by remember { mutableStateOf<String?>(null) }
    val requiredNameError = strings.dbInitDimensionSetupErrorNameRequired
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isAddMode) {
                    strings.dbInitDimensionSetupAddDialogTitle
                } else {
                    strings.dbInitDimensionSetupEditDialogTitle
                },
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
                    label = { Text(strings.dbInitDimensionSetupNameLabel) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Text(
                    text = strings.dbInitDimensionSetupColorLabel,
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
                Text(strings.locSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}

/** Which copy the validation failed with, named rather than an Android resource id. */
private enum class DimensionSetupError {
    NO_SLOTS_LEFT,
    AT_LEAST_ONE_REQUIRED,
    NAME_REQUIRED,
    UNIQUE_NAMES,
    UNIQUE_COLORS,
    UNIQUE_ICONS,
}

/** Resolves the copy for [error] in the active language. */
private fun DimensionSetupError.copy(strings: UiStrings): String =
    when (this) {
        DimensionSetupError.NO_SLOTS_LEFT -> strings.dbInitDimensionSetupErrorNoSlotsLeft
        DimensionSetupError.AT_LEAST_ONE_REQUIRED -> strings.dbInitDimensionSetupErrorAtLeastOne
        DimensionSetupError.NAME_REQUIRED -> strings.dbInitDimensionSetupErrorNameRequired
        DimensionSetupError.UNIQUE_NAMES -> strings.dbInitDimensionSetupErrorUniqueNames
        DimensionSetupError.UNIQUE_COLORS -> strings.dbInitDimensionSetupErrorUniqueColors
        DimensionSetupError.UNIQUE_ICONS -> strings.dbInitDimensionSetupErrorUniqueIcons
    }

private data class DimensionSetupValidationResult(
    val error: DimensionSetupError?,
    val duplicateNameIds: Set<String> = emptySet(),
    val duplicateColorIds: Set<String> = emptySet(),
    val duplicateIconIds: Set<String> = emptySet(),
)

private fun validateDimensionInputs(dimensionInputs: List<NewDatabaseDimensionInput>): DimensionSetupValidationResult {
    val activeDimensions = dimensionInputs.filter { it.isEnabled }
    if (dimensionInputs.size > MAX_USER_DIMENSIONS) {
        return DimensionSetupValidationResult(error = DimensionSetupError.NO_SLOTS_LEFT)
    }
    if (activeDimensions.isEmpty()) {
        return DimensionSetupValidationResult(error = DimensionSetupError.AT_LEAST_ONE_REQUIRED)
    }
    if (activeDimensions.any { it.label.trim().isEmpty() }) {
        return DimensionSetupValidationResult(error = DimensionSetupError.NAME_REQUIRED)
    }
    val duplicateNameIds = findDuplicateIds(
        rows = activeDimensions,
        keySelector = { it.label.trim().lowercase(Locale.ROOT) },
    )
    if (duplicateNameIds.isNotEmpty()) {
        return DimensionSetupValidationResult(
            error = DimensionSetupError.UNIQUE_NAMES,
            duplicateNameIds = duplicateNameIds,
        )
    }
    val duplicateColorIds = findDuplicateIds(
        rows = activeDimensions,
        keySelector = { it.colorHex.trim().uppercase(Locale.ROOT) },
    )
    if (duplicateColorIds.isNotEmpty()) {
        return DimensionSetupValidationResult(
            error = DimensionSetupError.UNIQUE_COLORS,
            duplicateColorIds = duplicateColorIds,
        )
    }
    val duplicateIconIds = findDuplicateIds(
        rows = dimensionInputs,
        keySelector = { it.iconKey.trim().ifEmpty { DimensionIconCatalog.defaultIconKeyForDimensionId(it.id) } },
    )
    if (duplicateIconIds.isNotEmpty()) {
        return DimensionSetupValidationResult(
            error = DimensionSetupError.UNIQUE_ICONS,
            duplicateIconIds = duplicateIconIds,
        )
    }
    return DimensionSetupValidationResult(error = null)
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
