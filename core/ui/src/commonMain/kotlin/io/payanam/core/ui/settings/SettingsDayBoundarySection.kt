/*
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Payanam - the day-boundary hour control, shared by both platforms.
 *
 * The row existed in the state and the mapper long before this control: every platform stored the hour,
 * read it back and clamped it, and neither platform offered a way to change it - the setter existed on
 * Android's ViewModel with no composable calling it. This section is that control, and it is shared so
 * the setting reads the same on both platforms.
 *
 * A slider rather than a dropdown: the range is small (0-5), the value is ordinal, and a slider shows
 * where the boundary currently sits relative to the ends without opening anything. The labels under it
 * name the ends, so the number is not the only clue.
 */
package io.payanam.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.LocalUiStrings

/** The hours a day boundary may sit at, matching the mapper's own clamp. */
private const val DAY_BOUNDARY_MIN_HOUR = 0

private const val DAY_BOUNDARY_MAX_HOUR = 5

@Composable
fun SettingsDayBoundarySection(
    currentHour: Int,
    onDayBoundaryHourChange: (Int) -> Unit,
) {
    val strings = LocalUiStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = strings.settingsDayBoundaryTitle,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = strings.settingsDayBoundaryHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = currentHour.toFloat(),
            onValueChange = { raw -> onDayBoundaryHourChange(raw.toInt()) },
            valueRange = DAY_BOUNDARY_MIN_HOUR.toFloat()..DAY_BOUNDARY_MAX_HOUR.toFloat(),
            steps = DAY_BOUNDARY_MAX_HOUR - DAY_BOUNDARY_MIN_HOUR - 1,
            // The slider's own value is a number with nothing naming what it governs, so the merged node
            // carries the title as its description - the same arrangement the auto-tracking toggle uses.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = strings.settingsDayBoundaryTitle },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.settingsDayBoundaryMidnight,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.settingsDayBoundaryFiveAm,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
