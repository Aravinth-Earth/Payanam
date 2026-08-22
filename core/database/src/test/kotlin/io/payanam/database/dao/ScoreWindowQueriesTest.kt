//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * Provides the score window queries test.
 */
class ScoreWindowQueriesTest {

    private lateinit var database: PayanamDatabase

    @Before
    /**
     * Updates the setup.
     */
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
    }

    @After
    /**
     * Performs the tear down.
     */
    fun tearDown() {
        database.close()
    }

    @Test
    /**
     * Performs the dimension metrics window returns rows in window.
     */
    fun dimensionMetricsWindowReturnsRowsInWindow() = runBlocking {
        val dao = database.dimensionMetricDao()
        dao.upsertAll(
            listOf(
                DimensionMetricEntity("dim_a", "2026-08-10", 0.5, 0.5, 0.0, 1, 1, 10),
                DimensionMetricEntity("dim_a", "2026-08-14", 0.82, 0.78, 0.10, 3, 6, 31),
                DimensionMetricEntity("dim_a", "2026-08-20", 0.9, 0.8, 0.05, 4, 7, 40),
            ),
        )
        val rows = dao.getForWindow("2026-08-01", "2026-08-15")
        assertThat(rows).hasSize(2)
        assertThat(rows[0].dayKey).isEqualTo("2026-08-10")
        assertThat(rows[1].dayKey).isEqualTo("2026-08-14")
        assertThat(rows[1].score).isEqualTo(0.82)
    }

    @Test
    /**
     * Performs the day metrics window returns day rows.
     */
    fun dayMetricsWindowReturnsDayRows() = runBlocking {
        val dao = database.dayMetricDao()
        dao.upsertAll(
            listOf(
                DayMetricEntity("2026-08-10", 0.5, 0.5, 0.0, 1, 1, 10),
                DayMetricEntity("2026-08-14", 0.82, 0.78, 0.10, 3, 6, 31),
                DayMetricEntity("2026-08-20", 0.9, 0.8, 0.05, 4, 7, 40),
            ),
        )
        val rows = dao.getForWindow("2026-08-01", "2026-08-15")
        assertThat(rows).hasSize(2)
        assertThat(rows[0].dayKey).isEqualTo("2026-08-10")
        assertThat(rows[1].dayKey).isEqualTo("2026-08-14")
        assertThat(rows[1].dayScore).isEqualTo(0.82)
    }

    @Test
    /**
     * Performs the window boundaries are inclusive.
     */
    fun windowBoundariesAreInclusive() = runBlocking {
        val dao = database.dayMetricDao()
        dao.upsertAll(
            listOf(
                DayMetricEntity("2026-08-01", 0.4, 0.4, 0.0, 0, 0, 5),
                DayMetricEntity("2026-08-14", 0.82, 0.78, 0.10, 3, 6, 31),
            ),
        )
        val rows = dao.getForWindow("2026-08-01", "2026-08-14")
        assertThat(rows).hasSize(2)
    }

    @Test
    /**
     * Performs the earliest day key returns oldest logged day.
     */
    fun earliestDayKey_returnsOldestLoggedDay() = runBlocking {
        val dao = database.dayMetricDao()
        dao.upsertAll(
            listOf(
                DayMetricEntity("2026-08-14", 0.82, 0.78, 0.10, 3, 6, 31),
                DayMetricEntity("2026-07-20", 0.5, 0.5, 0.0, 0, 0, 9),
                DayMetricEntity("2026-08-01", 0.4, 0.4, 0.0, 0, 0, 5),
            ),
        )
        assertThat(dao.earliestDayKey()).isEqualTo("2026-07-20")
    }

    @Test
    /**
     * Performs the earliest day key null when no rows.
     */
    fun earliestDayKey_nullWhenNoRows() = runBlocking {
        assertThat(database.dayMetricDao().earliestDayKey()).isNull()
    }

    @Test
    /**
     * Performs the earliest dimension day key scoped to dimension.
     */
    fun earliestDimensionDayKey_scopedToDimension() = runBlocking {
        val dao = database.dimensionMetricDao()
        dao.upsertAll(
            listOf(
                DimensionMetricEntity("dim_health", "2026-08-10", 0.7, 0.7, 0.0, 1, 2, 7),
                DimensionMetricEntity("dim_health", "2026-08-14", 0.8, 0.75, 0.1, 2, 3, 8),
                DimensionMetricEntity("dim_money", "2026-08-01", 0.6, 0.6, 0.0, 0, 0, 3),
            ),
        )
        assertThat(dao.earliestDayKey("dim_health")).isEqualTo("2026-08-10")
        assertThat(dao.earliestDayKey("dim_money")).isEqualTo("2026-08-01")
    }

    @Test
    /**
     * Performs the max day key per habit returns latest per habit only.
     */
    fun maxDayKeyPerHabit_returnsLatestPerHabitOnly() = runBlocking {
        val dao = database.habitMetricDao()
        dao.upsertAll(
            listOf(
                HabitMetricEntity("h1", "2026-08-01", 0.4, 0.4, 0.0, 0, 0, 5),
                HabitMetricEntity("h1", "2026-08-10", 0.7, 0.55, 0.15, 1, 2, 8),
                HabitMetricEntity("h1", "2026-08-14", 0.82, 0.62, 0.07, 2, 3, 9),
                HabitMetricEntity("h2", "2026-08-02", 0.5, 0.5, 0.0, 0, 0, 2),
            ),
        )
        val result = dao.maxDayKeyPerHabit().associate { it.habitId to it.maxDayKey }
        assertThat(result).containsExactly("h1", "2026-08-14", "h2", "2026-08-02")
        Unit
    }
}
