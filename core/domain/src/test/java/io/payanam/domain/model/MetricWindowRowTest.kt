//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
class MetricWindowRowTest {

    @Test
    fun habitSummarySatisfiesMetricWindowRowContract() {
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
        val row: MetricWindowRow = habit
        assertEquals("habit-1", row.key)
        assertEquals("habit-1", row.label)
        assertEquals("2026-08-14", row.dayKey)
        assertEquals(0.82000, row.score, 0.0)
        assertEquals(0.78000, row.runningAvg, 0.0)
        assertEquals(0.10000, row.progress, 0.0)
        assertEquals(3, row.streakPos)
        assertEquals(6, row.streakNet)
        assertEquals(31, row.posContinue)
    }
}
