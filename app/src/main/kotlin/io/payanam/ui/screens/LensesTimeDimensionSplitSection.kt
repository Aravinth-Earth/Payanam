//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.components.DimensionBadgeLabelRow
import io.payanam.ui.components.DimensionCompactBadge
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.DimensionSplitState
import io.payanam.ui.viewmodel.DimensionSplitWindow
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.colorForDimensionId
import io.payanam.ui.viewmodel.iconKeyForDimensionId
import io.payanam.ui.viewmodel.labelForDimensionId
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = UnifiedLogger.getInstance()
private val MONTH_DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val DONUT_UNASSIGNED_COLOR = Color(0xFF9E9E9E)

@Composable
internal fun DimensionSplitSection(
    state: DimensionSplitState,
    onWindowSelect: (DimensionSplitWindow) -> Unit,
    onShiftLeft: () -> Unit,
    onShiftRight: () -> Unit,
) {
    val appPrefs = LocalAppPreferences.current

    LaunchedEffect(state.window, state.windowOffset, state.totalMinutes) {
        logger.d(
            "DimensionSplitSection",
            "Rendering dimension split",
            mapOf(
                "window" to state.window.name,
                "offset" to state.windowOffset,
                "totalMinutes" to state.totalMinutes,
                "dimensions" to state.byDimension.size,
            ),
        )
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(id = R.string.loc_lens_dim_split_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        // Window selector chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DimensionSplitWindow.entries.forEach { window ->
                val isSelected = state.window == window
                val label = windowChipLabel(window)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .clickable { onWindowSelect(window) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        // Date range label with optional shift arrows
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = onShiftLeft,
                enabled = state.canShiftLeft,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = if (state.canShiftLeft) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = rangeLabelFor(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (state.isClamped && state.requestedDays > 0) {
                    Text(
                        text = stringResource(
                            id = R.string.loc_lens_dim_split_clamped_info,
                            state.clampedDays,
                            state.requestedDays,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            IconButton(
                onClick = onShiftRight,
                enabled = state.canShiftRight,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (state.canShiftRight) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                )
            }
        }

        if (state.totalMinutes == 0 && !state.isLoading) {
            Text(
                text = stringResource(id = R.string.loc_lens_dim_split_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        // Build ordered slices: assigned dims sorted desc, then unassigned
        val slices = buildSlices(state, appPrefs)

        // Donut chart
        DimensionDonutChart(slices = slices, totalMinutes = state.totalMinutes)

        Spacer(modifier = Modifier.height(4.dp))

        // Legend
        slices.forEach { slice ->
            DimensionLegendRow(slice = slice, totalMinutes = state.totalMinutes)
        }
    }
}

private data class DimensionSlice(
    val dimensionId: String?,
    val iconKey: String,
    val label: String,
    val color: Color,
    val minutes: Int,
)

private fun buildSlices(state: DimensionSplitState, appPrefs: AppPreferencesState): List<DimensionSlice> {
    val assigned = state.byDimension
        .filterKeys { it != null }
        .entries
        .sortedByDescending { it.value }
        .map { (id, minutes) ->
            val label = appPrefs.labelForDimensionId(id) ?: id ?: ""
            val color = appPrefs.colorForDimensionId(id) ?: DONUT_UNASSIGNED_COLOR
            DimensionSlice(
                dimensionId = id,
                iconKey = appPrefs.iconKeyForDimensionId(id) ?: DimensionIconCatalog.defaultIconKeyForDimensionId(id),
                label = label,
                color = color,
                minutes = minutes,
            )
        }
    val unassignedMinutes = state.byDimension[null] ?: 0
    val unassignedSlice = DimensionSlice(
        dimensionId = null,
        iconKey = DimensionIconCatalog.defaultIconKeyForDimensionId(null),
        label = "", // filled by composable via stringResource
        color = DONUT_UNASSIGNED_COLOR,
        minutes = unassignedMinutes,
    )
    return assigned + unassignedSlice
}

@Composable
private fun DimensionDonutChart(slices: List<DimensionSlice>, totalMinutes: Int) {
    val unassignedLabel = stringResource(id = R.string.loc_dimension_fallback_unassigned)
    val resolvedSlices = remember(slices, unassignedLabel) {
        slices.map { if (it.label.isEmpty()) it.copy(label = unassignedLabel) else it }
    }
    val sweepAngles = remember(resolvedSlices, totalMinutes) {
        if (totalMinutes == 0) {
            emptyList()
        } else {
            resolvedSlices.map { slice -> (slice.minutes.toFloat() / totalMinutes) * 360f }
        }
    }
    val centerLabel = remember(totalMinutes) { formatMinutes(totalMinutes) }
    val dominantSlice = remember(resolvedSlices) {
        resolvedSlices.filter { it.dimensionId != null }
            .maxByOrNull { it.minutes }
            ?: resolvedSlices.firstOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(160.dp)) {
            val strokeWidth = 28.dp.toPx()
            val inset = strokeWidth / 2f
            val arcRect = androidx.compose.ui.geometry.Rect(inset, inset, size.width - inset, size.height - inset)
            var startAngle = -90f
            val gapAngle = if (resolvedSlices.size > 1) 1.5f else 0f
            sweepAngles.forEachIndexed { index, sweep ->
                val effectiveSweep = (sweep - gapAngle).coerceAtLeast(0.5f)
                drawArc(
                    color = resolvedSlices[index].color,
                    startAngle = startAngle,
                    sweepAngle = effectiveSweep,
                    useCenter = false,
                    topLeft = arcRect.topLeft,
                    size = arcRect.size,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            dominantSlice?.let { slice ->
                DimensionCompactBadge(
                    label = slice.label,
                    color = slice.color,
                    iconOption = DimensionIconCatalog.resolve(slice.iconKey, slice.dimensionId),
                    size = 28.dp,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DimensionLegendRow(slice: DimensionSlice, totalMinutes: Int) {
    val unassignedLabel = stringResource(id = R.string.loc_dimension_fallback_unassigned)
    val label = slice.label.ifEmpty { unassignedLabel }
    val pct = if (totalMinutes > 0) ((slice.minutes.toFloat() / totalMinutes) * 100).toInt() else 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DimensionBadgeLabelRow(
            label = label,
            color = slice.color,
            iconOption = DimensionIconCatalog.resolve(slice.iconKey, slice.dimensionId),
            modifier = Modifier.weight(1f),
            labelColor = MaterialTheme.colorScheme.onSurface,
            badgeSize = 24.dp,
        )
        Text(
            text = formatMinutes(slice.minutes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$pct%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun windowChipLabel(window: DimensionSplitWindow): String = when (window) {
    DimensionSplitWindow.W1 -> stringResource(id = R.string.loc_lens_dim_split_chip_w1)
    DimensionSplitWindow.W7 -> stringResource(id = R.string.loc_7_days)
    DimensionSplitWindow.W30 -> stringResource(id = R.string.loc_30_days)
    DimensionSplitWindow.W90 -> stringResource(id = R.string.loc_90_days)
    DimensionSplitWindow.W180 -> stringResource(id = R.string.loc_lens_dim_split_chip_w180)
    DimensionSplitWindow.W365 -> stringResource(id = R.string.loc_lens_dim_split_chip_w365)
    DimensionSplitWindow.ALL -> stringResource(id = R.string.loc_all_time)
}

private fun rangeLabelFor(state: DimensionSplitState): String {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    return when (state.window) {
        DimensionSplitWindow.W1 -> when (state.effectiveEnd) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> state.effectiveEnd.format(MONTH_DAY_FMT)
        }

        DimensionSplitWindow.ALL -> "${state.effectiveStart.format(MONTH_DAY_FMT)} – ${state.effectiveEnd.format(MONTH_DAY_FMT)}"

        else -> "${state.effectiveStart.format(MONTH_DAY_FMT)} – ${state.effectiveEnd.format(MONTH_DAY_FMT)}"
    }
}
