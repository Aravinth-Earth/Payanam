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
/**
 * Lens dimension radar section.
 */
fun LensDimensionRadarSection(
    axes: List<RadarAxis>,
    selectedMetric: ScoreMetricColumn = ScoreMetricColumn.PROGRESS,
    modifier: Modifier = Modifier,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Card. */
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        /** Column. */
        Column(modifier = Modifier.padding(12.dp)) {
            /** Row. */
            Row(verticalAlignment = Alignment.CenterVertically) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.loc_lens_radar_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                /** Spacer. */
                Spacer(modifier = Modifier.weight(1f))
                /** Legend dot. */
                LegendDot(color = TODAY_COLOR, label = stringResource(id = R.string.loc_today))
                /** If. */
                if (hasAverageMetric(selectedMetric)) {
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(10.dp))
                    /** Legend dot. */
                    LegendDot(color = AVG_COLOR, label = stringResource(id = R.string.activity_detail_chart_running_avg))
                }
            }
            /** Spacer. */
            Spacer(modifier = Modifier.height(8.dp))
            /** If. */
            if (axes.isEmpty()) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.loc_lens_no_dimension_distribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                logger.d(
                    "LensDimensionRadarSection.rendered",
                    "Radar rendered",
                    /** Map of. */
                    mapOf("dimensions" to axes.size, "metric" to selectedMetric.key),
                )
                /** Radar canvas. */
                RadarCanvas(axes = axes, selectedMetric = selectedMetric, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    /** Row. */
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier =
                /** Modifier. */
                Modifier
                    .size(8.dp)
                    .background(color, CircleShape),
        )
        /** Spacer. */
        Spacer(modifier = Modifier.width(4.dp))
        /** Text. */
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
    /** Selected metric. */
    selectedMetric: ScoreMetricColumn,
    modifier: Modifier = Modifier,
) {
    /** Radius fraction. */
    val radiusFraction = 0.36f
    // User-custom dimension labels win; taxonomy fallback otherwise.
    /** App prefs. */
    val appPrefs = io.payanam.ui.viewmodel.LocalAppPreferences.current
    /** Display axes. */
    val displayAxes =
        axes.map { axis ->
            axis.copy(displayLabel = appPrefs.labelForDimensionId(axis.key) ?: axis.label)
        }
    // Score and Running-avg share the SAME radar: solid = today's score,
    // dashed = running avg. The matrix toggle between them must not change
    // what the radar plots.
    /** Radar metric. */
    val radarMetric =
        /** If. */
        if (selectedMetric == ScoreMetricColumn.RUNNING_AVG) ScoreMetricColumn.SCORE
        else selectedMetric
    /** Canvas. */
    Canvas(modifier = modifier.height(230.dp)) {
        /** If. */
        if (axes.isEmpty()) return@Canvas
        /** Cx. */
        val cx = size.width / 2f
        /** Cy. */
        val cy = size.height / 2f
        /** Radius. */
        val radius = min(size.width, size.height) * radiusFraction
        /** N. */
        val n = displayAxes.size
        /** Angle step. */
        val angleStep = (2.0 * Math.PI) / n

        // Normalize the selected metric across axes so the polygon is readable
        // regardless of unit (0..1 scores, ± progress, large streak counts).
        /** Today values. */
        val todayValues = displayAxes.map { it.today(radarMetric) }.filterNotNull()
        /** Avg values. */
        val avgValues = displayAxes.map { it.runningAvg(radarMetric) }.filterNotNull()
        /** Scale max. */
        val scaleMax =
            /** Max of. */
            maxOf(
                todayValues.maxOrNull()?.let { abs(it) } ?: 0.0,
                avgValues.maxOrNull()?.let { abs(it) } ?: 0.0,
                1e-6,
            )
        // Non-negative metrics (score, streaks, continue): 0 = CENTER, max = edge.
        // Signed metrics (progress, net): 0 = mid-ring, ±max = edge.
        /** Signed metric. */
        val signedMetric =
            radarMetric == ScoreMetricColumn.PROGRESS ||
                radarMetric == ScoreMetricColumn.STREAK_NET
        /**
         * Scale.
         */
        fun scale(v: Double?): Float {
            /** N. */
            val n = ((v ?: 0.0) / scaleMax).coerceIn(-1.0, 1.0)
            /** Return. */
            return (if (signedMetric) n else n * 2.0 - 1.0).toFloat()
        }

        /**
         * Point.
         */
        fun point(index: Int, value: Float): Offset {
            /** Angle. */
            val angle = -Math.PI / 2 + angleStep * index
            /** R. */
            val r = radius * (0.5f + value * 0.5f).coerceIn(0.05f, 1.0f)
            return Offset(cx + (cos(angle) * r).toFloat(), cy + (sin(angle) * r).toFloat())
        }

        // Rings
        /** List of. */
        listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { ring ->
            /** Ring path. */
            val ringPath = Path()
            displayAxes.indices.forEach { i ->
                /** P. */
                val p = point(i, ring * 2f - 1f)
                /** If. */
                if (i == 0) ringPath.moveTo(p.x, p.y) else ringPath.lineTo(p.x, p.y)
            }
            ringPath.close()
            /** Draw path. */
            drawPath(
                path = ringPath,
                color = Color.White.copy(alpha = 0.08f),
                style = Stroke(width = 1f),
            )
        }

        // Spokes + labels
        displayAxes.forEachIndexed { i, axis ->
            /** P. */
            val p = point(i, 1f)
            /** Draw line. */
            drawLine(
                color = Color.White.copy(alpha = 0.06f),
                start = Offset(cx, cy),
                end = p,
                strokeWidth = 1f,
            )
            /** Label p. */
            val labelP = point(i, 1.22f)
            /** Label. */
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
        /** If. */
        if (hasAverageMetric(radarMetric)) {
            /** Avg path. */
            val avgPath = Path()
            displayAxes.forEachIndexed { i, axis ->
                /** P. */
                val p = point(i, scale(axis.runningAvg(radarMetric)))
                /** If. */
                if (i == 0) avgPath.moveTo(p.x, p.y) else avgPath.lineTo(p.x, p.y)
            }
            avgPath.close()
            /** Draw path. */
            drawPath(
                path = avgPath,
                color = AVG_COLOR.copy(alpha = 0.15f),
                style = Stroke(width = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
            /** Draw path. */
            drawPath(path = avgPath, color = AVG_COLOR, style = Stroke(width = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }

        // Today polygon (solid)
        /** Today path. */
        val todayPath = Path()
        displayAxes.forEachIndexed { i, axis ->
            /** P. */
            val p = point(i, scale(axis.today(radarMetric)))
            /** If. */
            if (i == 0) todayPath.moveTo(p.x, p.y) else todayPath.lineTo(p.x, p.y)
        }
        todayPath.close()
        /** Draw path. */
        drawPath(path = todayPath, color = TODAY_COLOR.copy(alpha = 0.18f))
        /** Draw path. */
        drawPath(
            path = todayPath,
            color = TODAY_COLOR,
            style = Stroke(width = 1.5f, cap = StrokeCap.Round),
        )
    }
}
