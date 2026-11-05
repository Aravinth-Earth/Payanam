//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.theme.scoreColor
import io.payanam.presentation.habits.DayMetricChipData

/**
 * Habits day-metrics strip: a 7-chip row of habit-cascade values + ranks,
 * mounted atop the Habits listing. Chips are in natural cascade order
 * (score → runningAvg → progress → streakPos → streakNet → posContinue, then Due).
 *
 * Layout tuned for phone width (380dp): a single 7-column grid, each chip
 * showing value (big), rank "X/Y" (coloured by that chip's OWN position on its
 * OWN scale, dimmed when there is no rank to colour), and a tiny label.
 */
@Composable
fun DayMetricsStrip(
    chips: List<DayMetricChipData>,
    modifier: Modifier = Modifier,
    onChipClick: (() -> Unit)? = null,
) {
    if (chips.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { chip ->
            DayMetricChip(chip = chip, modifier = Modifier.weight(1f), onClick = onChipClick)
        }
    }
}

@Composable
private fun DayMetricChip(chip: DayMetricChipData, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    // The rank colour is scaled by THIS chip's rank on THIS chip's own scale, and stays neutral when
    // there is no rank to scale - no history yet ("—"), or a placeholder rank (Due).
    val rankColor = dayMetricRankColor(chip = chip, neutralColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(
        modifier = modifier
            .let { base ->
                if (onClick != null) base.clickable(onClick = onClick) else base
            }
            .testTag("day_metric_chip")
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = chip.value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = chip.rank,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = rankColor,
            textAlign = TextAlign.Center,
        )
        Text(
            text = chip.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The colour of a chip's rank text.
 *
 * A rank is a position on that chip's OWN scale - today's 1-based rank among the distinct values that
 * metric has ever had - so the colour is scaled by that chip's own rank: 1 of N is the best value it
 * has ever had (green), N of N is the worst (red), and the ranks between are proportional. The mapping
 * goes through the shared [scoreColor] palette, so a rank never reads as a different severity than any
 * other score in the app.
 *
 * THE COLOUR IS REDUNDANT ENCODING, and the "X/Y" text is never dimmed away to make it work: the
 * text is drawn beside the colour and stays fully legible, so a user who cannot tell the colours apart
 * still reads the rank.
 *
 * @param chip the chip whose rank is being drawn.
 * @param neutralColor the theme's dimmed text colour, used when there is no rank to scale.
 */
internal fun dayMetricRankColor(
    chip: DayMetricChipData,
    neutralColor: Color,
): Color {
    // CASE 1 - a placeholder rank (Due) has no ordinal position at all, so there is nothing to scale.
    if (chip.isPlaceholderRank) return neutralColor

    // CASE 2 - no history: the rank reads "—" and both numbers are null. This is NEUTRAL, never green:
    // the strip used to paint EVERY non-placeholder rank green, so a metric with no history at all was
    // drawn as the best possible one. An absent number is not a good number.
    val rankNumber = chip.rankNumber
    val rankTotal = chip.rankTotal
    if (rankNumber == null || rankTotal == null) return neutralColor

    // CASE 3 - a single distinct historical value (rank 1 of 1) is the best there is, and the scale
    // below would divide by (total - 1) == 0.
    val goodness =
        if (rankTotal <= 1) {
            1f
        } else {
            1f - (rankNumber - 1).toFloat() / (rankTotal - 1).toFloat()
        }

    // Clamped, because a rank outside 1..total must not produce a colour outside the palette's range.
    return scoreColor(goodness.coerceIn(0f, 1f))
}
