//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.theme

import androidx.compose.ui.graphics.Color
import io.payanam.domain.model.DimensionTaxonomyCatalog

// Primary colors — fallback palette (used when dynamic colors are unavailable)
// Dark theme: deep teal + warm grey-teal + soft amber
/** Purple80. */
val Purple80 = Color(0xFF80CBC4)
/** Purple grey80. */
val PurpleGrey80 = Color(0xFFB0BEC5)
/** Pink80. */
val Pink80 = Color(0xFFFFD54F)

// Light theme: indigo-teal + slate + amber
/** Purple40. */
val Purple40 = Color(0xFF00796B)
/** Purple grey40. */
val PurpleGrey40 = Color(0xFF546E7A)
/** Pink40. */
val Pink40 = Color(0xFFFF8F00)

// Life Dimension Colors (matching original app)
/**
 * LifeDimensionColors.
 */
object LifeDimensionColors {
    private val fallbackLoggedDimensions = mutableSetOf<String>()

    /** Career work. */
    val CareerWork = Color(0xFF4CAF50) // Green
    /** Health wellness. */
    val HealthWellness = Color(0xFF2196F3) // Blue
    /** Relationships. */
    val Relationships = Color(0xFFE91E63) // Pink
    /** Personal growth. */
    val PersonalGrowth = Color(0xFF9C27B0) // Purple
    /** Financial. */
    val Financial = Color(0xFFFF9800) // Orange
    /** Spiritual. */
    val Spiritual = Color(0xFF795548) // Brown
    /** Recreation. */
    val Recreation = Color(0xFF00BCD4) // Cyan
    /** Learning. */
    val Learning = Color(0xFFFFC107) // Amber
    /** Contribution. */
    val Contribution = Color(0xFF607D8B) // Blue Grey

    /**
     * For dimension id.
     */
    fun forDimensionId(dimensionId: String?): Color? {
        /** Normalized id. */
        val normalizedId = dimensionId?.trim().orEmpty()
        /** Canonical id. */
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(normalizedId)?.id
        /** Canonical color hex. */
        val canonicalColorHex = DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.defaultColorHex
            ?: return null
        return parseHexColor(canonicalColorHex)
    }

    /**
     * Default dimension colors - used ONLY for initial setup.
     * All UI should use LocalAppPreferences.current.colorFor() instead.
     * @deprecated Phase 4: Use user-defined colors from AppPreferencesState
     */
    fun forDimension(dimension: String): Color {
        // Log warning if called (Phase 4: moving to user preferences)
        // Safe for tests - catch any exceptions from logger
        try {
            /** Synchronized. */
            synchronized(fallbackLoggedDimensions) {
                /** If. */
                if (fallbackLoggedDimensions.add(dimension)) {
                    io.payanam.common.logging.UnifiedLogger.getInstance().d(
                        "LifeDimensionColors.forDimension",
                        "Using fallback colors - UI should use LocalAppPreferences.colorFor() instead",
                        /** Map of. */
                        mapOf("dimension" to dimension),
                    )
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Ignore logger errors in test context
        }
        /** Canonical id. */
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimension)?.id
        /** If. */
        if (canonicalId == null) {
            try {
                io.payanam.common.logging.UnifiedLogger.getInstance().w(
                    "LifeDimensionColors.forDimension",
                    "Missing canonical dimension id; using default palette color",
                    /** Map of. */
                    mapOf("dimension" to dimension),
                )
            } catch (_: Exception) {
            }
            return PurpleGrey40
        }
        return DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.defaultColorHex?.let(::parseHexColor)
            ?: PurpleGrey40
    }

    private fun parseHexColor(hex: String): Color {
        /** Normalized. */
        val normalized = hex.removePrefix("#")
        /** Color long. */
        val colorLong = normalized.toLong(16)
        return if (normalized.length <= 6) {
            /** Color. */
            Color((0xFF000000 or colorLong).toInt())
        } else {
            /** Color. */
            Color(colorLong.toInt())
        }
    }
}

// Task Status Colors
/**
 * StatusColors.
 */
object StatusColors {
    /** Pending. */
    val Pending = Color(0xFF757575) // Grey
    /** Completed. */
    val Completed = Color(0xFF4CAF50) // Green
    /** Skipped. */
    val Skipped = Color(0xFFFF9800) // Orange
    /** Missed. */
    val Missed = Color(0xFFF44336) // Red
    /** Archived. */
    val Archived = Color(0xFF9E9E9E) // Light Grey
}

// Score Colors (gradient based on score 0..1)
/**
 * Score color.
 */
fun scoreColor(score: Float): Color = when {
    score >= 0.8f -> Color(0xFF4CAF50)

    // Green - high priority
    score >= 0.6f -> Color(0xFF8BC34A)

    // Light green
    score >= 0.4f -> Color(0xFFFFC107)

    // Amber - medium
    score >= 0.2f -> Color(0xFFFF9800)

    // Orange
    else -> Color(0xFFF44336) // Red - low priority
}
