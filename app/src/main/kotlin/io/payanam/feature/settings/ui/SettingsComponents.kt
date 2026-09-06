//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.feature.settings.DatabaseArtifactUiModel
import io.payanam.ui.components.DimensionBadgeLabelRow
import io.payanam.ui.components.DimensionColorPicker
import io.payanam.ui.components.DimensionIconPicker
import io.payanam.ui.components.toDimensionHexString
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.viewmodel.DimensionOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DimensionPreferenceCard(
    preference: DimensionOption,
    usedColorHexes: Set<String>,
    usedIconKeys: Set<String>,
    onLabelCommit: (String) -> Unit,
    onLabelReset: () -> Unit,
    onColorSelected: (Color) -> Unit,
    onIconSelected: (String) -> Unit,
    onWeightCommit: (Double) -> Unit,
    onVisibilityToggleRequested: () -> Unit,
) {
    var isEditing by remember(preference.id) { mutableStateOf(false) }
    var editLabel by remember(preference.id) { mutableStateOf(preference.label) }
    var editWeight by remember(preference.id) { mutableFloatStateOf(preference.weight.toFloat()) }
    val logger = remember { UnifiedLogger.getInstance() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DimensionBadgeLabelRow(
                    label = preference.label,
                    color = preference.color,
                    iconOption = DimensionIconCatalog.resolve(preference.iconKey, preference.id),
                    labelColor = MaterialTheme.colorScheme.onSurface,
                )
                preference.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 22.dp),
                    )
                }
            }
            IconButton(
                onClick = {
                    if (!isEditing) editLabel = preference.label
                    isEditing = !isEditing
                    logger.d(
                        "DimensionPreferenceCard",
                        if (!isEditing) "Dimension edit closed" else "Dimension edit opened",
                        mapOf("dimensionId" to preference.id),
                    )
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                    contentDescription = if (isEditing) {
                        stringResource(id = R.string.settings_dimension_cancel)
                    } else {
                        stringResource(id = R.string.settings_dimension_edit)
                    },
                    modifier = Modifier.size(16.dp),
                    tint = if (isEditing) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        AnimatedVisibility(visible = isEditing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = editLabel,
                        onValueChange = { editLabel = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val trimmed = editLabel.trim()
                        if (trimmed.isNotBlank() && trimmed != preference.label) {
                            onLabelCommit(trimmed)
                        }
                        isEditing = false
                    }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(id = R.string.settings_dimension_save),
                        )
                    }
                }
                if (preference.hasCustomLabelOverride &&
                    preference.id != "dim_unassigned" &&
                    DimensionTaxonomyCatalog.fromCanonicalId(preference.id) != null
                ) {
                    TextButton(
                        onClick = {
                            onLabelReset()
                            editLabel = preference.label
                            isEditing = false
                            logger.i(
                                "DimensionPreferenceCard",
                                "Dimension label reset requested",
                                mapOf("dimensionId" to preference.id),
                            )
                        },
                    ) {
                        Text(text = stringResource(id = R.string.loc_reset_to_defaults))
                    }
                }
                DimensionColorPicker(
                    selectedColorHex = preference.color.toDimensionHexString(),
                    usedColorHexes = usedColorHexes,
                    onSelect = { colorHex -> onColorSelected(io.payanam.ui.components.colorFromHex(colorHex)) },
                )
                DimensionIconPicker(
                    selectedIconKey = preference.iconKey,
                    usedIconKeys = usedIconKeys,
                    onSelect = onIconSelected,
                )

                // C2: user-editable dimension weight (relative importance in the
                // L3 day-score aggregation). 1.0 = equal weighting (legacy).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_dimension_weight_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = editWeight,
                        onValueChange = { editWeight = it },
                        valueRange = 0.1f..10f,
                        steps = 17,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", editWeight),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.widthIn(min = 34.dp),
                    )
                    IconButton(
                        onClick = {
                            if (Math.abs(editWeight - preference.weight.toFloat()) > 0.01f) {
                                onWeightCommit(editWeight.toDouble())
                            }
                            isEditing = false
                            logger.i(
                                "DimensionPreferenceCard",
                                "Dimension weight committed",
                                mapOf("dimensionId" to preference.id, "weight" to editWeight),
                            )
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(id = R.string.settings_dimension_save),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                TextButton(
                    onClick = {
                        isEditing = false
                        onVisibilityToggleRequested()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            id = if (preference.isVisible) {
                                R.string.db_init_dimension_setup_disable_action
                            } else {
                                R.string.db_init_dimension_setup_enable_action
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
internal fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(id = R.string.settings_action_collapse)
                    } else {
                        stringResource(id = R.string.settings_action_expand)
                    },
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
internal fun StatRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun DatabaseArtifactsSection(
    artifacts: List<DatabaseArtifactUiModel>,
    onDeleteArtifact: (String) -> Unit,
    onCleanStaleArtifacts: () -> Unit = {},
) {
    val logger = remember { UnifiedLogger.getInstance() }
    Text(
        text = stringResource(id = R.string.settings_database_files_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (artifacts.isEmpty()) {
        Text(
            text = stringResource(id = R.string.settings_database_files_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val activeArtifacts = artifacts.filter { it.isActive }
    val staleArtifacts = artifacts.filter { !it.isActive }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        activeArtifacts.forEach { artifact ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = artifact.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(
                            id = R.string.settings_database_file_meta,
                            artifact.sizeKb,
                            artifact.lastModifiedLabel,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (staleArtifacts.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = stringResource(id = R.string.settings_database_stale_files_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            staleArtifacts.forEach { artifact ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = artifact.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                id = R.string.settings_database_file_meta,
                                artifact.sizeKb,
                                artifact.lastModifiedLabel,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        logger.i(
                            "DatabaseArtifactsSection.onDeleteArtifact",
                            "Deleting stale database artifact from Settings",
                            mapOf("fileName" to artifact.fileName),
                        )
                        onDeleteArtifact(artifact.fileName)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(id = R.string.settings_action_delete_file),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    logger.d("SettingsScreen.dbArtifactActionTapped", "Database artifact action tapped", mapOf("action" to "clean_stale"))
                    onCleanStaleArtifacts()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(id = R.string.settings_database_clean_stale))
            }
        }
    }
}

internal enum class SettingsSection {
    APPEARANCE,
    DEFAULT_LANDING,
    FOCUS_MODE,
    DIMENSIONS,
    AUTO_TRACK_HABIT_TIME,
    TIME_INSIGHTS,
    AUTO_BACKUP,
    SCORING,
    SECURITY,
    DEBUG,
    DATABASE,
    DATA_MANAGEMENT,
    ABOUT,
}
internal fun SettingsSection?.toggle(target: SettingsSection): SettingsSection? = if (this == target) null else target
