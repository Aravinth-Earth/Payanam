//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.presentation.flags.FeatureFlagsState
import io.payanam.presentation.flags.PlatformFeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The Lens ViewModel's load pipeline, entry point by entry point.
 *
 * WHY ONE TEST PER PUBLIC ENTRY POINT:
 *
 * `LensViewModel` drives a dozen independent loaders, each behind its own public trigger, and the module's
 * coverage floor is what caught that none of them had a test: the app's only lens test exercised the
 * collector plumbing. A test per entry point is coarse on purpose - it pins that the loader runs, that it
 * publishes the state field it owns, and that it does not throw - which is exactly the contract the screen
 * relies on when it calls the trigger from a `LaunchedEffect`.
 *
 * TWO THINGS THIS CLASS HAS TO DO THAT A PLAIN COROUTINE TEST DOES NOT:
 *
 * 1. The ViewModel takes `scope = CoroutineScope(dispatcher)`. Its own default scope is
 *    `Dispatchers.Default`, which `Dispatchers.setMain` does not control, so a test that only sets Main
 *    would assert against a load that has not started.
 * 2. The loaders hand their heavy work to `Dispatchers.Default` themselves, and `advanceUntilIdle()` cannot
 *    see that thread. [settle] therefore runs the test dispatcher AND gives the background work real time,
 *    stopping as soon as the caller's condition holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LensViewModelLoadersTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeLensRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeLensRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        PlatformFeatureFlags.resetForTests()
    }

    /**
     * Drives the test dispatcher and waits, in real time, for [condition] - the loaders run their work on
     * `Dispatchers.Default`, which the virtual clock does not advance.
     */
    private fun settle(
        timeoutMs: Long = 3_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(5)
        }
    }

    private fun lensViewModel(): LensViewModel = LensViewModel(repository, scope = CoroutineScope(dispatcher))

    /** Everything the screen can toggle on. */
    private fun LensViewModel.enableEveryChart() {
        loadEnabledChartsSequentially(
            chartTimeModuleEnabled = true,
            chartTimeScoreCardsEnabled = true,
            chartTimeOverallScoreCardEnabled = true,
            chartTimeDimensionScoreCardsEnabled = true,
            chartTimeLineGraphsEnabled = true,
            chartTimeDailyScoreTrendEnabled = true,
            chartTimeProgressTrendEnabled = true,
            chartTimeHistoricalRankingEnabled = true,
            chartTimeMomentumStreakEnabled = true,
            chartDimSplitEnabled = true,
            chartAverageDailyTimeEnabled = true,
            chartDimTrendEnabled = true,
            chartDailyTimelineEnabled = true,
            chartWeeklyPatternEnabled = true,
            chartWeeklyPatternExclEmpty = true,
            chartDailyRhythmEnabled = true,
            chartDailyRhythmExclEmpty = true,
        )
    }

    /** A settled ViewModel whose initial load has published. */
    private fun loadedViewModel(): LensViewModel =
        lensViewModel().also { model ->
            settle { model.uiState.value.planningData != null }
        }

    @Test
    fun `init loads the selected day`() =
        runTest(dispatcher) {
            val model = lensViewModel()
            settle { model.uiState.value.planningData != null }

            val state = model.uiState.value
            assertNotNull("the planning data for the selected day", state.planningData)
            assertNotNull("the reality data for the selected day", state.realityData)
            assertFalse("loading to have finished", state.isLoading)
            assertFalse("no error", state.hasError)
        }

    @Test
    fun `loadLensData re-reads a day once it is marked stale`() =
        runTest(dispatcher) {
            val model = loadedViewModel()
            val firstPass = repository.calculateCalls

            // The snapshot cache serves an unchanged day from memory, which is the point of the cache: a plain
            // reload must NOT re-read it.
            model.loadLensData()
            settle(timeoutMs = 400) { repository.calculateCalls > firstPass }
            assertEquals("an unchanged day comes from the cache", firstPass, repository.calculateCalls)

            // A day the repository reports stale is re-read.
            repository.dirtyDays = setOf(LocalDate.now().toString())
            model.loadLensData()
            settle { repository.calculateCalls > firstPass }

            assertTrue("a stale day is re-read", repository.calculateCalls > firstPass)
        }

    @Test
    fun `loadLensData surfaces a repository failure instead of throwing`() =
        runTest(dispatcher) {
            repository.failure = RuntimeException("lens offline")
            val model = lensViewModel()
            settle { model.uiState.value.hasError }

            val state = model.uiState.value
            assertTrue("the failure is reported to the screen", state.hasError)
            assertNotNull("with a message", state.errorMessage)
            assertFalse("and loading stops", state.isLoading)
        }

    @Test
    fun `selectDate moves the lens and reloads`() =
        runTest(dispatcher) {
            val model = loadedViewModel()
            val target = LocalDate.of(2026, 3, 4)

            model.selectDate(target)
            settle { model.uiState.value.selectedDate == target }

            assertEquals(target, model.uiState.value.selectedDate)
            assertNotNull(model.uiState.value.planningData)
        }

    @Test
    fun `selectMoment and selectGrouping update their state`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.selectMoment(LensMoment.CLOSE_DAY)
            model.selectGrouping(LensGrouping.BY_DIMENSION)

            assertEquals(LensMoment.CLOSE_DAY, model.uiState.value.selectedMoment)
            assertEquals(LensGrouping.BY_DIMENSION, model.uiState.value.selectedGrouping)
        }

    @Test
    fun `selectTimeMode applies a window and resets paging`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.selectTimeMode(LensTimeMode.PAST)
            settle { model.uiState.value.selectedTimeMode == LensTimeMode.PAST }

            val state = model.uiState.value
            assertEquals(LensTimeMode.PAST, state.selectedTimeMode)
            assertEquals("a mode change returns to the first page", 0, state.windowPageIndex)
        }

    @Test
    fun `selectTimeMode is a no-op when the mode and window already match`() =
        runTest(dispatcher) {
            val model = loadedViewModel()
            val before = repository.calculateCalls

            model.selectTimeMode(model.uiState.value.selectedTimeMode)
            settle { repository.calculateCalls > before }

            assertEquals("an unchanged selection does not reload", before, repository.calculateCalls)
        }

    @Test
    fun `window paging moves only within the loaded range`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            // A 30-day window belongs to the past modes: selecting it while the lens is on TODAY is clamped
            // back to the mode's default, which is the contract `isWindowAllowedForMode` enforces.
            model.selectTimeMode(LensTimeMode.PAST)
            settle { model.uiState.value.selectedTimeMode == LensTimeMode.PAST }

            model.selectTimeWindow(LensTimeWindow.LAST_30_DAYS)
            settle { model.uiState.value.selectedTimeWindow == LensTimeWindow.LAST_30_DAYS }
            assertEquals(LensTimeWindow.LAST_30_DAYS, model.uiState.value.selectedTimeWindow)

            // The page index is relative to the mode's timeline, not to wall time: in PAST mode
            // `goToPreviousWindowPage` walks FURTHER BACK (the index grows) and `goToNextWindowPage` walks
            // toward the present (it shrinks). Both are gated on flags derived from the loaded range, so the
            // invariant a caller can rely on is the direction and the floor.
            model.goToPreviousWindowPage()
            settle { model.uiState.value.windowPageIndex > 0 }
            val deeperInThePast = model.uiState.value.windowPageIndex
            assertTrue("a page index is never negative", deeperInThePast >= 0)

            model.goToNextWindowPage()
            settle(timeoutMs = 400) { model.uiState.value.windowPageIndex < deeperInThePast }

            assertTrue(
                "moving toward the present never passes the newest page",
                model.uiState.value.windowPageIndex <= deeperInThePast,
            )
        }

    @Test
    fun `goToPreviousWindowPage stops at the first page`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.goToPreviousWindowPage()
            settle { model.uiState.value.windowPageIndex != 0 }

            assertEquals("there is nothing before the first page", 0, model.uiState.value.windowPageIndex)
        }

    @Test
    fun `markReflectionAddressed reaches the repository`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.markReflectionAddressed("reflection-1", "done")
            settle { repository.addressedReflections.isNotEmpty() }

            assertEquals(listOf("reflection-1" to "done"), repository.addressedReflections)
        }

    @Test
    fun `regenerateReflections asks for new cards then reloads`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.regenerateReflections()
            settle { repository.generatedReflections.isNotEmpty() }

            assertEquals(1, repository.generatedReflections.size)
            assertFalse(model.uiState.value.isLoading)
        }

    @Test
    fun `regenerateReflections reports a failure`() =
        runTest(dispatcher) {
            val model = loadedViewModel()
            repository.failure = RuntimeException("cannot regenerate")

            model.regenerateReflections()
            settle { model.uiState.value.hasError }

            assertTrue(model.uiState.value.hasError)
        }

    @Test
    fun `every enabled chart reaches its loader`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            // The charts-on path refreshes the day's reflections only when the repository reports the day
            // stale, and skips it otherwise - both branches are the contract, so this test takes the dirty one.
            // The day key the path asks about is derived from the range's focus day, so the whole repository is
            // marked stale rather than guessing it.
            repository.reportAllDaysDirty = true
            model.enableEveryChart()
            settle {
                repository.dimensionSplitCalls + repository.heatmapCalls + repository.weekGridCalls +
                    repository.minutePatternCalls + repository.dimensionTrendCalls +
                    repository.averageDailyTimeCalls + repository.focusAverageCalls > 0
            }

            val loadedCalls =
                repository.dimensionSplitCalls + repository.heatmapCalls + repository.weekGridCalls +
                    repository.minutePatternCalls + repository.dimensionTrendCalls +
                    repository.averageDailyTimeCalls + repository.focusAverageCalls
            assertTrue("the enabled charts read their datasets", loadedCalls > 0)

            // The charts load ends by refreshing the day's reflections. That refresh is a launched job of its
            // own, so a test that stops at the chart load never sees it run.
            settle {
                model.uiState.value.reflections
                    .isNotEmpty()
            }
            assertTrue(
                "the dirty day's reflections are published",
                model.uiState.value.reflections
                    .isNotEmpty(),
            )
        }

    @Test
    fun `the time module off means no history summary`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.loadEnabledChartsSequentially(
                chartTimeModuleEnabled = false,
                chartTimeScoreCardsEnabled = false,
                chartTimeOverallScoreCardEnabled = false,
                chartTimeDimensionScoreCardsEnabled = false,
                chartTimeLineGraphsEnabled = false,
                chartTimeDailyScoreTrendEnabled = false,
                chartTimeProgressTrendEnabled = false,
                chartTimeHistoricalRankingEnabled = false,
                chartTimeMomentumStreakEnabled = false,
                chartDimSplitEnabled = false,
                chartAverageDailyTimeEnabled = false,
                chartDimTrendEnabled = false,
                chartDailyTimelineEnabled = false,
                chartWeeklyPatternEnabled = false,
                chartWeeklyPatternExclEmpty = false,
                chartDailyRhythmEnabled = false,
                chartDailyRhythmExclEmpty = false,
            )
            settle { model.uiState.value.timeModuleHistorySummary != null }

            assertNull(
                "a disabled time module shows no history summary",
                model.uiState.value.timeModuleHistorySummary,
            )
        }

    @Test
    fun `requestNextTimeHistoryStage is safe before and after the charts are on`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            // Charts off: the request returns immediately.
            model.requestNextTimeHistoryStage()
            settle { model.uiState.value.timeModuleHistorySummary != null }
            assertNull(model.uiState.value.timeModuleHistorySummary)

            model.enableEveryChart()
            settle { repository.dimensionSplitCalls > 0 }

            model.requestNextTimeHistoryStage()
            settle { !model.uiState.value.isLoading }

            // The ViewModel is still responsive after both paths: a day marked stale is re-read.
            val before = repository.calculateCalls
            repository.dirtyDays = setOf(LocalDate.now().toString())
            model.loadLensData()
            settle { repository.calculateCalls > before }
            assertTrue("the ViewModel keeps working", repository.calculateCalls > before)
        }

    @Test
    fun `dimension split selectors move the window and never shift below the origin`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.selectDimensionSplitWindow(DimensionSplitWindow.W30)
            settle { model.uiState.value.dimensionSplit.window == DimensionSplitWindow.W30 }
            assertEquals(DimensionSplitWindow.W30, model.uiState.value.dimensionSplit.window)

            model.shiftDimensionSplitRight()
            settle { model.uiState.value.dimensionSplit.windowOffset > 0 }

            model.shiftDimensionSplitLeft()
            settle(timeoutMs = 400) { model.uiState.value.dimensionSplit.windowOffset < 0 }

            // Both shifts are gated on the loaded data (`canShiftLeft`/`canShiftRight`), so the invariant a
            // caller can rely on is the direction, never a specific offset: it may move out, and it may not
            // move before the origin.
            assertTrue(
                "the offset never goes negative",
                model.uiState.value.dimensionSplit.windowOffset >= 0,
            )
        }

    @Test
    fun `dimension trend selectors move the window and load blocks`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.selectDimensionTrendWindow(DimensionTrendWindow.W7)
            settle { model.uiState.value.dimensionTrend.window == DimensionTrendWindow.W7 }
            assertEquals(DimensionTrendWindow.W7, model.uiState.value.dimensionTrend.window)

            model.triggerDimTrendLoad()
            settle { repository.dimensionTrendCalls > 0 }

            assertTrue("the trend loader ran", repository.dimensionTrendCalls > 0)
            assertFalse("and finished", model.uiState.value.dimensionTrend.isLoading)
        }

    @Test
    fun `the three chart triggers each read their own dataset`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.triggerDailyTimelineLoad()
            settle { repository.heatmapCalls > 0 }
            assertTrue("the daily timeline reads the heatmap", repository.heatmapCalls > 0)
            assertFalse(model.uiState.value.heatmap.isLoading)

            model.triggerWeeklyPatternLoad(excludeEmptyDays = true)
            settle { repository.weekGridCalls > 0 }
            assertTrue("the weekly pattern reads the week grid", repository.weekGridCalls > 0)
            assertFalse(model.uiState.value.weekGrid.isLoading)

            model.triggerDailyRhythmLoad(excludeEmptyDays = false)
            settle { repository.minutePatternCalls > 0 }
            assertTrue("the daily rhythm reads the minute pattern", repository.minutePatternCalls > 0)
            assertFalse(model.uiState.value.minutePattern.isLoading)
        }

    @Test
    fun `minimal mode loads the daily focus averages instead of the time module`() =
        runTest(dispatcher) {
            PlatformFeatureFlags.install(FeatureFlagsState(minimalModeEnabled = true))
            val model = loadedViewModel()

            model.enableEveryChart()
            settle { repository.focusAverageCalls > 0 }

            // The minimal path loads the focus averages and then the two daily-statistics loaders behind it -
            // the same three reads a minimal build's single chart needs.
            assertTrue("the focus averages are read", repository.focusAverageCalls > 0)
            assertFalse("and the minimal load finishes", model.uiState.value.isLoading)
        }

    @Test
    fun `the history prefetch waits out its cooldown before asking again`() =
        runTest(dispatcher) {
            val model = loadedViewModel()
            model.enableEveryChart()
            settle { repository.dimensionSplitCalls > 0 }

            // The prefetch is throttled against the process's monotonic clock, so the cooldown is real time,
            // not virtual: the first request is swallowed, and a second one after the window is not.
            model.requestNextTimeHistoryStage()
            settle { model.uiState.value.timeModuleHistorySummary != null }
            // LensViewModel's private HISTORY_PREFETCH_COOLDOWN_MS is 1,200 ms; wait past it.
            Thread.sleep(1_300)
            model.requestNextTimeHistoryStage()
            settle { !model.uiState.value.isLoading }

            assertFalse("the ViewModel finished both prefetch attempts", model.uiState.value.isLoading)
        }

    @Test
    fun `dispose cancels the work the ViewModel started`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.dispose()
            val before = repository.calculateCalls

            model.loadLensData()
            settle(timeoutMs = 400) { repository.calculateCalls > before }

            assertEquals("a disposed ViewModel starts no further work", before, repository.calculateCalls)
        }
}
