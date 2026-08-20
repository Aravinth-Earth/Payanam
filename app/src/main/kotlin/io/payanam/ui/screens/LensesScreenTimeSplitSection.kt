//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger

internal data class LensTimeSplitItem(
    /** Label. */
    val label: String,
    /** Minutes. */
    val minutes: Int,
)

@Composable
internal fun LensTimeSplitCard(
    /** Title. */
    title: String,
    items: List<LensTimeSplitItem>,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Launched effect. */
    LaunchedEffect(title, items.size) {
        logger.d(
            "LensesScreenTimeSplitSection.LensTimeSplitCard",
            "Rendered compact time split card",
            /** Map of. */
            mapOf("title" to title, "itemCount" to items.size),
        )
    }
    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
    ) {
        /** Column. */
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            /** Text. */
            Text(text = title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            items.chunked(2).forEach { pair ->
                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEachIndexed { index, item ->
                        /** Lens time split tile. */
                        LensTimeSplitTile(
                            label = item.label,
                            value = formatMinutes(item.minutes),
                            markerIndex = index,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    /** If. */
                    if (pair.size < 2) {
                        /** Spacer. */
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LensTimeSplitTile(
    /** Label. */
    label: String,
    /** Value. */
    value: String,
    /** Marker index. */
    markerIndex: Int,
    modifier: Modifier = Modifier,
) {
    /** Marker color. */
    val markerColor = when (markerIndex) {
        0 -> MaterialTheme.colorScheme.primary
        1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    /** Row. */
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /** Box. */
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = markerColor, shape = CircleShape),
        )
        /** Column. */
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            /** Text. */
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            /** Text. */
            Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}
