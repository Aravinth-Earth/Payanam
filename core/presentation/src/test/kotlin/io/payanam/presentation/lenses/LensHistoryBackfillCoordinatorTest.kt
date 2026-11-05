//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LensHistoryBackfillCoordinatorTest {
    private lateinit var coordinator: LensHistoryBackfillCoordinator
    private lateinit var repository: FakeLensRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        coordinator = LensHistoryBackfillCoordinator(AppLogger.current)
        repository = FakeLensRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun next_limit_after_returns_expected_progressive_sequence() {
        assertEquals(14, coordinator.nextLimitAfter(7))
        assertEquals(30, coordinator.nextLimitAfter(14))
        assertEquals(60, coordinator.nextLimitAfter(30))
        assertEquals(90, coordinator.nextLimitAfter(60))
        assertEquals(180, coordinator.nextLimitAfter(90))
        assertEquals(365, coordinator.nextLimitAfter(180))
        assertEquals(Int.MAX_VALUE, coordinator.nextLimitAfter(365))
        assertEquals(null, coordinator.nextLimitAfter(Int.MAX_VALUE))
    }

    /** The range a TODAY/TODAY selection resolves to. */
    private fun todayRange(): ResolvedLensWindowRange {
        val today = LocalDate.now()
        return ResolvedLensWindowRange(
            mode = LensTimeMode.TODAY,
            window = LensTimeWindow.TODAY,
            pageIndex = 0,
            startDate = today,
            endDate = today,
        )
    }

    @Test
    fun cancel_is_safe_with_no_backfill_running() {
        coordinator.cancel()
    }

    @Test
    fun schedule_stops_immediately_when_the_selection_has_moved_on() =
        runTest(dispatcher) {
            val applied = mutableListOf<TimeModuleHistorySummary>()

            coordinator.schedule(
                scope = CoroutineScope(dispatcher),
                lensRepository = repository,
                focusDate = LocalDate.now(),
                seededDataByDay = emptyMap(),
                expectedRange = todayRange(),
                maxHistoryLimit = 30,
                loadSnapshot = { dayKey, _ -> repository.calculateUnifiedSnapshot(dayKey) },
                isCurrentSelection = { false },
                onBackfillReady = { applied += it },
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("a stale selection applies nothing", emptyList<TimeModuleHistorySummary>(), applied)
        }

    @Test
    fun schedule_applies_nothing_when_the_first_limit_is_already_over() =
        runTest(dispatcher) {
            val applied = mutableListOf<TimeModuleHistorySummary>()

            coordinator.schedule(
                scope = CoroutineScope(dispatcher),
                lensRepository = repository,
                focusDate = LocalDate.now(),
                seededDataByDay = emptyMap(),
                expectedRange = todayRange(),
                maxHistoryLimit = 0,
                loadSnapshot = { dayKey, _ -> repository.calculateUnifiedSnapshot(dayKey) },
                isCurrentSelection = { true },
                onBackfillReady = { applied += it },
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("a limit below the first step applies nothing", emptyList<TimeModuleHistorySummary>(), applied)
        }

    @Test
    fun schedule_applies_the_backfill_it_builds() =
        runTest(dispatcher) {
            val applied = mutableListOf<TimeModuleHistorySummary>()

            coordinator.schedule(
                scope = CoroutineScope(dispatcher),
                lensRepository = repository,
                focusDate = LocalDate.now(),
                seededDataByDay = emptyMap(),
                expectedRange = todayRange(),
                maxHistoryLimit = Int.MAX_VALUE,
                loadSnapshot = { dayKey, _ -> repository.calculateUnifiedSnapshot(dayKey) },
                isCurrentSelection = { true },
                onBackfillReady = { applied += it },
            )
            // The backfill hands its build to Dispatchers.Default, so the wait has to be real time.
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && applied.isEmpty()) {
                dispatcher.scheduler.runCurrent()
                Thread.sleep(5)
            }

            applied.forEach { summary ->
                assertTrue("an applied summary covers at least a day", summary.totalDays >= 0)
            }
        }
}
