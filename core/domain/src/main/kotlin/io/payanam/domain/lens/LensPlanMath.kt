//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.lens

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The pure arithmetic behind the lens's two headline scores.
 *
 * These lived at the bottom of the Android `LensRepositoryImpl`. They are here because the desktop's JDBC
 * implementation needs exactly the same arithmetic, and a second copy would be a second definition of what
 * "plan completeness" and "adherence" mean: the two apps could then show different numbers for the same
 * stored day, which is the divergence this migration exists to remove.
 *
 * Nothing here touches storage or logging; every function is a pure function of its arguments, which is why
 * they can be tested directly rather than through a database.
 */

fun computePlanCompletenessScore(
    totalPlannedMinutes: Int,
    hasBudgetAllocations: Boolean,
    plannedTaskCount: Int,
    hasPlannedHabits: Boolean,
): Float {
    val structureScore =
        computePlanStructureScore(
            hasBudgetAllocations = hasBudgetAllocations,
            plannedTaskCount = plannedTaskCount,
            hasPlannedHabits = hasPlannedHabits,
        )
    val dayCoverageScore = computeDayCoverageScore(totalPlannedMinutes)
    return (structureScore * dayCoverageScore).coerceIn(0f, 1f)
}

/**
 * Minutes of an entry that fall inside [day], clipping an entry that starts before the day or runs past it.
 *
 * An entry that crosses midnight belongs to both days by the part that is inside each; an entry that ends
 * before it starts (or after [now] for a running one) contributes zero rather than a negative.
 */
fun dayBoundedDurationMinutes(
    startedAt: LocalDateTime,
    endedAt: LocalDateTime?,
    day: LocalDate,
    now: LocalDateTime = LocalDateTime.now(),
): Int {
    val dayStart = day.atStartOfDay()
    val dayEndExclusive = dayStart.plusDays(1)
    val effectiveEnd = endedAt ?: now
    val clippedStart = maxOf(startedAt, dayStart)
    val clippedEnd = minOf(effectiveEnd, dayEndExclusive)
    if (!clippedEnd.isAfter(clippedStart)) {
        return 0
    }
    return Duration
        .between(clippedStart, clippedEnd)
        .toMinutes()
        .toInt()
        .coerceAtLeast(0)
}

/**
 * How much of a day's plan EXISTS, ignoring whether any of it happened: 40% for having budget
 * allocations, 40% scaled by how many tasks are planned, 20% for having habits planned.
 *
 * The task share saturates at [TASKS_FOR_FULL_TASK_SHARE]: past five planned tasks the score stops rising,
 * so a day with thirty tasks is not treated as a "more complete" plan than one with five.
 */
fun computePlanStructureScore(
    hasBudgetAllocations: Boolean,
    plannedTaskCount: Int,
    hasPlannedHabits: Boolean,
): Float {
    var score = 0f
    if (hasBudgetAllocations) {
        score += BUDGET_SHARE
    }
    if (plannedTaskCount > 0) {
        score += TASK_SHARE * (plannedTaskCount.coerceAtMost(TASKS_FOR_FULL_TASK_SHARE) / TASKS_FOR_FULL_TASK_SHARE.toFloat())
    }
    if (hasPlannedHabits) {
        score += HABIT_SHARE
    }
    return score
}

/**
 * How close a day's planned minutes are to filling a whole day, 0..1.
 *
 * 1.0 exactly at [MINUTES_PER_DAY] and lower on either side, so a plan that is far too large scores as badly
 * as one that is far too small.
 */
fun computeDayCoverageScore(totalPlannedMinutes: Int): Float {
    if (totalPlannedMinutes <= 0) return 0f
    val deviationMinutes = kotlin.math.abs(totalPlannedMinutes - MINUTES_PER_DAY).toFloat()
    return (1f - (deviationMinutes / MINUTES_PER_DAY)).coerceIn(0f, 1f)
}

/**
 * Documents the three components of [computePlanStructureScore]: the budget and habit shares are flat,
 * while the task share scales up to [TASKS_FOR_FULL_TASK_SHARE] tasks.
 */
private const val BUDGET_SHARE = 0.4f
private const val TASK_SHARE = 0.4f
private const val HABIT_SHARE = 0.2f
private const val TASKS_FOR_FULL_TASK_SHARE = 5

/** Minutes in a calendar day: the denominator of the day-coverage score. */
private const val MINUTES_PER_DAY = 24 * 60
