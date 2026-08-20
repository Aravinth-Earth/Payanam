//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MetricWindowRowTest.
 */
class MetricWindowRowTest {

    @Test
    /**
     * Habit summary satisfies metric window row contract.
     */
    fun habitSummarySatisfiesMetricWindowRowContract() {
        /** Habit. */
        val habit = HabitL1Summary(
            habitId = "habit-1",
            dayKey = "2026-08-14",
            score = 0.82000,
            runningAvg = 0.78000,
            progress = 0.10000,
            streakPos = 3,
            streakNet = 6,
            posContinue = 31,
        )

        /** Row. */
        val row: MetricWindowRow = habit
        /** Assert equals. */
        assertEquals("habit-1", row.key)
        /** Assert equals. */
        assertEquals("habit-1", row.label)
        /** Assert equals. */
        assertEquals("2026-08-14", row.dayKey)
        /** Assert equals. */
        assertEquals(0.82000, row.score, 0.0)
        /** Assert equals. */
        assertEquals(0.78000, row.runningAvg, 0.0)
        /** Assert equals. */
        assertEquals(0.10000, row.progress, 0.0)
        /** Assert equals. */
        assertEquals(3, row.streakPos)
        /** Assert equals. */
        assertEquals(6, row.streakNet)
        /** Assert equals. */
        assertEquals(31, row.posContinue)
    }
}
