//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DayJournalEntry
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.JournalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
/**
 * DayViewModelJournalDebounceTest.
 */
class DayViewModelJournalDebounceTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var journalRepository: JournalRepository

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }

        journalRepository = mock()

        runBlocking {
            whenever(journalRepository.getEntryByDate(any())).thenReturn(null)
            whenever(journalRepository.getResponsesByEntryId(any())).thenReturn(emptyList())
        }
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    /**
     * Update overall response debounces writes and persists latest value.
     */
    fun updateOverallResponse_debounces_writes_and_persists_latest_value() = runTest {
        val viewModel = DayViewModel(
            journalRepository = journalRepository,
        )
        advanceUntilIdle()
        val sourceDate = viewModel.uiState.value.selectedDate

        viewModel.updateOverallResponse(sourceDate = sourceDate, promptKey = "gratitude", response = "first")
        viewModel.updateOverallResponse(sourceDate = sourceDate, promptKey = "gratitude", response = "second")
        advanceTimeBy(499)
        runCurrent()
        verify(journalRepository, times(0)).upsertResponse(any())
        advanceTimeBy(1)
        advanceUntilIdle()
        val responseCaptor = argumentCaptor<DayJournalResponse>()
        verify(journalRepository, times(1)).upsertResponse(responseCaptor.capture())
        val savedResponse = responseCaptor.firstValue
        assertEquals("gratitude", savedResponse.promptKey)
        assertEquals("second", savedResponse.responseText)
        assertEquals("overall", savedResponse.scope)
        assertNull(savedResponse.dimensionKey)
    }

    @Test
    /**
     * Update overall response independent prompts keep separate saves.
     */
    fun updateOverallResponse_independent_prompts_keep_separate_saves() = runTest {
        val viewModel = DayViewModel(
            journalRepository = journalRepository,
        )
        advanceUntilIdle()
        val sourceDate = viewModel.uiState.value.selectedDate

        viewModel.updateOverallResponse(sourceDate = sourceDate, promptKey = "gratitude", response = "a")
        viewModel.updateOverallResponse(sourceDate = sourceDate, promptKey = "accomplishment", response = "b")
        advanceTimeBy(500)
        advanceUntilIdle()
        val responseCaptor = argumentCaptor<DayJournalResponse>()
        verify(journalRepository, times(2)).upsertResponse(responseCaptor.capture())
        assertEquals(
            setOf("gratitude", "accomplishment"),
            responseCaptor.allValues.map { it.promptKey }.toSet(),
        )
    }

    @Test
    /**
     * Update dimension response persists canonical dimension id.
     */
    fun updateDimensionResponse_persists_canonical_dimension_id() = runTest {
        val viewModel = DayViewModel(
            journalRepository = journalRepository,
        )
        advanceUntilIdle()
        val sourceDate = viewModel.uiState.value.selectedDate

        viewModel.updateDimensionResponse(
            sourceDate = sourceDate,
            dimensionId = DimensionTaxonomyCatalog.MENTAL_HEALTH.id,
            promptKey = "feeling",
            response = "steady",
        )
        advanceTimeBy(500)
        advanceUntilIdle()
        val responseCaptor = argumentCaptor<DayJournalResponse>()
        verify(journalRepository).upsertResponse(responseCaptor.capture())
        assertEquals("dimension", responseCaptor.firstValue.scope)
        assertEquals(DimensionTaxonomyCatalog.MENTAL_HEALTH.id, responseCaptor.firstValue.dimensionKey)
        assertEquals("steady", responseCaptor.firstValue.responseText)
    }

    @Test
    /**
     * Update overall response keeps original source date after navigation.
     */
    fun updateOverallResponse_keeps_original_source_date_after_navigation() = runTest {
        val sourceDate = LocalDate.now().minusDays(1)
        val futureSelectedDate = LocalDate.now()
        whenever(journalRepository.getEntryByDate(sourceDate.toString())).thenReturn(
            DayJournalEntry(
                id = "entry-$sourceDate",
                entryDate = sourceDate.toString(),
                createdAt = "2026-03-10T00:00:00",
                updatedAt = "2026-03-10T00:00:00",
            ),
        )
        val viewModel = DayViewModel(
            journalRepository = journalRepository,
        )
        advanceUntilIdle()

        viewModel.selectDate(sourceDate)
        advanceUntilIdle()
        viewModel.updateOverallResponse(sourceDate = sourceDate, promptKey = "gratitude", response = "kept-on-10th")
        viewModel.selectDate(futureSelectedDate)
        advanceUntilIdle()
        advanceTimeBy(500)
        advanceUntilIdle()
        verify(journalRepository, atLeastOnce()).getEntryByDate(sourceDate.toString())
        verify(journalRepository, never()).getEntryByDate(LocalDate.now().plusDays(1).toString())
        val responseCaptor = argumentCaptor<DayJournalResponse>()
        verify(journalRepository).upsertResponse(responseCaptor.capture())
        assertEquals("kept-on-10th", responseCaptor.firstValue.responseText)
        assertEquals(sourceDate, viewModel.uiState.value.lastSavedJournalDate)
    }

    @Test
    /**
     * Next day blocks navigation beyond today.
     */
    fun nextDay_blocks_navigation_beyond_today() = runTest {
        val viewModel = DayViewModel(
            journalRepository = journalRepository,
        )
        advanceUntilIdle()
        val startingDate = viewModel.uiState.value.selectedDate
        assertEquals(LocalDate.now(), startingDate)
        assertFalse(startingDate.isBefore(LocalDate.now()))

        viewModel.nextDay()
        advanceUntilIdle()
        assertEquals(startingDate, viewModel.uiState.value.selectedDate)
    }
}
