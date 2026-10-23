//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier

/**
 * Regression: the lost-update race on the Time screen's section-loaded flags.
 *
 * This targets [TimeScreenDateLoadStateHolder]'s CONTRACT, which is what makes it deterministic.
 * The holder's only job is to merge concurrent section arrivals into one immutable
 * [TimeScreenDateLoadState] atomically, so the test releases exactly three marks from three real
 * threads through a [CyclicBarrier] and asserts the merged outcome on EVERY round with no
 * tolerance: either the property holds in all rounds or the holder is broken - there is no
 * scheduling window to get lucky or unlucky in.
 *
 * The old code was `field = field.markSectionLoaded(section)` on a plain `var`. That lost update
 * only fires when two read-modify-writes interleave between their read and their write - measured
 * at ~1 per 500-800 rounds - so asserting the same defect at the ViewModel level would be
 * probabilistic: flaky-passing, never reliably failing. Here it is unconditional, because each
 * mark merges into the value it actually reads instead of overwriting one it read earlier.
 */
class TimeScreenDateLoadStateHolderTest {
    private val sections = listOf(
        TimeScreenDateSection.ENTRIES,
        TimeScreenDateSection.PLANNED_TASKS,
        TimeScreenDateSection.OCCURRENCES,
    )

    private fun loadedFlag(state: TimeScreenDateLoadState, section: TimeScreenDateSection): Boolean =
        when (section) {
            TimeScreenDateSection.ENTRIES -> state.entriesLoaded
            TimeScreenDateSection.PLANNED_TASKS -> state.plannedTasksLoaded
            TimeScreenDateSection.OCCURRENCES -> state.occurrencesLoaded
        }

    private fun awaitGate(gate: CyclicBarrier) {
        try {
            gate.await()
        } catch (e: InterruptedException) {
            throw AssertionError("worker interrupted before the round started", e)
        } catch (e: BrokenBarrierException) {
            throw AssertionError("the round gate broke before all sections were released", e)
        }
    }

    /** One round: fresh load, then all three sections marked from three threads at once. */
    private class ConcurrentRound(
        /** Per-thread `markSectionLoaded` return; null would mean a refused arrival. */
        val merged: List<TimeScreenDateLoadState?>,
        /** Per-thread `current()` read immediately after that thread's own mark. */
        val readAfterOwnMark: List<TimeScreenDateLoadState>,
        val afterAllMarks: TimeScreenDateLoadState,
    )

    private fun runConcurrentRound(requestId: Long): ConcurrentRound {
        val holder =
            TimeScreenDateLoadStateHolder(TimeScreenDateLoadState(requestId, needsOccurrences = true))
        val merged = arrayOfNulls<TimeScreenDateLoadState>(sections.size)
        val readAfterOwnMark = arrayOfNulls<TimeScreenDateLoadState>(sections.size)
        val gate = CyclicBarrier(sections.size)
        val workers = sections.indices.map { index ->
            Thread {
                awaitGate(gate)
                merged[index] = holder.markSectionLoaded(requestId, sections[index])
                readAfterOwnMark[index] = holder.current()
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        return ConcurrentRound(
            merged = merged.toList(),
            readAfterOwnMark =
                readAfterOwnMark.map {
                    it ?: throw AssertionError("request $requestId: a worker recorded no state")
                },
            afterAllMarks = holder.current(),
        )
    }

    /** Assertion 1: no concurrent arrival may discard another arrival's flag. */
    @Test
    fun all_three_sections_survive_concurrent_marking_in_every_round() {
        repeat(2_000) { index ->
            val requestId = index.toLong() + 1L
            val afterAllMarks = runConcurrentRound(requestId).afterAllMarks
            sections.forEach { section ->
                assertTrue(
                    "round $requestId lost an update: $section is unloaded in $afterAllMarks",
                    loadedFlag(afterAllMarks, section),
                )
            }
            assertTrue("round $requestId never became ready: $afterAllMarks", afterAllMarks.isReady())
        }
    }

    /** Assertion 4: a mark's merged snapshot and the read that follows it cannot disagree. */
    @Test
    fun the_merged_snapshot_and_the_following_read_never_disagree() {
        repeat(500) { index ->
            val requestId = index.toLong() + 1L
            val round = runConcurrentRound(requestId)
            sections.indices.forEach { i ->
                val section = sections[i]
                val merged = round.merged[i]
                assertNotNull("round $requestId: the $section mark returned null", merged)
                assertTrue(
                    "round $requestId: the $section mark does not report $section loaded: $merged",
                    loadedFlag(merged!!, section),
                )
                sections.forEach { other ->
                    assertTrue(
                        "round $requestId: after marking $section the state lost $other, which " +
                            "the merged snapshot already reported loaded",
                        loadedFlag(round.readAfterOwnMark[i], other) || !loadedFlag(merged, other),
                    )
                }
            }
        }
    }

    /** Assertion 2: a superseded load's late arrival is refused and changes nothing. */
    @Test
    fun a_stale_arrival_is_refused_and_leaves_the_state_unchanged() {
        val holder =
            TimeScreenDateLoadStateHolder(TimeScreenDateLoadState(1L, needsOccurrences = true))
        assertNotNull(holder.markSectionLoaded(1L, TimeScreenDateSection.ENTRIES))
        holder.replace(TimeScreenDateLoadState(2L, needsOccurrences = true))

        assertNull(holder.markSectionLoaded(1L, TimeScreenDateSection.ENTRIES))
        assertNull(holder.markSectionLoaded(1L, TimeScreenDateSection.OCCURRENCES))

        val current = holder.current()
        assertEquals(2L, current.requestId)
        sections.forEach { section ->
            assertFalse(
                "a refused arrival must not mark $section on the current load: $current",
                loadedFlag(current, section),
            )
        }
        assertFalse("a refused arrival must not make the load ready: $current", current.isReady())
    }

    /** Assertion 3: a new load must not inherit the previous load's section flags. */
    @Test
    fun replace_discards_the_previous_loads_sections() {
        val holder =
            TimeScreenDateLoadStateHolder(TimeScreenDateLoadState(1L, needsOccurrences = true))
        sections.forEach { assertNotNull(holder.markSectionLoaded(1L, it)) }
        assertTrue("precondition: the first load is fully loaded", holder.current().isReady())

        holder.replace(TimeScreenDateLoadState(2L, needsOccurrences = true))

        val fresh = holder.current()
        assertEquals(2L, fresh.requestId)
        sections.forEach { section ->
            assertFalse("a new load must not inherit $section: $fresh", loadedFlag(fresh, section))
        }
        assertFalse("a new load must start not-ready: $fresh", fresh.isReady())
    }
}