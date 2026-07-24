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
import androidx.compose.foundation.layout.padding
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
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.repository.DimensionTrendBlock
import io.payanam.ui.components.DimensionCompactBadge
import io.payanam.ui.components.DimensionIdentityRow
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.DimensionTrendState
import io.payanam.ui.viewmodel.DimensionTrendWindow
import io.payanam.ui.viewmodel.colorFor
import io.payanam.ui.viewmodel.iconKeyForDimensionId
import io.payanam.ui.viewmodel.labelFor
import io.payanam.ui.viewmodel.visibleDimensions
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = UnifiedLogger.getInstance()
private val stackedBarDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

@Composable
internal fun DimensionTrendSection(
    state: DimensionTrendState,
    onWindowSelect: (DimensionTrendWindow) -> Unit,
    appPrefs: AppPreferencesState,
) {
    LaunchedEffect(state.window, state.blocks.size) {
        logger.d(
            "DimensionTrendSection",
            "Rendered dimension trend section",
            mapOf(
                "window" to state.window.name,
                "blockCount" to state.blocks.size,
                "isLoading" to state.isLoading,
            ),
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(id = R.string.loc_lens_dim_trend_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        // Window chip row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DimensionTrendWindow.entries.forEach { window ->
                FilterChip(
                    selected = state.window == window,
                    onClick = { onWindowSelect(window) },
                    label = { Text(dimensionTrendWindowLabel(window)) },
                )
            }
        }

        if (state.blocks.isEmpty()) {
            Text(
                text = stringResource(id = R.string.loc_lens_dim_split_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Bar chart row - most recent (blocks[0]) leftmost
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                state.blocks.forEach { block ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DimensionTrendBadge(block = block, appPrefs = appPrefs)
                        DimensionTrendBar(
                            block = block,
                            appPrefs = appPrefs,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = dimensionTrendBarLabel(block, state.window),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            // Legend
            DimensionTrendLegend(appPrefs = appPrefs)
        }
    }
}

@Composable
private fun DimensionTrendBadge(
    block: DimensionTrendBlock,
    appPrefs: AppPreferencesState,
) {
    val dominantDimensionId = remember(block.byDimension) {
        block.byDimension
            .filterKeys { it != null }
            .maxByOrNull { it.value }
            ?.key
    }
    val dominantDimension = appPrefs.visibleDimensions().firstOrNull { it.id == dominantDimensionId }
    val badgeLabel = dominantDimension?.label ?: stringResource(id = R.string.loc_dimension_fallback_unassigned)
    val badgeColor = dominantDimension?.color ?: Color(0xFF9E9E9E)
    val badgeIcon = DimensionIconCatalog.resolve(dominantDimension?.iconKey, dominantDimensionId)

    DimensionCompactBadge(
        label = badgeLabel,
        color = badgeColor,
        iconOption = badgeIcon,
        size = 22.dp,
    )
}

@Composable
private fun DimensionTrendBar(
    block: DimensionTrendBlock,
    appPrefs: AppPreferencesState,
) {
    val visibleDimensions = appPrefs.visibleDimensions()
    val untrackedColor = Color(0xFF9E9E9E)
    val untrackedAlpha = 0.35f
    val totalPossible = block.totalPossibleMinutes.toFloat().coerceAtLeast(1f)
    val trackedTotal = block.byDimension.values.sum().toFloat().coerceAtLeast(0f)
    val untrackedMinutes = (block.totalPossibleMinutes - trackedTotal).coerceAtLeast(0f)

    Canvas(
        modifier = Modifier
            .width(28.dp)
            .height(500.dp),
    ) {
        val barHeight = size.height
        var currentY = 0f

        // Untracked at top
        val untrackedH = ((untrackedMinutes / totalPossible) * barHeight).coerceAtLeast(0f)
        if (untrackedH > 0f) {
            drawRect(
                color = untrackedColor.copy(alpha = untrackedAlpha),
                topLeft = Offset(0f, currentY),
                size = Size(size.width, untrackedH),
            )
            currentY += untrackedH
        }

        // Dimension segments in preference order
        visibleDimensions.forEach { dimPref ->
            val dimId = dimPref.id
            val minutes = block.byDimension[dimId]?.toFloat() ?: 0f
            if (minutes <= 0f) return@forEach
            val segH = ((minutes / totalPossible) * barHeight).coerceAtLeast(1f)
            drawRect(
                color = dimPref.color,
                topLeft = Offset(0f, currentY),
                size = Size(size.width, segH),
            )
            currentY += segH
        }

        // Null/unassigned dimension
        val nullMinutes = block.byDimension[null]?.toFloat() ?: 0f
        if (nullMinutes > 0f) {
            val segH = ((nullMinutes / totalPossible) * barHeight).coerceAtLeast(1f)
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
    val visibleDimensions = appPrefs.visibleDimensions()
    val untrackedColor = Color(0xFF9E9E9E)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        visibleDimensions.forEach { dimPref ->
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
        DimensionIdentityRow(
            label = stringResource(id = R.string.loc_dimension_fallback_unassigned),
            color = untrackedColor,
            iconOption = DimensionIconCatalog.resolve(null, null),
            iconSize = 12.dp,
            dotSize = 6.dp,
            labelColor = MaterialTheme.colorScheme.onSurface,
        )
        // Untracked
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(untrackedColor.copy(alpha = 0.35f)),
            )
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
