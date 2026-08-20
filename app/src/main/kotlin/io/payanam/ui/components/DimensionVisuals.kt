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
    /** Label. */
    label: String,
    /** Color. */
    color: Color,
    /** Icon option. */
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    /** Compact icon tint. */
    val compactIconTint = if (color.luminance() > 0.45f) Color(0xFF111111) else Color.White
    /** Surface. */
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
        /** Box. */
        Box(contentAlignment = Alignment.Center) {
            /** Icon. */
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
    /** Label. */
    label: String,
    /** Color. */
    color: Color,
    /** Icon option. */
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 24.dp,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 1,
) {
    /** Row. */
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /** Dimension compact badge. */
        DimensionCompactBadge(
            label = label,
            color = color,
            iconOption = iconOption,
            size = badgeSize,
        )
        /** Text. */
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
    /** Prefs. */
    prefs: AppPreferencesState,
    dimensionId: String?,
    /** Fallback label. */
    fallbackLabel: String,
    /** Fallback color. */
    fallbackColor: Color,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    badgeSize: Dp = 24.dp,
    maxLines: Int = 1,
) {
    /** Icon option. */
    val iconOption = prefs.iconOptionForDimensionId(dimensionId)
        ?: remember(dimensionId) { DimensionIconCatalog.resolve(null, dimensionId) }
    /** Label. */
    val label = prefs.labelForDimensionId(dimensionId) ?: fallbackLabel
    /** Color. */
    val color = prefs.colorForDimensionId(dimensionId) ?: fallbackColor
    /** Dimension badge label row. */
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
    /** Label. */
    label: String,
    /** Color. */
    color: Color,
    /** Icon option. */
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    /** Compact icon tint. */
    val compactIconTint = if (color.luminance() > 0.45f) Color(0xFF111111) else Color.White
    /** Surface. */
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
        /** Box. */
        Box(contentAlignment = Alignment.Center) {
            /** Icon. */
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
    /** Label. */
    label: String,
    /** Color. */
    color: Color,
    /** Icon option. */
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 22.dp,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 1,
) {
    /** Row. */
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /** Dimension dropdown badge. */
        DimensionDropdownBadge(
            label = label,
            color = color,
            iconOption = iconOption,
            size = badgeSize,
        )
        /** Text. */
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
    /** Label. */
    label: String,
    /** Color. */
    color: Color,
    /** Icon option. */
    iconOption: DimensionIconOption,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 16.dp,
    dotSize: Dp = 8.dp,
    maxLines: Int = 1,
    showLabel: Boolean = true,
) {
    /** Compact badge size. */
    val compactBadgeSize = (iconSize + dotSize + 8.dp).coerceAtLeast(20.dp)
    /** Row. */
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (showLabel) 8.dp else 6.dp),
    ) {
        /** If. */
        if (showLabel) {
            /** Icon. */
            Icon(
                imageVector = iconOption.imageVector,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = iconTint,
            )
            /** Box. */
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .background(color, CircleShape),
            )
            /** Text. */
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            /** Dimension compact badge. */
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
    /** Icon option. */
    iconOption: DimensionIconOption,
    /** Tint. */
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 10.dp,
    columns: Int = 5,
    rows: Int = 3,
    alpha: Float = 0.16f,
    rotationDegrees: Float = 0f,
    animated: Boolean = false,
) {
    /** Transition. */
    val transition = rememberInfiniteTransition(label = "dimension-pattern")
    /** Drift. */
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
    /** Column. */
    Column(
        modifier = modifier.graphicsLayer {
            rotationZ = rotationDegrees
            translationX = drift
            translationY = drift * -0.65f
        },
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        /** Repeat. */
        repeat(rows) {
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /** Repeat. */
                repeat(columns) {
                    /** Icon. */
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
    /** Start xfraction. */
    val startXFraction: Float,
    /** End xfraction. */
    val endXFraction: Float,
    /** Start yfraction. */
    val startYFraction: Float,
    /** End yfraction. */
    val endYFraction: Float,
    /** Phase offset. */
    val phaseOffset: Float,
    /** Size factor. */
    val sizeFactor: Float,
    /** Alpha factor. */
    val alphaFactor: Float,
)

@Composable
internal fun DimensionIconCascadeLayer(
    /** Icon option. */
    iconOption: DimensionIconOption,
    /** Tint. */
    tint: Color,
    modifier: Modifier = Modifier,
    /** Seed key. */
    seedKey: String,
    iconCount: Int = 11,
    minIconSize: Dp = 8.dp,
    maxIconSize: Dp = 16.dp,
    alphaRange: ClosedFloatingPointRange<Float> = 0.14f..0.28f,
    animated: Boolean = true,
) {
    /** Box with constraints. */
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp)),
    ) {
        /** Transition. */
        val transition = rememberInfiniteTransition(label = "dimension-cascade")
        /** Target speed dp per second. */
        val targetSpeedDpPerSecond = 18f
        /** Area score. */
        val areaScore = (maxWidth.value * maxHeight.value) / 420f
        /** Resolved icon count. */
        val resolvedIconCount = areaScore.toInt().coerceIn(iconCount, iconCount * 4)
        /** Specs. */
        val specs = remember(seedKey, resolvedIconCount, alphaRange.start, alphaRange.endInclusive) {
            /** List. */
            List(resolvedIconCount) { index ->
                /** Random. */
                val random = Random("${seedKey}_$index".hashCode().absoluteValue)
                /** Start x. */
                val startX = random.nextFloat() * 0.86f + 0.02f
                /** End x. */
                val endX = (startX + (random.nextFloat() * 0.18f - 0.09f)).coerceIn(0.02f, 0.9f)
                /** Dimension cascade spec. */
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
            /** Horizontal travel dp. */
            val horizontalTravelDp = (maxWidth.value * (spec.endXFraction - spec.startXFraction))
            /** Vertical travel dp. */
            val verticalTravelDp = (maxHeight.value * (spec.endYFraction - spec.startYFraction))
            /** Travel distance dp. */
            val travelDistanceDp = sqrt((horizontalTravelDp * horizontalTravelDp) + (verticalTravelDp * verticalTravelDp))
            /** Duration millis. */
            val durationMillis = ((travelDistanceDp / targetSpeedDpPerSecond) * 1000f)
                .toInt()
                .coerceIn(3600, 22000)
            /** Base progress. */
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
            /** Progress. */
            val progress = (baseProgress + spec.phaseOffset) % 1f
            /** Icon size. */
            val iconSize = minIconSize + (maxIconSize - minIconSize) * spec.sizeFactor
            /** X fraction. */
            val xFraction = spec.startXFraction + ((spec.endXFraction - spec.startXFraction) * progress)
            /** Y fraction. */
            val yFraction = spec.startYFraction + ((spec.endYFraction - spec.startYFraction) * progress)
            /** Fade multiplier. */
            val fadeMultiplier = when {
                progress < 0.16f -> progress / 0.16f
                progress > 0.84f -> (1f - progress) / 0.16f
                else -> 1f
            }.coerceIn(0f, 1f)
            /** Live alpha. */
            val liveAlpha = (spec.alphaFactor * fadeMultiplier).coerceIn(0.03f, alphaRange.endInclusive)
            /** Icon. */
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
    /** Prefs. */
    prefs: AppPreferencesState,
    dimensionId: String?,
    /** Fallback label. */
    fallbackLabel: String,
    /** Fallback color. */
    fallbackColor: Color,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 16.dp,
    dotSize: Dp = 8.dp,
    maxLines: Int = 1,
    showLabel: Boolean = true,
) {
    /** Icon option. */
    val iconOption = prefs.iconOptionForDimensionId(dimensionId)
        ?: remember(dimensionId) { DimensionIconCatalog.resolve(null, dimensionId) }
    /** Label. */
    val label = prefs.labelForDimensionId(dimensionId) ?: fallbackLabel
    /** Color. */
    val color = prefs.colorForDimensionId(dimensionId) ?: fallbackColor
    /** Dimension identity row. */
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
    /** Selected color hex. */
    selectedColorHex: String,
    usedColorHexes: Set<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    /** Normalized selected. */
    val normalizedSelected = selectedColorHex.trim().uppercase()
    /** Column. */
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        /** Text. */
        Text(
            text = stringResource(id = R.string.db_init_dimension_setup_color_label),
            style = MaterialTheme.typography.labelLarge,
        )
        /** Outlined button. */
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            /** Box. */
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(colorFromHex(selectedColorHex), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
            /** Spacer. */
            Spacer(modifier = Modifier.width(12.dp))
            /** Text. */
            Text(
                text = stringResource(id = R.string.dimension_picker_choose_color),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        /** Text. */
        Text(
            text = stringResource(id = R.string.db_init_dimension_setup_color_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    /** If. */
    if (showDialog) {
        /** Alert dialog. */
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                /** Text. */
                Text(text = stringResource(id = R.string.db_init_dimension_setup_color_label))
            },
            text = {
                /** Lazy vertical grid. */
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 52.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    /** Items. */
                    items(DimensionColorHexOptions) { hex ->
                        /** Normalized hex. */
                        val normalizedHex = hex.trim().uppercase()
                        /** Is selected. */
                        val isSelected = normalizedHex == normalizedSelected
                        /** Is used. */
                        val isUsed = normalizedHex in usedColorHexes && !isSelected
                        /** Surface. */
                        Surface(
                            onClick = {
                                /** On select. */
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
                /** Outlined button. */
                OutlinedButton(onClick = { showDialog = false }) {
                    /** Text. */
                    Text(text = stringResource(id = R.string.settings_action_cancel))
                }
            },
        )
    }
}

@Composable
internal fun DimensionIconPicker(
    /** Selected icon key. */
    selectedIconKey: String,
    usedIconKeys: Set<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Column. */
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        /** Text. */
        Text(
            text = androidx.compose.ui.res.stringResource(id = R.string.db_init_dimension_setup_icon_label),
            style = MaterialTheme.typography.labelLarge,
        )
        /** Outlined button. */
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            /** Icon. */
            Icon(
                imageVector = DimensionIconCatalog.resolve(selectedIconKey).imageVector,
                contentDescription = null,
            )
            /** Spacer. */
            Spacer(modifier = Modifier.width(12.dp))
            /** Text. */
            Text(
                text = stringResource(id = R.string.dimension_picker_choose_icon),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        /** Text. */
        Text(
            text = stringResource(id = R.string.db_init_dimension_setup_icon_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    /** If. */
    if (showDialog) {
        /** Alert dialog. */
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                /** Text. */
                Text(text = stringResource(id = R.string.db_init_dimension_setup_icon_label))
            },
            text = {
                /** Lazy vertical grid. */
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 44.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    /** Items. */
                    items(DimensionIconCatalog.options) { option ->
                        /** Is selected. */
                        val isSelected = option.key == selectedIconKey
                        /** Is used. */
                        val isUsed = option.key in usedIconKeys && !isSelected
                        /** Box. */
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    /** If. */
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
                                        /** Map of. */
                                        mapOf("iconKey" to option.key),
                                    )
                                    /** On select. */
                                    onSelect(option.key)
                                    showDialog = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            /** Icon. */
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
                /** Outlined button. */
                OutlinedButton(onClick = { showDialog = false }) {
                    /** Text. */
                    Text(text = stringResource(id = R.string.settings_action_cancel))
                }
            },
        )
    }
}

internal val DimensionColorHexOptions = listOf(
    /** Color. */
    Color(0xFF3F51B5).toDimensionHexString(),
    /** Color. */
    Color(0xFF4CAF50).toDimensionHexString(),
    /** Color. */
    Color(0xFFE91E63).toDimensionHexString(),
    /** Color. */
    Color(0xFF009688).toDimensionHexString(),
    /** Color. */
    Color(0xFFFF9800).toDimensionHexString(),
    /** Color. */
    Color(0xFF9C27B0).toDimensionHexString(),
    /** Color. */
    Color(0xFF00BCD4).toDimensionHexString(),
    /** Color. */
    Color(0xFF673AB7).toDimensionHexString(),
    /** Color. */
    Color(0xFF8BC34A).toDimensionHexString(),
    /** Color. */
    Color(0xFF795548).toDimensionHexString(),
    /** Color. */
    Color(0xFFFF5722).toDimensionHexString(),
    /** Color. */
    Color(0xFF607D8B).toDimensionHexString(),
    /** Color. */
    Color(0xFF6D4C41).toDimensionHexString(),
    /** Color. */
    Color(0xFF1E88E5).toDimensionHexString(),
    /** Color. */
    Color(0xFF43A047).toDimensionHexString(),
    /** Color. */
    Color(0xFFD81B60).toDimensionHexString(),
    /** Color. */
    Color(0xFF00897B).toDimensionHexString(),
    /** Color. */
    Color(0xFFFB8C00).toDimensionHexString(),
    /** Color. */
    Color(0xFF8E24AA).toDimensionHexString(),
    /** Color. */
    Color(0xFF039BE5).toDimensionHexString(),
    /** Color. */
    Color(0xFF5E35B1).toDimensionHexString(),
    /** Color. */
    Color(0xFF7CB342).toDimensionHexString(),
    /** Color. */
    Color(0xFFE53935).toDimensionHexString(),
    /** Color. */
    Color(0xFF546E7A).toDimensionHexString(),
    /** Color. */
    Color(0xFF3949AB).toDimensionHexString(),
    /** Color. */
    Color(0xFF00838F).toDimensionHexString(),
    /** Color. */
    Color(0xFF2E7D32).toDimensionHexString(),
    /** Color. */
    Color(0xFFC2185B).toDimensionHexString(),
    /** Color. */
    Color(0xFFEF6C00).toDimensionHexString(),
    /** Color. */
    Color(0xFF6A1B9A).toDimensionHexString(),
    /** Color. */
    Color(0xFF1565C0).toDimensionHexString(),
    /** Color. */
    Color(0xFFAD1457).toDimensionHexString(),
    /** Color. */
    Color(0xFF00695C).toDimensionHexString(),
    /** Color. */
    Color(0xFF7B1FA2).toDimensionHexString(),
    /** Color. */
    Color(0xFF0277BD).toDimensionHexString(),
    /** Color. */
    Color(0xFFF9A825).toDimensionHexString(),
    /** Color. */
    Color(0xFF558B2F).toDimensionHexString(),
    /** Color. */
    Color(0xFF8D6E63).toDimensionHexString(),
    /** Color. */
    Color(0xFF455A64).toDimensionHexString(),
)

internal fun colorFromHex(hex: String): Color {
    /** Normalized. */
    val normalized = hex.trim().removePrefix("#")
    return try {
        /** Color value. */
        val colorValue = normalized.toLong(16)
        /** If. */
        if (normalized.length <= 6) {
            /** Color. */
            Color((0xFF000000 or colorValue).toInt())
        } else {
            /** Color. */
            Color(colorValue.toInt())
        }
    } catch (_: Exception) {
        /** Color. */
        Color(0xFF757575)
    }
}

internal fun Color.toDimensionHexString(): String = "#%06X".format(0xFFFFFF and toArgb())
