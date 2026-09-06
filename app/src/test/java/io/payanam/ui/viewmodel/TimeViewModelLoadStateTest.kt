//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TimeViewModelLoadStateTest {
    private val logger: UnifiedLogger by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Before
    fun setUp() {
        logger.i("TimeViewModelLoadStateTest.setUp", "Preparing TimeViewModel selected-date load state tests")
    }

    @Test
    /**
     * Is time screen date content ready false until all required sections are loaded.
     */
    fun isTimeScreenDateContentReady_false_until_all_required_sections_are_loaded() {
        val isReady = isTimeScreenDateContentReady(
            entriesLoaded = true,
            plannedTasksLoaded = false,
            occurrencesLoaded = true,
            needsOccurrences = true,
        )
        assertFalse(isReady)
    }

    @Test
    /**
     * Is time screen date content ready true when entries planned tasks and occurrences are loaded.
     */
    fun isTimeScreenDateContentReady_true_when_entries_planned_tasks_and_occurrences_are_loaded() {
        val isReady = isTimeScreenDateContentReady(
            entriesLoaded = true,
            plannedTasksLoaded = true,
            occurrencesLoaded = true,
            needsOccurrences = true,
        )
        assertTrue(isReady)
    }

    @Test
    /**
     * Is time screen date content ready true without occurrences when minimal mode contract applies.
     */
    fun isTimeScreenDateContentReady_true_without_occurrences_when_minimal_mode_contract_applies() {
        val isReady = isTimeScreenDateContentReady(
            entriesLoaded = true,
            plannedTasksLoaded = true,
            occurrencesLoaded = false,
            needsOccurrences = false,
        )
        assertTrue(isReady)
    }

    @Test
    fun shouldUseTodaysPlannedTasks_true_only_for_today() {
        val today = LocalDate.of(2026, 4, 9)
        assertTrue(shouldUseTodaysPlannedTasks(today, today))
        assertEquals(false, shouldUseTodaysPlannedTasks(today.minusDays(1), today))
        assertEquals(false, shouldUseTodaysPlannedTasks(today.plusDays(1), today))
    }
}
