//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.components

/**
 * The English ordinal suffix for a day of the month - `1st`, `2nd`, `3rd`, `4th` and so on.
 *
 * Only the two letters come back; the caller composes them onto the number it already has. The rule
 * is the English one, teens included: 11, 12 and 13 take `th` even though their last digits are 1,
 * 2 and 3, and every other day is decided by its last digit alone. Because only the last digit is
 * tested, 21 is `st` and 111 is `th` with no special casing.
 *
 * English only, which is why the monthly-recurrence chips read `1st` and `15th` in every language:
 * an ordinal suffix is a numeral convention rather than a translation, so there is nothing to
 * localize here. The `32` chip - the last day of the month - is answered by the caller before this
 * is reached, because 32 is not a date.
 */
fun getDaySuffix(day: Int): String =
    when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
