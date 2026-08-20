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
 * ScoreWindowQueriesTest.
 */
class ScoreWindowQueriesTest {

    private lateinit var database: PayanamDatabase

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            /** Room. */
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        database.close()
    }

    @Test
    /**
     * Dimension metrics window returns rows in window.
     */
    fun dimensionMetricsWindowReturnsRowsInWindow() = runBlocking {
        /** Dao. */
        val dao = database.dimensionMetricDao()
        dao.upsertAll(
            /** List of. */
            listOf(
                /** Dimension metric entity. */
                DimensionMetricEntity("dim_a", "2026-08-10", 0.5, 0.5, 0.0, 1, 1, 10),
                /** Dimension metric entity. */
                DimensionMetricEntity("dim_a", "2026-08-14", 0.82, 0.78, 0.10, 3, 6, 31),
                /** Dimension metric entity. */
                DimensionMetricEntity("dim_a", "2026-08-20", 0.9, 0.8, 0.05, 4, 7, 40),
            ),
        )

        /** Rows. */
        val rows = dao.getForWindow("2026-08-01", "2026-08-15")

        /** Assert that. */
        assertThat(rows).hasSize(2)
        /** Assert that. */
        assertThat(rows[0].dayKey).isEqualTo("2026-08-10")
        /** Assert that. */
        assertThat(rows[1].dayKey).isEqualTo("2026-08-14")
        /** Assert that. */
        assertThat(rows[1].score).isEqualTo(0.82)
    }

    @Test
    /**
     * Day metrics window returns day rows.
     */
    fun dayMetricsWindowReturnsDayRows() = runBlocking {
        /** Dao. */
        val dao = database.dayMetricDao()
        dao.upsertAll(
            /** List of. */
            listOf(
                /** Day metric entity. */
                DayMetricEntity("2026-08-10", 0.5, 0.5, 0.0, 1, 1, 10),
                /** Day metric entity. */
                DayMetricEntity("2026-08-14", 0.82, 0.78, 0.10, 3, 6, 31),
                /** Day metric entity. */
                DayMetricEntity("2026-08-20", 0.9, 0.8, 0.05, 4, 7, 40),
            ),
        )

        /** Rows. */
        val rows = dao.getForWindow("2026-08-01", "2026-08-15")

        /** Assert that. */
        assertThat(rows).hasSize(2)
        /** Assert that. */
        assertThat(rows[0].dayKey).isEqualTo("2026-08-10")
        /** Assert that. */
        assertThat(rows[1].dayKey).isEqualTo("2026-08-14")
        /** Assert that. */
        assertThat(rows[1].dayScore).isEqualTo(0.82)
    }

    @Test
    /**
     * Window boundaries are inclusive.
     */
    fun windowBoundariesAreInclusive() = runBlocking {
        /** Dao. */
        val dao = database.dayMetricDao()
        dao.upsertAll(
            /** List of. */
            listOf(
                /** Day metric entity. */
                DayMetricEntity("2026-08-01", 0.4, 0.4, 0.0, 0, 0, 5),
                /** Day metric entity. */
                DayMetricEntity("2026-08-14", 0.82, 0.78, 0.10, 3, 6, 31),
            ),
        )

        /** Rows. */
        val rows = dao.getForWindow("2026-08-01", "2026-08-14")

        /** Assert that. */
        assertThat(rows).hasSize(2)
    }

    @Test
    /**
     * Earliest day key returns oldest logged day.
     */
    fun earliestDayKey_returnsOldestLoggedDay() = runBlocking {
        /** Dao. */
        val dao = database.dayMetricDao()
        dao.upsertAll(
            /** List of. */
            listOf(
                /** Day metric entity. */
                DayMetricEntity("2026-08-14", 0.82, 0.78, 0.10, 3, 6, 31),
                /** Day metric entity. */
                DayMetricEntity("2026-07-20", 0.5, 0.5, 0.0, 0, 0, 9),
                /** Day metric entity. */
                DayMetricEntity("2026-08-01", 0.4, 0.4, 0.0, 0, 0, 5),
            ),
        )

        /** Assert that. */
        assertThat(dao.earliestDayKey()).isEqualTo("2026-07-20")
    }

    @Test
    /**
     * Earliest day key null when no rows.
     */
    fun earliestDayKey_nullWhenNoRows() = runBlocking {
        /** Assert that. */
        assertThat(database.dayMetricDao().earliestDayKey()).isNull()
    }

    @Test
    /**
     * Earliest dimension day key scoped to dimension.
     */
    fun earliestDimensionDayKey_scopedToDimension() = runBlocking {
        /** Dao. */
        val dao = database.dimensionMetricDao()
        dao.upsertAll(
            /** List of. */
            listOf(
                /** Dimension metric entity. */
                DimensionMetricEntity("dim_health", "2026-08-10", 0.7, 0.7, 0.0, 1, 2, 7),
                /** Dimension metric entity. */
                DimensionMetricEntity("dim_health", "2026-08-14", 0.8, 0.75, 0.1, 2, 3, 8),
                /** Dimension metric entity. */
                DimensionMetricEntity("dim_money", "2026-08-01", 0.6, 0.6, 0.0, 0, 0, 3),
            ),
        )

        /** Assert that. */
        assertThat(dao.earliestDayKey("dim_health")).isEqualTo("2026-08-10")
        /** Assert that. */
        assertThat(dao.earliestDayKey("dim_money")).isEqualTo("2026-08-01")
    }

    @Test
    /**
     * Max day key per habit returns latest per habit only.
     */
    fun maxDayKeyPerHabit_returnsLatestPerHabitOnly() = runBlocking {
        /** Dao. */
        val dao = database.habitMetricDao()
        dao.upsertAll(
            /** List of. */
            listOf(
                /** Habit metric entity. */
                HabitMetricEntity("h1", "2026-08-01", 0.4, 0.4, 0.0, 0, 0, 5),
                /** Habit metric entity. */
                HabitMetricEntity("h1", "2026-08-10", 0.7, 0.55, 0.15, 1, 2, 8),
                /** Habit metric entity. */
                HabitMetricEntity("h1", "2026-08-14", 0.82, 0.62, 0.07, 2, 3, 9),
                /** Habit metric entity. */
                HabitMetricEntity("h2", "2026-08-02", 0.5, 0.5, 0.0, 0, 0, 2),
            ),
        )

        /** Result. */
        val result = dao.maxDayKeyPerHabit().associate { it.habitId to it.maxDayKey }

        /** Assert that. */
        assertThat(result).containsExactly("h1", "2026-08-14", "h2", "2026-08-02")
        /** Unit. */
        Unit
    }
}
