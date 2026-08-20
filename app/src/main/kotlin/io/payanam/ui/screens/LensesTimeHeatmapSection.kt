//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.HeatmapDayData
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.HeatmapState
import io.payanam.ui.viewmodel.colorForDimension
import io.payanam.ui.viewmodel.colorForDimensionId
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val heatmapLogger = UnifiedLogger.getInstance()
private val heatmapDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
private const val HEATMAP_TOTAL_MINUTES = 24 * 60
private val HEATMAP_HOUR_MARKS = listOf(0, 6, 12, 18, 24)

@Composable
internal fun DimensionHeatmapSection(
    /** State. */
    state: HeatmapState,
    /** App prefs. */
    appPrefs: AppPreferencesState,
) {
    /** Launched effect. */
    LaunchedEffect(state.days.size) {
        heatmapLogger.d(
            "DimensionHeatmapSection",
            "Rendered dimension heatmap section",
            /** Map of. */
            mapOf(
                "dayCount" to state.days.size,
                "isLoading" to state.isLoading,
            ),
        )
    }

    /** Column. */
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /** Text. */
        Text(
            text = stringResource(id = R.string.loc_lens_heatmap_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        /** If. */
        if (state.days.isEmpty()) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_heatmap_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            /** Untracked bg. */
            val untrackedBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Y-axis (fixed, not scrolling)
                /** Heatmap yaxis. */
                HeatmapYAxis()

                /** Spacer. */
                Spacer(modifier = Modifier.width(4.dp))

                // Scrollable day columns - most recent leftmost
                /** Row. */
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    state.days.forEach { day ->
                        /** Column. */
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            /** Box. */
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(576.dp),
                            ) {
                                /** Canvas. */
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    // Background untracked
                                    /** Draw rect. */
                                    drawRect(color = untrackedBg)

                                    // Segments
                                    day.segments.forEach { seg ->
                                        /** Top. */
                                        val top = (seg.startMinute / HEATMAP_TOTAL_MINUTES.toFloat()) * size.height
                                        /** Seg h. */
                                        val segH = ((seg.durationMinutes / HEATMAP_TOTAL_MINUTES.toFloat()) * size.height).coerceAtLeast(1f)
                                        /** Dim color. */
                                        val dimColor = heatmapColorForDimensionId(seg.dimensionId, appPrefs)
                                        /** Draw rect. */
                                        drawRect(
                                            color = dimColor,
                                            topLeft = Offset(0f, top),
                                            size = Size(size.width, segH),
                                        )
                                    }
                                }
                            }
                            /** Spacer. */
                            Spacer(modifier = Modifier.height(2.dp))
                            /** Text. */
                            Text(
                                text = heatmapDayLabel(day.dayKey),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        /** Spacer. */
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapYAxis() {
    /** Box. */
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(576.dp),
    ) {
        HEATMAP_HOUR_MARKS.forEach { hour ->
            /** Fraction. */
            val fraction = hour / 24f
            /** Top fraction. */
            val topFraction = fraction
            /** Text. */
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
