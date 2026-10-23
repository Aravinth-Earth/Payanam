//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.lenses

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.AverageDailyTimeRow
import io.payanam.domain.repository.AverageDailyTimeRowType
import io.payanam.domain.repository.AverageDailyTimeTableData
import io.payanam.domain.repository.AverageDailyTimeWindow
import io.payanam.core.ui.components.DimensionIdentityRow
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger
import kotlin.math.roundToInt

private const val AVERAGE_TABLE_DIMENSION_COL_WIDTH = 72
private const val AVERAGE_TABLE_VALUE_COL_WIDTH = 88
private const val AVERAGE_TABLE_CELL_HEIGHT = 32
private const val AVERAGE_TABLE_UNTRACKED_KEY = "__untracked__"
private val averageTableLogger = AppLogger.current
private val averageTableUnassignedColor = Color(0xFF8F8F8F)
private val averageTableUntrackedColor = Color(0xFF757575)

private enum class AverageDailyTableValueMode {
    TIME,
    PERCENT,
}

@Composable
fun AverageDailyTimeTableSection(summary: AverageDailyTimeTableData?) {
    val strings = LocalUiStrings.current
    val appPrefs = LocalAppPreferences.current
    var valueMode by remember { mutableStateOf(AverageDailyTableValueMode.TIME) }
    LaunchedEffect(summary?.totalCalendarDays, summary?.rows?.size, summary?.visibleWindows?.size) {
        averageTableLogger.d(
            "LensesTimeAverageDailySection",
            "Rendering average daily time section",
            mapOf(
                "hasSummary" to (summary != null),
                "days" to (summary?.totalCalendarDays ?: 0),
                "rows" to (summary?.rows?.size ?: 0),
                "windows" to (summary?.visibleWindows?.size ?: 0),
            ),
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = strings.locLensTimeAverageDailyTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = strings.locLensTimeAverageDailyNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary == null || summary.rows.isEmpty() || summary.visibleWindows.isEmpty()) {
                Text(
                    text = strings.locLensTimeNoHistory,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            AverageDailyTimeValueModeToggle(
                valueMode = valueMode,
                onValueModeChange = { valueMode = it },
            )
            val columnTotals = remember(summary) { averageDailyTimeColumnTotals(summary) }
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.width(AVERAGE_TABLE_DIMENSION_COL_WIDTH.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(AVERAGE_TABLE_DIMENSION_COL_WIDTH.dp)
                            .height(AVERAGE_TABLE_CELL_HEIGHT.dp),
                    )
                    summary.rows.forEach { row ->
                        AverageDailyTimePinnedRowCell(
                            row = row,
                            summary = summary,
                            appPrefs = appPrefs,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AverageDailyTimeHeaderRow(summary = summary)
                        summary.rows.forEach { row ->
                            AverageDailyTimeDataRow(
                                row = row,
                                visibleWindows = summary.visibleWindows,
                                valueMode = valueMode,
                                columnTotals = columnTotals,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AverageDailyTimeValueModeToggle(
    valueMode: AverageDailyTableValueMode,
    onValueModeChange: (AverageDailyTableValueMode) -> Unit,
) {
    val strings = LocalUiStrings.current
    val options = AverageDailyTableValueMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = valueMode == option,
                onClick = { onValueModeChange(option) },
            ) {
                Text(
                    text = when (option) {
                            AverageDailyTableValueMode.TIME -> strings.locTime
                            AverageDailyTableValueMode.PERCENT -> strings.locLensSharePercent
                        },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun AverageDailyTimeHeaderRow(summary: AverageDailyTimeTableData) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        summary.visibleWindows.forEach { window ->
            Box(
                modifier = Modifier
                    .width(AVERAGE_TABLE_VALUE_COL_WIDTH.dp)
                    .height(AVERAGE_TABLE_CELL_HEIGHT.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = windowLabel(window = window, allDays = summary.totalCalendarDays),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun AverageDailyTimePinnedRowCell(
    row: AverageDailyTimeRow,
    summary: AverageDailyTimeTableData,
    appPrefs: io.payanam.core.ui.preferences.AppPreferencesState,
) {
    val strings = LocalUiStrings.current
    val labelFallback = when (row.rowType) {
        AverageDailyTimeRowType.DIMENSION ->
            row.dimensionId?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.fallbackLabel } ?: row.dimensionId.orEmpty()
        AverageDailyTimeRowType.UNASSIGNED -> strings.locDimensionFallbackUnassigned
        AverageDailyTimeRowType.UNTRACKED -> strings.locUntracked
    }
    val fallbackColor = when (row.rowType) {
        AverageDailyTimeRowType.DIMENSION -> MaterialTheme.colorScheme.primary
        AverageDailyTimeRowType.UNASSIGNED -> averageTableUnassignedColor
        AverageDailyTimeRowType.UNTRACKED -> averageTableUntrackedColor
    }
    Box(
        modifier = Modifier
            .width(AVERAGE_TABLE_DIMENSION_COL_WIDTH.dp)
            .height(AVERAGE_TABLE_CELL_HEIGHT.dp),
    ) {
        val dimensionId = when (row.rowType) {
            AverageDailyTimeRowType.DIMENSION -> row.dimensionId
            AverageDailyTimeRowType.UNASSIGNED -> null
            AverageDailyTimeRowType.UNTRACKED -> AVERAGE_TABLE_UNTRACKED_KEY
        }
        DimensionIdentityRow(
            prefs = appPrefs,
            dimensionId = dimensionId,
            fallbackLabel = labelFallback,
            fallbackColor = fallbackColor,
            modifier = Modifier.padding(top = 2.dp),
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
            iconSize = 16.dp,
            dotSize = 8.dp,
            maxLines = 1,
            showLabel = false,
        )
    }
}

@Composable
private fun AverageDailyTimeDataRow(
    row: AverageDailyTimeRow,
    visibleWindows: List<AverageDailyTimeWindow>,
    valueMode: AverageDailyTableValueMode,
    columnTotals: Map<AverageDailyTimeWindow, Double>,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visibleWindows.forEach { window ->
            val minutes = row.averageMinutesByWindow[window] ?: 0.0
            val share = averageDailyTimeCellShare(minutes = minutes, columnTotalMinutes = columnTotals[window] ?: 0.0)
            Box(
                modifier = Modifier
                    .width(AVERAGE_TABLE_VALUE_COL_WIDTH.dp)
                    .height(AVERAGE_TABLE_CELL_HEIGHT.dp)
                    .background(
                        color = averageDailyTableCellColor(share = share),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = formatAverageDailyTimeCellValue(
                        minutes = minutes,
                        share = share,
                        valueMode = valueMode,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    color = when (row.rowType) {
                        AverageDailyTimeRowType.UNTRACKED -> averageTableUntrackedColor
                        AverageDailyTimeRowType.UNASSIGNED -> averageTableUnassignedColor
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun windowLabel(window: AverageDailyTimeWindow, allDays: Int): String{
    val strings = LocalUiStrings.current
    return  when (window) {
    AverageDailyTimeWindow.TODAY_SO_FAR -> strings.locTodaySoFar
    AverageDailyTimeWindow.YESTERDAY -> strings.locYesterday
    AverageDailyTimeWindow.LAST_7_DAYS -> strings.locLensChartStat7dLabel
    AverageDailyTimeWindow.LAST_30_DAYS -> strings.locLensTimeWindow30d
    AverageDailyTimeWindow.LAST_90_DAYS -> strings.locLensTimeWindow90d
    AverageDailyTimeWindow.LAST_180_DAYS -> strings.locLensTimeWindow180d
    AverageDailyTimeWindow.LAST_365_DAYS -> strings.locLensTimeWindow365d
    AverageDailyTimeWindow.ALL_DAYS -> strings.locLensTimeWindowAllDays.format(allDays)
}
}

/**
 * The per-column denominators for the average-daily-time table: for each window in
 * [AverageDailyTimeTableData.visibleWindows], the summed average tracked minutes across every
 * row — dimension, unassigned and untracked together.
 *
 * A window missing from a row's [AverageDailyTimeRow.averageMinutesByWindow] counts as 0.0, and
 * negative entries are floored to 0.0, so the totals stay usable as share denominators. The map
 * is keyed by [AverageDailyTimeTableData.visibleWindows] alone, so even empty [rows] yield a key
 * per window — each mapped to 0.0, which [averageDailyTimeCellShare] reads as an empty column.
 */
fun averageDailyTimeColumnTotals(summary: AverageDailyTimeTableData): Map<AverageDailyTimeWindow, Double> =
    summary.visibleWindows.associateWith { window ->
        summary.rows.sumOf { row -> (row.averageMinutesByWindow[window] ?: 0.0).coerceAtLeast(0.0) }
    }

/**
 * One cell's share of its window column, as a 0.0..1.0 fraction used for both the percent label
 * and the cell's background intensity.
 *
 * Returns 0.0 rather than dividing when either input is not finite (NaN/Infinity) or the column
 * total is not positive — an empty column, which is what a window with no tracked time yields.
 * The ratio is then clamped to 0.0..1.0, so a row that somehow exceeds the column total cannot
 * render above 100%. [minutes] is an average-minute-per-day figure, not a raw total, and unlike
 * the column total it is not floored at zero here — a negative cell collapses to 0.0 through the
 * final clamp instead.
 */
fun averageDailyTimeCellShare(
    minutes: Double,
    columnTotalMinutes: Double,
): Double {
    if (!minutes.isFinite() || !columnTotalMinutes.isFinite() || columnTotalMinutes <= 0.0) {
        return 0.0
    }
    return (minutes / columnTotalMinutes).coerceIn(0.0, 1.0)
}

@Composable
private fun averageDailyTableCellColor(share: Double): Color {
    val safeShare = if (share.isFinite()) share.toFloat().coerceIn(0f, 1f) else 0f
    val alpha = 0.10f + (safeShare * 0.34f)
    return MaterialTheme.colorScheme.tertiary.copy(alpha = alpha)
        .compositeOver(MaterialTheme.colorScheme.surface)
}

@Composable
private fun formatAverageDailyTimeCellValue(
    minutes: Double,
    share: Double,
    valueMode: AverageDailyTableValueMode,
): String{
    val strings = LocalUiStrings.current
    return  when (valueMode) {
    AverageDailyTableValueMode.TIME -> formatAverageDailyTimeValue(minutes)
    AverageDailyTableValueMode.PERCENT -> strings.locPercentValueDecimal.format(share * 100.0,)
}
}

private fun formatAverageDailyTimeValue(minutes: Double): String = formatMinutes(minutes.roundToInt())
