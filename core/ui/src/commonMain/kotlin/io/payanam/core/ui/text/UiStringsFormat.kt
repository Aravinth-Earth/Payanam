//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.text

import kotlin.math.abs
import kotlin.math.round

/** The base every decimal digit-parsing and digit-accumulating step below runs in. */
private const val DECIMAL_RADIX = 10

/** `String.format`'s default precision for `%f` — used when a format string names no precision. */
private const val DEFAULT_FRACTION_DIGITS = 6

/**
 * Substitutes positional arguments into a string-table value, following Android's `%` conventions.
 *
 * WHY THIS IS HERE:
 *
 * Android's copy uses format placeholders - `%1$s`, `%2$d`, `@string/...`-style resources are matched with
 * `String.format` at render time, and the existing screens call `stringResource(id, arg)` which does that
 * for them. The shared layer reads its copy from [UiStrings], a plain Kotlin table, so nothing performs
 * that substitution: a screen that renders `strings.x` directly would show the literal `%1$d`.
 *
 * `String.format` does not exist in Kotlin common code (it is a JVM API), and reaching for a
 * platform-specific formatter per target is exactly the duplication this module exists to avoid. So the
 * substitution is implemented once, here, for every platform - and it is deliberately the SAME semantics
 * as Android's, because the deliverable is the identical screen on every platform, not a near-miss.
 *
 * The value must be rendered as Android would render it: a literal newline, not an escaped `\n`
 * (that is resolved by the generator, which parses the string files).
 *
 * `%%` is a literal percent sign. A missing argument renders as `null`, matching `String.format`.
 */
fun String.formatArgs(vararg args: Any?): String {
    val out = StringBuilder(length + 16)
    var cursor = 0
    var nextSequentialIndex = 0
    while (cursor < this.length) {
        val c = this[cursor]
        if (c != '%') {
            out.append(c)
            cursor++
            continue
        }
        var i = cursor + 1
        if (i >= this.length) {
            out.append(c)
            break
        }
        if (this[i] == '%') {
            out.append('%')
            cursor = i + 1
            continue
        }
        // Optional explicit argument index, e.g. the "2" in %2$d.
        val digitsStart = i
        while (i < this.length && this[i].isDigit()) i++
        var explicitIndex: Int? = null
        if (i > digitsStart && i < this.length && this[i] == '$') {
            explicitIndex = substring(digitsStart, i).toInt() - 1
            i++
        }
        // Optional width and .precision (only precision affects the output here).
        var precision: Int? = null
        var seenDot = false
        while (i < this.length && (this[i] == '.' || this[i].isDigit())) {
            if (this[i] == '.') {
                seenDot = true
                precision = 0
            } else if (seenDot) {
                precision = precision!! * DECIMAL_RADIX + (this[i] - '0')
            }
            i++
        }
        if (i >= this.length) {
            out.append(substring(cursor))
            break
        }
        val conversion = this[i]
        val argumentIndex = explicitIndex ?: nextSequentialIndex++
        val argument = args.getOrNull(argumentIndex)
        out.append(renderConversion(conversion, precision, argument))
        cursor = i + 1
    }
    return out.toString()
}

private fun renderConversion(
    conversion: Char,
    precision: Int?,
    argument: Any?,
): String =
    when (conversion) {
        's' -> {
            argument?.toString() ?: "null"
        }

        'd' -> {
            (argument as? Number)?.toLong()?.toString() ?: argument?.toString() ?: "null"
        }

        'f' -> {
            val value = (argument as? Number)?.toDouble()
            if (value == null) {
                argument?.toString() ?: "null"
            } else {
                fixedDecimals(value, precision ?: DEFAULT_FRACTION_DIGITS)
            }
        }

        // An unknown conversion is left as written rather than silently dropped: a wrong-looking screen is
        // easier to diagnose than a missing one.
        else -> {
            "%$conversion"
        }
    }

/**
 * Formats [value] with exactly [decimals] digits after the point, the way `%.<decimals>f` would.
 *
 * Written by hand because `String.format` is a JVM API and `NumberFormat` does not exist in common code.
 * Rounding is half-up on the scaled magnitude, which matches `String.format` for the values the shipped
 * copy uses (build numbers, averages).
 */
private fun fixedDecimals(
    value: Double,
    decimals: Int,
): String {
    if (decimals <= 0) return round(value).toLong().toString()
    var factor = 1.0
    repeat(decimals) { factor *= DECIMAL_RADIX }
    val scaled = round(abs(value) * factor).toLong()
    val whole = scaled / factor.toLong()
    val fraction = scaled % factor.toLong()
    val sign = if (value < 0) "-" else ""
    return "$sign$whole.${fraction.toString().padStart(decimals, '0')}"
}
