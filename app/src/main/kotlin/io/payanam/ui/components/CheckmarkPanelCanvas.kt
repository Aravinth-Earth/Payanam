//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single-Canvas CheckmarkPanel — draws all checkmarks in one pass.
 *
 * Replaces the Row-of-CheckmarkButtons approach with a single Canvas draw.
 * Reduces composable count from ~45 per card to ~1.
 *
 * Touch handling: calculates which checkmark was tapped from x-coordinate.
 */
@Composable
fun CheckmarkPanelCanvas(
    checkmarks: List<DayCheckmark>,
    onCheckmarkClick: (DayCheckmark) -> Unit,
    onCheckmarkLongClick: (DayCheckmark) -> Unit,
    modifier: Modifier = Modifier,
    shortToggleEnabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // Pre-compute colors once (not per-button)
    val completedColor = Color(0xFF4CAF50).copy(alpha = 0.9f)
    val skippedColor = Color(0xFF9E9E9E).copy(alpha = 0.6f)
    val missedColor = Color(0xFFF44336).copy(alpha = 0.8f)
    val pendingBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val unknownBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val pendingIcon = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val unknownIcon = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val notesColor = MaterialTheme.colorScheme.tertiary

    // Layout constants
    val buttonSizePx = with(density) { 28.dp.toPx() }
    val spacingPx = with(density) { 3.dp.toPx() }
    val cornerRadiusPx = with(density) { 6.dp.toPx() }
    val borderWidthPx = with(density) { 1.2.dp.toPx() }
    val notesDotRadius = with(density) { 3.dp.toPx() }

    // Total width = sum of button sizes + spacing
    val totalCount = checkmarks.size
    val totalWidthPx = totalCount * buttonSizePx + (totalCount - 1) * spacingPx
    val totalHeightPx = buttonSizePx

    val totalWidthDp = with(density) { totalWidthPx.toDp() }
    val totalHeightDp = with(density) { totalHeightPx.toDp() }

    // Stable checkmarks reference for click calculation
    val reversedCheckmarks = remember(checkmarks) { checkmarks.reversed() }

    Canvas(
        modifier = modifier
            .size(width = totalWidthDp, height = totalHeightDp)
            .pointerInput(reversedCheckmarks) {
                detectTapGestures(
                    onTap = { offset ->
                        val index = (offset.x / (buttonSizePx + spacingPx)).toInt()
                            .coerceIn(0, reversedCheckmarks.lastIndex)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCheckmarkClick(reversedCheckmarks[index])
                    },
                    onLongPress = { offset ->
                        val index = (offset.x / (buttonSizePx + spacingPx)).toInt()
                            .coerceIn(0, reversedCheckmarks.lastIndex)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCheckmarkLongClick(reversedCheckmarks[index])
                    },
                )
            },
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Draw each checkmark in a single pass
        for (i in reversedCheckmarks.indices) {
            val checkmark = reversedCheckmarks[i]
            val x = i * (buttonSizePx + spacingPx)
            val y = (canvasHeight - buttonSizePx) / 2

            drawCheckmark(
                checkmark = checkmark,
                x = x,
                y = y,
                size = buttonSizePx,
                cornerRadius = cornerRadiusPx,
                borderWidth = borderWidthPx,
                completedColor = completedColor,
                skippedColor = skippedColor,
                missedColor = missedColor,
                pendingBorder = pendingBorder,
                unknownBg = unknownBg,
                pendingIcon = pendingIcon,
                unknownIcon = unknownIcon,
                notesColor = notesColor,
                notesDotRadius = notesDotRadius,
                textMeasurer = textMeasurer,
            )
        }
    }
}

/**
 * Draw a single checkmark in the Canvas.
 */
private fun DrawScope.drawCheckmark(
    checkmark: DayCheckmark,
    x: Float,
    y: Float,
    size: Float,
    cornerRadius: Float,
    borderWidth: Float,
    completedColor: Color,
    skippedColor: Color,
    missedColor: Color,
    pendingBorder: Color,
    unknownBg: Color,
    pendingIcon: Color,
    unknownIcon: Color,
    notesColor: Color,
    notesDotRadius: Float,
    textMeasurer: TextMeasurer,
) {
    val rect = androidx.compose.ui.geometry.Rect(x, y, x + size, y + size)
    val cornerShape = CornerRadius(cornerRadius, cornerRadius)

    // Background
    val bgColor = when (checkmark.status) {
        CheckmarkStatus.COMPLETED -> completedColor
        CheckmarkStatus.SKIPPED -> skippedColor
        CheckmarkStatus.MISSED -> missedColor
        CheckmarkStatus.PENDING -> Color.Transparent
        CheckmarkStatus.UNKNOWN -> unknownBg
    }

    if (bgColor != Color.Transparent) {
        drawRoundRect(
            color = bgColor,
            topLeft = Offset(x, y),
            size = Size(size, size),
            cornerRadius = cornerShape,
        )
    }

    // Border (only for PENDING)
    if (checkmark.status == CheckmarkStatus.PENDING) {
        drawRoundRect(
            color = pendingBorder,
            topLeft = Offset(x, y),
            size = Size(size, size),
            cornerRadius = cornerShape,
            style = Stroke(
                width = borderWidth,
                pathEffect = PathEffect.cornerPathEffect(cornerRadius),
            ),
        )
    }

    // Icon glyph (drawn as text)
    val iconColor = when (checkmark.status) {
        CheckmarkStatus.COMPLETED, CheckmarkStatus.SKIPPED, CheckmarkStatus.MISSED -> Color.White
        CheckmarkStatus.PENDING -> pendingIcon
        CheckmarkStatus.UNKNOWN -> unknownIcon
    }

    val glyph = when (checkmark.status) {
        CheckmarkStatus.COMPLETED -> "✓"
        CheckmarkStatus.SKIPPED -> "−"
        CheckmarkStatus.MISSED -> "✗"
        CheckmarkStatus.PENDING -> "?"
        CheckmarkStatus.UNKNOWN -> "?"
    }

    val fontSize = when (checkmark.status) {
        CheckmarkStatus.PENDING, CheckmarkStatus.UNKNOWN -> 12.sp
        else -> 14.sp
    }

    val textStyle = TextStyle(
        color = iconColor,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
    )

    val textLayout = textMeasurer.measure(glyph, textStyle)
    val textX = x + (size - textLayout.size.width) / 2
    val textY = y + (size - textLayout.size.height) / 2

    drawText(textLayout, topLeft = Offset(textX, textY))

    // Notes indicator dot
    if (checkmark.hasNote) {
        drawCircle(
            color = notesColor,
            radius = notesDotRadius,
            center = Offset(x + size - notesDotRadius, y + notesDotRadius),
        )
    }
}
