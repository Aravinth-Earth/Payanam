//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.settings.SettingsCreditsSection
import io.payanam.core.ui.settings.StatRow
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import java.awt.Desktop
import java.net.URI

/** The project's own page, the one link the About card offers before the credits list. */
internal const val PAYANAM_PROJECT_URL = "https://github.com/Aravinth-Earth/Payanam"

/**
 * The desktop About card: the same version, tagline and project link Android's card carries, and the
 * shared credits list below them.
 *
 * WHY THIS EXISTS AT ALL: the shared settings screen takes its About body as a slot, and the desktop
 * passed nothing, so its About card opened empty. The rows here are shared copy read through
 * [LocalUiStrings] and the credits come from [SettingsCreditsSection] - the desktop only owns what is
 * genuinely its own, which is opening a URL in a browser.
 */
@Composable
fun DesktopAboutContent(version: String) {
    val strings = LocalUiStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatRow(
            label = strings.settingsAboutVersionLabel,
            value = strings.settingsAboutVersionValue.formatArgs(version),
        )
        Text(
            text = strings.appName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = strings.settingsAboutTagline,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = strings.settingsAboutDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { openInBrowser(PAYANAM_PROJECT_URL) }) {
            Text(strings.settingsActionViewOnGithub)
        }
        SettingsCreditsSection(onOpenUrl = ::openInBrowser)
    }
}

/**
 * Opens [url] in the desktop's own browser. A machine with no registered browser (a bare CI image, a
 * window manager that never set `BROWSER`) must not take the About card down with it, so the failure is
 * swallowed here rather than thrown into a composable.
 */
private fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
