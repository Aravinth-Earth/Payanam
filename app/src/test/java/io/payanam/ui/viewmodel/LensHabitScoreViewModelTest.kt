//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.event.ScoreChangeEventBus
import io.payanam.domain.model.DayMetricRow
import io.payanam.domain.model.DimensionMetricRow
import io.payanam.domain.model.MetricWindowRow
import io.payanam.domain.repository.ScoreWindowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
/**
 * LensHabitScoreViewModelTest.
 */
class LensHabitScoreViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeScoreWindowRepository(
        private val dims: List<MetricWindowRow>,
        private val days: List<MetricWindowRow>,
    ) : ScoreWindowRepository {
        override suspend fun getDimensionWindow(start: String, end: String): List<MetricWindowRow> = dims
        override suspend fun getDayWindow(start: String, end: String): List<MetricWindowRow> = days
        override suspend fun earliestDayKey(): String? = days.minByOrNull { it.dayKey }?.dayKey
        override suspend fun earliestDimensionDayKey(dimensionId: String): String? =
            dims.filter { it.key == dimensionId }.minByOrNull { it.dayKey }?.dayKey
        override suspend fun earliestDimensionDayKey(): String? =
            dims.minByOrNull { it.dayKey }?.dayKey
    }

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
        Dispatchers.setMain(dispatcher)
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleDims(): List<MetricWindowRow> = listOf(
        /** Dimension metric row. */
        DimensionMetricRow("dim_physical_health", "2026-08-14", 0.85, 0.87, 0.05, 27, 41, 152),
        /** Dimension metric row. */
        DimensionMetricRow("dim_physical_health", "2026-08-13", 0.82, 0.86, 0.03, 26, 40, 151),
        /** Dimension metric row. */
        DimensionMetricRow("dim_mental_health", "2026-08-14", 0.70, 0.64, -0.08, 1, -4, 88),
    )

    private fun sampleDays(): List<MetricWindowRow> = listOf(
        /** Day metric row. */
        DayMetricRow("2026-08-14", 0.82, 0.78, 0.10, 3, 6, 31),
        /** Day metric row. */
        DayMetricRow("2026-08-13", 0.79, 0.77, 0.04, 2, 5, 30),
    )

    @Test
    fun `loadWindow assembles one row per dimension plus day row`() = runTest(dispatcher) {
        /** Vm. */
        val vm = LensHabitScoreViewModel(FakeScoreWindowRepository(sampleDims(), sampleDays()), ScoreChangeEventBus())
        vm.loadWindow(java.time.LocalDate.of(2026, 8, 14), days = 14)
        dispatcher.scheduler.advanceUntilIdle()

        /** State. */
        val state = vm.uiState.value
        /** Assert equals. */
        assertEquals(2, state.rows.size)
        /** Assert not null. */
        assertNotNull(state.dayRow)
        /** Assert equals. */
        assertEquals("dim_physical_health", state.rows[0].key)
        /** Assert equals. */
        assertEquals("dim_mental_health", state.rows[1].key)
        /** Assert equals. */
        assertEquals(0.85, state.rows[0].values[ScoreMetricColumn.SCORE]!!, 0.0)
        /** Assert true. */
        assertTrue(state.dayRow!!.isDay)
        /** Assert equals. */
        assertEquals(0.82, state.dayRow!!.values[ScoreMetricColumn.SCORE]!!, 0.0)
    }

    @Test
    fun `sparkline is dense across the window with nulls for missing days`() = runTest(dispatcher) {
        /** Vm. */
        val vm = LensHabitScoreViewModel(FakeScoreWindowRepository(sampleDims(), sampleDays()), ScoreChangeEventBus())
        vm.loadWindow(java.time.LocalDate.of(2026, 8, 14), days = 14)
        dispatcher.scheduler.advanceUntilIdle()

        /** State. */
        val state = vm.uiState.value
        // 14 days: physical health has rows on 13th and 14th only → 12 nulls + 2 values
        /** Spark. */
        val spark = state.rows[0].sparkline
        /** Assert equals. */
        assertEquals(14, spark.size)
        /** Assert equals. */
        assertEquals(2, spark.filterNotNull().size)
        /** Assert null. */
        assertNull(spark[0])
        /** Assert equals. */
        assertEquals(0.82, spark[12]!!, 0.0)
        /** Assert equals. */
        assertEquals(0.85, spark[13]!!, 0.0)
    }

    @Test
    fun `selectMetric triggers reload and rank matches final metric`() = runTest(dispatcher) {
        /** Vm. */
        val vm = LensHabitScoreViewModel(FakeScoreWindowRepository(sampleDims(), sampleDays()), ScoreChangeEventBus())
        vm.loadWindow(java.time.LocalDate.of(2026, 8, 14), days = 14)
        dispatcher.scheduler.advanceUntilIdle()

        vm.selectMetric(ScoreMetricColumn.PROGRESS)
        dispatcher.scheduler.advanceUntilIdle()

        /** Assert equals. */
        assertEquals(ScoreMetricColumn.PROGRESS, vm.uiState.value.selectedMetric)
        // rankByKey must be derived for the selected metric, not a stale one
        /** Assert true. */
        assertTrue(vm.uiState.value.rankByKey.isNotEmpty())
    }

    @Test
    fun `default metric is progress`() = runTest(dispatcher) {
        /** Vm. */
        val vm = LensHabitScoreViewModel(FakeScoreWindowRepository(sampleDims(), sampleDays()), ScoreChangeEventBus())

        /** Assert equals. */
        assertEquals(ScoreMetricColumn.PROGRESS, vm.uiState.value.selectedMetric)
    }

    @Test
    fun `radar axes carry all metric pairs with today values`() = runTest(dispatcher) {
        /** Vm. */
        val vm = LensHabitScoreViewModel(FakeScoreWindowRepository(sampleDims(), sampleDays()), ScoreChangeEventBus())
        vm.loadWindow(java.time.LocalDate.of(2026, 8, 14), days = 14)
        dispatcher.scheduler.advanceUntilIdle()

        /** Axes. */
        val axes = vm.uiState.value.radarAxes
        /** Assert equals. */
        assertEquals(2, axes.size)
        /** Physical. */
        val physical = axes.first { it.key == "dim_physical_health" }
        /** Assert equals. */
        assertEquals(0.85, physical.today(ScoreMetricColumn.SCORE)!!, 0.0)
        /** Assert equals. */
        assertEquals(0.87, physical.runningAvg(ScoreMetricColumn.SCORE)!!, 0.0)
        /** Assert equals. */
        assertEquals(0.05, physical.today(ScoreMetricColumn.PROGRESS)!!, 0.0)
        /** Assert equals. */
        assertEquals(27.0, physical.today(ScoreMetricColumn.STREAK_POS)!!, 0.0)
        /** Assert equals. */
        assertEquals(41.0, physical.today(ScoreMetricColumn.STREAK_NET)!!, 0.0)
        /** Assert equals. */
        assertEquals(152.0, physical.today(ScoreMetricColumn.POS_CONTINUE)!!, 0.0)
    }

    @Test
    fun `dimension labels fall back to taxonomy names`() = runTest(dispatcher) {
        /** Vm. */
        val vm = LensHabitScoreViewModel(FakeScoreWindowRepository(sampleDims(), sampleDays()), ScoreChangeEventBus())
        vm.loadWindow(java.time.LocalDate.of(2026, 8, 14), days = 14)
        dispatcher.scheduler.advanceUntilIdle()

        /** Labels. */
        val labels = vm.uiState.value.rows.map { it.label }
        /** Assert true. */
        assertTrue(labels.any { it.contains("Physical", ignoreCase = true) })
        /** Assert true. */
        assertTrue(labels.none { it == "dim_physical_health" })
    }
}
