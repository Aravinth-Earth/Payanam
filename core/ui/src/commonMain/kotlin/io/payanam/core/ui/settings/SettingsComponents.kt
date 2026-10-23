//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

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
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.DimensionBadgeLabelRow
import io.payanam.core.ui.components.DimensionColorPicker
import io.payanam.core.ui.components.DimensionIconPicker
import io.payanam.core.ui.components.colorFromHex
import io.payanam.core.ui.components.toDimensionHexString
import io.payanam.core.ui.model.DimensionIconCatalog
import io.payanam.core.ui.preferences.DimensionOption
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.presentation.settings.DatabaseArtifactUiModel
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.shared.logging.AppLogger
import kotlin.math.abs
import kotlin.math.round

/**
 * The settings screen's presentational furniture: the collapsible card every section sits in, the
 * label/value row the database stats read out with, the per-dimension editor, the artifact list, and the
 * section identity enum the expansion state is keyed by.
 *
 * WHY THE CARD CAME WITH THE COMPONENTS RATHER THAN STAYING PLATFORM-SIDE:
 *
 * Every other settings section is shared and every one of them is wrapped by [SettingsCard], which is
 * what gives the screen its single-expanding-section behaviour. A card kept on the platform would mean
 * each shared section has to be wrapped at the call site instead, so the section list would be spread
 * across two files and the desktop could not compose the screen without copying the card too. The same
 * goes for [StatRow] and [DatabaseArtifactsSection], which are called from the database stats section
 * rather than from the screen: they are not standalone, so they travel with whatever calls them.
 *
 * WHY THE LOG CALLS CAME ACROSS UNCHANGED IN MEANING: they read [AppLogger] rather than reaching for
 * the app's logger singleton, because this module is shared and has no business holding one. The tags
 * are the same strings the Android side used, so existing log greps keep matching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DimensionPreferenceCard(
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
    val strings = LocalUiStrings.current
    var isEditing by remember(preference.id) { mutableStateOf(false) }
    var editLabel by remember(preference.id) { mutableStateOf(preference.label) }
    var editWeight by remember(preference.id) { mutableFloatStateOf(preference.weight.toFloat()) }
    val logger = remember { AppLogger.current }
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
                        strings.settingsDimensionCancel
                    } else {
                        strings.settingsDimensionEdit
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
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_dimension_name_field"),
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
                            contentDescription = strings.settingsDimensionSave,
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
                        Text(text = strings.locResetToDefaults)
                    }
                }
                DimensionColorPicker(
                    selectedColorHex = preference.color.toDimensionHexString(),
                    usedColorHexes = usedColorHexes,
                    onSelect = { colorHex -> onColorSelected(colorFromHex(colorHex)) },
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
                        text = strings.settingsDimensionWeightLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = editWeight,
                        onValueChange = { editWeight = it },
                        valueRange = 0.1f..10f,
                        steps = 17,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = strings.settingsDimensionWeightLabel }
                            .testTag("settings_dimension_weight_slider"),
                    )
                    Text(
                        // Hand-rounded rather than String.format("%.1f"), which is a JVM API. Locale.US
                        // was pinned on Android so a comma-decimal locale could not render "1,0" for a
                        // value the L3 day score reads back as a number.
                        text = oneDecimalPlace(editWeight.toDouble()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.widthIn(min = 34.dp),
                    )
                    IconButton(
                        onClick = {
                            if (abs(editWeight - preference.weight.toFloat()) > 0.01f) {
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
                            contentDescription = strings.settingsDimensionSave,
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
                        text = if (preference.isVisible) {
                            strings.dbInitDimensionSetupDisableAction
                        } else {
                            strings.dbInitDimensionSetupEnableAction
                        },
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

/** Formats [value] with exactly one digit after the point, locale-independently. */
internal fun oneDecimalPlace(value: Double): String {
    val scaled = round(abs(value) * 10.0).toLong()
    val whole = scaled / 10
    val fraction = scaled % 10
    val sign = if (value < 0) "-" else ""
    return "$sign$whole.$fraction"
}

@Composable
fun SettingsCard(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit,
) {
    val strings = LocalUiStrings.current
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
                        strings.settingsActionCollapse
                    } else {
                        strings.settingsActionExpand
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
fun StatRow(
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
fun DatabaseArtifactsSection(
    artifacts: List<DatabaseArtifactUiModel>,
    onDeleteArtifact: (String) -> Unit,
    onCleanStaleArtifacts: () -> Unit = {},
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    Text(
        text = strings.settingsDatabaseFilesTitle,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (artifacts.isEmpty()) {
        Text(
            text = strings.settingsDatabaseFilesEmpty,
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
                        text = strings.settingsDatabaseFileMeta.formatArgs(
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
                text = strings.settingsDatabaseStaleFilesTitle,
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
                            text = strings.settingsDatabaseFileMeta.formatArgs(
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
                            contentDescription = strings.settingsActionDeleteFile,
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
                Text(strings.settingsDatabaseCleanStale)
            }
        }
    }
}

@Composable
fun DatabaseStatsSettingsSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    uiState: SettingsUiState,
    onDeleteArtifact: (String) -> Unit,
    onCleanStaleArtifacts: () -> Unit = {},
) {
    val strings = LocalUiStrings.current
    SettingsCard(
        title = strings.settingsDatabaseTitle,
        icon = Icons.Default.Storage,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        StatRow(label = strings.settingsDatabaseTasks, value = uiState.taskCount.toString())
        StatRow(label = strings.settingsDatabaseTimeEntries, value = uiState.timeEntryCount.toString())
        StatRow(label = strings.settingsDatabaseNotes, value = uiState.noteCount.toString())
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        StatRow(
            label = strings.settingsDatabaseSize,
            value = strings.settingsDatabaseSizeValue.formatArgs(uiState.databaseSizeKb),
        )
        StatRow(
            label = strings.settingsDatabaseCurrentSchema,
            value = uiState.currentDatabaseSchemaVersion.toString(),
        )
        StatRow(
            label = strings.settingsDatabaseMinSupportedSchema,
            value = uiState.minimumSupportedSchemaVersion.toString(),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        DatabaseArtifactsSection(
            artifacts = uiState.databaseArtifacts,
            onDeleteArtifact = onDeleteArtifact,
            onCleanStaleArtifacts = onCleanStaleArtifacts,
        )
    }
}

/**
 * The settings screen's collapsible sections.
 *
 * The value doubles as the screen's open-section state rather than a separate type: [toggle] below
 * collapses an already-open section to `null`, so "which section is open" and "which section does this
 * row belong to" are one identity, which is what stops a header and its body disagreeing about a tap.
 */
enum class SettingsSection {
    APPEARANCE,
    DAY_BOUNDARY,
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

/** Collapses [this] when it is already [target], so tapping an open section's header closes it. */
fun SettingsSection?.toggle(target: SettingsSection): SettingsSection? = if (this == target) null else target