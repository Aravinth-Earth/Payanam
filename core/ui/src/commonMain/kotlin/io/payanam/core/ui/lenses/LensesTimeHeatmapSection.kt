//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.lenses

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.colorForDimension
import io.payanam.core.ui.preferences.colorForDimensionId
import io.payanam.domain.repository.HeatmapDayData
import io.payanam.presentation.lenses.HeatmapState
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val heatmapLogger = AppLogger.current
private val heatmapDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
private const val HEATMAP_TOTAL_MINUTES = 24 * 60
private val HEATMAP_HOUR_MARKS = listOf(0, 6, 12, 18, 24)

@Composable
fun DimensionHeatmapSection(
    state: HeatmapState,
    appPrefs: AppPreferencesState,
) {
    val strings = LocalUiStrings.current
    LaunchedEffect(state.days.size) {
        heatmapLogger.d(
            "DimensionHeatmapSection",
            "Rendered dimension heatmap section",
            mapOf(
                "dayCount" to state.days.size,
                "isLoading" to state.isLoading,
            ),
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = strings.locLensHeatmapTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.days.isEmpty()) {
            Text(
                text = strings.locLensHeatmapNoData,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val untrackedBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Y-axis (fixed, not scrolling)
                HeatmapYAxis()
                Spacer(modifier = Modifier.width(4.dp))

                // Scrollable day columns - most recent leftmost
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    state.days.forEach { day ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(576.dp),
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    // Background untracked
                                    drawRect(color = untrackedBg)

                                    // Segments
                                    day.segments.forEach { seg ->
                                        val top = (seg.startMinute / HEATMAP_TOTAL_MINUTES.toFloat()) * size.height
                                        val segH = ((seg.durationMinutes / HEATMAP_TOTAL_MINUTES.toFloat()) * size.height).coerceAtLeast(1f)
                                        val dimColor = heatmapColorForDimensionId(seg.dimensionId, appPrefs)
                                        drawRect(
                                            color = dimColor,
                                            topLeft = Offset(0f, top),
                                            size = Size(size.width, segH),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = heatmapDayLabel(day.dayKey),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapYAxis() {
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(576.dp),
    ) {
        HEATMAP_HOUR_MARKS.forEach { hour ->
            val fraction = hour / 24f
            val topFraction = fraction
            Text(
                text = "${hour}h",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = (576 * topFraction).dp),
            )
        }
    }
}

private fun heatmapColorForDimensionId(dimensionId: String?, appPrefs: AppPreferencesState): Color = appPrefs.colorForDimensionId(dimensionId)
    ?: appPrefs.colorForDimension(dimensionId, null)
    ?: Color(0xFF9E9E9E)

private fun heatmapDayLabel(dayKey: String): String = runCatching {
    LocalDate.parse(dayKey.take(10)).format(heatmapDayFormatter)
}.getOrElse { dayKey.take(5) }
