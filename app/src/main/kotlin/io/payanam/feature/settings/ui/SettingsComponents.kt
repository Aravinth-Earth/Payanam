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
    /** Preference. */
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
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }

    /** Column. */
    Column(modifier = Modifier.fillMaxWidth()) {
        /** Row. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /** Column. */
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                /** Dimension badge label row. */
                DimensionBadgeLabelRow(
                    label = preference.label,
                    color = preference.color,
                    iconOption = DimensionIconCatalog.resolve(preference.iconKey, preference.id),
                    labelColor = MaterialTheme.colorScheme.onSurface,
                )
                preference.description?.takeIf { it.isNotBlank() }?.let { description ->
                    /** Text. */
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 22.dp),
                    )
                }
            }
            /** Icon button. */
            IconButton(
                onClick = {
                    /** If. */
                    if (!isEditing) editLabel = preference.label
                    isEditing = !isEditing
                    logger.d(
                        "DimensionPreferenceCard",
                        /** If. */
                        if (!isEditing) "Dimension edit closed" else "Dimension edit opened",
                        /** Map of. */
                        mapOf("dimensionId" to preference.id),
                    )
                },
                modifier = Modifier.size(32.dp),
            ) {
                /** Icon. */
                Icon(
                    imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                    contentDescription = if (isEditing) {
                        /** String resource. */
                        stringResource(id = R.string.settings_dimension_cancel)
                    } else {
                        /** String resource. */
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

        /** Animated visibility. */
        AnimatedVisibility(visible = isEditing) {
            /** Column. */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                /** Row. */
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    /** Outlined text field. */
                    OutlinedTextField(
                        value = editLabel,
                        onValueChange = { editLabel = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    /** Icon button. */
                    IconButton(onClick = {
                        /** Trimmed. */
                        val trimmed = editLabel.trim()
                        /** If. */
                        if (trimmed.isNotBlank() && trimmed != preference.label) {
                            /** On label commit. */
                            onLabelCommit(trimmed)
                        }
                        isEditing = false
                    }) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(id = R.string.settings_dimension_save),
                        )
                    }
                }

                /** If. */
                if (preference.hasCustomLabelOverride &&
                    preference.id != "dim_unassigned" &&
                    DimensionTaxonomyCatalog.fromCanonicalId(preference.id) != null
                ) {
                    /** Text button. */
                    TextButton(
                        onClick = {
                            /** On label reset. */
                            onLabelReset()
                            editLabel = preference.label
                            isEditing = false
                            logger.i(
                                "DimensionPreferenceCard",
                                "Dimension label reset requested",
                                /** Map of. */
                                mapOf("dimensionId" to preference.id),
                            )
                        },
                    ) {
                        /** Text. */
                        Text(text = stringResource(id = R.string.loc_reset_to_defaults))
                    }
                }

                /** Dimension color picker. */
                DimensionColorPicker(
                    selectedColorHex = preference.color.toDimensionHexString(),
                    usedColorHexes = usedColorHexes,
                    onSelect = { colorHex -> onColorSelected(io.payanam.ui.components.colorFromHex(colorHex)) },
                )

                /** Dimension icon picker. */
                DimensionIconPicker(
                    selectedIconKey = preference.iconKey,
                    usedIconKeys = usedIconKeys,
                    onSelect = onIconSelected,
                )

                // C2: user-editable dimension weight (relative importance in the
                // L3 day-score aggregation). 1.0 = equal weighting (legacy).
                /** Row. */
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.settings_dimension_weight_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    /** Slider. */
                    Slider(
                        value = editWeight,
                        onValueChange = { editWeight = it },
                        valueRange = 0.1f..10f,
                        steps = 17,
                        modifier = Modifier.weight(1f),
                    )
                    /** Text. */
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", editWeight),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.widthIn(min = 34.dp),
                    )
                    /** Icon button. */
                    IconButton(
                        onClick = {
                            /** If. */
                            if (Math.abs(editWeight - preference.weight.toFloat()) > 0.01f) {
                                /** On weight commit. */
                                onWeightCommit(editWeight.toDouble())
                            }
                            isEditing = false
                            logger.i(
                                "DimensionPreferenceCard",
                                "Dimension weight committed",
                                /** Map of. */
                                mapOf("dimensionId" to preference.id, "weight" to editWeight),
                            )
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(id = R.string.settings_dimension_save),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                /** Text button. */
                TextButton(
                    onClick = {
                        isEditing = false
                        /** On visibility toggle requested. */
                        onVisibilityToggleRequested()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    /** Icon. */
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(4.dp))
                    /** Text. */
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

        /** Horizontal divider. */
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
internal fun SettingsCard(
    /** Title. */
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** Expanded. */
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit,
) {
    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        /** Column. */
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            /** Row. */
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
            ) {
                /** Row. */
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /** Icon. */
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(8.dp))
                    /** Text. */
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                /** Icon. */
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) {
                        /** String resource. */
                        stringResource(id = R.string.settings_action_collapse)
                    } else {
                        /** String resource. */
                        stringResource(id = R.string.settings_action_expand)
                    },
                )
            }

            /** If. */
            if (expanded) {
                /** Spacer. */
                Spacer(modifier = Modifier.height(12.dp))
                /** Content. */
                content()
            }
        }
    }
}

@Composable
internal fun StatRow(
    /** Label. */
    label: String,
    /** Value. */
    value: String,
) {
    /** Row. */
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        /** Text. */
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Text. */
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
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Text. */
    Text(
        text = stringResource(id = R.string.settings_database_files_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )

    /** If. */
    if (artifacts.isEmpty()) {
        /** Text. */
        Text(
            text = stringResource(id = R.string.settings_database_files_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Return. */
        return
    }

    /** Active artifacts. */
    val activeArtifacts = artifacts.filter { it.isActive }
    /** Stale artifacts. */
    val staleArtifacts = artifacts.filter { !it.isActive }

    /** Column. */
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        activeArtifacts.forEach { artifact ->
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /** Column. */
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    /** Text. */
                    Text(
                        text = artifact.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    /** Text. */
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

        /** If. */
        if (staleArtifacts.isNotEmpty()) {
            /** Horizontal divider. */
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.settings_database_stale_files_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            staleArtifacts.forEach { artifact ->
                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /** Column. */
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        /** Text. */
                        Text(
                            text = artifact.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        /** Text. */
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
                    /** Icon button. */
                    IconButton(onClick = {
                        logger.i(
                            "DatabaseArtifactsSection.onDeleteArtifact",
                            "Deleting stale database artifact from Settings",
                            /** Map of. */
                            mapOf("fileName" to artifact.fileName),
                        )
                        /** On delete artifact. */
                        onDeleteArtifact(artifact.fileName)
                    }) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(id = R.string.settings_action_delete_file),
                        )
                    }
                }
            }
            /** Outlined button. */
            OutlinedButton(
                onClick = {
                    logger.d("SettingsScreen.dbArtifactActionTapped", "Database artifact action tapped", mapOf("action" to "clean_stale"))
                    /** On clean stale artifacts. */
                    onCleanStaleArtifacts()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                /** Text. */
                Text(stringResource(id = R.string.settings_database_clean_stale))
            }
        }
    }
}

internal enum class SettingsSection {
    /** Appearance. */
    APPEARANCE,
    /** Default landing. */
    DEFAULT_LANDING,
    /** Focus mode. */
    FOCUS_MODE,
    /** Dimensions. */
    DIMENSIONS,
    /** Auto track habit time. */
    AUTO_TRACK_HABIT_TIME,
    /** Time insights. */
    TIME_INSIGHTS,
    /** Auto backup. */
    AUTO_BACKUP,
    /** Scoring. */
    SCORING,
    /** Security. */
    SECURITY,
    /** Debug. */
    DEBUG,
    /** Database. */
    DATABASE,
    /** Data management. */
    DATA_MANAGEMENT,
    /** About. */
    ABOUT,
}
internal fun SettingsSection?.toggle(target: SettingsSection): SettingsSection? = if (this == target) null else target
