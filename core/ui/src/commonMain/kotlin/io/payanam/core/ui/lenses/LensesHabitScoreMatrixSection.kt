//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.lenses

import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.shared.logging.AppLogger
import io.payanam.presentation.scoring.ScoreMetricColumn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.presentation.lenses.LensHabitScoreUiState
import io.payanam.presentation.lenses.LensHabitScoreViewModel
import io.payanam.presentation.lenses.ScoreMatrixRow
import java.util.Locale

/**
 * Lenses › Habits score matrix: DAY + all dimension rows, single-select metric
 * dropdown, value + 14-day sparkline per cell (self-gov style).
 * Row tap opens the full detail page via [onRowSelected].
 */
@Composable
fun LensHabitScoreMatrixSection(
    viewModel: LensHabitScoreViewModel,
    onRowSelected: (isDay: Boolean, key: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val logger = remember { AppLogger.current }
    val uiState by viewModel.uiState.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadWindow()
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            MetricDropdown(
                selected = uiState.selectedMetric,
                onSelect = viewModel::selectMetric,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ScoreMatrixTable(
                uiState = uiState,
                onRowSelected = { isDay, key ->
                    logger.d(
                        "LensHabitScoreMatrixSection.rowSelected",
                        "Score matrix row selected",
                        mapOf("isDay" to isDay, "key" to key),
                    )
                    onRowSelected(isDay, key)
                },
            )
            Spacer(modifier = Modifier.height(10.dp))
            LensDimensionRadarSection(
                axes = uiState.radarAxes,
                selectedMetric = uiState.selectedMetric,
            )
        }
    }
}

/** Metric selector dropdown; invokes [onSelect] with the chosen [ScoreMetricColumn]. */
@Composable
private fun MetricDropdown(
    selected: ScoreMetricColumn,
    onSelect: (ScoreMetricColumn) -> Unit,
) {
    val strings = LocalUiStrings.current
    var expanded by remember { mutableStateOf(false) }
    val logger = remember { AppLogger.current }
    Box {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.locLensMetricLabel.formatArgs(metricLabel(selected)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScoreMetricColumn.entries.forEach { metric ->
                DropdownMenuItem(
                    text = { Text(metricLabel(metric)) },
                    onClick = {
                        expanded = false
                        logger.d(
                            "LensHabitScoreMatrixSection.metricSelected",
                            "Score metric selected",
                            mapOf("metric" to metric.key),
                        )
                        onSelect(metric)
                    },
                )
            }
        }
    }
}

/** Maps a [ScoreMetricColumn] to its localized label. */
@Composable
private fun metricLabel(metric: ScoreMetricColumn): String {
    val strings = LocalUiStrings.current
    return when (metric) {
    ScoreMetricColumn.SCORE -> strings.locScore
    ScoreMetricColumn.RUNNING_AVG -> strings.activityDetailChartRunningAvg
    ScoreMetricColumn.PROGRESS -> strings.locLensTimeProgressLabel
    ScoreMetricColumn.STREAK_POS -> strings.activityDetailChartStreakPos
    ScoreMetricColumn.STREAK_NET -> strings.locLensMetricStreakNet
    ScoreMetricColumn.POS_CONTINUE -> strings.locContinue
}
}

/** Renders the full matrix: header (metric + rank) + DAY + dimension rows. */
@Composable
private fun ScoreMatrixTable(
    uiState: LensHabitScoreUiState,
    onRowSelected: (Boolean, String) -> Unit,
) {
    val strings = LocalUiStrings.current
    val selectedMetric = uiState.selectedMetric
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Rank comes from the ViewModel (computed across each row's full history
    // of unique values); DAY row also gets a rank, never "—".
    val rankByKey: Map<String, String> = uiState.rankByKey
    // Recomposition marker: fires when rank data settles into the matrix.
    LaunchedEffect(rankByKey) {
        if (rankByKey.isNotEmpty()) {
            AppLogger.current.d(
                "LensHabitScoreMatrixSection.ScoreMatrixTable",
                "Score matrix rendered with rank",
                mapOf(
                    "rankKeys" to rankByKey.size,
                    "sampleDay" to (rankByKey["DAY"] ?: "none"),
                    "renderedAtMs" to System.currentTimeMillis(),
                ),
            )
        }
    }
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = strings.locDimension,
                style = MaterialTheme.typography.labelSmall,
                color = headerColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = metricLabel(selectedMetric),
                style = MaterialTheme.typography.labelSmall,
                color = headerColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = strings.locRank,
                style = MaterialTheme.typography.labelSmall,
                color = headerColor,
                modifier = Modifier.width(42.dp),
            )
        }
        // Plain Column — NOT LazyColumn: this section lives inside
        // LensesScreen's verticalScroll parent; a nested scrollable would
        // be measured with infinite height and crash on expansion.
        Column {
            if (uiState.dayRow != null) {
                MatrixRow(
                    row = uiState.dayRow!!,
                    selectedMetric = selectedMetric,
                    isDay = true,
                    rank = rankByKey["DAY"],
                    onClick = { onRowSelected(true, "DAY") },
                )
            }
            uiState.rows.forEach { row ->
                MatrixRow(
                    row = row,
                    selectedMetric = selectedMetric,
                    isDay = false,
                    rank = rankByKey[row.key],
                    onClick = { onRowSelected(false, row.key) },
                )
            }
        }
    }
}

@Composable
/** One matrix row: label, metric value, 14-day sparkline, and rank (X/Y). */
private fun MatrixRow(
    row: ScoreMatrixRow,
    selectedMetric: ScoreMetricColumn,
    isDay: Boolean,
    rank: String?,
    onClick: () -> Unit,
) {
    val logger = remember { AppLogger.current }
    // User-custom dimension labels win; taxonomy fallback otherwise.
    val appPrefs = io.payanam.core.ui.preferences.LocalAppPreferences.current
    val displayLabel = if (isDay) row.label else appPrefs.labelForDimensionId(row.key) ?: row.label
    val value = row.values[selectedMetric]
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    logger.d(
                        "LensHabitScoreMatrixSection.rowTap",
                        "Matrix row tapped",
                        mapOf("key" to row.key, "isDay" to isDay),
                    )
                    onClick()
                }
                .testTag("lenses_matrix_row")
                .background(if (isDay) Color(0xFF818CF8).copy(alpha = 0.08f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(7.dp)
                    .background(
                        color = parseHexColor(row.colorHex),
                        shape = CircleShape,
                    ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = displayLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isDay) FontWeight.Bold else FontWeight.Normal,
            color =
                if (isDay) Color(0xFF818CF8)
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatMetricValue(value, selectedMetric),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = valueColor(value),
            )
            Sparkline(
                series = row.sparkline,
                color = parseHexColor(row.colorHex),
                modifier = Modifier.size(width = 78.dp, height = 16.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = rank ?: "—",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (rank == null) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFC4B5FD),
            modifier = Modifier.width(42.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** Formats a metric value for display: score/avg/progress to 5 decimals, streaks as ints, null → em dash. */
private fun formatMetricValue(value: Double?, metric: ScoreMetricColumn): String {
    if (value == null) return "—"
    return when (metric) {
        ScoreMetricColumn.SCORE, ScoreMetricColumn.RUNNING_AVG ->
            String.format(Locale.US, "%.5f", value.coerceIn(0.0, 1.0))
        ScoreMetricColumn.PROGRESS ->
            if (value > 0.0) "+${String.format(Locale.US, "%.5f", value)}"
            else String.format(Locale.US, "%.5f", value)
        else -> value.toLong().toString()
    }
}

/** Color for a metric value: gray (null), red (negative), green (non-negative). */
private fun valueColor(value: Double?): Color = when {
    value == null -> Color(0xFF64748B)
    value < 0.0 -> Color(0xFFF87171)
    else -> Color(0xFF34D399)
}

/** Draws a compact line sparkline from a numeric series (nulls skipped). */
@Composable
private fun Sparkline(
    series: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val points = series.mapIndexedNotNull { index, v ->
        v?.let { index to it }
    }
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val min = points.minOf { it.second }.toFloat()
        val max = points.maxOf { it.second }.toFloat()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (series.size - 1).coerceAtLeast(1)
        val path = Path()
        points.forEachIndexed { i, (index, v) ->
            val x = index * stepX
            val y = size.height - ((v.toFloat() - min) / range) * (size.height - 2f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Parses a #RRGGBB / RRGGBB hex string to a Compose [Color]; falls back to gray on error. */
private fun parseHexColor(hex: String): Color = runCatching {
    val normalized = hex.removePrefix("#")
    Color((0xFF000000 or normalized.toLong(16)).toInt())
}.getOrDefault(Color.Gray)
