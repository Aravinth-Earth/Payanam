//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens

import androidx.annotation.StringRes
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import kotlin.math.abs

private val logger = UnifiedLogger.getInstance()

internal data class TimeScalePreset(
    /** Slot minutes. */
    val slotMinutes: Int,
    /** Label res id. */
    @StringRes val labelResId: Int,
)

internal val TIME_SCALE_PRESETS: List<TimeScalePreset> = listOf(
    /** Time scale preset. */
    TimeScalePreset(slotMinutes = 120, labelResId = R.string.loc_time_scale_2h),
    /** Time scale preset. */
    TimeScalePreset(slotMinutes = 60, labelResId = R.string.loc_time_scale_1h),
    /** Time scale preset. */
    TimeScalePreset(slotMinutes = 30, labelResId = R.string.loc_time_scale_30m),
    /** Time scale preset. */
    TimeScalePreset(slotMinutes = 20, labelResId = R.string.loc_time_scale_20m),
    /** Time scale preset. */
    TimeScalePreset(slotMinutes = 10, labelResId = R.string.loc_time_scale_10m),
    /** Time scale preset. */
    TimeScalePreset(slotMinutes = 5, labelResId = R.string.loc_time_scale_5m),
    /** Time scale preset. */
    TimeScalePreset(slotMinutes = 3, labelResId = R.string.loc_time_scale_3m),
    /** Time scale preset. */
    TimeScalePreset(slotMinutes = 2, labelResId = R.string.loc_time_scale_2m),
    /** Time scale preset. */
    TimeScalePreset(slotMinutes = 1, labelResId = R.string.loc_time_scale_1m),
)

private const val TIME_SCALE_UNIT_HEIGHT_DP = 48f
internal const val MIN_TIME_HOUR_HEIGHT_DP = 24f
internal const val MAX_TIME_HOUR_HEIGHT_DP = 2880f

internal fun hourHeightDpForSlotMinutes(slotMinutes: Int): Float {
    /** Safe minutes. */
    val safeMinutes = slotMinutes.coerceIn(1, 120)
    /** If. */
    if (safeMinutes != slotMinutes) {
        logger.w(
            "TimeScalePresets.slotClamp",
            "Slot minutes clamped to supported range",
            /** Map of. */
            mapOf(
                "requested" to slotMinutes.toString(),
                "applied" to safeMinutes.toString(),
            ),
        )
    }
    return TIME_SCALE_UNIT_HEIGHT_DP * (60f / safeMinutes.toFloat())
}

internal fun nearestTimeScalePreset(hourHeightDp: Float): TimeScalePreset = TIME_SCALE_PRESETS.minByOrNull { preset ->
    /** Abs. */
    abs(hourHeightDpForSlotMinutes(preset.slotMinutes) - hourHeightDp)
} ?: TIME_SCALE_PRESETS[1]
