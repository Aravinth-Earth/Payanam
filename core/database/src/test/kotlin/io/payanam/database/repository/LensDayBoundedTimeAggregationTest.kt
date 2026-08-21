//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * LensDayBoundedTimeAggregationTest.
 */
class LensDayBoundedTimeAggregationTest {
    @Before
    /**
     * Set up.
     */
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    /**
     * Day bounded duration minutes splits overnight entry per day.
     */
    fun dayBoundedDurationMinutes_splitsOvernightEntryPerDay() {
        val yesterday = LocalDate.of(2026, 2, 17)
        val today = yesterday.plusDays(1)
        val startedAt = yesterday.atTime(23, 50)
        val endedAt = today.atTime(0, 20)
        val yesterdayMinutes = dayBoundedDurationMinutes(startedAt, endedAt, yesterday)
        val todayMinutes = dayBoundedDurationMinutes(startedAt, endedAt, today)
        assertThat(yesterdayMinutes).isEqualTo(10)
        assertThat(todayMinutes).isEqualTo(20)
    }

    @Test
    /**
     * Day bounded duration minutes caps entry at day end.
     */
    fun dayBoundedDurationMinutes_capsEntryAtDayEnd() {
        val day = LocalDate.of(2026, 2, 18)
        val startedAt = day.atTime(23, 0)
        val endedAt = day.plusDays(1).atTime(2, 0)
        val minutes = dayBoundedDurationMinutes(startedAt, endedAt, day)
        assertThat(minutes).isEqualTo(60)
    }

    @Test
    /**
     * Day bounded duration minutes returns zero when no overlap.
     */
    fun dayBoundedDurationMinutes_returnsZeroWhenNoOverlap() {
        val day = LocalDate.of(2026, 2, 18)
        val startedAt = day.minusDays(1).atTime(10, 0)
        val endedAt = day.minusDays(1).atTime(11, 0)
        val minutes = dayBoundedDurationMinutes(startedAt, endedAt, day)
        assertThat(minutes).isEqualTo(0)
    }

    @Test
    /**
     * Day bounded duration minutes uses now for active entry and clips to day.
     */
    fun dayBoundedDurationMinutes_usesNowForActiveEntryAndClipsToDay() {
        val day = LocalDate.of(2026, 2, 18)
        val startedAt = day.atTime(23, 30)
        val now = day.plusDays(1).atTime(1, 0)
        val minutes = dayBoundedDurationMinutes(startedAt, endedAt = null, day = day, now = now)
        assertThat(minutes).isEqualTo(30)
    }
}
