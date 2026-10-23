//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.LocalUiStrings

/**
 * The consent gate in front of AI Assistance, shown on the Lenses screen when the entry card is tapped.
 *
 * WHY A GATE HERE AND NOT ONLY THE NOTICE INSIDE THE ASSISTANT:
 *
 * Payanam's headline claim is that it is fully local. This feature is the one place that claim stops being
 * true, and it stops being true by network action, not by accident - the user supplies a provider key and a
 * model, and the provider's own terms then govern what is processed there. So the moment of consent has to
 * be BEFORE the key screen, where the user is still on Lenses with nothing set up and can still back out
 * freely. Consent asked inside the destination is consent asked after the commitment.
 *
 * The existing first-run notice (`assistant_notice_body`) covers a different job: it explains what an AI
 * ANSWER is and is not, and stays in place. This gate covers the DATA one, and is not a substitute for it.
 *
 * Both platforms render this from shared code, so the wording can never differ between them.
 */
@Composable
fun AssistantConsentDialog(
    onProceed: () -> Unit,
    onCancel: () -> Unit,
) {
    val strings = LocalUiStrings.current
    AlertDialog(
        // Dismissal routes to Cancel, not to silence: tapping outside is a "no", never a "yes".
        onDismissRequest = onCancel,
        title = { Text(strings.assistantConsentTitle, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.assistantConsentLocalFirst)
                Text(strings.assistantConsentWhatLeaves)
                Text(strings.assistantConsentNotControlled)
                Text(strings.assistantConsentTurnOff)
            }
        },
        confirmButton = {
            Button(onClick = onProceed) {
                Text(strings.assistantConsentProceed)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(strings.assistantConsentCancel)
            }
        },
    )
}
