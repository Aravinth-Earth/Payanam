//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.lenses

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.presentation.lenses.LensMoment

@Composable
fun ModuleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    ctaText: String,
    onCta: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val strings = LocalUiStrings.current
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onToggle) {
                        val icon = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore
                        Icon(
                            imageVector = icon,
                            contentDescription = if (expanded) strings.settingsActionCollapse else strings.settingsActionExpand,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (expanded) strings.settingsActionCollapse else strings.settingsActionExpand,
                        )
                    }
                    FilledTonalButton(onClick = onCta) { Text(ctaText) }
                }
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            }
        }
    }
}

@Composable
fun momentLabel(moment: LensMoment): String{
    val strings = LocalUiStrings.current
    return  when (moment) {
    LensMoment.START_DAY -> strings.locLensStartDay
    LensMoment.LIVE_DAY -> strings.locLensLiveDay
    LensMoment.CLOSE_DAY -> strings.locLensCloseDay
}
}

@Composable
fun momentHint(moment: LensMoment): String{
    val strings = LocalUiStrings.current
    return  when (moment) {
    LensMoment.START_DAY -> strings.locLensMomentHintStartDay
    LensMoment.LIVE_DAY -> strings.locLensMomentHintLiveDay
    LensMoment.CLOSE_DAY -> strings.locLensMomentHintCloseDay
}
}

@Composable
fun MinimalTaskSummaryRow(
    overdue: Int,
    today: Int,
    future: Int,
) {
    val strings = LocalUiStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MinimalTaskSummaryChip(
            label = strings.locOverdue,
            count = overdue,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        MinimalTaskSummaryChip(
            label = strings.locToday,
            count = today,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        MinimalTaskSummaryChip(
            label = strings.locFuture,
            count = future,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun MinimalTaskSummaryChip(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
