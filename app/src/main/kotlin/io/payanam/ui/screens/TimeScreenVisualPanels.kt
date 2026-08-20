//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.theme.rememberInsightsVisualTokens
import io.payanam.ui.viewmodel.DimensionPreference
import io.payanam.ui.viewmodel.TimeDayOverallSummary
import io.payanam.ui.viewmodel.TimeDimensionDaySummary
import io.payanam.ui.viewmodel.TimeTrendStripSummary
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.absoluteValue

@Composable
internal fun TimeDayOverallPanel(
    /** Summary. */
    summary: TimeDayOverallSummary,
) {
    /** Tokens. */
    val tokens = rememberInsightsVisualTokens()
    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardContainer),
    ) {
        /** Column. */
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                /** Time metric chip. */
                TimeMetricChip(
                    label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_tracked_time),
                    value = formatDuration(summary.trackedMinutes),
                    modifier = Modifier.weight(1f),
                )
                /** Time metric chip. */
                TimeMetricChip(
                    label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_untracked),
                    value = formatDuration(summary.untrackedMinutesEstimate),
                    modifier = Modifier.weight(1f),
                )
            }
            /** If. */
            if (shouldShowTimelineQualityCues(summary)) {
                /** Timeline quality cue row. */
                TimelineQualityCueRow(summary = summary)
            }
        }
    }
}

@Composable
internal fun TimeTrendStripPanel(
    /** Trend. */
    trend: TimeTrendStripSummary,
) {
    /** Max. */
    val max = maxOf(trend.selectedDayMinutes, trend.previousDayMinutes, trend.last7AverageMinutes).coerceAtLeast(1L)
    /** Tokens. */
    val tokens = rememberInsightsVisualTokens()
    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardContainer),
    ) {
        /** Row. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            /** Trend bar. */
            TrendBar(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_selected_day), trend.selectedDayMinutes, max)
            /** Trend bar. */
            TrendBar(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_previous_day), trend.previousDayMinutes, max)
            /** Trend bar. */
            TrendBar(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_last_7_days_avg), trend.last7AverageMinutes, max)
        }
    }
}

@Composable
private fun TrendBar(label: String, value: Long, max: Long) {
    /** Tokens. */
    val tokens = rememberInsightsVisualTokens()
    /** Column. */
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        /** Box. */
        Box(
            modifier = Modifier
                .width(18.dp)
                .height((58f * (value.toFloat() / max.toFloat()).coerceIn(0.05f, 1f)).dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.chartPrimary),
        )
        /** Spacer. */
        Spacer(modifier = Modifier.height(2.dp))
        /** Text. */
        Text(text = formatDuration(value), style = MaterialTheme.typography.labelSmall)
        /** Text. */
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TimePerDimensionPanel(
    rows: List<TimeDimensionDaySummary>,
    selectedDimensionId: String?,
    onSelectDimension: (String) -> Unit,
) {
    /** If. */
    if (rows.isEmpty()) return
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Tokens. */
    val tokens = rememberInsightsVisualTokens()
    /** Item container color. */
    val itemContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    /** Selected item container color. */
    val selectedItemContainerColor = MaterialTheme.colorScheme.secondaryContainer
    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardContainer),
    ) {
        /** Column. */
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            /** Text. */
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_per_dimension),
                style = MaterialTheme.typography.titleSmall,
            )
            /** Time per dimension segments chart. */
            TimePerDimensionSegmentsChart(rows = rows)
            rows.forEach { row ->
                /** Is selected. */
                val isSelected = selectedDimensionId == row.dimensionId
                /** Row. */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) selectedItemContainerColor else itemContainerColor)
                        .clickable {
                            logger.d(
                                "TimeScreenVisualPanels.TimePerDimensionPanel",
                                "Toggled per-dimension drill-in filter",
                                /** Map of. */
                                mapOf("dimensionId" to row.dimensionId, "selected" to (!isSelected).toString()),
                            )
                            /** On select dimension. */
                            onSelectDimension(row.dimensionId)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        /** Text. */
                        Text(text = displayDimensionLabel(row.dimensionLabel), style = MaterialTheme.typography.bodyMedium)
                        /** Text. */
                        Text(
                            text = "${(row.sharePercent * 100).toInt()}% • ${row.blockCount} ${androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_blocks)} • " +
                                androidx.compose.ui.res.stringResource(
                                    id = io.payanam.R.string.loc_focused_minutes_value,
                                    /** Format duration. */
                                    formatDuration(row.focusedMinutes),
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    /** Column. */
                    Column(horizontalAlignment = Alignment.End) {
                        /** Text. */
                        Text(text = formatDuration(row.trackedMinutes), style = MaterialTheme.typography.bodyMedium)
                        /** Delta. */
                        val delta = row.plannedDeltaMinutes
                        /** Delta prefix. */
                        val deltaPrefix = if (delta >= 0) "+" else "-"
                        /** Text. */
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                id = io.payanam.R.string.loc_plan_vs_actual_line,
                                /** Format duration. */
                                formatDuration(row.plannedMinutes.toLong()),
                                "$deltaPrefix${formatDuration(delta.absoluteValue)}",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeMetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    /** Column. */
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        /** Text. */
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        /** Text. */
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TimePerDimensionSegmentsChart(rows: List<TimeDimensionDaySummary>) {
    /** Total. */
    val total = rows.sumOf { it.trackedMinutes }.coerceAtLeast(1L)
    /** Row. */
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        rows.forEachIndexed { index, row ->
            /** Width weight. */
            val widthWeight = (row.trackedMinutes.toFloat() / total.toFloat()).coerceIn(0.05f, 1f)
            /** Color. */
            val color = dimensionChartColor(index)
            /** Box. */
            Box(
                modifier = Modifier
                    .weight(widthWeight)
                    .height(16.dp)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                /** If. */
                if (widthWeight >= 0.17f) {
                    /** Text. */
                    Text(
                        text = "${(row.sharePercent * 100).toInt()}%",
                        color = contentColorFor(color),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineQualityCueRow(summary: TimeDayOverallSummary) {
    /** Tokens. */
    val tokens = rememberInsightsVisualTokens()
    /** Column. */
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        /** Text. */
        Text(
            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_timeline_quality_cues),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Row. */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /** Quality cue chip. */
            QualityCueChip(
                label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_gaps),
                value = summary.gapCount,
                color = tokens.qualityGap,
            )
            /** Quality cue chip. */
            QualityCueChip(
                label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_overlaps),
                value = summary.overlapCount,
                color = tokens.qualityOverlap,
            )
        }
    }
}

@Composable
private fun QualityCueChip(label: String, value: Int, color: Color) {
    /** Display value. */
    val displayValue = if (value > 0) value.toString() else androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_none)
    /** Row. */
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /** Box. */
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        /** Text. */
        Text(
            text = "$label: $displayValue",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun displayDimensionLabel(label: String): String = if (label.isBlank()) {
    androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_dimension_fallback_unassigned)
} else {
    /** Label. */
    label
}

private fun dimensionChartColor(index: Int): Color = when (index % 6) {
    0 -> Color(0xFF1B5E20)
    1 -> Color(0xFF0D47A1)
    2 -> Color(0xFFB71C1C)
    3 -> Color(0xFF4A148C)
    4 -> Color(0xFFE65100)
    else -> Color(0xFF37474F)
}

internal fun shortDimensionLabel(label: String): String {
    /** Words. */
    val words = Regex("[\\p{L}\\p{M}]+").findAll(label).map { it.value }.toList()
    return when {
        words.size >= 2 -> (firstGrapheme(words[0]) + firstGrapheme(words[1])).uppercase(Locale.ROOT)
        words.size == 1 -> firstNGraphemes(words[0], 2).uppercase(Locale.ROOT)
        else -> "--"
    }
}

private fun firstGrapheme(text: String): String = firstNGraphemes(text, 1)

private fun firstNGraphemes(text: String, count: Int): String {
    /** If. */
    if (text.isBlank() || count <= 0) return ""
    /** Iterator. */
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    /** Builder. */
    val builder = StringBuilder()
    /** Start. */
    var start = iterator.first()
    /** End. */
    var end = iterator.next()
    /** Taken. */
    var taken = 0
    /** While. */
    while (end != BreakIterator.DONE && taken < count) {
        builder.append(text.substring(start, end))
        taken++
        start = end
        end = iterator.next()
    }
    return builder.toString()
}

private fun formatSignedDuration(minutes: Long): String {
    /** If. */
    if (minutes == 0L) return "0m"
    /** Sign. */
    val sign = if (minutes > 0) "+" else "-"
    return sign + formatDuration(minutes.absoluteValue)
}

internal fun shouldShowTimelineQualityCues(summary: TimeDayOverallSummary): Boolean = summary.gapCount > 0 || summary.overlapCount > 0

@Composable
internal fun TimeDimensionCompactOverviewPanel(
    rows: List<TimeDimensionDaySummary>,
    visibleDimensions: List<DimensionPreference>,
    selectedDimensionId: String?,
    onSelectDimension: (String) -> Unit,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Tokens. */
    val tokens = rememberInsightsVisualTokens()
    /** Rows by id. */
    val rowsById = remember(rows) { rows.associateBy { it.dimensionId } }
    /** Display rows. */
    val displayRows = remember(visibleDimensions, rowsById) {
        visibleDimensions.take(9).map { preference ->
            /** Row. */
            val row = rowsById[preference.id]
            /** Compact dimension tile. */
            CompactDimensionTile(
                dimensionId = preference.id,
                label = preference.label,
                trackedMinutes = row?.trackedMinutes ?: 0L,
                plannedMinutes = row?.plannedMinutes?.toLong() ?: 0L,
                deltaMinutes = row?.plannedDeltaMinutes ?: 0L,
                barColor = preference.color,
            )
        }
    }
    /** Padded rows. */
    val paddedRows = remember(displayRows) {
        /** If. */
        if (displayRows.size == 9) {
            displayRows + CompactDimensionTile(
                dimensionId = "spacer",
                label = "",
                trackedMinutes = 0L,
                plannedMinutes = 0L,
                deltaMinutes = 0L,
                barColor = Color.Transparent,
                isSpacer = true,
            )
        } else {
            /** Display rows. */
            displayRows
        }
    }
    /** If. */
    if (paddedRows.isEmpty()) return

    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardContainer),
    ) {
        /** Column. */
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            /** Text. */
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_per_dimension),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            /** Padded rows. */
            paddedRows
                .chunked(5)
                .forEach { rowTiles ->
                    /** Row. */
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rowTiles.forEach { tile ->
                            /** If. */
                            if (tile.isSpacer) {
                                /** Box. */
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)),
                                )
                            } else {
                                /** Compact dimension grid tile. */
                                CompactDimensionGridTile(
                                    tile = tile,
                                    isSelected = selectedDimensionId == tile.dimensionId,
                                    onClick = {
                                        logger.d(
                                            "TimeScreenVisualPanels.TimeDimensionCompactOverviewPanel",
                                            "Toggled compact dimension filter",
                                            /** Map of. */
                                            mapOf("dimensionId" to tile.dimensionId, "selected" to (selectedDimensionId != tile.dimensionId).toString()),
                                        )
                                        /** On select dimension. */
                                        onSelectDimension(tile.dimensionId)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        /** Repeat. */
                        repeat(5 - rowTiles.size) {
                            /** Spacer. */
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
        }
    }
}

@Composable
private fun CompactDimensionGridTile(
    /** Tile. */
    tile: CompactDimensionTile,
    /** Is selected. */
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /** Safe planned. */
    val safePlanned = tile.plannedMinutes.coerceAtLeast(0L)
    /** Safe tracked. */
    val safeTracked = tile.trackedMinutes.coerceAtLeast(0L)
    /** Progress. */
    val progress = when {
        safePlanned > 0L -> (safeTracked.toFloat() / safePlanned.toFloat()).coerceIn(0f, 1f)
        safeTracked > 0L -> 1f
        else -> 0f
    }
    /** Is over planned. */
    val isOverPlanned = safePlanned > 0L && safeTracked > safePlanned
    /** Delta text. */
    val deltaText = formatSignedDuration(tile.deltaMinutes)
    /** Short label. */
    val shortLabel = shortDimensionLabel(tile.label)
    /** Column. */
    Column(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                /** If. */
                if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        /** Row. */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /** Text. */
            Text(
                text = shortLabel,
                style = MaterialTheme.typography.labelSmall,
                color = tile.barColor,
            )
            /** Text. */
            Text(
                text = deltaText,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    tile.deltaMinutes > 0 -> MaterialTheme.colorScheme.primary
                    tile.deltaMinutes < 0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        /** Box. */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(tile.barColor.copy(alpha = 0.20f)),
        ) {
            /** Box. */
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tile.barColor),
            )
            /** If. */
            if (isOverPlanned) {
                /** Box. */
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(4.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
    }
}

private data class CompactDimensionTile(
    /** Dimension id. */
    val dimensionId: String,
    /** Label. */
    val label: String,
    /** Tracked minutes. */
    val trackedMinutes: Long,
    /** Planned minutes. */
    val plannedMinutes: Long,
    /** Delta minutes. */
    val deltaMinutes: Long,
    /** Bar color. */
    val barColor: Color,
    /** Is spacer. */
    val isSpacer: Boolean = false,
)

internal fun formatDuration(minutes: Long): String = when {
    minutes < 60 -> "${minutes}m"
    minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
}
