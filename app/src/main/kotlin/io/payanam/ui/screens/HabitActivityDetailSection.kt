//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.AxisValueOverrider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import io.payanam.R
import io.payanam.domain.model.MetricWindowRow
import io.payanam.domain.model.TaskOccurrence
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Activity detail (Inc 4 Part C) — self-gov style habit history:
 * date-window navigation, range switcher, chart/table view toggle,
 * summary bar, 6 Vico line charts, and a full history table.
 * Replaces RecurrenceScoreCard + HabitCalendarSection + OccurrenceHistorySection.
 */
@Composable
internal fun HabitActivityDetailSection(
    windowSizeDays: Int,
    windowEnd: LocalDate,
    rows: List<MetricWindowRow>,
    occurrences: Map<String, TaskOccurrence>,
    isLoading: Boolean,
    showChartView: Boolean,
    onWindowSizeChange: (Int) -> Unit,
    onWindowBack: () -> Unit,
    onWindowForward: () -> Unit,
    onWindowToday: () -> Unit,
    onChartViewChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        WindowNavBar(
            windowSizeDays = windowSizeDays,
            windowEnd = windowEnd,
            onBack = onWindowBack,
            onForward = onWindowForward,
            onToday = onWindowToday,
        )
        Spacer(modifier = Modifier.height(8.dp))
        RangeSwitcher(
            selectedDays = windowSizeDays,
            onSelect = onWindowSizeChange,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ViewToggle(
            showChartView = showChartView,
            onChange = onChartViewChange,
        )
        Spacer(modifier = Modifier.height(10.dp))

        when {
            rows.isEmpty() && isLoading -> {
                // Only blank the section when there is NOTHING to show yet.
                // During window/range switches keep the previous rows visible so
                // the page does not collapse and the scroll position is stable.
                Text(
                    text = androidx.compose.ui.res.stringResource(id = R.string.activity_detail_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            rows.isEmpty() -> {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = R.string.activity_detail_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            showChartView -> {
                ActivitySummaryBar(rows = rows, occurrences = occurrences)
                Spacer(modifier = Modifier.height(12.dp))
                ChartView(rows = rows, windowSizeDays = windowSizeDays)
            }
            else -> {
                ActivityTable(rows = rows, occurrences = occurrences)
            }
        }
    }
}

// ── Window navigation ─────────────────────────────────────────────────────

private val windowLabelFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
private fun WindowNavBar(
    windowSizeDays: Int,
    windowEnd: LocalDate,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onToday: () -> Unit,
) {
    val start = if (windowSizeDays > 0) windowEnd.minusDays((windowSizeDays - 1).toLong()) else LocalDate.of(2020, 1, 1)
    val today = LocalDate.now()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = androidx.compose.ui.res.stringResource(id = R.string.activity_detail_earlier),
            )
        }
        Text(
            text = "${start.format(windowLabelFormatter)} — ${windowEnd.format(windowLabelFormatter)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onForward,
            enabled = windowEnd < today,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = androidx.compose.ui.res.stringResource(id = R.string.activity_detail_later),
            )
        }
        TextButton(onClick = onToday, enabled = windowEnd < today) {
            Text(text = androidx.compose.ui.res.stringResource(id = R.string.loc_today))
        }
    }
}

// ── Range switcher ────────────────────────────────────────────────────────

private val rangeOptions: List<Pair<Int, Int>> = listOf(7 to 7, 30 to 30, 90 to 90, 180 to 180, 365 to 365, 0 to 0)

@Composable
private fun RangeSwitcher(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rangeOptions.forEach { (days, _) ->
            val label = if (days == 0) {
                androidx.compose.ui.res.stringResource(id = R.string.loc_all)
            } else {
                "${days}d"
            }
            val selected = days == selectedDays
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(days) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Chart / Table toggle ──────────────────────────────────────────────────

@Composable
private fun ViewToggle(
    showChartView: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(true to R.string.activity_detail_chart_view, false to R.string.activity_detail_table_view).forEach { (chart, labelRes) ->
            val selected = chart == showChartView
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onChange(chart) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Summary bar ───────────────────────────────────────────────────────────

@Composable
private fun ActivitySummaryBar(
    rows: List<MetricWindowRow>,
    occurrences: Map<String, TaskOccurrence>,
) {
    val avgScore = rows.map { it.score }.average()
    val last = rows.last()
    val daysWithEntry = rows.count { it.score > 0.0 || occurrences.containsKey(it.dayKey) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryStat(
                label = androidx.compose.ui.res.stringResource(id = R.string.loc_lens_summary_avg_score),
                value = String.format(java.util.Locale.US, "%.5f", avgScore),
            )
            SummaryStat(
                label = androidx.compose.ui.res.stringResource(id = R.string.activity_detail_chart_running_avg),
                value = String.format(java.util.Locale.US, "%.5f", last.runningAvg),
            )
            SummaryStat(
                label = androidx.compose.ui.res.stringResource(id = R.string.loc_lens_time_progress_label),
                value = if (last.progress >= 0) "+%.5f".format(java.util.Locale.US, last.progress) else "%.5f".format(java.util.Locale.US, last.progress),
                color = if (last.progress >= 0) Color(0xFF2E7D32) else Color(0xFFC62828),
            )
            SummaryStat(
                label = androidx.compose.ui.res.stringResource(id = R.string.loc_days),
                value = daysWithEntry.toString(),
            )
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

// ── Chart view (6 Vico line charts) ───────────────────────────────────────

@Composable
private fun ChartView(rows: List<MetricWindowRow>, windowSizeDays: Int) {
    // Each chart gets its own full-width row — no horizontal space sharing.
    // Y-axis per self-gov: Score/RunningAvg pad 20% clamped to [0,1];
    // Progress symmetric around 0 (±absMax + 20%); streaks auto-scale.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricLineChart(
            title = androidx.compose.ui.res.stringResource(id = R.string.loc_score),
            rows = rows,
            metric = { it.score },
            yOverrider = { values -> clampedUnitRange(values) },
            color = Color(0xFF1565C0),
            windowSizeDays = windowSizeDays,
            modifier = Modifier.fillMaxWidth(),
        )
        MetricLineChart(
            title = androidx.compose.ui.res.stringResource(id = R.string.activity_detail_chart_running_avg),
            rows = rows,
            metric = { it.runningAvg },
            yOverrider = { values -> clampedUnitRange(values) },
            color = Color(0xFF6A1B9A),
            windowSizeDays = windowSizeDays,
            modifier = Modifier.fillMaxWidth(),
        )
        MetricLineChart(
            title = androidx.compose.ui.res.stringResource(id = R.string.loc_lens_time_progress_label),
            rows = rows,
            metric = { it.progress },
            yOverrider = { values -> symmetricAroundZero(values) },
            color = Color(0xFF2E7D32),
            windowSizeDays = windowSizeDays,
            modifier = Modifier.fillMaxWidth(),
        )
        MetricLineChart(
            title = androidx.compose.ui.res.stringResource(id = R.string.activity_detail_chart_streak_pos),
            rows = rows,
            metric = { it.streakPos.toDouble() },
            yOverrider = { values -> paddedIntRange(values) },
            color = Color(0xFFEF6C00),
            windowSizeDays = windowSizeDays,
            modifier = Modifier.fillMaxWidth(),
        )
        MetricLineChart(
            title = androidx.compose.ui.res.stringResource(id = R.string.loc_metric_net_streak),
            rows = rows,
            metric = { it.streakNet.toDouble() },
            yOverrider = { values -> paddedIntRange(values) },
            color = Color(0xFFC62828),
            windowSizeDays = windowSizeDays,
            modifier = Modifier.fillMaxWidth(),
        )
        MetricLineChart(
            title = androidx.compose.ui.res.stringResource(id = R.string.loc_continue),
            rows = rows,
            metric = { it.posContinue.toDouble() },
            yOverrider = { values -> paddedIntRange(values) },
            color = Color(0xFF00838F),
            windowSizeDays = windowSizeDays,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Y-axis scaling helpers (self-gov parity) ──────────────────────────────

/** Score/RunningAvg: pad 20% of the data range, then clamp to [0, 1]. */
internal fun clampedUnitRange(values: List<Double>): Pair<Double, Double>? {
    if (values.isEmpty()) return null
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    val pad = range * 0.2
    return (min - pad).coerceAtLeast(0.0) to (max + pad).coerceAtMost(1.0)
}

/** Progress: symmetric around zero — ±(max abs value + 20% pad). */
internal fun symmetricAroundZero(values: List<Double>): Pair<Double, Double>? {
    if (values.isEmpty()) return null
    val pAbs = values.map { kotlin.math.abs(it) }.maxOrNull()?.coerceAtLeast(1e-6) ?: 1e-6
    val pPad = pAbs * 0.2
    return (-pAbs - pPad) to (pAbs + pPad)
}

/** Streaks: auto-scale with headroom — negative net values stay visible. */
internal fun paddedIntRange(values: List<Double>): Pair<Double, Double>? {
    if (values.isEmpty()) return null
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    val pad = range * 0.15
    return (min - pad) to (max + pad).coerceAtLeast(1.0)
}

@Composable
private fun MetricLineChart(
    title: String,
    rows: List<MetricWindowRow>,
    metric: (MetricWindowRow) -> Double,
    color: Color,
    modifier: Modifier = Modifier,
    yOverrider: ((List<Double>) -> Pair<Double, Double>?)? = null,
    windowSizeDays: Int = 30,
) {
    // x = epoch day so the line's horizontal scale reflects REAL time gaps
    // (interval habits with non-due days stay visually honest) and the axis
    // can render actual dates for the selected window/range.
    val points = rows.map { it.dayKey.toEpochDayDouble() to metric(it) }
    // Y-scale: per-chart dynamic bounds from the window's own data (self-gov
    // parity) so each window/range shows its variants clearly.
    val yBounds = yOverrider?.invoke(points.map { it.second })
    // X-axis: auto label placement (maxCount=0 lets Vico decide spacing from
    // available width — the same pattern as LensesTimeInsights). Forcing a
    // fixed count collapsed the 7-day window to one label; auto keeps every
    // day labeled on short windows and evenly-spaced dates on wide ones.
    val axisOverrider = remember(yBounds) {
        if (yBounds != null) {
            AxisValueOverrider.fixed(
                minX = null, maxX = null,
                minY = yBounds.first, maxY = yBounds.second,
            )
        } else {
            AxisValueOverrider.auto()
        }
    }
    val chart = rememberCartesianChart(
        layers = arrayOf(
            LineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(color.toArgb())),
                        thicknessDp = 2f,
                    ),
                ),
                axisValueOverrider = axisOverrider,
            ),
        ),
        startAxis = rememberStartAxis(),
        bottomAxis =
            rememberBottomAxis(
                valueFormatter = CartesianValueFormatter { value, _, _ ->
                    epochDayToLabel(value)
                },
                itemPlacer =
                    com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis.ItemPlacer
                        .default(1, 0, true, true),
            ),
    )
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        producer.runTransaction {
            lineSeries {
                series(points.map { it.first }, points.map { it.second })
            }
        }
    }
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CartesianChartHost(
                chart = chart,
                modelProducer = producer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
            )
        }
    }
}

private fun String.toEpochDayDouble(): Double =
    runCatching { java.time.LocalDate.parse(take(10)).toEpochDay().toDouble() }.getOrDefault(0.0)

private fun epochDayToLabel(epochDay: Double): CharSequence {
    if (epochDay <= 0.0) return ""
    return runCatching {
        java.time.LocalDate.ofEpochDay(epochDay.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
    }.getOrDefault("")
}

// ── Table view ────────────────────────────────────────────────────────────

@Composable
private fun ActivityTable(
    rows: List<MetricWindowRow>,
    occurrences: Map<String, TaskOccurrence>,
) {
    val ordered = rows.sortedByDescending { it.dayKey }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // Fixed header: stays put while ONLY the row body scrolls.
            TableHeaderRow()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Bounded-height LazyColumn so the table rows scroll independently
            // of the page (the page itself is scrollable for charts/header).
            // Bounded height keeps this legal inside the outer verticalScroll.
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
            ) {
                items(ordered, key = { it.dayKey }) { row ->
                    ActivityTableRow(row = row, occurrence = occurrences[row.dayKey])
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun TableHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        headerCell(androidx.compose.ui.res.stringResource(id = R.string.activity_detail_col_date), 74.dp, bold = true)
        headerCell(androidx.compose.ui.res.stringResource(id = R.string.loc_status), 28.dp, bold = true)
        headerCell(androidx.compose.ui.res.stringResource(id = R.string.loc_score), 38.dp, bold = true)
        headerCell(androidx.compose.ui.res.stringResource(id = R.string.loc_metric_running_avg), 40.dp, bold = true)
        headerCell(androidx.compose.ui.res.stringResource(id = R.string.loc_lens_time_progress_label), 46.dp, bold = true)
        headerCell(androidx.compose.ui.res.stringResource(id = R.string.activity_detail_col_streak_pos), 36.dp, bold = true)
        headerCell(androidx.compose.ui.res.stringResource(id = R.string.loc_metric_net_streak), 34.dp, bold = true)
        headerCell(androidx.compose.ui.res.stringResource(id = R.string.loc_lens_time_streak_label), 40.dp, bold = true)
    }
}

@Composable
private fun ActivityTableRow(row: MetricWindowRow, occurrence: TaskOccurrence?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        headerCell(row.dayKey.takeLast(5), 74.dp)
        headerCell(rawSymbol(occurrence), 28.dp)
        headerCell(String.format(java.util.Locale.US, "%.2f", row.score), 38.dp)
        headerCell(String.format(java.util.Locale.US, "%.2f", row.runningAvg), 40.dp)
        headerCell(
            if (row.progress >= 0) "+%.2f".format(java.util.Locale.US, row.progress) else "%.2f".format(java.util.Locale.US, row.progress),
            46.dp,
            color = if (row.progress >= 0) Color(0xFF2E7D32) else Color(0xFFC62828),
        )
        headerCell(row.streakPos.toString(), 36.dp)
        headerCell(row.streakNet.toString(), 34.dp)
        headerCell(row.posContinue.toString(), 40.dp)
    }
}

private fun rawSymbol(occurrence: TaskOccurrence?): String = when (occurrence?.status) {
    "completed" -> "✓"
    "skipped" -> "⏭"
    "missed" -> "✗"
    else -> "—"
}

@Composable
private fun headerCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    bold: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier.width(width),
    )
}
