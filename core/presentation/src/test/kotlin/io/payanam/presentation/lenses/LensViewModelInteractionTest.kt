//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.presentation.flags.FeatureFlagsState
import io.payanam.presentation.flags.PlatformFeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The progressive-history day limits the backfill walks, mirrored from `LensHistoryBackfillCoordinator`'s
 * private `PROGRESSIVE_HISTORY_LIMITS`. A test that hard-codes the numbers would keep passing if the SUT's
 * ladder changed, which is the one thing these assertions exist to catch; naming the pair keeps the
 * expected widening ("one step, not a jump to the end") in the reader's face.
 */
private const val INITIAL_STAGE_DAYS = 14
private const val NEXT_STAGE_DAYS = 30

/**
 * The Lens ViewModel's reflection-addressing and history-prefetch GUARDS.
 *
 * `LensViewModelLoadersTest` pins what a loader publishes once it runs. This file pins two entry points
 * whose contract is mostly "when must it NOT do the thing, and what does it do when it may":
 * addressing a reflection (write, then reload, then surface a failure) and requesting the next history
 * stage (five distinct reasons to decline, and the one reason to proceed).
 *
 * It reuses [LensTestFixtures]'s `FakeLensRepository` and the same `settle` idiom - the loaders hand their
 * work to `Dispatchers.Default`, which the virtual clock cannot advance, so a test has to give the real
 * thread real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LensViewModelInteractionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeLensRepository

    @Before
    fun setUp() {
        repository = FakeLensRepository()
    }

    @After
    fun tearDown() {
        PlatformFeatureFlags.resetForTests()
    }

    /** Drives the test dispatcher and waits, in real time, for [condition]. */
    private fun settle(
        timeoutMs: Long = 5_000,
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

    /** A settled ViewModel whose initial load has published. */
    private fun loadedViewModel(): LensViewModel =
        lensViewModel().also { model ->
            settle { model.uiState.value.planningData != null }
        }

    private fun LensViewModel.enableTimeHistoryCharts() {
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

    /**
     * A ViewModel with the time-history charts on and its INITIAL backfill stage applied.
     *
     * The fake needs a first tracked day: `buildTimeModuleHistorySummary` returns null without one, and a
     * null summary makes the coordinator stop before it ever applies anything. The date sits 30 days back so
     * the progressive stages actually WIDEN (14 → 30), which is what puts days outside the initially
     * seeded range on the backfill's own snapshot loader.
     *
     * The wait is for the BACKFILL, not for the first summary: enabling the charts publishes the fast
     * 7-day history immediately and the 14-day stage lands on a later turn, so a test that stops at the
     * first non-null summary would read 7 and mis-judge what the next request may widen to.
     */
    private fun backfillingViewModel(): LensViewModel {
        repository.firstTrackedDate = LocalDate.now().minusDays(30)
        val model = loadedViewModel()
        model.enableTimeHistoryCharts()
        settle {
            (
                model.uiState.value.timeModuleHistorySummary
                    ?.totalDays ?: 0
            ) >= INITIAL_STAGE_DAYS
        }
        return model
    }

    // ── markReflectionAddressed ─────────────────────────────────────────

    /** Addressing a card is a write plus a reload: the screen must not keep showing the stale card. */
    @Test
    fun `addressing a reflection reloads the lens data`() =
        runTest(dispatcher) {
            val model = loadedViewModel()
            // A clean day is served from the snapshot cache, so a reload would be invisible in the call
            // count. Marking the day stale is what makes the post-write reload observable - and it is a
            // realistic state: the user just acted on the day.
            repository.reportAllDaysDirty = true
            val generationsBefore = repository.generatedReflections.size

            model.markReflectionAddressed("reflection-1", "handled")
            settle { repository.generatedReflections.size > generationsBefore }

            assertEquals(listOf("reflection-1" to "handled"), repository.addressedReflections)
            // The reload is what pulled the card back in, and it ran to completion.
            assertTrue("the lens data was reloaded", repository.generatedReflections.size > generationsBefore)
            assertTrue("the reload finished", !model.uiState.value.isLoading)
        }

    /** A null note is a legitimate call (the user can address a card without writing anything). */
    @Test
    fun `addressing a reflection passes a null note through`() =
        runTest(dispatcher) {
            val model = loadedViewModel()

            model.markReflectionAddressed("reflection-2", null)
            settle { repository.addressedReflections.isNotEmpty() }

            assertEquals(listOf("reflection-2" to null), repository.addressedReflections)
        }

    /** A repository that cannot write must not take the screen down: the failure is published, not thrown. */
    @Test
    fun `a failing address write is published on hasError`() =
        runTest(dispatcher) {
            val model = loadedViewModel()
            repository.failure = IllegalStateException("write boom")

            model.markReflectionAddressed("reflection-3", "nope")
            settle { model.uiState.value.hasError }

            assertTrue(model.uiState.value.hasError)
            assertEquals(
                "Failed to update reflection: write boom",
                model.uiState.value.errorMessage,
            )
            // The write never landed.
            assertTrue("nothing was addressed", repository.addressedReflections.isEmpty())
        }

    // ── scheduleHistoryBackfill, through requestNextTimeHistoryStage ────

    /**
     * RETURN 1 - the time-history module is off. A fresh ViewModel never enables the charts, so the request
     * must decline before touching the coordinator, and the summary it would have built stays absent.
     */
    @Test
    fun `a history stage is declined while the charts are disabled`() =
        runTest(dispatcher) {
            val model = loadedViewModel()
            assertNull("charts start off", model.uiState.value.timeModuleHistorySummary)

            model.requestNextTimeHistoryStage()
            settle { model.uiState.value.timeModuleHistorySummary != null }

            assertNull("no stage was scheduled", model.uiState.value.timeModuleHistorySummary)
        }

    /** RETURN 2 - minimal mode opts out of the time module entirely, even with every chart enabled. */
    @Test
    fun `a history stage is declined in minimal mode`() =
        runTest(dispatcher) {
            PlatformFeatureFlags.install(FeatureFlagsState(minimalModeEnabled = true))
            val model = loadedViewModel()
            model.enableTimeHistoryCharts()
            settle { repository.focusAverageCalls > 0 }

            model.requestNextTimeHistoryStage()
            settle { repository.dimensionSplitCalls > 0 }

            assertNull("minimal mode never builds a time history", model.uiState.value.timeModuleHistorySummary)
        }

    /**
     * The PROCEED path, and the one that carries the backfill's own lambdas: the request schedules a wider
     * stage, the coordinator applies it, and the days it widened into are read through the snapshot
     * loader rather than the initially seeded range.
     */
    @Test
    fun `a history stage past the cooldown widens the time history`() =
        runTest(dispatcher) {
            val model = backfillingViewModel()
            val initialDays =
                model.uiState.value.timeModuleHistorySummary
                    ?.totalDays
            assertNotNull("the initial stage was applied", initialDays)
            assertEquals("the initial stage stops at 14 days", INITIAL_STAGE_DAYS, initialDays)
            val calculationsBefore = repository.calculateCalls

            // The cooldown is real time (it reads the process monotonic clock), so it cannot be wound
            // back by the test dispatcher. It does not need to be: this fixture is already PAST it.
            // `loadLensData` seeds `lastHistoryPrefetchAtMs` to zero and the initial 14-day stage is
            // SCHEDULED rather than REQUESTED, so nothing has opened the window yet - `elapsedMillis()`
            // reads process uptime, which has been longer than the 1,200 ms cooldown since the ViewModel
            // was constructed. Sleeping here bought a guarantee the arrival state already provided, and
            // cost 1.3 s of wall clock the case did not need.
            model.requestNextTimeHistoryStage()
            settle {
                (
                    model.uiState.value.timeModuleHistorySummary
                        ?.totalDays ?: 0
                ) > (initialDays ?: 0)
            }

            assertEquals(
                "the request widened the history one stage",
                NEXT_STAGE_DAYS,
                model.uiState.value.timeModuleHistorySummary
                    ?.totalDays,
            )
            // Widening forced reads for the days the first stage never covered.
            assertTrue(
                "the backfill loaded the days it widened into",
                repository.calculateCalls > calculationsBefore,
            )
        }

    /**
     * RETURN 4 - the cooldown. The prefetch is throttled against the process MONOTONIC clock
     * (`elapsedMillis()`), so the window is real time, not virtual: a second request inside the
     * 1,200 ms cooldown is swallowed and the summary does not move.
     *
     * The fixture has to OPEN the cooldown before it can test anything inside it. Loading the lens does
     * not: `loadLensData` seeds `lastHistoryPrefetchAtMs` to zero and the initial 14-day stage is
     * SCHEDULED rather than REQUESTED, so the first `requestNextTimeHistoryStage()` on a fresh ViewModel
     * is legitimately honoured and widens 14 -> 30. A test that issued only one request was therefore not
     * inside a cooldown at all - it raced the widen it had just triggered, and read 30 whenever the
     * background stage beat the assertion, which is why this case failed intermittently and passed when
     * run alone.
     *
     * So: request once (that request opens the window), settle on the widened summary, then request again
     * and assert nothing moved. The assertion below names NEXT_STAGE_DAYS because the DECLINED request
     * would have widened one stage further - its absence is what the cooldown guarantees.
     */
    @Test
    fun `a second history stage inside the cooldown is declined`() =
        runTest(dispatcher) {
            val model = backfillingViewModel()

            // The first request is honoured, and it is what starts the cooldown.
            model.requestNextTimeHistoryStage()
            settle {
                model.uiState.value.timeModuleHistorySummary
                    ?.totalDays == NEXT_STAGE_DAYS
            }
            val afterFirst = model.uiState.value.timeModuleHistorySummary
            assertEquals(
                "the first request widened the history one stage",
                NEXT_STAGE_DAYS,
                afterFirst?.totalDays,
            )

            model.requestNextTimeHistoryStage()
            settle { !model.uiState.value.isLoading }

            assertEquals(
                "the cooldown swallowed the widening",
                afterFirst,
                model.uiState.value.timeModuleHistorySummary,
            )
            // A widening is what the declined request would have produced, so its absence is the assertion.
            assertEquals(
                NEXT_STAGE_DAYS,
                model.uiState.value.timeModuleHistorySummary
                    ?.totalDays,
            )
        }

    /**
     * RETURN 3 - no context. The backfill context is only captured by a load that ran, so a disposed
     * ViewModel (whose load can no longer run) has none: the request is a no-op rather than a crash.
     */
    @Test
    fun `a history stage is declined once the ViewModel can no longer load`() =
        runTest(dispatcher) {
            val model = backfillingViewModel()
            model.dispose()
            val before = model.uiState.value.timeModuleHistorySummary

            model.requestNextTimeHistoryStage()
            Thread.sleep(1_300)
            settle { !model.uiState.value.isLoading }

            assertEquals(before, model.uiState.value.timeModuleHistorySummary)
        }
}
