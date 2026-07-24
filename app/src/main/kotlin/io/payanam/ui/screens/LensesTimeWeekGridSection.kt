//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.WeekGridState
import io.payanam.ui.viewmodel.colorForDimensionId
import java.time.format.TextStyle
import java.util.Locale

private val weekGridLogger = UnifiedLogger.getInstance()
private const val CELL_HEIGHT_DP = 60 // 3× — 60dp per 30-min slot, 2880dp total
private const val TOTAL_SLOTS = 48
private const val WEEK_GRID_UNTRACKED_SENTINEL = "__untracked__"
private val UNTRACKED_COLOR = Color(0xFF9E9E9E).copy(alpha = 0.4f)
private val UNASSIGNED_COLOR = Color(0xFF9E9E9E)

@Composable
internal fun WeekGridSection(
    state: WeekGridState,
    appPrefs: AppPreferencesState,
) {
    LaunchedEffect(state.data.days.size) {
        weekGridLogger.d(
            "WeekGridSection",
            "Rendered week grid",
            mapOf("days" to state.data.days.size),
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(id = R.string.loc_lens_week_grid_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        if (state.data.days.isEmpty()) {
            Text(
                text = stringResource(id = R.string.loc_lens_week_grid_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val totalChartHeightDp = TOTAL_SLOTS * CELL_HEIGHT_DP
            // Grid line colors resolved from theme here (not inside Canvas)
            val slotLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            val hourLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
            val colBoundaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Fixed Y-axis with label for every 30-min slot
                WeekGridYAxis(totalChartHeightDp = totalChartHeightDp)

                // 7 day columns, equal weight
                state.data.days.forEach { day ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Day-of-week label above column
                        Text(
                            text = remember(day.dayOfWeek) {
                                day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Single Canvas per column: draw all 48 slots then overlay grid lines
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(totalChartHeightDp.dp),
                        ) {
                            val cellH = size.height / TOTAL_SLOTS.toFloat()

                            // — Draw slot content —
                            day.slots.forEachIndexed { slotIdx, slot ->
                                val cellTop = slotIdx * cellH
                                val entries = listOfNotNull(slot.rank1, slot.rank2, slot.rank3)
                                if (entries.isEmpty()) {
                                    drawRect(
                                        color = UNTRACKED_COLOR,
                                        topLeft = Offset(0f, cellTop),
                                        size = Size(size.width, cellH),
                                    )
                                } else {
                                    var y = cellTop
                                    entries.forEach { entry ->
                                        val h = (entry.proportion * cellH).coerceAtLeast(1f)
                                        drawRect(
                                            color = colorForWeekGridEntry(entry.dimensionId, appPrefs),
                                            topLeft = Offset(0f, y),
                                            size = Size(size.width, h),
                                        )
                                        y += h
                                    }
                                }
                            }

                            // — Horizontal slot boundary lines (every 30 min) —
                            for (lineIdx in 0..TOTAL_SLOTS) {
                                val lineY = lineIdx * cellH
                                val isHourBoundary = lineIdx % 2 == 0
                                drawLine(
                                    color = if (isHourBoundary) hourLineColor else slotLineColor,
                                    start = Offset(0f, lineY),
                                    end = Offset(size.width, lineY),
                                    strokeWidth = if (isHourBoundary) 1.5f else 0.5f,
                                )
                            }

                            // — Left vertical column boundary line —
                            drawLine(
                                color = colBoundaryColor,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 1f,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekGridYAxis(totalChartHeightDp: Int) {
    // Y-axis shows a label for every 30-min slot (0:00, 0:30, 1:00 … 23:30)
    // Labels alternate between HH:00 and HH:30 format.
    // Height includes padding-top for the day-label row above the grid columns.
    val dayLabelHeightDp = 16
    val axisHeightDp = totalChartHeightDp + dayLabelHeightDp
    val gridHeightDp = totalChartHeightDp

    Box(
        modifier = Modifier
            .width(32.dp)
            .height(axisHeightDp.dp),
    ) {
        for (slotIdx in 0 until TOTAL_SLOTS) {
            val hour = slotIdx / 2
            val minute = if (slotIdx % 2 == 0) "00" else "30"
            val label = if (slotIdx % 2 == 0) "${hour}h" else "·30"
            val yOffsetDp = dayLabelHeightDp + (slotIdx * CELL_HEIGHT_DP)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = if (slotIdx % 2 == 0) 8.sp else 7.sp,
                color = if (slotIdx % 2 == 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = yOffsetDp.dp)
                    .padding(end = 2.dp),
            )
        }
    }
}

private fun colorForWeekGridEntry(dimensionId: String?, appPrefs: AppPreferencesState): Color = when {
    dimensionId == WEEK_GRID_UNTRACKED_SENTINEL -> UNTRACKED_COLOR
    dimensionId == null -> UNASSIGNED_COLOR
    else -> appPrefs.colorForDimensionId(dimensionId) ?: UNASSIGNED_COLOR
}
