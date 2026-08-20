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
 * InsightsVisualTokens.
 */
data class InsightsVisualTokens(
    /** Card container. */
    val cardContainer: Color,
    /** Chart track. */
    val chartTrack: Color,
    /** Chart primary. */
    val chartPrimary: Color,
    /** Quality gap. */
    val qualityGap: Color,
    /** Quality overlap. */
    val qualityOverlap: Color,
)

@Composable
/**
 * Remember insights visual tokens.
 */
fun rememberInsightsVisualTokens(): InsightsVisualTokens {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Color scheme. */
    val colorScheme = MaterialTheme.colorScheme
    /** Tokens. */
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
        /** Map of. */
        mapOf("isLightScheme" to colorScheme.background.luminance().let { (it > 0.5f).toString() }),
    )
    return tokens
}
