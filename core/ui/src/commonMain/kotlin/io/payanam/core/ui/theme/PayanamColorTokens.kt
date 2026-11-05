//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.theme

import androidx.compose.ui.graphics.Color
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.shared.logging.AppLogger

// Primary colours — the fallback palette, used whenever a platform's dynamic colours are unavailable.
// Dark theme: deep teal + warm grey-teal + soft amber
/** Fallback dark-theme primary. */
val PayanamDarkPrimary = Color(0xFF80CBC4)

/** Fallback dark-theme secondary. */
val PayanamDarkSecondary = Color(0xFFB0BEC5)

/** Fallback dark-theme tertiary. */
val PayanamDarkTertiary = Color(0xFFFFD54F)

// Light theme: indigo-teal + slate + amber
/** Fallback light-theme primary. */
val PayanamLightPrimary = Color(0xFF00796B)

/** Fallback light-theme secondary. */
val PayanamLightSecondary = Color(0xFF546E7A)

/** Fallback light-theme tertiary. */
val PayanamLightTertiary = Color(0xFFFF8F00)

/**
 * Fallback colours per life dimension.
 *
 * SHARED because a colour that differs between the two apps is a visible divergence: the same dimension
 * would render in two different greens depending on which app drew it.
 *
 * These are FALLBACKS only. A dimension the user has customised carries its own colour, stored with the
 * dimension row; these values are what an unconfigured install draws.
 */
object LifeDimensionColors {
    private val fallbackLoggedDimensions = mutableSetOf<String>()
    val CareerWork = Color(0xFF4CAF50) // Green
    val HealthWellness = Color(0xFF2196F3) // Blue
    val Relationships = Color(0xFFE91E63) // Pink
    val PersonalGrowth = Color(0xFF9C27B0) // Purple
    val Financial = Color(0xFFFF9800) // Orange
    val Spiritual = Color(0xFF795548) // Brown
    val Recreation = Color(0xFF00BCD4) // Cyan
    val Learning = Color(0xFFFFC107) // Amber
    val Contribution = Color(0xFF607D8B) // Blue Grey

    /**
     * Taxonomy colour for a canonical dimension id, or null when unrecognized.
     */
    fun forDimensionId(dimensionId: String?): Color? {
        val normalizedId = dimensionId?.trim().orEmpty()
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(normalizedId)?.id
        val canonicalColorHex =
            DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.defaultColorHex
                ?: return null
        return parseHexColor(canonicalColorHex)
    }

    /**
     * Default dimension colours — used ONLY for initial setup; UI reads the user's saved colour instead.
     */
    fun forDimension(dimension: String): Color {
        // Logged once per unknown dimension rather than per frame: this is called from draw paths.
        synchronized(fallbackLoggedDimensions) {
            if (fallbackLoggedDimensions.add(dimension)) {
                // AppLogger's un-installed default is a no-op, so this cannot throw and needs no guard -
                // the platform-specific logger used to be reachable only from the Android side.
                AppLogger.current.d(
                    "LifeDimensionColors.forDimension",
                    "Using fallback colours - UI should use the user's saved dimension colour instead",
                    mapOf("dimension" to dimension),
                )
            }
        }
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimension)?.id
        if (canonicalId == null) {
            AppLogger.current.w(
                "LifeDimensionColors.forDimension",
                "Missing canonical dimension id; using default palette colour",
                mapOf("dimension" to dimension),
            )
            return PayanamLightSecondary
        }
        return DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.defaultColorHex?.let(::parseHexColor)
            ?: PayanamLightSecondary
    }

    /**
     * Parses `#RRGGBB` / `RRGGBB` / `AARRGGBB` / `#AARRGGBB`; opaque when no alpha is given.
     */
    fun parseHexColor(hex: String): Color {
        val normalized = hex.removePrefix("#")
        val colorLong = normalized.toLong(16)
        return if (normalized.length <= 6) {
            Color((0xFF000000 or colorLong).toInt())
        } else {
            Color(colorLong.toInt())
        }
    }
}

/**
 * Task status colours, shared so a completed task is the same green in both apps.
 */
object StatusColors {
    val Pending = Color(0xFF757575) // Grey
    val Completed = Color(0xFF4CAF50) // Green
    val Skipped = Color(0xFFFF9800) // Orange
    val Missed = Color(0xFFF44336) // Red
    val Archived = Color(0xFF9E9E9E) // Light Grey
}

/**
 * Maps a 0..1 score onto a red→green severity gradient.
 *
 * The thresholds are half-open — `>= 0.8` — so a score of exactly 1.0 takes the top band and exactly 0.0
 * takes the bottom one, with no score falling between two bands.
 */
fun scoreColor(score: Float): Color =
    when {
        score >= 0.8f -> Color(0xFF4CAF50) // Green - high
        score >= 0.6f -> Color(0xFF8BC34A) // Light green
        score >= 0.4f -> Color(0xFFFFC107) // Amber - medium
        score >= 0.2f -> Color(0xFFFF9800) // Orange
        else -> Color(0xFFF44336) // Red - low
    }
