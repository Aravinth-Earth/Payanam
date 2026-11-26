//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.credits.CreditEntry
import io.payanam.core.ui.credits.ThirdPartyCredits
import io.payanam.core.ui.text.LocalUiStrings

/**
 * The open-source credits block the About card carries on both platforms.
 *
 * WHY ONE BLOCK AND NOT ONE PER PLATFORM: the list itself is shared data ([ThirdPartyCredits]), and a
 * credit rendered twice is a credit that drifts: Android would name a library the desktop row forgot.
 * Only opening a link is OS work, so that - and nothing else - arrives as a parameter.
 *
 * WHY THE ROW IS THE TAP TARGET, NOT A BUTTON BESIDE IT: the useful thing on screen is the library's name
 * and where it lives; a per-row "open" button would double the height of a list of thirty-odd rows to say
 * the same thing. The whole row opens the project's own page, and the URL is printed so a reader can see
 * where a tap goes before taking it.
 */
@Composable
fun SettingsCreditsSection(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
        Text(
            text = strings.settingsCreditsTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = strings.settingsCreditsIntro,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThirdPartyCredits.byScope().forEach { (scope, entries) ->
            Text(
                text = scope.heading,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
            )
            entries.forEach { entry ->
                CreditRow(entry = entry, onOpenUrl = onOpenUrl)
            }
        }
    }
}

@Composable
private fun CreditRow(
    entry: CreditEntry,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = entry.name) { onOpenUrl(entry.url) }
            .padding(vertical = 6.dp)
            // one node per credit: a screen reader reads the library, not four fragments of it
            .semantics(mergeDescendants = true) {}
            .testTag("settings-credit-${entry.name}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.version,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = entry.purpose,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // the scheme is the same on every row, so the space it takes buys a longer path
            text = "${entry.license} · ${entry.url.removePrefix("https://")}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
