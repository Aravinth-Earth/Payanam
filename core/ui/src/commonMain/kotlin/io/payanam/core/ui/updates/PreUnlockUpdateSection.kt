//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.updates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.core.ui.theme.LifeDimensionColors
import io.payanam.presentation.download.DownloadUiState
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.platform.SupportState

/**
 * Pre-unlock update hatch — manual check → download → install, shown on the
 * DB-locked screen (Diagnostics zone). Deliberately muted/secondary so the
 * unlock flow stays primary. All actions are manual taps; nothing automatic.
 *
 * WHY IT CANNOT STILL TAKE ITS VIEWMODEL: the old signature took exactly one parameter,
 * `viewModel: PreUnlockUpdateViewModel`, and that ViewModel is an `:app` Hilt class - it holds an
 * `@ApplicationContext Context` and reaches into `AutoDownloadManager` and `FileProvider`. `:app`
 * depends on `:core:ui`, so this module can never import it, and the section could not move while its
 * only parameter named a type from the layer above. [PreUnlockUpdateUiState] names the five values
 * this draws and [PreUnlockUpdateActions] names the three things a user can do here, so both platforms
 * supply them and this file reaches neither the ViewModel nor an Android string resource.
 *
 * NOT A REDESIGN: rows, order, copy, colours and every `when` branch are byte-for-byte what `:app`
 * drew. Copy comes from [LocalUiStrings] - all 15 keys this section read already ship in
 * `:core:strings` with EN+TA parity, so no string was added and the two keyed ones go through
 * [formatArgs], which reproduces Android's `%1$d` substitution.
 *
 * `DownloadUiState.Paused` is still not matched below, exactly as before: it falls through to the
 * check branch rather than growing a branch of its own.
 */
@Composable
fun PreUnlockUpdateSection(
    uiState: PreUnlockUpdateUiState,
    actions: PreUnlockUpdateActions,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }

    // What this platform can do about its own updates decides whether there is a button at all, so it
    // is matched FIRST and returns before any action can be dispatched. `DECLINES` is Android under
    // F-Droid, which manages updates for its installs; `NOT_APPLICABLE` is a platform with no in-app
    // updater to act through. Both replace the hatch with a static note — never a dead button.
    when (uiState.platformSupport) {
        SupportState.SUPPORTED -> Unit
        SupportState.DECLINES -> {
            Text(
                text = strings.settingsUpdateFdroidManaged,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
            )
            return
        }

        SupportState.NOT_APPLICABLE -> {
            Text(
                text = strings.settingsUpdateNotSupportedHere,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
            )
            return
        }

        // Neither is produced by any platform today, and both are listed rather than folded into an
        // `else` so that a state added to [SupportState] later is a compile error HERE instead of a
        // silently unrendered answer. Both mean this platform cannot hand an update to the user now, so
        // both render the same note as `NOT_APPLICABLE` - and neither claims an installer it cannot know.
        SupportState.NOT_SURFACED, SupportState.IN_PROCESS_ONLY -> {
            Text(
                text = strings.settingsUpdateNotSupportedHere,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
            )
            return
        }
    }

    val downloadState = uiState.downloadState
    val checking = uiState.isChecking
    val resultMessage = uiState.checkResultMessage

    // State-driven single button (same pattern as Settings update section).
    val buttonLabel: String
    val buttonEnabled: Boolean
    val buttonAction: () -> Unit
    val showProgress: Boolean

    when {
        checking -> {
            buttonLabel = strings.settingsUpdateChecking
            buttonEnabled = false
            buttonAction = {}
            showProgress = false
        }

        downloadState is DownloadUiState.Downloading -> {
            val d = downloadState as DownloadUiState.Downloading
            buttonLabel = strings.preUnlockUpdateDownloading.formatArgs(d.progressPercent)
            buttonEnabled = false
            buttonAction = {}
            showProgress = true
        }

        downloadState is DownloadUiState.Downloaded -> {
            buttonLabel = strings.settingsUpdateInstallNowButton
            buttonEnabled = true
            buttonAction = { actions.onInstallRequested() }
            showProgress = false
        }

        downloadState is DownloadUiState.Failed -> {
            buttonLabel = strings.settingsUpdateRetryButton
            buttonEnabled = true
            buttonAction = { actions.onDownloadRequested() }
            showProgress = false
        }

        resultMessage?.startsWith("update_available") == true -> {
            buttonLabel = strings.preUnlockUpdateDownloadInstall
            buttonEnabled = true
            buttonAction = { actions.onDownloadRequested() }
            showProgress = false
        }

        else -> {
            buttonLabel = strings.settingsUpdateCheckButton
            buttonEnabled = !checking
            buttonAction = {
                logger.i(
                    "PreUnlockUpdateSection.checkTapped",
                    "Check for update tapped (pre-unlock hatch)",
                )
                actions.onCheckRequested()
            }
            showProgress = false
        }
    }
    val message = when {
        resultMessage == "up_to_date" -> strings.preUnlockUpdateUpToDate
        resultMessage == "type_mismatch" -> strings.settingsUpdateTypeMismatch
        resultMessage == "no_release" -> strings.settingsUpdateNoReleases
        resultMessage?.startsWith("check_failed") == true -> strings.preUnlockUpdateCheckFailed
        downloadState is DownloadUiState.Failed -> strings.preUnlockUpdateDownloadFailed
        else -> null
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.settingsAboutVersionLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
                color = Color.White.copy(alpha = 0.4f),
            )
            Text(
                text = strings.preUnlockUpdateBuildValue.formatArgs(uiState.buildNumber),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Hatch button — bordered 40dp, gradient when emphasized (mirrors
                // the screen's DiagnosticButton visual language).
                val isEmphasized = buttonLabel == strings.settingsUpdateInstallNowButton ||
                    buttonLabel == strings.preUnlockUpdateDownloadInstall
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .then(
                            if (isEmphasized) {
                                Modifier.background(
                                    brush = Brush.horizontalGradient(
                                        colors =
                                            listOf(
                                                LifeDimensionColors.HealthWellness,
                                                LifeDimensionColors.CareerWork,
                                            ),
                                    ),
                                )
                            } else {
                                Modifier.border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.2f),
                                    RoundedCornerShape(8.dp),
                                )
                            },
                        )
                        .clickable(enabled = buttonEnabled) {
                            logger.i(
                                "PreUnlockUpdateSection.buttonTapped",
                                "Hatch action tapped",
                                mapOf("label" to buttonLabel),
                            )
                            buttonAction()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = buttonLabel,
                        color =
                            if (isEmphasized) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = if (buttonEnabled) 0.6f else 0.3f)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isEmphasized) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (resultMessage?.startsWith("check_failed") == true ||
                            resultMessage == "type_mismatch" ||
                            downloadState is DownloadUiState.Failed
                        ) {
                            Color(0xFFF28B82)
                        } else {
                            Color.White.copy(alpha = 0.55f)
                        },
                    )
                }
                Text(
                    text = strings.preUnlockUpdateHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.35f),
                )
            }
        }
        if (showProgress) {
            // Minimal progress bar placeholder — mirrors the mock; real progress
            // bar added with the Downloading state visuals.
            Spacer(modifier = Modifier.height(4.dp))
        }
        Spacer(modifier = Modifier.width(0.dp))
    }
}