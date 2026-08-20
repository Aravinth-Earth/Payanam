//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Part C window-bound logic tests (pure function — no Android deps).
 * Covers range switcher semantics and pagination bounds:
 *  - 7d/30d/90d/180d/365d windows end inclusive on the anchor date
 *  - all-time (0) anchors to the fixed 2020-01-01 start
 *  - forward navigation cannot pass today (clamp), back is unbounded
 */
class TaskDetailWindowBoundsTest {

    private val anchor: LocalDate = LocalDate.of(2026, 8, 9)

    @Test
    fun `7d window spans six days before anchor inclusive`() {
        /** Val. */
        val (start, end) = TaskDetailViewModel.windowBounds(7, anchor)
        /** Assert equals. */
        assertEquals(LocalDate.of(2026, 8, 3), start)
        /** Assert equals. */
        assertEquals(anchor, end)
    }

    @Test
    fun `30d window spans 29 days before anchor`() {
        /** Val. */
        val (start, end) = TaskDetailViewModel.windowBounds(30, anchor)
        /** Assert equals. */
        assertEquals(LocalDate.of(2026, 7, 11), start)
        /** Assert equals. */
        assertEquals(anchor, end)
    }

    @Test
    fun `90d window spans 89 days before anchor`() {
        /** Val. */
        val (start, end) = TaskDetailViewModel.windowBounds(90, anchor)
        /** Assert equals. */
        assertEquals(LocalDate.of(2026, 5, 12), start)
        /** Assert equals. */
        assertEquals(anchor, end)
    }

    @Test
    fun `365d window spans 364 days before anchor`() {
        /** Val. */
        val (start, end) = TaskDetailViewModel.windowBounds(365, anchor)
        /** Assert equals. */
        assertEquals(LocalDate.of(2025, 8, 10), start)
        /** Assert equals. */
        assertEquals(anchor, end)
    }

    @Test
    fun `all-time window anchors to 2020-01-01`() {
        /** Val. */
        val (start, end) = TaskDetailViewModel.windowBounds(0, anchor)
        /** Assert equals. */
        assertEquals(LocalDate.of(2020, 1, 1), start)
        /** Assert equals. */
        assertEquals(anchor, end)
    }

    @Test
    fun `back navigation shifts window end by full size`() {
        /** Val. */
        val (_, shiftedEnd) = TaskDetailViewModel.windowBounds(7, anchor.minusDays(7))
        /** Assert equals. */
        assertEquals(LocalDate.of(2026, 8, 2), shiftedEnd)
    }

    @Test
    fun `window on year boundary stays valid`() {
        /** Year end. */
        val yearEnd = LocalDate.of(2026, 1, 1)
        /** Val. */
        val (start, end) = TaskDetailViewModel.windowBounds(7, yearEnd)
        /** Assert equals. */
        assertEquals(LocalDate.of(2025, 12, 26), start)
        /** Assert equals. */
        assertEquals(yearEnd, end)
    }
}
