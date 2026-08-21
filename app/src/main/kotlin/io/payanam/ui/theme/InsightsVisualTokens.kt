//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.payanam.common.logging.UnifiedLogger

@Immutable
/**
 * Holds the insights visual tokens.
 */
data class InsightsVisualTokens(
    val cardContainer: Color,
    val chartTrack: Color,
    val chartPrimary: Color,
    val qualityGap: Color,
    val qualityOverlap: Color,
)

@Composable
/**
 * Performs the remember insights visual tokens.
 */
fun rememberInsightsVisualTokens(): InsightsVisualTokens {
    val logger = UnifiedLogger.getInstance()
    val colorScheme = MaterialTheme.colorScheme
    val tokens = InsightsVisualTokens(
        cardContainer = colorScheme.surfaceVariant.copy(alpha = 0.45f),
        chartTrack = lerp(colorScheme.surface, colorScheme.outlineVariant, 0.25f),
        chartPrimary = colorScheme.primary,
        qualityGap = colorScheme.secondary,
        qualityOverlap = colorScheme.error,
    )
    logger.d(
        "InsightsVisualTokens.rememberInsightsVisualTokens",
        "Resolved insight/time visual tokens",
        mapOf("isLightScheme" to colorScheme.background.luminance().let { (it > 0.5f).toString() }),
    )
    return tokens
}
