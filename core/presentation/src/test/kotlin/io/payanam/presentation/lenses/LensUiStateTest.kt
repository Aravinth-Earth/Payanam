//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * The Lenses vocabulary both platforms render from — the mode/window algebra and the chart states.
 *
 * Two properties are worth pinning rather than just the field lists. First, [windowsForMode] is the only
 * source of which windows a mode may show, so a window that appears under the wrong mode is a lens that can
 * page into a range its own mode cannot resolve; the past and future sets are asserted to be disjoint and to
 * exclude TODAY. Second, the span of each paging window is what the loader uses to size a range, so the two
 * span enums are asserted to agree with their own names — a `W30(7)` typo would silently load a week.
 */
class LensUiStateTest {
    @Test
    fun `today's mode exposes only today`() {
        assertThat(windowsForMode(LensTimeMode.TODAY)).containsExactly(LensTimeWindow.TODAY)
    }

    @Test
    fun `past and future windows are disjoint and never contain today`() {
        val past = windowsForMode(LensTimeMode.PAST)
        val future = windowsForMode(LensTimeMode.FUTURE)

        assertThat(past).isNotEmpty()
        assertThat(future).isNotEmpty()
        assertThat(past.intersect(future.toSet())).isEmpty()
        assertThat(past).doesNotContain(LensTimeWindow.TODAY)
        assertThat(future).doesNotContain(LensTimeWindow.TODAY)
    }

    @Test
    fun `every window is reachable from exactly one mode`() {
        val all = windowsForMode(LensTimeMode.TODAY) + windowsForMode(LensTimeMode.PAST) + windowsForMode(LensTimeMode.FUTURE)

        assertThat(all).containsNoDuplicates()
        assertThat(all).containsExactlyElementsIn(LensTimeWindow.entries)
    }

    @Test
    fun `dimension split windows carry their own span, and ALL is unbounded`() {
        assertThat(DimensionSplitWindow.W1.spanDays).isEqualTo(1)
        assertThat(DimensionSplitWindow.W7.spanDays).isEqualTo(7)
        assertThat(DimensionSplitWindow.W30.spanDays).isEqualTo(30)
        assertThat(DimensionSplitWindow.W90.spanDays).isEqualTo(90)
        assertThat(DimensionSplitWindow.W180.spanDays).isEqualTo(180)
        assertThat(DimensionSplitWindow.W365.spanDays).isEqualTo(365)
        assertThat(DimensionSplitWindow.ALL.spanDays).isNull()
    }

    @Test
    fun `dimension trend windows carry their own span`() {
        assertThat(DimensionTrendWindow.entries.map { it.spanDays }).containsExactly(1, 7, 30, 90, 180, 365).inOrder()
    }

    @Test
    fun `a fresh split state is the first window with nothing loaded`() {
        val state = DimensionSplitState()

        assertThat(state.window).isEqualTo(DimensionSplitWindow.W1)
        assertThat(state.windowOffset).isEqualTo(0)
        assertThat(state.byDimension).isEmpty()
        assertThat(state.totalMinutes).isEqualTo(0)
        assertThat(state.isClamped).isFalse()
        assertThat(state.canShiftLeft).isFalse()
        assertThat(state.canShiftRight).isFalse()
        assertThat(state.isLoading).isFalse()
        assertThat(state.firstEntryDate).isNull()
    }

    @Test
    fun `a fresh lens state is loading with no range selected`() {
        val state = LensUiState()

        assertThat(state.isLoading).isTrue()
        assertThat(state.selectedRangeSummary).isNull()
    }

    @Test
    fun `the lens moments are the three phases of a day`() {
        assertThat(LensMoment.entries.map { it.name }).containsExactly("START_DAY", "LIVE_DAY", "CLOSE_DAY").inOrder()
    }

    @Test
    fun `a trend point carries the day key and both sides of plan versus reality`() {
        val point = LensTrendPoint(dayKey = "2026-09-20", plannedMinutes = 480, actualMinutes = 300)

        assertThat(point.dayKey).isEqualTo("2026-09-20")
        assertThat(point.plannedMinutes).isEqualTo(480)
        assertThat(point.actualMinutes).isEqualTo(300)
    }

    @Test
    fun `a resolved window range round-trips its mode, window and page`() {
        val start = LocalDate.of(2026, 9, 14)
        val range = ResolvedLensWindowRange(LensTimeMode.PAST, LensTimeWindow.LAST_7_DAYS, 1, start, start.plusDays(6))

        assertThat(range.mode).isEqualTo(LensTimeMode.PAST)
        assertThat(range.window).isEqualTo(LensTimeWindow.LAST_7_DAYS)
        assertThat(range.pageIndex).isEqualTo(1)
        assertThat(range.startDate).isEqualTo(start)
        assertThat(range.endDate).isEqualTo(start.plusDays(6))
    }

    @Test
    fun `paging availability is per direction`() {
        assertThat(LensWindowNavigationState(canGoPrevious = true, canGoNext = false).canGoPrevious).isTrue()
        assertThat(LensWindowNavigationState(canGoPrevious = false, canGoNext = true).canGoNext).isTrue()
    }
}
