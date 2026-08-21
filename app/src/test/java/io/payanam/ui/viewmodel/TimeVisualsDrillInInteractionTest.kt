//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * TimeVisualsDrillInInteractionTest.
 */
class TimeVisualsDrillInInteractionTest {
    private lateinit var viewModel: TimeVisualsViewModel

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
        viewModel = TimeVisualsViewModel(
            timeEntryRepository = mock<TimeEntryRepository>(),
            taskRepository = mock<TaskRepository>(),
            dayPlanRepository = mock<DayPlanRepository>(),
            taskOccurrenceRepository = mock<TaskOccurrenceRepository>(),
        )
    }

    @Test
    /**
     * Toggle dimension filter behaves as drill in toggle.
     */
    fun toggleDimensionFilter_behaves_as_drill_in_toggle() {
        viewModel.toggleDimensionFilter("dim_learning")
        assertEquals("dim_learning", viewModel.uiState.value.selectedDimensionFilterId)

        viewModel.toggleDimensionFilter("dim_learning")
        assertEquals(null, viewModel.uiState.value.selectedDimensionFilterId)

        viewModel.toggleDimensionFilter("dim_financial")
        assertEquals("dim_financial", viewModel.uiState.value.selectedDimensionFilterId)
    }
}
