//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.TestTags
import io.payanam.core.ui.platform.rememberExactAlarmAccess
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.shared.logging.AppLogger

/**
 * Reminder delivery settings: the row that offers the system screen where exact alarms are allowed.
 *
 * The card renders only while exact alarms are unavailable, so an install that already holds the
 * permission - and every desktop build - never sees it.
 */
@Composable
fun SettingsRemindersSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    val exactAlarms = rememberExactAlarmAccess()
    if (exactAlarms.canScheduleExactAlarms()) return
    SettingsCard(
        title = strings.settingsRemindersTitle,
        icon = Icons.Default.Notifications,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        modifier = Modifier.testTag(TestTags.Settings.REMINDERS_CARD),
    ) {
        Column(
            // mergeDescendants puts the title and the supporting line on the clickable node, so a screen
            // reader announces both as one button instead of leaving them beside an unnamed action.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        logger.d("SettingsRemindersSection", "Exact-alarm settings screen opened")
                        exactAlarms.requestExactAlarmAccess()
                    }
                    .semantics(mergeDescendants = true) {}
                    .testTag(TestTags.Settings.EXACT_ALARM_ROW)
                    .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = strings.settingsExactAlarmTitle,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = strings.settingsExactAlarmBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
