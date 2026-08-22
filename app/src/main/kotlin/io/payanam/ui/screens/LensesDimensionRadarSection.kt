//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.RadarAxis
import io.payanam.ui.viewmodel.ScoreMetricColumn
import io.payanam.ui.viewmodel.labelForDimensionId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val TODAY_COLOR = Color(0xFF34D399)
private val AVG_COLOR = Color(0xFF818CF8)

/** Score/Running-avg metrics have a meaningful running average; progress and
 *  streaks do not (their "avg" would be a fixed, misleading line). */
private fun hasAverageMetric(metric: ScoreMetricColumn): Boolean =
    metric == ScoreMetricColumn.SCORE || metric == ScoreMetricColumn.RUNNING_AVG

/**
 * Dimension spread radar: one spoke per dimension, today's scores (solid) vs
 * running averages (dashed) — Canvas only, no chart library (Vico has no
 * polar support in the pinned version).
 */
@Composable
fun LensDimensionRadarSection(
    axes: List<RadarAxis>,
    selectedMetric: ScoreMetricColumn = ScoreMetricColumn.PROGRESS,
    modifier: Modifier = Modifier,
) {
    val logger = remember { UnifiedLogger.getInstance() }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.loc_lens_radar_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                LegendDot(color = TODAY_COLOR, label = stringResource(id = R.string.loc_today))
                if (hasAverageMetric(selectedMetric)) {
                    Spacer(modifier = Modifier.width(10.dp))
                    LegendDot(color = AVG_COLOR, label = stringResource(id = R.string.activity_detail_chart_running_avg))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (axes.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.loc_lens_no_dimension_distribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                logger.d(
                    "LensDimensionRadarSection.rendered",
                    "Radar rendered",
                    mapOf("dimensions" to axes.size, "metric" to selectedMetric.key),
                )
                RadarCanvas(axes = axes, selectedMetric = selectedMetric, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RadarCanvas(
    axes: List<RadarAxis>,
    selectedMetric: ScoreMetricColumn,
    modifier: Modifier = Modifier,
) {
    val radiusFraction = 0.36f
    // User-custom dimension labels win; taxonomy fallback otherwise.
    val appPrefs = io.payanam.ui.viewmodel.LocalAppPreferences.current
    val displayAxes =
        axes.map { axis ->
            axis.copy(displayLabel = appPrefs.labelForDimensionId(axis.key) ?: axis.label)
        }
    // Score and Running-avg share the SAME radar: solid = today's score,
    // dashed = running avg. The matrix toggle between them must not change
    // what the radar plots.
    val radarMetric =
        if (selectedMetric == ScoreMetricColumn.RUNNING_AVG) ScoreMetricColumn.SCORE
        else selectedMetric
    Canvas(modifier = modifier.height(230.dp)) {
        if (axes.isEmpty()) return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(size.width, size.height) * radiusFraction
        val n = displayAxes.size
        val angleStep = (2.0 * Math.PI) / n

        // Normalize the selected metric across axes so the polygon is readable
        // regardless of unit (0..1 scores, ± progress, large streak counts).
        val todayValues = displayAxes.map { it.today(radarMetric) }.filterNotNull()
        val avgValues = displayAxes.map { it.runningAvg(radarMetric) }.filterNotNull()
        val scaleMax =
            maxOf(
                todayValues.maxOrNull()?.let { abs(it) } ?: 0.0,
                avgValues.maxOrNull()?.let { abs(it) } ?: 0.0,
                1e-6,
            )
        // Non-negative metrics (score, streaks, continue): 0 = CENTER, max = edge.
        // Signed metrics (progress, net): 0 = mid-ring, ±max = edge.
        val signedMetric =
            radarMetric == ScoreMetricColumn.PROGRESS ||
                radarMetric == ScoreMetricColumn.STREAK_NET
        /**
         * Normalized radius for a metric value (signed metrics center at 0).
         */
        fun scale(v: Double?): Float {
            val n = ((v ?: 0.0) / scaleMax).coerceIn(-1.0, 1.0)
            return (if (signedMetric) n else n * 2.0 - 1.0).toFloat()
        }
        /**
         * Canvas point for an axis index at a normalized value.
         */
        fun point(index: Int, value: Float): Offset {
            val angle = -Math.PI / 2 + angleStep * index
            val r = radius * (0.5f + value * 0.5f).coerceIn(0.05f, 1.0f)
            return Offset(cx + (cos(angle) * r).toFloat(), cy + (sin(angle) * r).toFloat())
        }

        // Rings
        listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { ring ->
            val ringPath = Path()
            displayAxes.indices.forEach { i ->
                val p = point(i, ring * 2f - 1f)
                if (i == 0) ringPath.moveTo(p.x, p.y) else ringPath.lineTo(p.x, p.y)
            }
            ringPath.close()
            drawPath(
                path = ringPath,
                color = Color.White.copy(alpha = 0.08f),
                style = Stroke(width = 1f),
            )
        }

        // Spokes + labels
        displayAxes.forEachIndexed { i, axis ->
            val p = point(i, 1f)
            drawLine(
                color = Color.White.copy(alpha = 0.06f),
                start = Offset(cx, cy),
                end = p,
                strokeWidth = 1f,
            )
            val labelP = point(i, 1.22f)
            val label = axis.displayLabel
            drawContext.canvas.nativeCanvas.drawText(
                label.take(10),
                labelP.x,
                labelP.y,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 9.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                },
            )
        }

        // Running avg polygon (dashed) — ONLY when the selected metric has a
        // meaningful average (Score / Running avg). Progress and streaks have
        // no average in the model, so showing a fixed second polygon would
        // be misleading — today-only for those.
        if (hasAverageMetric(radarMetric)) {
            val avgPath = Path()
            displayAxes.forEachIndexed { i, axis ->
                val p = point(i, scale(axis.runningAvg(radarMetric)))
                if (i == 0) avgPath.moveTo(p.x, p.y) else avgPath.lineTo(p.x, p.y)
            }
            avgPath.close()
            drawPath(
                path = avgPath,
                color = AVG_COLOR.copy(alpha = 0.15f),
                style = Stroke(width = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
            drawPath(path = avgPath, color = AVG_COLOR, style = Stroke(width = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }

        // Today polygon (solid)
        val todayPath = Path()
        displayAxes.forEachIndexed { i, axis ->
            val p = point(i, scale(axis.today(radarMetric)))
            if (i == 0) todayPath.moveTo(p.x, p.y) else todayPath.lineTo(p.x, p.y)
        }
        todayPath.close()
        drawPath(path = todayPath, color = TODAY_COLOR.copy(alpha = 0.18f))
        drawPath(
            path = todayPath,
            color = TODAY_COLOR,
            style = Stroke(width = 1.5f, cap = StrokeCap.Round),
        )
    }
}
