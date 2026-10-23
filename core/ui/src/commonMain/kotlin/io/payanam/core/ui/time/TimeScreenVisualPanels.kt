//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.time

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
import io.payanam.presentation.time.TimeTrendStripSummary
import io.payanam.presentation.time.TimeDimensionDaySummary
import io.payanam.presentation.time.TimeDayOverallSummary
import io.payanam.core.ui.theme.rememberInsightsVisualTokens
import io.payanam.core.ui.preferences.DimensionPreference
import java.util.Locale
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger
import kotlin.math.absoluteValue

@Composable
fun TimeDayOverallPanel(
    summary: TimeDayOverallSummary,
) {
    val strings = LocalUiStrings.current
    val tokens = rememberInsightsVisualTokens()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimeMetricChip(
                    label = strings.locTrackedTime,
                    value = formatDuration(summary.trackedMinutes),
                    modifier = Modifier.weight(1f),
                )
                TimeMetricChip(
                    label = strings.locUntracked,
                    value = formatDuration(summary.untrackedMinutesEstimate),
                    modifier = Modifier.weight(1f),
                )
            }
            if (shouldShowTimelineQualityCues(summary)) {
                TimelineQualityCueRow(summary = summary)
            }
        }
    }
}

@Composable
fun TimeTrendStripPanel(
    trend: TimeTrendStripSummary,
) {
    val strings = LocalUiStrings.current
    val max = maxOf(trend.selectedDayMinutes, trend.previousDayMinutes, trend.last7AverageMinutes).coerceAtLeast(1L)
    val tokens = rememberInsightsVisualTokens()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TrendBar(strings.locSelectedDay, trend.selectedDayMinutes, max)
            TrendBar(strings.locPreviousDay, trend.previousDayMinutes, max)
            TrendBar(strings.locLast7DaysAvg, trend.last7AverageMinutes, max)
        }
    }
}

@Composable
private fun TrendBar(label: String, value: Long, max: Long) {
    val tokens = rememberInsightsVisualTokens()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height((58f * (value.toFloat() / max.toFloat()).coerceIn(0.05f, 1f)).dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.chartPrimary),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = formatDuration(value), style = MaterialTheme.typography.labelSmall)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TimePerDimensionPanel(
    rows: List<TimeDimensionDaySummary>,
    selectedDimensionId: String?,
    onSelectDimension: (String) -> Unit,
) {
    val strings = LocalUiStrings.current
    if (rows.isEmpty()) return
    val logger = remember { AppLogger.current }
    val tokens = rememberInsightsVisualTokens()
    val itemContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val selectedItemContainerColor = MaterialTheme.colorScheme.secondaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = strings.locPerDimension,
                style = MaterialTheme.typography.titleSmall,
            )
            TimePerDimensionSegmentsChart(rows = rows)
            rows.forEach { row ->
                val isSelected = selectedDimensionId == row.dimensionId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) selectedItemContainerColor else itemContainerColor)
                        .clickable {
                            logger.d(
                                "TimeScreenVisualPanels.TimePerDimensionPanel",
                                "Toggled per-dimension drill-in filter",
                                mapOf("dimensionId" to row.dimensionId, "selected" to (!isSelected).toString()),
                            )
                            onSelectDimension(row.dimensionId)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(text = displayDimensionLabel(row.dimensionLabel), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${(row.sharePercent * 100).toInt()}% • ${row.blockCount} ${strings.locBlocks} • " +
                                strings.locFocusedMinutesValue.format(formatDuration(row.focusedMinutes),),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = formatDuration(row.trackedMinutes), style = MaterialTheme.typography.bodyMedium)
                        val delta = row.plannedDeltaMinutes
                        val deltaPrefix = if (delta >= 0) "+" else "-"
                        Text(
                            text = strings.locPlanVsActualLine.format(formatDuration(row.plannedMinutes.toLong()),
                                "$deltaPrefix${formatDuration(delta.absoluteValue)}",),
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
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TimePerDimensionSegmentsChart(rows: List<TimeDimensionDaySummary>) {
    val total = rows.sumOf { it.trackedMinutes }.coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        rows.forEachIndexed { index, row ->
            val widthWeight = (row.trackedMinutes.toFloat() / total.toFloat()).coerceIn(0.05f, 1f)
            val color = dimensionChartColor(index)
            Box(
                modifier = Modifier
                    .weight(widthWeight)
                    .height(16.dp)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                if (widthWeight >= 0.17f) {
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
    val strings = LocalUiStrings.current
    val tokens = rememberInsightsVisualTokens()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = strings.locTimelineQualityCues,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QualityCueChip(
                label = strings.locGaps,
                value = summary.gapCount,
                color = tokens.qualityGap,
            )
            QualityCueChip(
                label = strings.locOverlaps,
                value = summary.overlapCount,
                color = tokens.qualityOverlap,
            )
        }
    }
}

@Composable
private fun QualityCueChip(label: String, value: Int, color: Color) {
    val strings = LocalUiStrings.current
    val displayValue = if (value > 0) value.toString() else strings.locNone
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(
            text = "$label: $displayValue",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun displayDimensionLabel(label: String): String = if (label.isBlank()) {
    val strings = LocalUiStrings.current
    strings.locDimensionFallbackUnassigned
} else {
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

/**
 * Uppercase abbreviation of a dimension label, for the fixed-width compact tiles.
 *
 * Tokens are the runs of letters and combining marks, so an ampersand or hyphen splits words while
 * Tamil vowel signs stay attached to their base letter. Two or more tokens yield the first grapheme of
 * the first two, uppercased ("Learning & Grow" -> "LG"); a single token yields its first two graphemes
 * ("Financial" -> "FI"). Counting graphemes rather than characters is what keeps a combining mark from
 * being split off its letter. A label with no letters at all - blank, or digits and punctuation only -
 * yields "--".
 *
 * Uppercasing uses [Locale.ROOT] rather than the default locale so the result does not change shape
 * with the device locale.
 */
fun shortDimensionLabel(label: String): String {
    val words = Regex("[\\p{L}\\p{M}]+").findAll(label).map { it.value }.toList()
    return when {
        words.size >= 2 -> (firstGrapheme(words[0]) + firstGrapheme(words[1])).uppercase(Locale.ROOT)
        words.size == 1 -> firstNGraphemes(words[0], 2).uppercase(Locale.ROOT)
        else -> "--"
    }
}

private fun firstGrapheme(text: String): String = firstNGraphemes(text, 1)

/**
 * Groups base letters with their trailing combining marks (vowel signs,
 * viramas) into user-perceived units before counting, so Indic scripts
 * behave identically on JVM and Android (JDK BreakIterator does not form
 * extended grapheme clusters for Tamil).
 */
private fun firstNGraphemes(text: String, count: Int): String {
    if (text.isBlank() || count <= 0) return ""
    val builder = StringBuilder()
    var taken = 0
    var previousWasBase = false
    for (ch in text) {
        val type = Character.getType(ch)
        val isMark = type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        if (previousWasBase && !isMark && taken == count - 1) {
            break
        }
        if (!isMark && previousWasBase) {
            taken++
            if (taken >= count) break
        }
        builder.append(ch)
        previousWasBase = true
    }
    return builder.toString()
}

private fun formatSignedDuration(minutes: Long): String {
    if (minutes == 0L) return "0m"
    val sign = if (minutes > 0) "+" else "-"
    return sign + formatDuration(minutes.absoluteValue)
}

/**
 * Whether the day summary has anything worth flagging in its timeline quality cues.
 *
 * True as soon as the day has at least one untracked gap or one overlap, false only when both counts
 * are zero. This is a pure count check on [TimeDayOverallSummary]: the gap and overlap magnitudes do
 * not matter, and a day with a single minute untracked shows cues just as one with hours does. A clean
 * day hides the row rather than showing two zeroed chips.
 */
fun shouldShowTimelineQualityCues(summary: TimeDayOverallSummary): Boolean = summary.gapCount > 0 || summary.overlapCount > 0

@Composable
fun TimeDimensionCompactOverviewPanel(
    rows: List<TimeDimensionDaySummary>,
    visibleDimensions: List<DimensionPreference>,
    selectedDimensionId: String?,
    onSelectDimension: (String) -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    val tokens = rememberInsightsVisualTokens()
    val rowsById = remember(rows) { rows.associateBy { it.dimensionId } }
    val displayRows = remember(visibleDimensions, rowsById) {
        visibleDimensions.take(9).map { preference ->
            val row = rowsById[preference.id]
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
    val paddedRows = remember(displayRows) {
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
            displayRows
        }
    }
    if (paddedRows.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tokens.cardContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = strings.locPerDimension,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            paddedRows
                .chunked(5)
                .forEach { rowTiles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rowTiles.forEach { tile ->
                            if (tile.isSpacer) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)),
                                )
                            } else {
                                CompactDimensionGridTile(
                                    tile = tile,
                                    isSelected = selectedDimensionId == tile.dimensionId,
                                    onClick = {
                                        logger.d(
                                            "TimeScreenVisualPanels.TimeDimensionCompactOverviewPanel",
                                            "Toggled compact dimension filter",
                                            mapOf("dimensionId" to tile.dimensionId, "selected" to (selectedDimensionId != tile.dimensionId).toString()),
                                        )
                                        onSelectDimension(tile.dimensionId)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        repeat(5 - rowTiles.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
        }
    }
}

@Composable
private fun CompactDimensionGridTile(
    tile: CompactDimensionTile,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safePlanned = tile.plannedMinutes.coerceAtLeast(0L)
    val safeTracked = tile.trackedMinutes.coerceAtLeast(0L)
    val progress = when {
        safePlanned > 0L -> (safeTracked.toFloat() / safePlanned.toFloat()).coerceIn(0f, 1f)
        safeTracked > 0L -> 1f
        else -> 0f
    }
    val isOverPlanned = safePlanned > 0L && safeTracked > safePlanned
    val deltaText = formatSignedDuration(tile.deltaMinutes)
    val shortLabel = shortDimensionLabel(tile.label)
    Column(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shortLabel,
                style = MaterialTheme.typography.labelSmall,
                color = tile.barColor,
            )
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(tile.barColor.copy(alpha = 0.20f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tile.barColor),
            )
            if (isOverPlanned) {
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
    val dimensionId: String,
    val label: String,
    val trackedMinutes: Long,
    val plannedMinutes: Long,
    val deltaMinutes: Long,
    val barColor: Color,
    val isSpacer: Boolean = false,
)

/**
 * Renders a whole-minute span as a compact duration, in the largest unit that leaves a useful
 * remainder.
 *
 * Under an hour is bare minutes ("45m"), under a day is hours and the remaining minutes ("1h 59m"),
 * and a day or more is days and hours ("1d 2h"). The sub-unit is always printed, so a whole figure
 * still carries a zero remainder - 120 minutes is "2h 0m", and 1440 is "1d 0h". Each part truncates
 * rather than rounds, so the two never add up to more than the span they came from: 119 minutes is
 * "1h 59m", never "2h", and the sub-unit is discarded once a day is the leading unit.
 *
 * This is a formatter only - it does not clamp or take an absolute value, so a negative span prints
 * with its own sign. Callers that need the magnitude take it first, as the signed delta does.
 */
fun formatDuration(minutes: Long): String = when {
    minutes < 60 -> "${minutes}m"
    minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
}
