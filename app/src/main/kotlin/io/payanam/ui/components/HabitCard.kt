//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.ui.components


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.ui.theme.scoreColor
import io.payanam.ui.viewmodel.colorForDimensionId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Get color for life dimension indicator.
 * Uses dimensionId-first resolution to keep customized colors stable.
 */
@Composable
/**
 * Returns the life dimension color.
 */
fun getLifeDimensionColor(dimensionId: String): Color {
    val preferences = io.payanam.ui.viewmodel.LocalAppPreferences.current
    return preferences.colorForDimensionId(dimensionId)
        ?: MaterialTheme.colorScheme.primary
}

/**
 * Status for a single day's checkmark in the habit grid.
 */
enum class CheckmarkStatus {
    COMPLETED, // Green check - task done
    SKIPPED, // Grey dash - intentionally skipped
    MISSED, // Red X - missed/not done
    PENDING, // Hollow circle - not yet due (future)
    UNKNOWN, // Faded - no data
}

private val dayNumberFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d")

/**
 * Data for a single day's checkmark.
 */
@Immutable
/**
 * Holds the day checkmark.
 */
data class DayCheckmark(
    val date: LocalDate,
    val status: CheckmarkStatus,
    val hasNote: Boolean = false,
    val note: String? = null,
)

/**
 * Calculate how many checkmark buttons can fit based on screen width.
 * Similar to uHabits' buttonCount calculation.
 */
@Composable
/**
 * Performs the calculate button count.
 */
fun calculateButtonCount(
    scoreRingWidth: Dp = 28.dp, // Reduced from 48dp
    labelWidth: Dp = 120.dp, // Keep more width reserved for habit title text
    buttonWidth: Dp = 30.dp,
    horizontalPadding: Dp = 16.dp, // Reduced from 32dp
    minButtons: Int = 3,
    maxButtons: Int = 10,
): Int {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val availableWidth = screenWidthDp - scoreRingWidth - labelWidth - horizontalPadding - 16.dp
    val count = (availableWidth / buttonWidth).toInt()

    return count.coerceIn(minButtons, maxButtons)
}

/**
 * Score Ring - circular progress indicator showing the task's decay score.
 * Similar to uHabits' ScoreRing.
 */
@Composable
/**
 * Performs the score ring.
 */
fun ScoreRing(
    score: Double,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp, // Slightly smaller to free title space
    strokeWidth: Dp = 3.dp, // Reduced from 4dp
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val scoreColorValue = scoreColor(score.toFloat())
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokeWidthPx = strokeWidth.toPx()
            val radius = (size.toPx() - strokeWidthPx) / 2

            // Background circle
            drawCircle(
                color = backgroundColor,
                radius = radius,
                style = Stroke(width = strokeWidthPx),
            )

            // Progress arc
            drawArc(
                color = scoreColorValue,
                startAngle = -90f,
                sweepAngle = 360f * score.toFloat(),
                useCenter = false,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
            )
        }

        // Score percentage text
        Text(
            text = "${(score.toFloat() * 100).toInt()}",
            style = MaterialTheme.typography.labelSmall, // Reduced from labelMedium
            fontWeight = FontWeight.Bold,
            color = scoreColorValue,
        )
    }
}

/**
 * Individual checkmark button for a single day.
 *
 * Click: Quick toggle (if enabled) or open dialog
 * Long-press: Open edit dialog
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
/**
 * Returns true when the checkmark button.
 */
fun CheckmarkButton(
    checkmark: DayCheckmark,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    shortToggleEnabled: Boolean = true,
) {
    val logger = UnifiedLogger.getInstance()
    val haptic = LocalHapticFeedback.current
    val backgroundColor = when (checkmark.status) {
        CheckmarkStatus.COMPLETED -> Color(0xFF4CAF50).copy(alpha = 0.9f)
        CheckmarkStatus.SKIPPED -> Color(0xFF9E9E9E).copy(alpha = 0.6f)
        CheckmarkStatus.MISSED -> Color(0xFFF44336).copy(alpha = 0.8f)
        CheckmarkStatus.PENDING -> Color.Transparent
        CheckmarkStatus.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    val borderColor = when (checkmark.status) {
        CheckmarkStatus.PENDING -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val iconColor = when (checkmark.status) {
        CheckmarkStatus.COMPLETED -> Color.White
        CheckmarkStatus.SKIPPED -> Color.White
        CheckmarkStatus.MISSED -> Color.White
        CheckmarkStatus.PENDING -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        CheckmarkStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(
                width = if (checkmark.status == CheckmarkStatus.PENDING) 1.2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(6.dp),
            )
            .combinedClickable(
                onClick = {
                    logger.i("CheckmarkButton", "Checkmark clicked", mapOf("date" to checkmark.date.toString(), "status" to checkmark.status.name, "shortToggle" to shortToggleEnabled.toString()))
                    if (shortToggleEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    onClick()
                },
                onLongClick = {
                    logger.i("CheckmarkButton", "Checkmark long clicked", mapOf("date" to checkmark.date.toString(), "status" to checkmark.status.name))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (checkmark.status) {
            CheckmarkStatus.COMPLETED -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_completed),
                    tint = iconColor,
                    modifier = Modifier.size(size * 0.6f),
                )
            }

            CheckmarkStatus.SKIPPED -> {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_skipped),
                    tint = iconColor,
                    modifier = Modifier.size(size * 0.5f),
                )
            }

            CheckmarkStatus.MISSED -> {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_missed),
                    tint = iconColor,
                    modifier = Modifier.size(size * 0.5f),
                )
            }

            CheckmarkStatus.PENDING, CheckmarkStatus.UNKNOWN -> {
                // Question mark for pending/not filled
                Icon(
                    imageVector = Icons.Default.QuestionMark,
                    contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_not_filled),
                    tint = iconColor,
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        }

        // Notes indicator dot
        if (checkmark.hasNote) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * Panel of checkmark buttons showing past N days.
 * Responsive: button count adjusts to screen width.
 */
@Composable
/**
 * Returns true when the checkmark panel.
 */
fun CheckmarkPanel(
    checkmarks: List<DayCheckmark>,
    onCheckmarkClick: (DayCheckmark) -> Unit,
    onCheckmarkLongClick: (DayCheckmark) -> Unit,
    modifier: Modifier = Modifier,
    shortToggleEnabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in checkmarks.indices.reversed()) {
            val checkmark = checkmarks[index]
            CheckmarkButton(
                checkmark = checkmark,
                onClick = { onCheckmarkClick(checkmark) },
                onLongClick = { onCheckmarkLongClick(checkmark) },
                shortToggleEnabled = shortToggleEnabled,
            )
        }
    }
}

/**
 * HabitCard - main composable for displaying a recurring task as a habit.
 *
 * Layout: [ScoreRing] [Title] [CheckmarkPanel for past N days]
 *
 * Similar to uHabits' HabitCardView:
 * - Score ring shows current decay score
 * - Checkmark grid shows past N days of completions
 * - Click on card to view details
 * - Click on checkmark to toggle (short mode) or edit
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
/**
 * Performs the habit card.
 */
fun HabitCard(
    task: Task,
    checkmarks: List<DayCheckmark>,
    onCardClick: () -> Unit,
    onCheckmarkClick: (DayCheckmark) -> Unit,
    onCheckmarkLongClick: (DayCheckmark) -> Unit,
    modifier: Modifier = Modifier,
    buttonCount: Int = calculateButtonCount(),
    shortToggleEnabled: Boolean = true,
    latestL1RunningAvg: Double? = null,
) {
    val logger = UnifiedLogger.getInstance()
    val displayCheckmarks = remember(checkmarks, buttonCount) {
        checkmarks.take(buttonCount)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        logger.i("HabitCard", "Card clicked", mapOf("taskId" to task.id, "taskTitle" to task.title))
                        onCardClick()
                    },
                    onLongClick = { }, // Could add multi-select later
                )
                .padding(horizontal = 3.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Life dimension color bar indicator (Phase 4: use dimensionId)
            task.dimensionId?.let { dimensionId ->
                Box(
                    modifier = Modifier
                        .width(2.dp) // Reduced from 4dp
                        .height(22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(getLifeDimensionColor(dimensionId)),
                )
                Spacer(modifier = Modifier.width(4.dp)) // Reduced from 8dp
            }

            // Score Ring — Inc 4: shows the habit's latest L1 runningAvg as a
            // percentage (ScoreRing renders 0..1; text shows score*100).
            ScoreRing(
                score = latestL1RunningAvg ?: 0.0,
                modifier = Modifier.padding(end = 4.dp),
            )

            // Title (takes remaining space after checkmarks)
            Text(
                text = task.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // Checkmark Panel (Canvas-based)
            CheckmarkPanelCanvas(
                checkmarks = displayCheckmarks,
                onCheckmarkClick = onCheckmarkClick,
                onCheckmarkLongClick = onCheckmarkLongClick,
                shortToggleEnabled = shortToggleEnabled,
            )
        }
    }
}

/**
 * Day header showing day labels (e.g., "SUN\n26") above checkmarks.
 * Like uHabits HeaderView with weekday + day number.
 */
@Composable
/**
 * Performs the day header row.
 */
fun DayHeaderRow(
    buttonCount: Int,
    modifier: Modifier = Modifier,
    scoreRingWidth: Dp = 28.dp + 6.dp, // ring + padding (reduced)
    scoreRingPadding: Dp = 0.dp,
) {
    val today = LocalDate.now()
    val weekdayFormatter = remember(Locale.getDefault()) {
        DateTimeFormatter.ofPattern("EEE").withLocale(Locale.getDefault())
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp), // Match HabitCard horizontal padding
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Account for life dimension bar (3dp + 6dp spacing)
        Spacer(modifier = Modifier.width(9.dp))

        // Spacer for score ring
        Spacer(modifier = Modifier.width(scoreRingWidth + scoreRingPadding))

        // Spacer for title area
        Spacer(modifier = Modifier.weight(1f))

        // Day labels (most recent on right) - like uHabits
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            (buttonCount - 1 downTo 0).forEach { daysAgo ->
                val date = today.minusDays(daysAgo.toLong())
                val weekday = date.format(weekdayFormatter).uppercase().take(3)
                val dayNum = date.format(dayNumberFormatter)
                val isToday = daysAgo == 0
                Column(
                    modifier = Modifier.size(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = weekday,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85f,
                        ),
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        text = dayNum,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.9f,
                        ),
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
