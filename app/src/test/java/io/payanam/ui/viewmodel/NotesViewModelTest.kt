//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import io.payanam.domain.model.Tag
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
/**
 * NotesViewModelTest.
 */
class NotesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var noteRepository: FakeNoteRepository
    private lateinit var tagRepository: FakeTagRepository

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
        Dispatchers.setMain(dispatcher)
        noteRepository = FakeNoteRepository()
        tagRepository = FakeTagRepository()
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
     * Set dimension filter uses dimension ids for filtering.
     */
    fun setDimensionFilter_uses_dimensionIds_for_filtering() = runTest {
        /** Now. */
        val now = LocalDateTime.of(2026, 3, 15, 10, 0)
        noteRepository.notes.value = listOf(
            /** Note. */
            Note(
                id = "n1",
                title = "Learn Kotlin",
                lifeIntentionCategory = "Learning & Growth",
                dimensionId = "dim_learning_growth",
                createdAt = now,
                updatedAt = now,
            ),
            /** Note. */
            Note(
                id = "n2",
                title = "Volunteer",
                lifeIntentionCategory = "Community & Service",
                dimensionId = "dim_community_service",
                createdAt = now,
                updatedAt = now,
            ),
        )

        /** View model. */
        val viewModel = NotesViewModel(noteRepository, tagRepository)
        /** Advance until idle. */
        advanceUntilIdle()

        viewModel.setDimensionFilter("dim_learning_growth")
        /** Advance until idle. */
        advanceUntilIdle()

        /** State. */
        val state = viewModel.uiState.value
        /** Assert equals. */
        assertEquals("dim_learning_growth", state.selectedDimensionId)
        /** Assert equals. */
        assertEquals(listOf("n1"), state.filteredNotes.map { it.id })
    }

    @Test
    /**
     * Create note preserves selected dimension id and label.
     */
    fun createNote_preserves_selected_dimension_id_and_label() = runTest {
        /** View model. */
        val viewModel = NotesViewModel(noteRepository, tagRepository)
        /** Advance until idle. */
        advanceUntilIdle()

        viewModel.createNote(
            title = "Journal idea",
            details = "Details",
            dimensionId = "dim_mental_health",
            dimensionLabel = "Mental Health",
            tags = listOf("tag1"),
        )
        /** Advance until idle. */
        advanceUntilIdle()

        /** Assert equals. */
        assertEquals(
            /** Note input. */
            NoteInput(
                title = "Journal idea",
                details = "Details",
                lifeIntentionCategory = "Mental Health",
                dimensionId = "dim_mental_health",
            ),
            noteRepository.lastCreateInput,
        )
        /** Assert equals. */
        assertEquals(listOf("tag1"), tagRepository.noteTags["created-note"])
    }

    private class FakeNoteRepository : NoteRepository {
        /** Notes. */
        val notes = MutableStateFlow<List<Note>>(emptyList())
        /** Last create input. */
        var lastCreateInput: NoteInput? = null

        override fun getAllNotes(): Flow<List<Note>> = notes

        override fun getNotesByDimension(dimension: String): Flow<List<Note>> = flowOf(emptyList())

        override fun getNotesForDate(date: LocalDate): Flow<List<Note>> = flowOf(emptyList())

        override suspend fun getNoteById(id: String): Note? = notes.value.firstOrNull { it.id == id }

        override suspend fun createNote(input: NoteInput): Note {
            lastCreateInput = input
            return Note(
                id = "created-note",
                title = input.title,
                details = input.details,
                lifeIntentionCategory = input.lifeIntentionCategory,
                dimensionId = input.dimensionId,
                createdAt = LocalDateTime.of(2026, 3, 15, 12, 0),
                updatedAt = LocalDateTime.of(2026, 3, 15, 12, 0),
            )
        }

        override suspend fun updateNote(id: String, input: NoteInput): Note = createNote(input).copy(id = id)

        override suspend fun deleteNote(id: String) = Unit
    }

    private class FakeTagRepository : TagRepository {
        /** Note tags. */
        val noteTags = mutableMapOf<String, List<String>>()

        override fun observeAllTags(): Flow<List<Tag>> = flowOf(emptyList())

        override fun searchTagsByPrefix(query: String, limit: Int): Flow<List<Tag>> = flowOf(emptyList())

        override fun observeTagsForTask(taskId: String): Flow<List<Tag>> = flowOf(emptyList())

        override fun observeTagsForNote(noteId: String): Flow<List<Tag>> = flowOf(emptyList())

        override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> =
            noteIds.associateWith { noteTags[it].orEmpty() }

        override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> = flowOf(emptyList())

        override suspend fun replaceTaskTags(taskId: String, tagNames: List<String>) = Unit

        override suspend fun replaceNoteTags(noteId: String, tagNames: List<String>) {
            noteTags[noteId] = tagNames
        }

        override suspend fun replaceTimeEntryTags(timeEntryId: String, tagNames: List<String>) = Unit
    }
}
