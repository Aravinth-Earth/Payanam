//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming", "MagicNumber")

package io.payanam.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.model.DimensionIconOption
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.colorForDimensionId
import io.payanam.ui.viewmodel.iconOptionForDimensionId
import io.payanam.ui.viewmodel.labelForDimensionId
import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
internal fun DimensionCompactBadge(
    label: String,
    color: Color,
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val compactIconTint = if (color.luminance() > 0.45f) Color(0xFF111111) else Color.White
    Surface(
        modifier = modifier
            .size(size)
            .semantics(mergeDescendants = true) {
                contentDescription = label
            },
        shape = RoundedCornerShape((size.value * 0.38f).dp),
        color = color,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = iconOption.imageVector,
                contentDescription = null,
                modifier = Modifier.size(size * 0.55f),
                tint = compactIconTint,
            )
        }
    }
}

@Composable
internal fun DimensionBadgeLabelRow(
    label: String,
    color: Color,
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 24.dp,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 1,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DimensionCompactBadge(
            label = label,
            color = color,
            iconOption = iconOption,
            size = badgeSize,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun DimensionBadgeLabelRow(
    prefs: AppPreferencesState,
    dimensionId: String?,
    fallbackLabel: String,
    fallbackColor: Color,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    badgeSize: Dp = 24.dp,
    maxLines: Int = 1,
) {
    val iconOption = prefs.iconOptionForDimensionId(dimensionId)
        ?: remember(dimensionId) { DimensionIconCatalog.resolve(null, dimensionId) }
    val label = prefs.labelForDimensionId(dimensionId) ?: fallbackLabel
    val color = prefs.colorForDimensionId(dimensionId) ?: fallbackColor
    DimensionBadgeLabelRow(
        label = label,
        color = color,
        iconOption = iconOption,
        modifier = modifier,
        badgeSize = badgeSize,
        labelColor = labelColor,
        maxLines = maxLines,
    )
}

@Composable
internal fun DimensionDropdownBadge(
    label: String,
    color: Color,
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val compactIconTint = if (color.luminance() > 0.45f) Color(0xFF111111) else Color.White
    Surface(
        modifier = modifier
            .size(size)
            .semantics(mergeDescendants = true) {
                contentDescription = label
            },
        shape = RoundedCornerShape((size.value * 0.34f).dp),
        color = color,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = iconOption.imageVector,
                contentDescription = null,
                modifier = Modifier.size(size * 0.52f),
                tint = compactIconTint,
            )
        }
    }
}

@Composable
internal fun DimensionDropdownBadgeLabelRow(
    label: String,
    color: Color,
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 22.dp,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 1,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DimensionDropdownBadge(
            label = label,
            color = color,
            iconOption = iconOption,
            size = badgeSize,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun DimensionIdentityRow(
    label: String,
    color: Color,
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 16.dp,
    dotSize: Dp = 8.dp,
    maxLines: Int = 1,
    showLabel: Boolean = true,
) {
    val compactBadgeSize = (iconSize + dotSize + 8.dp).coerceAtLeast(20.dp)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (showLabel) 8.dp else 6.dp),
    ) {
        if (showLabel) {
            Icon(
                imageVector = iconOption.imageVector,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = iconTint,
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(color, CircleShape),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            DimensionCompactBadge(
                label = label,
                color = color,
                iconOption = iconOption,
                size = compactBadgeSize,
            )
        }
    }
}

@Composable
internal fun DimensionIconPatternLayer(
    iconOption: DimensionIconOption,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 10.dp,
    columns: Int = 5,
    rows: Int = 3,
    alpha: Float = 0.16f,
    rotationDegrees: Float = 0f,
    animated: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "dimension-pattern")
    val drift = if (animated) {
        transition.animateFloat(
            initialValue = -10f,
            targetValue = 10f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dimension-pattern-drift",
        ).value
    } else {
        0f
    }
    Column(
        modifier = modifier.graphicsLayer {
            rotationZ = rotationDegrees
            translationX = drift
            translationY = drift * -0.65f
        },
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(columns) {
                    Icon(
                        imageVector = iconOption.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = tint.copy(alpha = alpha),
                    )
                }
            }
        }
    }
}

private data class DimensionCascadeSpec(
    val startXFraction: Float,
    val endXFraction: Float,
    val startYFraction: Float,
    val endYFraction: Float,
    val phaseOffset: Float,
    val sizeFactor: Float,
    val alphaFactor: Float,
)

@Composable
internal fun DimensionIconCascadeLayer(
    iconOption: DimensionIconOption,
    tint: Color,
    modifier: Modifier = Modifier,
    seedKey: String,
    iconCount: Int = 11,
    minIconSize: Dp = 8.dp,
    maxIconSize: Dp = 16.dp,
    alphaRange: ClosedFloatingPointRange<Float> = 0.14f..0.28f,
    animated: Boolean = true,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp)),
    ) {
        val transition = rememberInfiniteTransition(label = "dimension-cascade")
        val targetSpeedDpPerSecond = 18f
        val areaScore = (maxWidth.value * maxHeight.value) / 420f
        val resolvedIconCount = areaScore.toInt().coerceIn(iconCount, iconCount * 4)
        val specs = remember(seedKey, resolvedIconCount, alphaRange.start, alphaRange.endInclusive) {
            List(resolvedIconCount) { index ->
                val random = Random("${seedKey}_$index".hashCode().absoluteValue)
                val startX = random.nextFloat() * 0.86f + 0.02f
                val endX = (startX + (random.nextFloat() * 0.18f - 0.09f)).coerceIn(0.02f, 0.9f)
                DimensionCascadeSpec(
                    startXFraction = startX,
                    endXFraction = endX,
                    startYFraction = -0.24f - (random.nextFloat() * 0.28f),
                    endYFraction = 1.04f + (random.nextFloat() * 0.24f),
                    phaseOffset = random.nextFloat(),
                    sizeFactor = random.nextFloat(),
                    alphaFactor = alphaRange.start + (random.nextFloat() * (alphaRange.endInclusive - alphaRange.start)),
                )
            }
        }
        specs.forEachIndexed { index, spec ->
            val horizontalTravelDp = (maxWidth.value * (spec.endXFraction - spec.startXFraction))
            val verticalTravelDp = (maxHeight.value * (spec.endYFraction - spec.startYFraction))
            val travelDistanceDp = sqrt((horizontalTravelDp * horizontalTravelDp) + (verticalTravelDp * verticalTravelDp))
            val durationMillis = ((travelDistanceDp / targetSpeedDpPerSecond) * 1000f)
                .toInt()
                .coerceIn(3600, 22000)
            val baseProgress = if (animated) {
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = durationMillis, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "dimension-cascade-$index",
                ).value
            } else {
                0.42f
            }
            val progress = (baseProgress + spec.phaseOffset) % 1f
            val iconSize = minIconSize + (maxIconSize - minIconSize) * spec.sizeFactor
            val xFraction = spec.startXFraction + ((spec.endXFraction - spec.startXFraction) * progress)
            val yFraction = spec.startYFraction + ((spec.endYFraction - spec.startYFraction) * progress)
            val fadeMultiplier = when {
                progress < 0.16f -> progress / 0.16f
                progress > 0.84f -> (1f - progress) / 0.16f
                else -> 1f
            }.coerceIn(0f, 1f)
            val liveAlpha = (spec.alphaFactor * fadeMultiplier).coerceIn(0.03f, alphaRange.endInclusive)
            Icon(
                imageVector = iconOption.imageVector,
                contentDescription = null,
                modifier = Modifier
                    .offset(maxWidth * xFraction, maxHeight * yFraction)
                    .size(iconSize),
                tint = tint.copy(alpha = liveAlpha),
            )
        }
    }
}

@Composable
internal fun DimensionIdentityRow(
    prefs: AppPreferencesState,
    dimensionId: String?,
    fallbackLabel: String,
    fallbackColor: Color,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 16.dp,
    dotSize: Dp = 8.dp,
    maxLines: Int = 1,
    showLabel: Boolean = true,
) {
    val iconOption = prefs.iconOptionForDimensionId(dimensionId)
        ?: remember(dimensionId) { DimensionIconCatalog.resolve(null, dimensionId) }
    val label = prefs.labelForDimensionId(dimensionId) ?: fallbackLabel
    val color = prefs.colorForDimensionId(dimensionId) ?: fallbackColor
    DimensionIdentityRow(
        label = label,
        color = color,
        iconOption = iconOption,
        modifier = modifier,
        iconTint = iconTint,
        labelColor = labelColor,
        iconSize = iconSize,
        dotSize = dotSize,
        maxLines = maxLines,
        showLabel = showLabel,
    )
}

@Composable
internal fun DimensionColorPicker(
    selectedColorHex: String,
    usedColorHexes: Set<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val normalizedSelected = selectedColorHex.trim().uppercase()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(id = R.string.db_init_dimension_setup_color_label),
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(colorFromHex(selectedColorHex), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(id = R.string.dimension_picker_choose_color),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(id = R.string.db_init_dimension_setup_color_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(text = stringResource(id = R.string.db_init_dimension_setup_color_label))
            },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 52.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(DimensionColorHexOptions) { hex ->
                        val normalizedHex = hex.trim().uppercase()
                        val isSelected = normalizedHex == normalizedSelected
                        val isUsed = normalizedHex in usedColorHexes && !isSelected
                        Surface(
                            onClick = {
                                onSelect(hex)
                                showDialog = false
                            },
                            enabled = !isUsed,
                            modifier = Modifier.size(52.dp),
                            shape = CircleShape,
                            color = colorFromHex(hex).copy(alpha = if (isUsed) 0.25f else 1f),
                            border = BorderStroke(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                        ) {}
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showDialog = false }) {
                    Text(text = stringResource(id = R.string.settings_action_cancel))
                }
            },
        )
    }
}

@Composable
internal fun DimensionIconPicker(
    selectedIconKey: String,
    usedIconKeys: Set<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val logger = remember { UnifiedLogger.getInstance() }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(id = R.string.db_init_dimension_setup_icon_label),
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = DimensionIconCatalog.resolve(selectedIconKey).imageVector,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(id = R.string.dimension_picker_choose_icon),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(id = R.string.db_init_dimension_setup_icon_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(text = stringResource(id = R.string.db_init_dimension_setup_icon_label))
            },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 44.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(DimensionIconCatalog.options) { option ->
                        val isSelected = option.key == selectedIconKey
                        val isUsed = option.key in usedIconKeys && !isSelected
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else if (isUsed) {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable(enabled = !isUsed) {
                                    logger.d(
                                        "DimensionIconPicker",
                                        "Dimension icon selected",
                                        mapOf("iconKey" to option.key),
                                    )
                                    onSelect(option.key)
                                    showDialog = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = option.imageVector,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = if (isUsed) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                } else if (isSelected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showDialog = false }) {
                    Text(text = stringResource(id = R.string.settings_action_cancel))
                }
            },
        )
    }
}

internal val DimensionColorHexOptions = listOf(
    Color(0xFF3F51B5).toDimensionHexString(),
    Color(0xFF4CAF50).toDimensionHexString(),
    Color(0xFFE91E63).toDimensionHexString(),
    Color(0xFF009688).toDimensionHexString(),
    Color(0xFFFF9800).toDimensionHexString(),
    Color(0xFF9C27B0).toDimensionHexString(),
    Color(0xFF00BCD4).toDimensionHexString(),
    Color(0xFF673AB7).toDimensionHexString(),
    Color(0xFF8BC34A).toDimensionHexString(),
    Color(0xFF795548).toDimensionHexString(),
    Color(0xFFFF5722).toDimensionHexString(),
    Color(0xFF607D8B).toDimensionHexString(),
    Color(0xFF6D4C41).toDimensionHexString(),
    Color(0xFF1E88E5).toDimensionHexString(),
    Color(0xFF43A047).toDimensionHexString(),
    Color(0xFFD81B60).toDimensionHexString(),
    Color(0xFF00897B).toDimensionHexString(),
    Color(0xFFFB8C00).toDimensionHexString(),
    Color(0xFF8E24AA).toDimensionHexString(),
    Color(0xFF039BE5).toDimensionHexString(),
    Color(0xFF5E35B1).toDimensionHexString(),
    Color(0xFF7CB342).toDimensionHexString(),
    Color(0xFFE53935).toDimensionHexString(),
    Color(0xFF546E7A).toDimensionHexString(),
    Color(0xFF3949AB).toDimensionHexString(),
    Color(0xFF00838F).toDimensionHexString(),
    Color(0xFF2E7D32).toDimensionHexString(),
    Color(0xFFC2185B).toDimensionHexString(),
    Color(0xFFEF6C00).toDimensionHexString(),
    Color(0xFF6A1B9A).toDimensionHexString(),
    Color(0xFF1565C0).toDimensionHexString(),
    Color(0xFFAD1457).toDimensionHexString(),
    Color(0xFF00695C).toDimensionHexString(),
    Color(0xFF7B1FA2).toDimensionHexString(),
    Color(0xFF0277BD).toDimensionHexString(),
    Color(0xFFF9A825).toDimensionHexString(),
    Color(0xFF558B2F).toDimensionHexString(),
    Color(0xFF8D6E63).toDimensionHexString(),
    Color(0xFF455A64).toDimensionHexString(),
)

internal fun colorFromHex(hex: String): Color {
    val normalized = hex.trim().removePrefix("#")
    return try {
        val colorValue = normalized.toLong(16)
        if (normalized.length <= 6) {
            Color((0xFF000000 or colorValue).toInt())
        } else {
            Color(colorValue.toInt())
        }
    } catch (_: Exception) {
        Color(0xFF757575)
    }
}

internal fun Color.toDimensionHexString(): String = "#%06X".format(0xFFFFFF and toArgb())
