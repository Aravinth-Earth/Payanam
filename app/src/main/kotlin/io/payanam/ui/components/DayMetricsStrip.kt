//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.payanam.ui.viewmodel.DayMetricChipData

/**
 * Habits day-metrics strip: a 7-chip row of habit-cascade values + ranks,
 * mounted atop the Habits listing. Chips are in natural cascade order
 * (score → runningAvg → progress → streakPos → streakNet → posContinue, then Due).
 *
 * Layout tuned for phone width (380dp): a single 7-column grid, each chip
 * showing value (big), rank "X/Y" (green, dimmed if placeholder), and a tiny label.
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
    val rankColor = if (chip.isPlaceholderRank) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color(0xFF5FD38A)
    }
    Column(
        modifier = modifier
            .let { base ->
                if (onClick != null) base.clickable(onClick = onClick) else base
            }
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
