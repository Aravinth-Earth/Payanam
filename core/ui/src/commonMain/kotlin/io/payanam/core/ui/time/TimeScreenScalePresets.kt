//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.time

import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger
import kotlin.math.abs

private val logger = AppLogger.current

/**
 * One zoom level for the timeline: how many minutes a single grid slot spans.
 *
 * The slot length is the whole of the preset. The matching on-screen height is not stored here but
 * derived from it by [hourHeightDpForSlotMinutes], so a preset cannot claim a height that disagrees
 * with its own slot - picking a preset and hand-rolling a height for it is the one thing this type
 * rules out. [TIME_SCALE_PRESETS] is the full menu, coarsest (2-hour slots) to finest (1-minute
 * slots), and [nearestTimeScalePreset] is how a stored height is mapped back onto one of them.
 *
 * A single-field value type, so two presets of equal [slotMinutes] are equal and interchangeable as
 * map or set keys.
 */
data class TimeScalePreset(
    val slotMinutes: Int,
)

val TIME_SCALE_PRESETS: List<TimeScalePreset> =
    listOf(
        TimeScalePreset(slotMinutes = 120),
        TimeScalePreset(slotMinutes = 60),
        TimeScalePreset(slotMinutes = 30),
        TimeScalePreset(slotMinutes = 20),
        TimeScalePreset(slotMinutes = 10),
        TimeScalePreset(slotMinutes = 5),
        TimeScalePreset(slotMinutes = 3),
        TimeScalePreset(slotMinutes = 2),
        TimeScalePreset(slotMinutes = 1),
    )

/**
 * The label for this preset, from the generated copy table.
 *
 * A `when` over the slot length rather than a stored resource id: `commonMain` has no `R`,
 * and the `when` makes a renamed or dropped string key a compile error.
 */
fun TimeScalePreset.label(strings: UiStrings): String =
    when (slotMinutes) {
        120 -> strings.locTimeScale2h
        60 -> strings.locTimeScale1h
        30 -> strings.locTimeScale30m
        20 -> strings.locTimeScale20m
        10 -> strings.locTimeScale10m
        5 -> strings.locTimeScale5m
        3 -> strings.locTimeScale3m
        2 -> strings.locTimeScale2m
        1 -> strings.locTimeScale1m
        else -> strings.locTimeScale1m
    }

private const val TIME_SCALE_UNIT_HEIGHT_DP = 48f
const val MIN_TIME_HOUR_HEIGHT_DP = 24f
const val MAX_TIME_HOUR_HEIGHT_DP = 2880f

/**
 * Converts a slot length into the on-screen height of one hour of the timeline, in dp.
 *
 * A slot is 48dp tall whatever its length, so an hour is 48dp divided across however many slots fit in
 * it: 24dp at the coarsest 120-minute slot, 2880dp at the finest 1-minute slot. Fixed per slot rather
 * than fixed per hour, because what the eye needs to resolve is a slot, not an hour - the height has to
 * grow as slots get shorter or the labels collide.
 *
 * [slotMinutes] is coerced into the supported 1..120 range, and the clamp is logged as a warning when
 * it bites, so an out-of-range request from a corrupted stored preference is visible rather than
 * silently drawn at a wrong zoom. The returned value is the unbounded model height; a caller that
 * draws with it still clamps to [MIN_TIME_HOUR_HEIGHT_DP]..[MAX_TIME_HOUR_HEIGHT_DP], and within the
 * supported range this function's results already sit inside those bounds.
 */
fun hourHeightDpForSlotMinutes(slotMinutes: Int): Float {
    val safeMinutes = slotMinutes.coerceIn(1, 120)
    if (safeMinutes != slotMinutes) {
        logger.w(
            "TimeScalePresets.slotClamp",
            "Slot minutes clamped to supported range",
            mapOf(
                "requested" to slotMinutes.toString(),
                "applied" to safeMinutes.toString(),
            ),
        )
    }
    return TIME_SCALE_UNIT_HEIGHT_DP * (60f / safeMinutes.toFloat())
}

/**
 * Picks the [TIME_SCALE_PRESETS] entry whose on-screen hour height is closest to [hourHeightDp], so a
 * height the user never chose - restored from a stored preference, or set before the preset menu
 * existed - still labels itself as the nearest zoom level instead of showing nothing.
 *
 * Nearest is by absolute difference in dp, compared against the height each preset would actually
 * produce. A height that is not exactly any preset's is still answered - snapping to the closest one
 * is the point - so the round trip through [hourHeightDpForSlotMinutes] is exact only for a height
 * that came from a preset in the first place. A height outside the whole range of preset heights,
 * including a non-finite one such as NaN or infinity, resolves to the coarsest preset, since every
 * distance then compares in its favour; callers clamp before getting here.
 *
 * @return the closest preset by [TimeScalePreset.slotMinutes], never null. The 1-hour preset is the
 *   fallback for the case that [TIME_SCALE_PRESETS] is ever empty, which is what stops an empty menu
 *   from turning into a crash on read.
 */
fun nearestTimeScalePreset(hourHeightDp: Float): TimeScalePreset =
    TIME_SCALE_PRESETS.minByOrNull { preset ->
        abs(hourHeightDpForSlotMinutes(preset.slotMinutes) - hourHeightDp)
    } ?: TIME_SCALE_PRESETS[1]
