//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
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
import io.payanam.ui.viewmodel.RadarAxis
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val TODAY_COLOR = Color(0xFF34D399)
private val AVG_COLOR = Color(0xFF818CF8)

/**
 * Dimension spread radar: one spoke per dimension, today's scores (solid) vs
 * running averages (dashed) — Canvas only, no chart library (Vico has no
 * polar support in the pinned version).
 */
@Composable
fun LensDimensionRadarSection(
    axes: List<RadarAxis>,
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
                Spacer(modifier = Modifier.width(10.dp))
                LegendDot(color = AVG_COLOR, label = stringResource(id = R.string.activity_detail_chart_running_avg))
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
                    mapOf("dimensions" to axes.size),
                )
                RadarCanvas(axes = axes, modifier = Modifier.fillMaxWidth())
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
    modifier: Modifier = Modifier,
) {
    val radiusFraction = 0.36f
    Canvas(modifier = modifier.height(230.dp)) {
        if (axes.isEmpty()) return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(size.width, size.height) * radiusFraction
        val n = axes.size
        val angleStep = (2.0 * Math.PI) / n

        fun point(index: Int, value: Float): Offset {
            val angle = -Math.PI / 2 + angleStep * index
            val r = radius * value.coerceIn(0f, 1.05f)
            return Offset(cx + (cos(angle) * r).toFloat(), cy + (sin(angle) * r).toFloat())
        }

        // Rings
        listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { ring ->
            val ringPath = Path()
            axes.indices.forEach { i ->
                val p = point(i, ring)
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
        axes.forEachIndexed { i, axis ->
            val p = point(i, 1f)
            drawLine(
                color = Color.White.copy(alpha = 0.06f),
                start = Offset(cx, cy),
                end = p,
                strokeWidth = 1f,
            )
            val labelP = point(i, 1.22f)
            drawContext.canvas.nativeCanvas.drawText(
                axis.label.take(10),
                labelP.x,
                labelP.y,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 9.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                },
            )
        }

        // Running avg polygon (dashed)
        val avgPath = Path()
        axes.forEachIndexed { i, axis ->
            val value = (axis.runningAvg ?: 0.0).toFloat()
            val p = point(i, value)
            if (i == 0) avgPath.moveTo(p.x, p.y) else avgPath.lineTo(p.x, p.y)
        }
        avgPath.close()
        drawPath(
            path = avgPath,
            color = AVG_COLOR.copy(alpha = 0.15f),
            style = Stroke(width = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
        )
        drawPath(path = avgPath, color = AVG_COLOR, style = Stroke(width = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))

        // Today polygon (solid)
        val todayPath = Path()
        axes.forEachIndexed { i, axis ->
            val value = (axis.today ?: 0.0).toFloat()
            val p = point(i, value)
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
