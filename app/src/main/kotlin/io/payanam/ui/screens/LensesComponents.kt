//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.ui.viewmodel.LensMoment

@Composable
internal fun ModuleCard(
    /** Title. */
    title: String,
    /** Expanded. */
    expanded: Boolean,
    onToggle: () -> Unit,
    /** Cta text. */
    ctaText: String,
    onCta: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    /** Elevated card. */
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
    ) {
        /** Column. */
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            /** Row. */
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                /** Text. */
                Text(
                    /** Title. */
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                /** Row. */
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    /** Text button. */
                    TextButton(onClick = onToggle) {
                        /** Icon. */
                        val icon = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore
                        /** Icon. */
                        Icon(
                            imageVector = icon,
                            contentDescription = stringResource(id = if (expanded) R.string.settings_action_collapse else R.string.settings_action_expand),
                            modifier = Modifier.size(18.dp),
                        )
                        /** Text. */
                        Text(
                            text = stringResource(id = if (expanded) R.string.settings_action_collapse else R.string.settings_action_expand),
                        )
                    }
                    /** Filled tonal button. */
                    FilledTonalButton(onClick = onCta) { Text(ctaText) }
                }
            }
            /** If. */
            if (expanded) {
                /** Column. */
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            }
        }
    }
}

@Composable
internal fun momentLabel(moment: LensMoment): String = when (moment) {
    LensMoment.START_DAY -> stringResource(id = R.string.loc_lens_start_day)
    LensMoment.LIVE_DAY -> stringResource(id = R.string.loc_lens_live_day)
    LensMoment.CLOSE_DAY -> stringResource(id = R.string.loc_lens_close_day)
}

@Composable
internal fun momentHint(moment: LensMoment): String = when (moment) {
    LensMoment.START_DAY -> stringResource(id = R.string.loc_lens_moment_hint_start_day)
    LensMoment.LIVE_DAY -> stringResource(id = R.string.loc_lens_moment_hint_live_day)
    LensMoment.CLOSE_DAY -> stringResource(id = R.string.loc_lens_moment_hint_close_day)
}

@Composable
internal fun MinimalTaskSummaryRow(
    /** Overdue. */
    overdue: Int,
    /** Today. */
    today: Int,
    /** Future. */
    future: Int,
) {
    /** Row. */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /** Minimal task summary chip. */
        MinimalTaskSummaryChip(
            label = stringResource(id = R.string.loc_overdue),
            count = overdue,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        /** Minimal task summary chip. */
        MinimalTaskSummaryChip(
            label = stringResource(id = R.string.loc_today),
            count = today,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        /** Minimal task summary chip. */
        MinimalTaskSummaryChip(
            label = stringResource(id = R.string.loc_future),
            count = future,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun MinimalTaskSummaryChip(
    /** Label. */
    label: String,
    /** Count. */
    count: Int,
    /** Color. */
    color: Color,
    modifier: Modifier = Modifier,
) {
    /** Card. */
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        /** Column. */
        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            /** Text. */
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            /** Text. */
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
