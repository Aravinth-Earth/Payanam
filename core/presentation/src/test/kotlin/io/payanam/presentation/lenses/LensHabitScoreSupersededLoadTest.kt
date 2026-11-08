//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.domain.model.DimensionMetricRow
import io.payanam.domain.model.MetricWindowRow
import io.payanam.domain.repository.ScoreWindowRepository
import io.payanam.presentation.scoring.ScoreMetricColumn
import io.payanam.presentation.tasks.FakeScoreChangeStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the load-ordering contract of [LensHabitScoreViewModel]: a load that a newer request superseded may
 * still finish its repository reads, but it must not publish anything afterwards.
 *
 * The interleaving is ordered by a latch in the fake repository rather than by timing: the older load parks
 * inside its FIRST read, the newer request registers and publishes to completion, and only then is the older
 * load released. Everything else in the older load then runs, so it genuinely arrives at its publish attempt
 * (the fake counts the history read that precedes it) with the newer state already in place.
 *
 * What this cannot pin: a preemption in the few instructions BETWEEN a load's currency check and its state
 * write. That window is what the ViewModel's single lock closes structurally - a black-box test cannot schedule
 * it, so this test pins the observable contract (a superseded load publishes nothing), not the interleaving
 * itself.
 */
class LensHabitScoreSupersededLoadTest {
    // Dates relative to today, so the newer request (which loads "today" through selectMetric) can never
    // collide with the gated older request's date - that date is what tells the two loads' rows apart.
    private val earliest: String = LocalDate.now().minusDays(60).toString()
    private val olderEnd: String = LocalDate.now().minusDays(30).toString()
    private val olderDim = "dim_older"
    private val newerDim = "dim_newer"

    /**
     * Serves a DISTINCT dimension key per request date so the published state identifies which load wrote it,
     * and holds the older load's first read on [release].
     *
     * A blocking latch rather than a `CompletableDeferred`: a suspension point is also a cancellation point, so
     * a deferred would kill the older load inside the read and it would never reach the publish attempt this
     * test is about. Blocking parks the thread with the coroutine still running.
     *
     * The history read (start == [earliest]) returns two days per row: scores 0.10 then 0.90, progress 0.50
     * both days. That makes the SCORE rank "1/2" and the PROGRESS rank "1/1", so the published rank string
     * also says which metric produced it.
     */
    private inner class GatedScoreWindowRepository : ScoreWindowRepository {
        private val gate = CountDownLatch(1)
        private val gateArmed = AtomicBoolean(false)

        /** Opens the gate that holds the older load's first read. Safe to call more than once. */
        fun release() = gate.countDown()

        /** Opened once the older load is parked inside its first read. */
        val gateReached = CountDownLatch(1)

        /** 1 once the superseded load served its history read, i.e. it reached its publish attempt. */
        val olderHistoryFetches = AtomicInteger()

        override suspend fun getDimensionWindow(
            start: String,
            end: String,
        ): List<MetricWindowRow> {
            if (end == olderEnd && gateArmed.compareAndSet(false, true)) {
                gateReached.countDown()
                gate.await()
            }
            if (end == olderEnd && start == earliest) olderHistoryFetches.incrementAndGet()
            return if (start == earliest) historyRows(end) else displayRows(end)
        }

        override suspend fun getDayWindow(
            start: String,
            end: String,
        ): List<MetricWindowRow> = emptyList()

        override suspend fun earliestDayKey(): String = earliest

        override suspend fun earliestDimensionDayKey(): String = earliest

        override suspend fun earliestDimensionDayKey(dimensionId: String): String = earliest

        private fun historyRows(end: String): List<MetricWindowRow> {
            val key = if (end == olderEnd) olderDim else newerDim
            return listOf(
                DimensionMetricRow(
                    dimensionId = key,
                    dayKey = earliest,
                    score = 0.10,
                    runningAvg = 0.10,
                    progress = 0.50,
                    streakPos = 0,
                    streakNet = 0,
                    posContinue = 0,
                ),
                DimensionMetricRow(
                    dimensionId = key,
                    dayKey = end,
                    score = 0.90,
                    runningAvg = 0.50,
                    progress = 0.50,
                    streakPos = 0,
                    streakNet = 0,
                    posContinue = 0,
                ),
            )
        }

        private fun displayRows(end: String): List<MetricWindowRow> {
            val key = if (end == olderEnd) olderDim else newerDim
            return listOf(
                DimensionMetricRow(
                    dimensionId = key,
                    dayKey = end,
                    score = 0.90,
                    runningAvg = 0.50,
                    progress = 0.50,
                    streakPos = 0,
                    streakNet = 0,
                    posContinue = 0,
                ),
            )
        }
    }

    @Test
    fun `a load superseded after registering cannot publish over the newer request`() {
        val repo = GatedScoreWindowRepository()
        // Unconfined, so each load runs inline on the thread that starts it: this test orders the two loads by
        // the repository gate, not by the dispatcher's scheduling.
        val vm =
            LensHabitScoreViewModel(
                repo,
                FakeScoreChangeStream(),
                scope = CoroutineScope(Dispatchers.Unconfined),
            )

        // 1. The older load (PROGRESS, the gated date) registers, then parks inside its first read.
        val older =
            Thread {
                vm.loadWindow(
                    LocalDate.parse(olderEnd),
                    days = 14,
                    metric = ScoreMetricColumn.PROGRESS,
                )
            }
        older.start()
        try {
            assertTrue(repo.gateReached.await(5, TimeUnit.SECONDS))

            // 2. The newer request (SCORE, today) registers and publishes to completion meanwhile.
            vm.selectMetric(ScoreMetricColumn.SCORE)
            assertEquals(listOf(newerDim), vm.uiState.value.rows.map { it.key })
            assertEquals("1/2", vm.uiState.value.rankByKey[newerDim])

            // 3. Release the older load so its remaining reads (including the history one) actually happen.
            repo.release()
            older.join(5_000)

            assertFalse(older.isAlive)
            // Proof it was past its last suspension and at the guarded publish attempt, not just cancelled
            // mid-read: the history read only runs after the display read and before the publish check.
            assertEquals(1, repo.olderHistoryFetches.get())
            val state = vm.uiState.value
            assertEquals(listOf(newerDim), state.rows.map { it.key })
            assertEquals(setOf(newerDim), state.rankByKey.keys)
            // "1/2" is the SCORE rank; a stale PROGRESS publish would have written this row's "1/1".
            assertEquals("1/2", state.rankByKey[newerDim])
            assertNotEquals(olderEnd, state.windowEnd)
            assertEquals(ScoreMetricColumn.SCORE, state.selectedMetric)
            assertFalse(state.isLoading)
            assertNull(state.error)
        } finally {
            repo.release()
            older.join(5_000)
        }
    }
}
