//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.DimensionTrendBlock
import io.payanam.ui.components.DimensionCompactBadge
import io.payanam.ui.components.DimensionIdentityRow
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.DimensionTrendState
import io.payanam.ui.viewmodel.DimensionTrendWindow
import io.payanam.ui.viewmodel.visibleDimensions
import java.time.format.DateTimeFormatter

private val logger = UnifiedLogger.getInstance()
private val stackedBarDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

@Composable
internal fun DimensionTrendSection(
    /** State. */
    state: DimensionTrendState,
    onWindowSelect: (DimensionTrendWindow) -> Unit,
    /** App prefs. */
    appPrefs: AppPreferencesState,
) {
    /** Launched effect. */
    LaunchedEffect(state.window, state.blocks.size) {
        logger.d(
            "DimensionTrendSection",
            "Rendered dimension trend section",
            /** Map of. */
            mapOf(
                "window" to state.window.name,
                "blockCount" to state.blocks.size,
                "isLoading" to state.isLoading,
            ),
        )
    }

    /** Column. */
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /** Text. */
        Text(
            text = stringResource(id = R.string.loc_lens_dim_trend_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        // Window chip row
        /** Row. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DimensionTrendWindow.entries.forEach { window ->
                /** Filter chip. */
                FilterChip(
                    selected = state.window == window,
                    onClick = { onWindowSelect(window) },
                    label = { Text(dimensionTrendWindowLabel(window)) },
                )
            }
        }

        /** If. */
        if (state.blocks.isEmpty()) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_dim_split_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Bar chart row - most recent (blocks[0]) leftmost
            /** Row. */
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                state.blocks.forEach { block ->
                    /** Column. */
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        /** Dimension trend badge. */
                        DimensionTrendBadge(block = block, appPrefs = appPrefs)
                        /** Dimension trend bar. */
                        DimensionTrendBar(
                            block = block,
                            appPrefs = appPrefs,
                        )
                        /** Spacer. */
                        Spacer(modifier = Modifier.height(2.dp))
                        /** Text. */
                        Text(
                            text = dimensionTrendBarLabel(block, state.window),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            // Legend
            /** Dimension trend legend. */
            DimensionTrendLegend(appPrefs = appPrefs)
        }
    }
}

@Composable
private fun DimensionTrendBadge(
    /** Block. */
    block: DimensionTrendBlock,
    /** App prefs. */
    appPrefs: AppPreferencesState,
) {
    /** Dominant dimension id. */
    val dominantDimensionId = remember(block.byDimension) {
        block.byDimension
            .filterKeys { it != null }
            .maxByOrNull { it.value }
            ?.key
    }
    /** Dominant dimension. */
    val dominantDimension = appPrefs.visibleDimensions().firstOrNull { it.id == dominantDimensionId }
    /** Badge label. */
    val badgeLabel = dominantDimension?.label ?: stringResource(id = R.string.loc_dimension_fallback_unassigned)
    /** Badge color. */
    val badgeColor = dominantDimension?.color ?: Color(0xFF9E9E9E)
    /** Badge icon. */
    val badgeIcon = DimensionIconCatalog.resolve(dominantDimension?.iconKey, dominantDimensionId)

    /** Dimension compact badge. */
    DimensionCompactBadge(
        label = badgeLabel,
        color = badgeColor,
        iconOption = badgeIcon,
        size = 22.dp,
    )
}

@Composable
private fun DimensionTrendBar(
    /** Block. */
    block: DimensionTrendBlock,
    /** App prefs. */
    appPrefs: AppPreferencesState,
) {
    /** Visible dimensions. */
    val visibleDimensions = appPrefs.visibleDimensions()
    /** Untracked color. */
    val untrackedColor = Color(0xFF9E9E9E)
    /** Untracked alpha. */
    val untrackedAlpha = 0.35f
    /** Total possible. */
    val totalPossible = block.totalPossibleMinutes.toFloat().coerceAtLeast(1f)
    /** Tracked total. */
    val trackedTotal = block.byDimension.values.sum().toFloat().coerceAtLeast(0f)
    /** Untracked minutes. */
    val untrackedMinutes = (block.totalPossibleMinutes - trackedTotal).coerceAtLeast(0f)

    /** Canvas. */
    Canvas(
        modifier = Modifier
            .width(28.dp)
            .height(500.dp),
    ) {
        /** Bar height. */
        val barHeight = size.height
        /** Current y. */
        var currentY = 0f

        // Untracked at top
        /** Untracked h. */
        val untrackedH = ((untrackedMinutes / totalPossible) * barHeight).coerceAtLeast(0f)
        /** If. */
        if (untrackedH > 0f) {
            /** Draw rect. */
            drawRect(
                color = untrackedColor.copy(alpha = untrackedAlpha),
                topLeft = Offset(0f, currentY),
                size = Size(size.width, untrackedH),
            )
            currentY += untrackedH
        }

        // Dimension segments in preference order
        visibleDimensions.forEach { dimPref ->
            /** Dim id. */
            val dimId = dimPref.id
            /** Minutes. */
            val minutes = block.byDimension[dimId]?.toFloat() ?: 0f
            /** If. */
            if (minutes <= 0f) return@forEach
            /** Seg h. */
            val segH = ((minutes / totalPossible) * barHeight).coerceAtLeast(1f)
            /** Draw rect. */
            drawRect(
                color = dimPref.color,
                topLeft = Offset(0f, currentY),
                size = Size(size.width, segH),
            )
            currentY += segH
        }

        // Null/unassigned dimension
        /** Null minutes. */
        val nullMinutes = block.byDimension[null]?.toFloat() ?: 0f
        /** If. */
        if (nullMinutes > 0f) {
            /** Seg h. */
            val segH = ((nullMinutes / totalPossible) * barHeight).coerceAtLeast(1f)
            /** Draw rect. */
            drawRect(
                color = untrackedColor,
                topLeft = Offset(0f, currentY),
                size = Size(size.width, segH),
            )
        }
    }
}

@Composable
private fun DimensionTrendLegend(appPrefs: AppPreferencesState) {
    /** Visible dimensions. */
    val visibleDimensions = appPrefs.visibleDimensions()
    /** Untracked color. */
    val untrackedColor = Color(0xFF9E9E9E)

    /** Row. */
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        visibleDimensions.forEach { dimPref ->
            /** Dimension identity row. */
            DimensionIdentityRow(
                label = dimPref.label,
                color = dimPref.color,
                iconOption = DimensionIconCatalog.resolve(dimPref.iconKey, dimPref.id),
                iconSize = 12.dp,
                dotSize = 6.dp,
                labelColor = MaterialTheme.colorScheme.onSurface,
            )
        }
        // Unassigned
        /** Dimension identity row. */
        DimensionIdentityRow(
            label = stringResource(id = R.string.loc_dimension_fallback_unassigned),
            color = untrackedColor,
            iconOption = DimensionIconCatalog.resolve(null, null),
            iconSize = 12.dp,
            dotSize = 6.dp,
            labelColor = MaterialTheme.colorScheme.onSurface,
        )
        // Untracked
        /** Row. */
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            /** Box. */
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(untrackedColor.copy(alpha = 0.35f)),
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_untracked),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun dimensionTrendWindowLabel(window: DimensionTrendWindow): String = when (window) {
    DimensionTrendWindow.W1 -> stringResource(id = R.string.loc_lens_dim_split_chip_w1)
    DimensionTrendWindow.W7 -> stringResource(id = R.string.loc_7_days)
    DimensionTrendWindow.W30 -> stringResource(id = R.string.loc_30_days)
    DimensionTrendWindow.W90 -> stringResource(id = R.string.loc_90_days)
    DimensionTrendWindow.W180 -> stringResource(id = R.string.loc_lens_dim_split_chip_w180)
    DimensionTrendWindow.W365 -> stringResource(id = R.string.loc_lens_dim_split_chip_w365)
}

private fun dimensionTrendBarLabel(block: DimensionTrendBlock, window: DimensionTrendWindow): String = when (window) {
    DimensionTrendWindow.W1 -> block.endDate.format(stackedBarDateFormatter)
    else -> block.startDate.format(stackedBarDateFormatter)
}
