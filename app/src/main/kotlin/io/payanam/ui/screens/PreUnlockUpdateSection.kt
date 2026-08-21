//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.DownloadUiState
import io.payanam.ui.theme.LifeDimensionColors
import io.payanam.ui.viewmodel.PreUnlockUpdateViewModel

/**
 * Pre-unlock update hatch — manual check → download → install, shown on the
 * DB-locked screen (Diagnostics zone). Deliberately muted/secondary so the
 * unlock flow stays primary. All actions are manual taps; nothing automatic.
 */
@Composable
/**
 * Performs the pre unlock update section.
 */
fun PreUnlockUpdateSection(viewModel: PreUnlockUpdateViewModel) {
    val logger = UnifiedLogger.getInstance()
    val downloadState by viewModel.downloadState.collectAsState()
    val checking by viewModel.checking.collectAsState()
    val resultMessage by viewModel.checkResultMessage.collectAsState()

    // State-driven single button (same pattern as Settings update section).
    val buttonLabel: String
    val buttonEnabled: Boolean
    val buttonAction: () -> Unit
    val showProgress: Boolean

    when {
        checking -> {
            buttonLabel = stringResource(id = R.string.settings_update_checking)
            buttonEnabled = false
            buttonAction = {}
            showProgress = false
        }
        downloadState is DownloadUiState.Downloading -> {
            val d = downloadState as DownloadUiState.Downloading
            buttonLabel = stringResource(
                id = R.string.pre_unlock_update_downloading,
                d.progressPercent,
            )
            buttonEnabled = false
            buttonAction = {}
            showProgress = true
        }
        downloadState is DownloadUiState.Downloaded -> {
            buttonLabel = stringResource(id = R.string.settings_update_install_now_button)
            buttonEnabled = true
            buttonAction = { viewModel.install() }
            showProgress = false
        }
        downloadState is DownloadUiState.Failed -> {
            buttonLabel = stringResource(id = R.string.settings_update_retry_button)
            buttonEnabled = true
            buttonAction = { viewModel.download() }
            showProgress = false
        }
        resultMessage?.startsWith("update_available") == true -> {
            buttonLabel = stringResource(id = R.string.pre_unlock_update_download_install)
            buttonEnabled = true
            buttonAction = { viewModel.download() }
            showProgress = false
        }
        else -> {
            buttonLabel = stringResource(id = R.string.settings_update_check_button)
            buttonEnabled = !checking
            buttonAction = {
                logger.i(
                    "PreUnlockUpdateSection.checkTapped",
                    "Check for update tapped (pre-unlock hatch)",
                )
                viewModel.checkForUpdate()
            }
            showProgress = false
        }
    }
    val message = when {
        resultMessage == "up_to_date" -> stringResource(id = R.string.pre_unlock_update_up_to_date)
        resultMessage?.startsWith("check_failed") == true -> stringResource(id = R.string.pre_unlock_update_check_failed)
        downloadState is DownloadUiState.Failed -> stringResource(id = R.string.pre_unlock_update_download_failed)
        else -> null
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.settings_about_version_label),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
                color = Color.White.copy(alpha = 0.4f),
            )
            Text(
                text = stringResource(
                    id = R.string.pre_unlock_update_build_value,
                    viewModel.currentBuildNumber,
                ),
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
                val isEmphasized = buttonLabel == stringResource(id = R.string.settings_update_install_now_button) ||
                    buttonLabel == stringResource(id = R.string.pre_unlock_update_download_install)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .then(
                            if (isEmphasized) {
                                Modifier.background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(LifeDimensionColors.HealthWellness, LifeDimensionColors.CareerWork),
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
                        color = if (isEmphasized) Color.White else Color.White.copy(alpha = if (buttonEnabled) 0.6f else 0.3f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isEmphasized) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (resultMessage?.startsWith("check_failed") == true ||
                            downloadState is DownloadUiState.Failed
                        ) {
                            Color(0xFFF28B82)
                        } else {
                            Color.White.copy(alpha = 0.55f)
                        },
                    )
                }
                Text(
                    text = stringResource(id = R.string.pre_unlock_update_hint),
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
