//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.LocalUiStrings

/**
 * The fail-closed surface a platform shows when the persisted security state cannot be read.
 *
 * WHY IT LIVES HERE: this surface used to be a private section of the desktop's own startup gate, with its
 * copy as hardcoded English literals. Both platforms can reach this state - an unreadable passphrase store
 * is not a desktop-only failure - and the copy belongs in the shared string table, so the surface moves to
 * the shared UI and its sentences come from [LocalUiStrings]. The platform supplies the two callbacks; the
 * words are not a platform's business.
 *
 * It deliberately names no file path: what the user needs to know is what resetting does and does not
 * touch, not where the store happens to live.
 */
@Composable
fun SecurityStateUnavailableScreen(
    onSecurityResetRequested: () -> Unit,
    onQuitRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings.startupSecurityUnavailableTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // Names what is lost and what is preserved.
            Text(
                text = strings.startupSecurityUnavailableBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(onClick = onSecurityResetRequested) {
                Text(strings.startupSecurityResetAction)
            }
            TextButton(onClick = onQuitRequested) {
                Text(strings.startupQuitAction)
            }
        }
    }
}
