//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * The ONE axis-label rule the habit, dimension and day layers' charts share.
 *
 * **Why this exists.** Each chart set its own X-axis label placement, and the Vico default (`ItemPlacer.aligned()`
 * with no spacing) decided for itself how many to draw - which on short windows collapsed to a SINGLE number.
 * One number is not a scale: the reader cannot tell the span, which is exactly the defect this rule fixes. So
 * the count is chosen here, once: **3 labels on a short window, 5 when there is room, never 1**.
 *
 * **Why 3 or 5 and not "as many as fit".** Those two counts were the owner's decision for these screens. A
 * count that varies with a device's width cannot be compared across the platforms, and comparing them is what
 * this milestone is for; a count that is always 5 squeezes unreadable dates onto a 7-point window.
 *
 * **Why the decimal policy is here too.** The same screens truncated values aggressively and inconsistently
 * (a score of 0.33333 shown as `0`), so "how many decimals" is part of the axis contract rather than a
 * per-screen choice: a SCORE keeps two decimals, a rating one, and counts/streaks/minutes are integers.
 *
 * The functions are pure and total, so the rule is testable without a chart - which is what makes a regression
 * here visible in a unit test rather than only on a screen.
 */
object ChartAxisPolicy {
    /** The two counts a screen may use. One label is never a scale; more than five does not fit. */
    const val SHORT_LABEL_COUNT: Int = 3

    /** See [SHORT_LABEL_COUNT]. */
    const val WIDE_LABEL_COUNT: Int = 5

    /**
     * The longest window that still gets [SHORT_LABEL_COUNT] labels: twenty points, which is as far as three
     * weeks of daily ones reach. Past it the labels would crowd the axis, so the wide count applies.
     */
    const val SHORT_WINDOW_MAX_POINTS: Int = 20

    /**
     * The number of labels an axis should draw for [pointCount] points.
     *
     * Up to [SHORT_WINDOW_MAX_POINTS] points get [SHORT_LABEL_COUNT]; longer windows get [WIDE_LABEL_COUNT]. A window of
     * one or two points has nothing to space, so it reports the short count and [spacingFor] keeps every point.
     */
    fun labelCountFor(pointCount: Int): Int =
        if (pointCount <= SHORT_WINDOW_MAX_POINTS) SHORT_LABEL_COUNT else WIDE_LABEL_COUNT

    /**
     * How many points to skip between labels so that roughly [labelCountFor] labels are drawn for
     * [pointCount] points. Always at least 1, so an axis can never be asked to draw fewer than every point.
     *
     * The caller passes this to Vico's aligned placer: `spacing = 3` draws points 0, 3, 6, 9... A 7-point
     * window therefore labels three of its seven days instead of one.
     */
    fun spacingFor(pointCount: Int): Int {
        if (pointCount <= 1) return 1
        val target = labelCountFor(pointCount)
        return ((pointCount - 1) / (target - 1)).coerceAtLeast(1)
    }

    /**
     * What a chart's numbers MEAN, which is what decides how they are written. The set is deliberately small:
     * a metric that does not fit one of these is a metric whose display has not been decided yet.
     */
    enum class Metric {
        /** A 0..1 dimension or habit score. */
        SCORE,

        /** A 0..5-ish rating. */
        RATING,

        /** Minutes of tracked time. */
        MINUTES,

        /** A row or task count. */
        COUNT,

        /** A streak length in days. */
        STREAK,
    }

    /** Decimals for [metric]: two for a score, one for a rating, none for the integer-valued metrics. */
    fun decimalsFor(metric: Metric): Int =
        when (metric) {
            Metric.SCORE -> 2
            Metric.RATING -> 1
            Metric.MINUTES -> 0
            Metric.COUNT -> 0
            Metric.STREAK -> 0
        }

    /**
     * [value] written for an axis label, rounded to [decimalsFor] decimals.
     *
     * Trailing zeros are dropped for values that do not need them (`0.50` -> `0.5`), because a fixed-width
     * label wastes the little space an axis has; the ROUNDING is what is fixed, not the printed width.
     */
    fun formatAxisValue(
        value: Double,
        metric: Metric,
    ): String {
        val decimals = decimalsFor(metric)
        if (value.isNaN() || value.isInfinite()) return ""
        val scale = 10.0.pow(decimals)
        val rounded = round(value * scale) / scale
        if (decimals == 0) return rounded.toLong().toString()
        val text = rounded.toString()
        return if ('E' in text || 'e' in text) text else text.trimEnd('0').trimEnd('.')
    }

    /**
     * The lower/upper bounds a chart should draw for [values]: the data's own min/max plus a proportional pad,
     * never an assumed constant.
     *
     * This is the policy half of "bounds verified against real data" - a chart whose range is hardcoded
     * misleads whenever the data leaves the assumed window, and a flat series (min == max) would otherwise
     * produce a zero-height axis, so a flat series is given a unit of room instead of none.
     */
    fun paddedBounds(
        values: List<Double>,
        padFraction: Double = 0.15,
    ): Pair<Double, Double>? {
        if (values.isEmpty()) return null
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { abs(it) > 1e-9 } ?: 1.0
        val pad = range * padFraction
        return (min - pad) to (max + pad)
    }
}
