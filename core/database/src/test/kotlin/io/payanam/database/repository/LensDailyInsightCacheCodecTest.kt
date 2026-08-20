//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.repository.HabitPlanItem
import io.payanam.domain.repository.HabitRealityItem
import io.payanam.domain.repository.PlanningLensData
import io.payanam.domain.repository.RealityLensData
import io.payanam.domain.repository.TaskPlanItem
import io.payanam.domain.repository.TaskRealityItem
import io.payanam.domain.repository.UnifiedLensSnapshot
import org.junit.Test

/**
 * LensDailyInsightCacheCodecTest.
 */
class LensDailyInsightCacheCodecTest {
    @Test
    /**
     * Encode decode round trips unified snapshot.
     */
    fun encodeDecode_roundTripsUnifiedSnapshot() {
        /** Snapshot. */
        val snapshot =
            /** Unified lens snapshot. */
            UnifiedLensSnapshot(
                planning =
                    /** Planning lens data. */
                    PlanningLensData(
                        dayKey = "2026-02-19",
                        totalPlannedMinutes = 180,
                        plannedTimeByDimension = mapOf("career_work" to 120, "health_wellness" to 60),
                        budgetAllocationsByDimension = mapOf("career_work" to 150),
                        plannedTasks =
                            /** List of. */
                            listOf(
                                /** Task plan item. */
                                TaskPlanItem(
                                    taskId = "task_1",
                                    title = "Deep work",
                                    dimensionId = "career_work",
                                    estimatedMinutes = 90,
                                    dueDate = "2026-02-19T10:00:00",
                                    priority = "high",
                                ),
                            ),
                        plannedHabits =
                            /** List of. */
                            listOf(
                                /** Habit plan item. */
                                HabitPlanItem(
                                    habitId = "habit_1",
                                    title = "Walk",
                                    dimensionId = "health_wellness",
                                    estimatedMinutes = 30,
                                    recurrenceRule = "DAILY",
                                ),
                            ),
                        timeGoals = emptyList(),
                        planCompletenessScore = 0.82f,
                    ),
                reality =
                    /** Reality lens data. */
                    RealityLensData(
                        dayKey = "2026-02-19",
                        totalActualMinutes = 140,
                        actualTimeByDimension = mapOf("career_work" to 100, "health_wellness" to 40),
                        budgetAllocationsByDimension = mapOf("career_work" to 150),
                        completedTasks =
                            /** List of. */
                            listOf(
                                /** Task reality item. */
                                TaskRealityItem(
                                    taskId = "task_1",
                                    title = "Deep work",
                                    dimensionId = "career_work",
                                    actualMinutes = 95,
                                    completedAt = "2026-02-19T12:00:00",
                                    status = "completed",
                                    adherenceGap = -5,
                                ),
                            ),
                        completedHabits =
                            /** List of. */
                            listOf(
                                /** Habit reality item. */
                                HabitRealityItem(
                                    habitId = "habit_1",
                                    title = "Walk",
                                    dimensionId = "health_wellness",
                                    actualMinutes = 35,
                                    completedAt = "2026-02-19T19:00:00",
                                    status = "completed",
                                ),
                            ),
                        untrackedMinutes = 20,
                        focusGapMinutes = 15,
                        adherenceScore = 0.77f,
                    ),
            )

        /** Encoded. */
        val encoded = encodeUnifiedLensSnapshot(snapshot)
        /** Decoded. */
        val decoded = decodeUnifiedLensSnapshot(snapshot.planning.dayKey, encoded)

        /** Assert that. */
        assertThat(decoded).isEqualTo(snapshot)
    }
}
