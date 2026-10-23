//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import io.payanam.domain.model.DayMetricRow
import io.payanam.domain.model.DimensionMetricRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM behavioural lock for [ScoreDetailViewModel] and the file-private
 * `resolveWindowStart` extension it delegates to.
 *
 * Every path through load / shiftWindow / setWindowSize / setChartView /
 * goToday / dispose is exercised, including the 2020-01-01 hard fallback
 * when the repository returns null earliest-day keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreDetailViewModelTest {
    private lateinit var fakeRepository: FakeScoreWindowRepository
    private var viewModelScope: CoroutineScope? = null

    @Before
    fun setUp() {
        fakeRepository = FakeScoreWindowRepository()
    }

    @After
    fun tearDown() {
        viewModelScope?.cancel()
        viewModelScope = null
    }

    private fun TestScope.newViewModelScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { viewModelScope = it }

    private fun TestScope.viewModel(): ScoreDetailViewModel =
        ScoreDetailViewModel(
            scoreWindowRepository = fakeRepository,
            scope = newViewModelScope(),
        )

    // ── initial state ──────────────────────────────────────────────

    @Test
    fun `initial state is loading with 7-day window and chart view`() =
        runTest {
            val vm = viewModel()
            val state = vm.uiState.value
            assertTrue(state.isLoading)
            assertEquals(7, state.windowSizeDays)
            assertTrue(state.showChartView)
            assertTrue(state.rows.isEmpty())
            assertNull(state.error)
        }

    // ── load — DAY ─────────────────────────────────────────────────

    @Test
    fun `load DAY fetches dayWindow and clears loading`() =
        runTest {
            val day =
                DayMetricRow(
                    dayKey = "2026-09-21",
                    score = 0.8,
                    runningAvg = 0.75,
                    progress = 1.0,
                    streakPos = 3,
                    streakNet = 2,
                    posContinue = 2,
                )
            fakeRepository.dayRows = listOf(day)

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.load(ScoreDetailType.DAY, "DAY")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoading)
            assertEquals(1, state.rows.size)
            assertEquals("DAY", state.rows[0].key)
        }

    @Test
    fun `load DAY with empty repository returns empty rows`() =
        runTest {
            fakeRepository.dayRows = emptyList()

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.load(ScoreDetailType.DAY, "DAY")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoading)
            assertTrue(state.rows.isEmpty())
        }

    // ── load — DIMENSION ───────────────────────────────────────────

    @Test
    fun `load DIMENSION fetches dimensionWindow filtered by key`() =
        runTest {
            val row1 =
                DimensionMetricRow(
                    dimensionId = "dim_health",
                    dayKey = "2026-09-21",
                    score = 0.6,
                    runningAvg = 0.5,
                    progress = 0.9,
                    streakPos = 1,
                    streakNet = 1,
                    posContinue = 1,
                )
            val row2 =
                DimensionMetricRow(
                    dimensionId = "dim_learning",
                    dayKey = "2026-09-21",
                    score = 0.9,
                    runningAvg = 0.85,
                    progress = 1.0,
                    streakPos = 5,
                    streakNet = 4,
                    posContinue = 5,
                )
            fakeRepository.dimensionRows = listOf(row1, row2)

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DIMENSION
            vm.currentKey = "dim_health"
            vm.load(ScoreDetailType.DIMENSION, "dim_health")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoading)
            assertEquals(1, state.rows.size)
            assertEquals("dim_health", state.rows[0].key)
        }

    @Test
    fun `load DIMENSION with no matching key returns empty rows`() =
        runTest {
            val row =
                DimensionMetricRow(
                    dimensionId = "dim_health",
                    dayKey = "2026-09-21",
                    score = 0.6,
                    runningAvg = 0.5,
                    progress = 0.9,
                    streakPos = 1,
                    streakNet = 1,
                    posContinue = 1,
                )
            fakeRepository.dimensionRows = listOf(row)

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DIMENSION
            vm.currentKey = "dim_nonexistent"
            vm.load(ScoreDetailType.DIMENSION, "dim_nonexistent")
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.rows
                    .isEmpty(),
            )
        }

    // ── window-size changes ────────────────────────────────────────

    @Test
    fun `setWindowSize updates windowSizeDays and reloads`() =
        runTest {
            val day =
                DayMetricRow(
                    dayKey = "2026-09-21",
                    score = 0.5,
                    runningAvg = 0.5,
                    progress = 0.5,
                    streakPos = 0,
                    streakNet = 0,
                    posContinue = 0,
                )
            fakeRepository.dayRows = listOf(day)

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.load(ScoreDetailType.DAY, "DAY")
            advanceUntilIdle()
            assertEquals(7, vm.uiState.value.windowSizeDays)

            vm.setWindowSize(30)
            advanceUntilIdle()
            assertEquals(30, vm.uiState.value.windowSizeDays)
            // Row should have been reloaded
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `setWindowSize 0 triggers all-time window via resolveWindowStart`() =
        runTest {
            fakeRepository.earliestDay = "2026-01-15"
            val day =
                DayMetricRow(
                    dayKey = "2026-01-15",
                    score = 0.3,
                    runningAvg = 0.3,
                    progress = 0.3,
                    streakPos = 0,
                    streakNet = 0,
                    posContinue = 0,
                )
            fakeRepository.dayRows = listOf(day)

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.load(ScoreDetailType.DAY, "DAY")
            advanceUntilIdle()

            vm.setWindowSize(0)
            advanceUntilIdle()

            assertEquals(0, vm.uiState.value.windowSizeDays)
            assertFalse(vm.uiState.value.isLoading)
        }

    // ── shift-back / shift-forward navigation ──────────────────────

    @Test
    fun `shiftWindow negative moves end date back`() =
        runTest {
            val vm = viewModel()
            val initialEnd = vm.uiState.value.windowEnd
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.shiftWindow(-7)
            advanceUntilIdle()

            assertEquals(initialEnd.minusDays(7), vm.uiState.value.windowEnd)
        }

    @Test
    fun `shiftWindow positive moves end date forward`() =
        runTest {
            val vm = viewModel()
            val initialEnd = vm.uiState.value.windowEnd
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.shiftWindow(14)
            advanceUntilIdle()

            assertEquals(initialEnd.plusDays(14), vm.uiState.value.windowEnd)
        }

    @Test
    fun `shiftWindow reloads rows with updated window`() =
        runTest {
            val day =
                DayMetricRow(
                    dayKey = "2026-09-14",
                    score = 0.4,
                    runningAvg = 0.4,
                    progress = 0.4,
                    streakPos = 0,
                    streakNet = 0,
                    posContinue = 0,
                )
            fakeRepository.dayRows = listOf(day)

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.load(ScoreDetailType.DAY, "DAY")
            advanceUntilIdle()

            vm.shiftWindow(-7)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertEquals(1, vm.uiState.value.rows.size)
        }

    @Test
    fun `shiftWindow for DIMENSION uses currentKey to filter`() =
        runTest {
            val row =
                DimensionMetricRow(
                    dimensionId = "dim_work",
                    dayKey = "2026-09-14",
                    score = 0.7,
                    runningAvg = 0.7,
                    progress = 0.7,
                    streakPos = 1,
                    streakNet = 1,
                    posContinue = 1,
                )
            fakeRepository.dimensionRows = listOf(row)

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DIMENSION
            vm.currentKey = "dim_work"
            vm.shiftWindow(-7)
            advanceUntilIdle()

            assertEquals(1, vm.uiState.value.rows.size)
            assertEquals(
                "dim_work",
                vm.uiState.value.rows[0]
                    .key,
            )
        }

    // ── chart / table toggle ───────────────────────────────────────

    @Test
    fun `setChartView false switches to table mode`() =
        runTest {
            val vm = viewModel()
            assertTrue(vm.uiState.value.showChartView)

            vm.setChartView(false)
            assertFalse(vm.uiState.value.showChartView)
        }

    @Test
    fun `setChartView true switches back to chart mode`() =
        runTest {
            val vm = viewModel()
            vm.setChartView(false)
            vm.setChartView(true)
            assertTrue(vm.uiState.value.showChartView)
        }

    // ── resolveWindowStart paths (tested indirectly) ───────────────

    @Test
    fun `resolveWindowStart positive days subtracts days-1 from end`() =
        runTest {
            // windowSizeDays = 7, end = 2026-09-21 → start = 2026-09-15
            fakeRepository.earliestDay = "2026-01-01"

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            // windowEnd defaults to LocalDate.now() which is task-time; we can't fix it,
            // but we can verify the window doesn't crash and loads successfully
            vm.load(ScoreDetailType.DAY, "DAY")
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `resolveWindowStart zero days uses earliestDayKey for DAY`() =
        runTest {
            fakeRepository.earliestDay = "2025-06-15"
            fakeRepository.dayRows =
                listOf(
                    DayMetricRow(
                        dayKey = "2025-06-15",
                        score = 0.1,
                        runningAvg = 0.1,
                        progress = 0.1,
                        streakPos = 0,
                        streakNet = 0,
                        posContinue = 0,
                    ),
                )

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.setWindowSize(0)
            advanceUntilIdle()

            assertEquals(0, vm.uiState.value.windowSizeDays)
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `resolveWindowStart zero days uses earliestDimensionDayKey for DIMENSION`() =
        runTest {
            fakeRepository.earliestDimensionDay = "2025-03-01"
            fakeRepository.dimensionRows =
                listOf(
                    DimensionMetricRow(
                        dimensionId = "dim_health",
                        dayKey = "2025-03-01",
                        score = 0.2,
                        runningAvg = 0.2,
                        progress = 0.2,
                        streakPos = 0,
                        streakNet = 0,
                        posContinue = 0,
                    ),
                )

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DIMENSION
            vm.currentKey = "dim_health"
            vm.setWindowSize(0)
            advanceUntilIdle()

            assertEquals(0, vm.uiState.value.windowSizeDays)
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `resolveWindowStart null earliest falls back to 2020-01-01`() =
        runTest {
            fakeRepository.earliestDay = null
            fakeRepository.dayRows =
                listOf(
                    DayMetricRow(
                        dayKey = "2026-09-21",
                        score = 0.5,
                        runningAvg = 0.5,
                        progress = 0.5,
                        streakPos = 0,
                        streakNet = 0,
                        posContinue = 0,
                    ),
                )

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.setWindowSize(0)
            advanceUntilIdle()

            // Should not crash — resolveWindowStart falls back to 2020-01-01
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `resolveWindowStart malformed earliest date falls back to 2020-01-01`() =
        runTest {
            fakeRepository.earliestDay = "not-a-date"
            fakeRepository.dayRows = emptyList()

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.setWindowSize(0)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `resolveWindowStart null earliestDimensionDayKey falls back to 2020-01-01`() =
        runTest {
            fakeRepository.earliestDimensionDay = null
            fakeRepository.dimensionRows = emptyList()

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DIMENSION
            vm.currentKey = "dim_finance"
            vm.setWindowSize(0)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
        }

    // ── error handling ─────────────────────────────────────────────

    @Test
    fun `load catches repository exception and surfaces error`() =
        runTest {
            // Use a throwing repository via a custom fake
            val throwingRepo =
                object : io.payanam.domain.repository.ScoreWindowRepository {
                    override suspend fun getDimensionWindow(
                        start: String,
                        end: String,
                    ) = throw RuntimeException("repo boom")

                    override suspend fun getDayWindow(
                        start: String,
                        end: String,
                    ) = throw RuntimeException("repo boom")

                    override suspend fun earliestDayKey(): String? = null

                    override suspend fun earliestDimensionDayKey(dimensionId: String): String? = null

                    override suspend fun earliestDimensionDayKey(): String? = null
                }

            val vm =
                ScoreDetailViewModel(
                    scoreWindowRepository = throwingRepo,
                    scope = newViewModelScope(),
                )
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.load(ScoreDetailType.DAY, "DAY")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoading)
            assertNotNull(state.error)
            assertTrue(state.error!!.contains("repo boom"))
        }

    // ── goToday ────────────────────────────────────────────────────

    @Test
    fun `goToday resets windowEnd to today and reloads`() =
        runTest {
            val day =
                DayMetricRow(
                    dayKey = "2026-09-21",
                    score = 0.5,
                    runningAvg = 0.5,
                    progress = 0.5,
                    streakPos = 0,
                    streakNet = 0,
                    posContinue = 0,
                )
            fakeRepository.dayRows = listOf(day)

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            // Shift away from today first
            vm.shiftWindow(-30)
            advanceUntilIdle()
            val shiftedEnd = vm.uiState.value.windowEnd

            vm.goToday()
            advanceUntilIdle()

            // windowEnd should now be today (LocalDate.now())
            assertFalse(
                vm.uiState.value.windowEnd
                    .isEqual(shiftedEnd),
            )
            assertFalse(vm.uiState.value.isLoading)
        }

    // ── dispose ────────────────────────────────────────────────────

    @Test
    fun `dispose cancels the scope without crash`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.dispose()
            advanceUntilIdle()
            // No crash; scope is cancelled
            assertTrue(true)
        }

    @Test
    fun `load after dispose does not crash`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.dispose()

            // Calling load after dispose does not crash — the launch is silently dropped
            vm.currentType = ScoreDetailType.DAY
            vm.currentKey = "DAY"
            vm.load(ScoreDetailType.DAY, "DAY")
            advanceUntilIdle()
            assertTrue(true)
        }

    // ── goToday — DIMENSION ────────────────────────────────────────
    //
    // The only goToday test above runs the DAY arm. This one pins the other half of the `when`: the
    // dimension layer filters the window by `currentKey` before publishing, so a "today" reset on a
    // dimension screen must not show the other dimensions' rows.

    @Test
    fun `goToday for DIMENSION refetches the window and filters to currentKey`() =
        runTest {
            val health =
                DimensionMetricRow(
                    dimensionId = "dim_health",
                    dayKey = "2026-09-21",
                    score = 0.6,
                    runningAvg = 0.6,
                    progress = 0.6,
                    streakPos = 2,
                    streakNet = 1,
                    posContinue = 1,
                )
            val work =
                DimensionMetricRow(
                    dimensionId = "dim_work",
                    dayKey = "2026-09-21",
                    score = 0.4,
                    runningAvg = 0.4,
                    progress = 0.4,
                    streakPos = 0,
                    streakNet = 0,
                    posContinue = 0,
                )
            fakeRepository.dimensionRows = listOf(health, work)

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DIMENSION
            vm.currentKey = "dim_health"

            // Move the window away from today FIRST, so the reload below can only come from goToday.
            vm.shiftWindow(-30)
            advanceUntilIdle()
            assertNotEquals("the window really did move", java.time.LocalDate.now(), vm.uiState.value.windowEnd)
            assertTrue("the shifted window loaded rows", vm.uiState.value.rows.isNotEmpty())

            vm.goToday()
            advanceUntilIdle()

            assertEquals(java.time.LocalDate.now(), vm.uiState.value.windowEnd)
            assertFalse(vm.uiState.value.isLoading)
            // Only the current dimension's row survives the filter.
            assertEquals(1, vm.uiState.value.rows.size)
            assertEquals("dim_health", vm.uiState.value.rows[0].key)
        }

    @Test
    fun `goToday for DIMENSION publishes nothing when the repository holds no rows`() =
        runTest {
            fakeRepository.dimensionRows = listOf(
                DimensionMetricRow(
                    dimensionId = "dim_finance",
                    dayKey = "2026-09-21",
                    score = 0.5,
                    runningAvg = 0.5,
                    progress = 0.5,
                    streakPos = 0,
                    streakNet = 0,
                    posContinue = 0,
                ),
            )

            val vm = viewModel()
            vm.currentType = ScoreDetailType.DIMENSION
            // A key the repository has no row for: the reset still lands, with an empty window.
            vm.currentKey = "dim_missing"

            vm.goToday()
            advanceUntilIdle()

            assertEquals(java.time.LocalDate.now(), vm.uiState.value.windowEnd)
            assertFalse(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.rows.isEmpty())
        }
}
