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
/**
 * Checkmark panel canvas.
 */
fun CheckmarkPanelCanvas(
    checkmarks: List<DayCheckmark>,
    onCheckmarkClick: (DayCheckmark) -> Unit,
    onCheckmarkLongClick: (DayCheckmark) -> Unit,
    modifier: Modifier = Modifier,
    shortToggleEnabled: Boolean = true,
) {
    /** Haptic. */
    val haptic = LocalHapticFeedback.current
    /** Density. */
    val density = LocalDensity.current
    /** Text measurer. */
    val textMeasurer = rememberTextMeasurer()

    // Pre-compute colors once (not per-button)
    /** Completed color. */
    val completedColor = Color(0xFF4CAF50).copy(alpha = 0.9f)
    /** Skipped color. */
    val skippedColor = Color(0xFF9E9E9E).copy(alpha = 0.6f)
    /** Missed color. */
    val missedColor = Color(0xFFF44336).copy(alpha = 0.8f)
    /** Pending border. */
    val pendingBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    /** Unknown bg. */
    val unknownBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    /** Pending icon. */
    val pendingIcon = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    /** Unknown icon. */
    val unknownIcon = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    /** Notes color. */
    val notesColor = MaterialTheme.colorScheme.tertiary

    // Layout constants
    /** Button size px. */
    val buttonSizePx = with(density) { 28.dp.toPx() }
    /** Spacing px. */
    val spacingPx = with(density) { 3.dp.toPx() }
    /** Corner radius px. */
    val cornerRadiusPx = with(density) { 6.dp.toPx() }
    /** Border width px. */
    val borderWidthPx = with(density) { 1.2.dp.toPx() }
    /** Notes dot radius. */
    val notesDotRadius = with(density) { 3.dp.toPx() }

    // Total width = sum of button sizes + spacing
    /** Total count. */
    val totalCount = checkmarks.size
    /** Total width px. */
    val totalWidthPx = totalCount * buttonSizePx + (totalCount - 1) * spacingPx
    /** Total height px. */
    val totalHeightPx = buttonSizePx

    /** Total width dp. */
    val totalWidthDp = with(density) { totalWidthPx.toDp() }
    /** Total height dp. */
    val totalHeightDp = with(density) { totalHeightPx.toDp() }

    // Stable checkmarks reference for click calculation
    /** Reversed checkmarks. */
    val reversedCheckmarks = remember(checkmarks) { checkmarks.reversed() }

    /** Canvas. */
    Canvas(
        modifier = modifier
            .size(width = totalWidthDp, height = totalHeightDp)
            .pointerInput(reversedCheckmarks) {
                /** Detect tap gestures. */
                detectTapGestures(
                    onTap = { offset ->
                        /** Index. */
                        val index = (offset.x / (buttonSizePx + spacingPx)).toInt()
                            .coerceIn(0, reversedCheckmarks.lastIndex)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        /** On checkmark click. */
                        onCheckmarkClick(reversedCheckmarks[index])
                    },
                    onLongPress = { offset ->
                        /** Index. */
                        val index = (offset.x / (buttonSizePx + spacingPx)).toInt()
                            .coerceIn(0, reversedCheckmarks.lastIndex)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        /** On checkmark long click. */
                        onCheckmarkLongClick(reversedCheckmarks[index])
                    },
                )
            },
    ) {
        /** Canvas width. */
        val canvasWidth = size.width
        /** Canvas height. */
        val canvasHeight = size.height

        // Draw each checkmark in a single pass
        /** For. */
        for (i in reversedCheckmarks.indices) {
            /** Checkmark. */
            val checkmark = reversedCheckmarks[i]
            /** X. */
            val x = i * (buttonSizePx + spacingPx)
            /** Y. */
            val y = (canvasHeight - buttonSizePx) / 2

            /** Draw checkmark. */
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
    /** Checkmark. */
    checkmark: DayCheckmark,
    /** X. */
    x: Float,
    /** Y. */
    y: Float,
    /** Size. */
    size: Float,
    /** Corner radius. */
    cornerRadius: Float,
    /** Border width. */
    borderWidth: Float,
    /** Completed color. */
    completedColor: Color,
    /** Skipped color. */
    skippedColor: Color,
    /** Missed color. */
    missedColor: Color,
    /** Pending border. */
    pendingBorder: Color,
    /** Unknown bg. */
    unknownBg: Color,
    /** Pending icon. */
    pendingIcon: Color,
    /** Unknown icon. */
    unknownIcon: Color,
    /** Notes color. */
    notesColor: Color,
    /** Notes dot radius. */
    notesDotRadius: Float,
    /** Text measurer. */
    textMeasurer: TextMeasurer,
) {
    /** Rect. */
    val rect = androidx.compose.ui.geometry.Rect(x, y, x + size, y + size)
    /** Corner shape. */
    val cornerShape = CornerRadius(cornerRadius, cornerRadius)

    // Background
    /** Bg color. */
    val bgColor = when (checkmark.status) {
        CheckmarkStatus.COMPLETED -> completedColor
        CheckmarkStatus.SKIPPED -> skippedColor
        CheckmarkStatus.MISSED -> missedColor
        CheckmarkStatus.PENDING -> Color.Transparent
        CheckmarkStatus.UNKNOWN -> unknownBg
    }

    /** If. */
    if (bgColor != Color.Transparent) {
        /** Draw round rect. */
        drawRoundRect(
            color = bgColor,
            topLeft = Offset(x, y),
            size = Size(size, size),
            cornerRadius = cornerShape,
        )
    }

    // Border (only for PENDING)
    /** If. */
    if (checkmark.status == CheckmarkStatus.PENDING) {
        /** Draw round rect. */
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
    /** Icon color. */
    val iconColor = when (checkmark.status) {
        CheckmarkStatus.COMPLETED, CheckmarkStatus.SKIPPED, CheckmarkStatus.MISSED -> Color.White
        CheckmarkStatus.PENDING -> pendingIcon
        CheckmarkStatus.UNKNOWN -> unknownIcon
    }

    /** Glyph. */
    val glyph = when (checkmark.status) {
        CheckmarkStatus.COMPLETED -> "✓"
        CheckmarkStatus.SKIPPED -> "−"
        CheckmarkStatus.MISSED -> "✗"
        CheckmarkStatus.PENDING -> "?"
        CheckmarkStatus.UNKNOWN -> "?"
    }

    /** Font size. */
    val fontSize = when (checkmark.status) {
        CheckmarkStatus.PENDING, CheckmarkStatus.UNKNOWN -> 12.sp
        else -> 14.sp
    }

    /** Text style. */
    val textStyle = TextStyle(
        color = iconColor,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
    )

    /** Text layout. */
    val textLayout = textMeasurer.measure(glyph, textStyle)
    /** Text x. */
    val textX = x + (size - textLayout.size.width) / 2
    /** Text y. */
    val textY = y + (size - textLayout.size.height) / 2

    /** Draw text. */
    drawText(textLayout, topLeft = Offset(textX, textY))

    // Notes indicator dot
    /** If. */
    if (checkmark.hasNote) {
        /** Draw circle. */
        drawCircle(
            color = notesColor,
            radius = notesDotRadius,
            center = Offset(x + size - notesDotRadius, y + notesDotRadius),
        )
    }
}
