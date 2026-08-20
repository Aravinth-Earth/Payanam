//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.AverageDailyTimeRow
import io.payanam.domain.repository.AverageDailyTimeRowType
import io.payanam.domain.repository.AverageDailyTimeTableData
import io.payanam.domain.repository.AverageDailyTimeWindow
import io.payanam.ui.components.DimensionIdentityRow
import io.payanam.ui.viewmodel.LocalAppPreferences
import kotlin.math.roundToInt

private const val AVERAGE_TABLE_DIMENSION_COL_WIDTH = 72
private const val AVERAGE_TABLE_VALUE_COL_WIDTH = 88
private const val AVERAGE_TABLE_CELL_HEIGHT = 32
private const val AVERAGE_TABLE_UNTRACKED_KEY = "__untracked__"
private val averageTableLogger = UnifiedLogger.getInstance()
private val averageTableUnassignedColor = Color(0xFF8F8F8F)
private val averageTableUntrackedColor = Color(0xFF757575)

private enum class AverageDailyTableValueMode {
    /** Time. */
    TIME,
    /** Percent. */
    PERCENT,
}

@Composable
internal fun AverageDailyTimeTableSection(summary: AverageDailyTimeTableData?) {
    /** App prefs. */
    val appPrefs = LocalAppPreferences.current
    var valueMode by remember { mutableStateOf(AverageDailyTableValueMode.TIME) }
    /** Launched effect. */
    LaunchedEffect(summary?.totalCalendarDays, summary?.rows?.size, summary?.visibleWindows?.size) {
        averageTableLogger.d(
            "LensesTimeAverageDailySection",
            "Rendering average daily time section",
            /** Map of. */
            mapOf(
                "hasSummary" to (summary != null),
                "days" to (summary?.totalCalendarDays ?: 0),
                "rows" to (summary?.rows?.size ?: 0),
                "windows" to (summary?.visibleWindows?.size ?: 0),
            ),
        )
    }

    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f)),
    ) {
        /** Column. */
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_time_average_daily_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_time_average_daily_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            /** If. */
            if (summary == null || summary.rows.isEmpty() || summary.visibleWindows.isEmpty()) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.loc_lens_time_no_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            /** Average daily time value mode toggle. */
            AverageDailyTimeValueModeToggle(
                valueMode = valueMode,
                onValueModeChange = { valueMode = it },
            )

            /** Column totals. */
            val columnTotals = remember(summary) { averageDailyTimeColumnTotals(summary) }
            /** Scroll state. */
            val scrollState = rememberScrollState()
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                /** Column. */
                Column(
                    modifier = Modifier.width(AVERAGE_TABLE_DIMENSION_COL_WIDTH.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    /** Box. */
                    Box(
                        modifier = Modifier
                            .width(AVERAGE_TABLE_DIMENSION_COL_WIDTH.dp)
                            .height(AVERAGE_TABLE_CELL_HEIGHT.dp),
                    )
                    summary.rows.forEach { row ->
                        /** Average daily time pinned row cell. */
                        AverageDailyTimePinnedRowCell(
                            row = row,
                            summary = summary,
                            appPrefs = appPrefs,
                        )
                    }
                }
                /** Box. */
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState),
                ) {
                    /** Column. */
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        /** Average daily time header row. */
                        AverageDailyTimeHeaderRow(summary = summary)
                        summary.rows.forEach { row ->
                            /** Average daily time data row. */
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
    /** Value mode. */
    valueMode: AverageDailyTableValueMode,
    onValueModeChange: (AverageDailyTableValueMode) -> Unit,
) {
    /** Options. */
    val options = AverageDailyTableValueMode.entries
    /** Single choice segmented button row. */
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            /** Segmented button. */
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                selected = valueMode == option,
                onClick = { onValueModeChange(option) },
            ) {
                /** Text. */
                Text(
                    text = stringResource(
                        id = when (option) {
                            AverageDailyTableValueMode.TIME -> R.string.loc_time
                            AverageDailyTableValueMode.PERCENT -> R.string.loc_lens_share_percent
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun AverageDailyTimeHeaderRow(summary: AverageDailyTimeTableData) {
    /** Row. */
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        summary.visibleWindows.forEach { window ->
            /** Box. */
            Box(
                modifier = Modifier
                    .width(AVERAGE_TABLE_VALUE_COL_WIDTH.dp)
                    .height(AVERAGE_TABLE_CELL_HEIGHT.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                /** Text. */
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
    /** Row. */
    row: AverageDailyTimeRow,
    /** Summary. */
    summary: AverageDailyTimeTableData,
    appPrefs: io.payanam.ui.viewmodel.AppPreferencesState,
) {
    /** Label fallback. */
    val labelFallback = when (row.rowType) {
        AverageDailyTimeRowType.DIMENSION ->
            row.dimensionId?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.fallbackLabel } ?: row.dimensionId.orEmpty()
        AverageDailyTimeRowType.UNASSIGNED -> stringResource(id = R.string.loc_dimension_fallback_unassigned)
        AverageDailyTimeRowType.UNTRACKED -> stringResource(id = R.string.loc_untracked)
    }
    /** Fallback color. */
    val fallbackColor = when (row.rowType) {
        AverageDailyTimeRowType.DIMENSION -> MaterialTheme.colorScheme.primary
        AverageDailyTimeRowType.UNASSIGNED -> averageTableUnassignedColor
        AverageDailyTimeRowType.UNTRACKED -> averageTableUntrackedColor
    }
    /** Box. */
    Box(
        modifier = Modifier
            .width(AVERAGE_TABLE_DIMENSION_COL_WIDTH.dp)
            .height(AVERAGE_TABLE_CELL_HEIGHT.dp),
    ) {
        /** Dimension id. */
        val dimensionId = when (row.rowType) {
            AverageDailyTimeRowType.DIMENSION -> row.dimensionId
            AverageDailyTimeRowType.UNASSIGNED -> null
            AverageDailyTimeRowType.UNTRACKED -> AVERAGE_TABLE_UNTRACKED_KEY
        }
        /** Dimension identity row. */
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
    /** Row. */
    row: AverageDailyTimeRow,
    visibleWindows: List<AverageDailyTimeWindow>,
    /** Value mode. */
    valueMode: AverageDailyTableValueMode,
    columnTotals: Map<AverageDailyTimeWindow, Double>,
) {
    /** Row. */
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visibleWindows.forEach { window ->
            /** Minutes. */
            val minutes = row.averageMinutesByWindow[window] ?: 0.0
            /** Share. */
            val share = averageDailyTimeCellShare(minutes = minutes, columnTotalMinutes = columnTotals[window] ?: 0.0)
            /** Box. */
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
                /** Text. */
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
private fun windowLabel(window: AverageDailyTimeWindow, allDays: Int): String = when (window) {
    AverageDailyTimeWindow.TODAY_SO_FAR -> stringResource(id = R.string.loc_today_so_far)
    AverageDailyTimeWindow.YESTERDAY -> stringResource(id = R.string.loc_yesterday)
    AverageDailyTimeWindow.LAST_7_DAYS -> stringResource(id = R.string.loc_lens_chart_stat_7d_label)
    AverageDailyTimeWindow.LAST_30_DAYS -> stringResource(id = R.string.loc_lens_time_window_30d)
    AverageDailyTimeWindow.LAST_90_DAYS -> stringResource(id = R.string.loc_lens_time_window_90d)
    AverageDailyTimeWindow.LAST_180_DAYS -> stringResource(id = R.string.loc_lens_time_window_180d)
    AverageDailyTimeWindow.LAST_365_DAYS -> stringResource(id = R.string.loc_lens_time_window_365d)
    AverageDailyTimeWindow.ALL_DAYS -> stringResource(id = R.string.loc_lens_time_window_all_days, allDays)
}

internal fun averageDailyTimeColumnTotals(summary: AverageDailyTimeTableData): Map<AverageDailyTimeWindow, Double> =
    summary.visibleWindows.associateWith { window ->
        summary.rows.sumOf { row -> (row.averageMinutesByWindow[window] ?: 0.0).coerceAtLeast(0.0) }
    }

internal fun averageDailyTimeCellShare(
    /** Minutes. */
    minutes: Double,
    /** Column total minutes. */
    columnTotalMinutes: Double,
): Double {
    /** If. */
    if (!minutes.isFinite() || !columnTotalMinutes.isFinite() || columnTotalMinutes <= 0.0) {
        return 0.0
    }
    /** Return. */
    return (minutes / columnTotalMinutes).coerceIn(0.0, 1.0)
}

@Composable
private fun averageDailyTableCellColor(share: Double): Color {
    /** Safe share. */
    val safeShare = if (share.isFinite()) share.toFloat().coerceIn(0f, 1f) else 0f
    /** Alpha. */
    val alpha = 0.10f + (safeShare * 0.34f)
    return MaterialTheme.colorScheme.tertiary.copy(alpha = alpha)
        .compositeOver(MaterialTheme.colorScheme.surface)
}

@Composable
private fun formatAverageDailyTimeCellValue(
    /** Minutes. */
    minutes: Double,
    /** Share. */
    share: Double,
    /** Value mode. */
    valueMode: AverageDailyTableValueMode,
): String = when (valueMode) {
    AverageDailyTableValueMode.TIME -> formatAverageDailyTimeValue(minutes)
    AverageDailyTableValueMode.PERCENT -> stringResource(
        id = R.string.loc_percent_value_decimal,
        share * 100.0,
    )
}

private fun formatAverageDailyTimeValue(minutes: Double): String = formatMinutes(minutes.roundToInt())
