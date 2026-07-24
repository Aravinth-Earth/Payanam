//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LensPlanCompletenessRegressionTest {
    @Before
    fun setUp() {
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(
                ApplicationProvider.getApplicationContext(),
                "test",
                0,
            )
        }
        UnifiedLogger.getInstance().d(
            "LensPlanCompletenessRegressionTest.setUp",
            "Initialized logger for plan completeness regression tests",
        )
    }

    @Test
    fun fullStructure_with25HoursPlanned_isNotPerfect() {
        val score =
            computePlanCompletenessScore(
                totalPlannedMinutes = 25 * 60,
                hasBudgetAllocations = true,
                plannedTaskCount = 5,
                hasPlannedHabits = true,
            )

        assertThat(score).isLessThan(1f)
        assertThat(score).isGreaterThan(0.95f)
    }

    @Test
    fun fullStructure_withExact24HoursPlanned_isPerfect() {
        val score =
            computePlanCompletenessScore(
                totalPlannedMinutes = 24 * 60,
                hasBudgetAllocations = true,
                plannedTaskCount = 5,
                hasPlannedHabits = true,
            )

        assertThat(score).isEqualTo(1f)
    }

    @Test
    fun fullStructure_with23HoursPlanned_isNotPerfect() {
        val score =
            computePlanCompletenessScore(
                totalPlannedMinutes = 23 * 60,
                hasBudgetAllocations = true,
                plannedTaskCount = 5,
                hasPlannedHabits = true,
            )

        assertThat(score).isLessThan(1f)
        assertThat(score).isGreaterThan(0.95f)
    }
}
