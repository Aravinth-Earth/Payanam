//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.LensHabitScoreUiState
import io.payanam.ui.viewmodel.LensHabitScoreViewModel
import io.payanam.ui.viewmodel.ScoreMatrixRow
import io.payanam.ui.viewmodel.ScoreMetricColumn
import io.payanam.ui.viewmodel.labelForDimensionId
import java.util.Locale

/**
 * Lenses › Habits score matrix: DAY + all dimension rows, single-select metric
 * dropdown, value + 14-day sparkline per cell (self-gov style).
 * Row tap opens the full detail page via [onRowSelected].
 */
@Composable
/**
 * Lens habit score matrix section.
 */
fun LensHabitScoreMatrixSection(
    viewModel: LensHabitScoreViewModel = hiltViewModel(),
    onRowSelected: (isDay: Boolean, key: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    val uiState by viewModel.uiState.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadWindow()
    }

    /** Card. */
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        /** Column. */
        Column(modifier = Modifier.padding(12.dp)) {
            /** Metric dropdown. */
            MetricDropdown(
                selected = uiState.selectedMetric,
                onSelect = viewModel::selectMetric,
            )
            /** Spacer. */
            Spacer(modifier = Modifier.height(8.dp))
            /** Score matrix table. */
            ScoreMatrixTable(
                uiState = uiState,
                onRowSelected = { isDay, key ->
                    logger.d(
                        "LensHabitScoreMatrixSection.rowSelected",
                        "Score matrix row selected",
                        /** Map of. */
                        mapOf("isDay" to isDay, "key" to key),
                    )
                    /** On row selected. */
                    onRowSelected(isDay, key)
                },
            )
            /** Spacer. */
            Spacer(modifier = Modifier.height(10.dp))
            /** Lens dimension radar section. */
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
    /** Selected. */
    selected: ScoreMetricColumn,
    onSelect: (ScoreMetricColumn) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    Box {
        /** Row. */
        Row(
            modifier =
                /** Modifier. */
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
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_metric_label, stringResource(metricLabel(selected))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            /** Spacer. */
            Spacer(modifier = Modifier.weight(1f))
            /** Text. */
            Text(text = "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        /** Dropdown menu. */
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScoreMetricColumn.entries.forEach { metric ->
                /** Dropdown menu item. */
                DropdownMenuItem(
                    text = { Text(stringResource(metricLabel(metric))) },
                    onClick = {
                        expanded = false
                        logger.d(
                            "LensHabitScoreMatrixSection.metricSelected",
                            "Score metric selected",
                            /** Map of. */
                            mapOf("metric" to metric.key),
                        )
                        /** On select. */
                        onSelect(metric)
                    },
                )
            }
        }
    }
}

/** Maps a [ScoreMetricColumn] to its localized string-resource id. */
@Composable
private fun metricLabel(metric: ScoreMetricColumn): Int = when (metric) {
    ScoreMetricColumn.SCORE -> R.string.loc_score
    ScoreMetricColumn.RUNNING_AVG -> R.string.activity_detail_chart_running_avg
    ScoreMetricColumn.PROGRESS -> R.string.loc_lens_time_progress_label
    ScoreMetricColumn.STREAK_POS -> R.string.activity_detail_chart_streak_pos
    ScoreMetricColumn.STREAK_NET -> R.string.loc_lens_metric_streak_net
    ScoreMetricColumn.POS_CONTINUE -> R.string.loc_continue
}

/** Renders the full matrix: header (metric + rank) + DAY + dimension rows. */
@Composable
private fun ScoreMatrixTable(
    /** Ui state. */
    uiState: LensHabitScoreUiState,
    onRowSelected: (Boolean, String) -> Unit,
) {
    /** Selected metric. */
    val selectedMetric = uiState.selectedMetric
    /** Header color. */
    val headerColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Rank comes from the ViewModel (computed across each row's full history
    // of unique values); DAY row also gets a rank, never "—".
    /** Rank by key. */
    val rankByKey: Map<String, String> = uiState.rankByKey
    // Recomposition marker: fires when rank data settles into the matrix.
    /** Launched effect. */
    LaunchedEffect(rankByKey) {
        /** If. */
        if (rankByKey.isNotEmpty()) {
            UnifiedLogger.getInstance().d(
                "LensHabitScoreMatrixSection.ScoreMatrixTable",
                "Score matrix rendered with rank",
                /** Map of. */
                mapOf(
                    "rankKeys" to rankByKey.size,
                    "sampleDay" to (rankByKey["DAY"] ?: "none"),
                    "renderedAtMs" to System.currentTimeMillis(),
                ),
            )
        }
    }
    Column {
        /** Row. */
        Row(
            modifier =
                /** Modifier. */
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_dimension),
                style = MaterialTheme.typography.labelSmall,
                color = headerColor,
                modifier = Modifier.weight(1f),
            )
            /** Text. */
            Text(
                text = stringResource(metricLabel(selectedMetric)),
                style = MaterialTheme.typography.labelSmall,
                color = headerColor,
            )
            /** Spacer. */
            Spacer(modifier = Modifier.width(8.dp))
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_rank),
                style = MaterialTheme.typography.labelSmall,
                color = headerColor,
                modifier = Modifier.width(42.dp),
            )
        }
        // Plain Column — NOT LazyColumn: this section lives inside
        // LensesScreen's verticalScroll parent; a nested scrollable would
        // be measured with infinite height and crash on expansion.
        Column {
            /** If. */
            if (uiState.dayRow != null) {
                /** Matrix row. */
                MatrixRow(
                    row = uiState.dayRow!!,
                    selectedMetric = selectedMetric,
                    isDay = true,
                    rank = rankByKey["DAY"],
                    onClick = { onRowSelected(true, "DAY") },
                )
            }
            uiState.rows.forEach { row ->
                /** Matrix row. */
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
    /** Row. */
    row: ScoreMatrixRow,
    /** Selected metric. */
    selectedMetric: ScoreMetricColumn,
    /** Is day. */
    isDay: Boolean,
    rank: String?,
    onClick: () -> Unit,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    // User-custom dimension labels win; taxonomy fallback otherwise.
    /** App prefs. */
    val appPrefs = io.payanam.ui.viewmodel.LocalAppPreferences.current
    /** Display label. */
    val displayLabel = if (isDay) row.label else appPrefs.labelForDimensionId(row.key) ?: row.label
    /** Value. */
    val value = row.values[selectedMetric]
    /** Row. */
    Row(
        modifier =
            /** Modifier. */
            Modifier
                .fillMaxWidth()
                .clickable {
                    logger.d(
                        "LensHabitScoreMatrixSection.rowTap",
                        "Matrix row tapped",
                        /** Map of. */
                        mapOf("key" to row.key, "isDay" to isDay),
                    )
                    /** On click. */
                    onClick()
                }
                .background(if (isDay) Color(0xFF818CF8).copy(alpha = 0.08f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /** Box. */
        Box(
            modifier =
                /** Modifier. */
                Modifier
                    .size(7.dp)
                    .background(
                        color = parseHexColor(row.colorHex),
                        shape = CircleShape,
                    ),
        )
        /** Spacer. */
        Spacer(modifier = Modifier.width(6.dp))
        /** Text. */
        Text(
            text = displayLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isDay) FontWeight.Bold else FontWeight.Normal,
            color =
                /** If. */
                if (isDay) Color(0xFF818CF8)
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        /** Column. */
        Column(horizontalAlignment = Alignment.End) {
            /** Text. */
            Text(
                text = formatMetricValue(value, selectedMetric),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = valueColor(value),
            )
            /** Sparkline. */
            Sparkline(
                series = row.sparkline,
                color = parseHexColor(row.colorHex),
                modifier = Modifier.size(width = 78.dp, height = 16.dp),
            )
        }
        /** Spacer. */
        Spacer(modifier = Modifier.width(8.dp))
        /** Text. */
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
    /** If. */
    if (value == null) return "—"
    return when (metric) {
        ScoreMetricColumn.SCORE, ScoreMetricColumn.RUNNING_AVG ->
            String.format(Locale.US, "%.5f", value.coerceIn(0.0, 1.0))
        ScoreMetricColumn.PROGRESS ->
            /** If. */
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
    /** Color. */
    color: Color,
    modifier: Modifier = Modifier,
) {
    /** Points. */
    val points = series.mapIndexedNotNull { index, v ->
        v?.let { index to it }
    }
    /** Canvas. */
    Canvas(modifier = modifier) {
        /** If. */
        if (points.size < 2) return@Canvas
        /** Min. */
        val min = points.minOf { it.second }.toFloat()
        /** Max. */
        val max = points.maxOf { it.second }.toFloat()
        /** Range. */
        val range = (max - min).takeIf { it > 0f } ?: 1f
        /** Step x. */
        val stepX = size.width / (series.size - 1).coerceAtLeast(1)
        /** Path. */
        val path = Path()
        points.forEachIndexed { i, (index, v) ->
            /** X. */
            val x = index * stepX
            /** Y. */
            val y = size.height - ((v.toFloat() - min) / range) * (size.height - 2f)
            /** If. */
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        /** Draw path. */
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Parses a #RRGGBB / RRGGBB hex string to a Compose [Color]; falls back to gray on error. */
private fun parseHexColor(hex: String): Color = runCatching {
    /** Normalized. */
    val normalized = hex.removePrefix("#")
    /** Color. */
    Color((0xFF000000 or normalized.toLong(16)).toInt())
}.getOrDefault(Color.Gray)
