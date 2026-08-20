//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

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
import io.payanam.ui.viewmodel.MinutePatternState
import io.payanam.ui.viewmodel.colorForDimensionId
import java.time.format.TextStyle
import java.util.Locale

private val minutePatternLogger = UnifiedLogger.getInstance()
private const val MINUTE_HEIGHT_DP = 1 // 1dp per minute, 1440dp total
private const val MP_UNTRACKED_SENTINEL = "__minute_untracked__"
private val MP_UNTRACKED_COLOR = Color(0xFF9E9E9E).copy(alpha = 0.4f)
private val MP_UNASSIGNED_COLOR = Color(0xFF9E9E9E)

@Composable
internal fun MinutePatternSection(
    /** State. */
    state: MinutePatternState,
    /** App prefs. */
    appPrefs: AppPreferencesState,
) {
    /** Launched effect. */
    LaunchedEffect(state.data.days.size) {
        minutePatternLogger.d(
            "MinutePatternSection",
            "Rendered minute pattern",
            /** Map of. */
            mapOf("days" to state.data.days.size),
        )
    }

    /** Column. */
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /** Text. */
        Text(
            text = stringResource(id = R.string.loc_lens_minute_pattern_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        /** If. */
        if (state.data.days.isEmpty()) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.loc_lens_minute_pattern_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            /** Grid line color. */
            val gridLineColor = MaterialTheme.colorScheme.onSurface
            /** Col boundary color. */
            val colBoundaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Fixed Y-axis with hour labels
                /** Minute pattern yaxis. */
                MinutePatternYAxis()

                // 7 day columns, equal weight
                state.data.days.forEach { day ->
                    /** Column. */
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Day-of-week label above column
                        /** Text. */
                        Text(
                            text = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Single Canvas per column: group consecutive same-winner minutes into one rect
                        /** Canvas. */
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((MINUTE_HEIGHT_DP * 1440).dp),
                        ) {
                            /** Min h. */
                            val minH = size.height / 1440f
                            /** Winners. */
                            val winners = day.minuteWinners
                            /** If. */
                            if (winners.isEmpty()) return@Canvas

                            // Draw color bands (grouped runs of same winner)
                            /** Run start. */
                            var runStart = 0
                            /** Run color. */
                            var runColor = colorForMinuteWinner(winners[0], appPrefs)
                            /** For. */
                            for (m in 1 until 1440) {
                                /** C. */
                                val c = colorForMinuteWinner(winners[m], appPrefs)
                                /** If. */
                                if (c != runColor) {
                                    /** Draw rect. */
                                    drawRect(
                                        color = runColor,
                                        topLeft = Offset(0f, runStart * minH),
                                        size = Size(size.width, (m - runStart) * minH),
                                    )
                                    runStart = m
                                    runColor = c
                                }
                            }
                            /** Draw rect. */
                            drawRect(
                                color = runColor,
                                topLeft = Offset(0f, runStart * minH),
                                size = Size(size.width, (1440 - runStart) * minH),
                            )

                            // Hour boundary lines (every 60 minutes)
                            /** Hour line color. */
                            val hourLineColor = gridLineColor.copy(alpha = 0.22f)
                            /** For. */
                            for (h in 0..24) {
                                /** Line y. */
                                val lineY = h * 60 * minH
                                /** Draw line. */
                                drawLine(
                                    color = hourLineColor,
                                    start = Offset(0f, lineY),
                                    end = Offset(size.width, lineY),
                                    strokeWidth = 1f,
                                )
                            }

                            // Left column boundary
                            /** Draw line. */
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
private fun MinutePatternYAxis() {
    /** Day label height dp. */
    val dayLabelHeightDp = 16
    /** Axis height dp. */
    val axisHeightDp = (MINUTE_HEIGHT_DP * 1440) + dayLabelHeightDp

    /** Box. */
    Box(
        modifier = Modifier
            .width(32.dp)
            .height(axisHeightDp.dp),
    ) {
        /** For. */
        for (hour in 0 until 24) {
            /** Y offset dp. */
            val yOffsetDp = dayLabelHeightDp + (hour * 60 * MINUTE_HEIGHT_DP)
            /** Text. */
            Text(
                text = "${hour}h",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = yOffsetDp.dp)
                    .padding(end = 2.dp),
            )
        }
    }
}

private fun colorForMinuteWinner(winnerId: String?, appPrefs: AppPreferencesState): Color = when {
    winnerId == MP_UNTRACKED_SENTINEL -> MP_UNTRACKED_COLOR
    winnerId == null -> MP_UNASSIGNED_COLOR
    else -> appPrefs.colorForDimensionId(winnerId) ?: MP_UNASSIGNED_COLOR
}
