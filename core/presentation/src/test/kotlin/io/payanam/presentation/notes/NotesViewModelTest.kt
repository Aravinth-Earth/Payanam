//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.notes

import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import io.payanam.domain.model.Tag
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Behavioural lock for the shared Notes ViewModel.
 *
 * Moved out of `:app` with the ViewModel itself, and converted: the original ran under Robolectric purely
 * to boot the Android logger singleton, which this module does not have. Here the logger is [AppLogger]'s
 * default no-op, so the test runs on a plain JVM - which is also the proof that the ViewModel no longer
 * needs a platform to exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    private lateinit var noteRepository: FakeNoteRepository
    private lateinit var tagRepository: FakeTagRepository
    private var viewModelScope: CoroutineScope? = null

    @Before
    fun setUp() {
        LocalDataLayer.resetForTests()
        noteRepository = FakeNoteRepository()
        tagRepository = FakeTagRepository()
    }

    @After
    fun tearDown() {
        // The ViewModel's collectors never complete, so the scope that owns them has to be cancelled here.
        // It is deliberately NOT the TestScope: runTest waits for its children, so a TestScope-held
        // collector leaves the test hanging. A platform owns this scope in the app too, which is why the
        // ViewModel takes one rather than creating it silently.
        viewModelScope?.cancel()
        viewModelScope = null
        LocalDataLayer.resetForTests()
    }

    /** A scope for the ViewModel under test, driven by the test scheduler so `advanceUntilIdle` runs it. */
    private fun TestScope.newViewModelScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { viewModelScope = it }

    private fun TestScope.viewModel(): NotesViewModel {
        val scope = newViewModelScope()
        return NotesViewModel(noteRepository, tagRepository, scope)
    }

    @Test
    fun `setDimensionFilter uses dimensionIds for filtering`() =
        runTest {
            val now = LocalDateTime.of(2026, 3, 15, 10, 0)
            noteRepository.notes.value =
                listOf(
                    note(id = "n1", title = "Learn Kotlin", dimensionId = "dim_learning_growth", now = now),
                    note(id = "n2", title = "Volunteer", dimensionId = "dim_community_service", now = now),
                )
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.setDimensionFilter("dim_learning_growth")
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals("dim_learning_growth", state.selectedDimensionId)
            assertEquals(listOf("n1"), state.filteredNotes.map { it.id })
        }

    @Test
    fun `createNote preserves selected dimension id and label`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.createNote(
                title = "Journal idea",
                details = "Details",
                dimensionId = "dim_mental_health",
                dimensionLabel = "Mental Health",
                tags = listOf("tag1"),
            )
            advanceUntilIdle()
            assertEquals(
                NoteInput(
                    title = "Journal idea",
                    details = "Details",
                    lifeIntentionCategory = "Mental Health",
                    dimensionId = "dim_mental_health",
                ),
                noteRepository.lastCreateInput,
            )
            assertEquals(listOf("tag1"), tagRepository.noteTags["created-note"])
        }

    @Test
    fun `createNote without tags does not touch the tag repository`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.createNote(
                title = "No tags",
                details = null,
                dimensionId = "dim_learning_growth",
                dimensionLabel = "Learning",
                tags = emptyList(),
            )
            advanceUntilIdle()
            assertTrue(tagRepository.noteTags.isEmpty())
        }

    @Test
    fun `search query filters by title and details`() =
        runTest {
            val now = LocalDateTime.of(2026, 3, 15, 10, 0)
            noteRepository.notes.value =
                listOf(
                    note(id = "n1", title = "Kotlin notes", details = null, now = now),
                    note(id = "n2", title = "Other", details = "kotlin in the body", now = now),
                    note(id = "n3", title = "Unrelated", details = null, now = now),
                )
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.updateSearchQuery("kotlin")
            advanceUntilIdle()
            assertEquals(
                listOf("n1", "n2"),
                viewModel.uiState.value.filteredNotes
                    .map { it.id },
            )

            viewModel.updateSearchQuery("")
            advanceUntilIdle()
            assertEquals(3, viewModel.uiState.value.filteredNotes.size)
        }

    @Test
    fun `loading notes publishes tag names for each note and clears loading`() =
        runTest {
            val now = LocalDateTime.of(2026, 3, 15, 10, 0)
            noteRepository.notes.value = listOf(note(id = "n1", title = "T", now = now))
            tagRepository.noteTags["n1"] = listOf("focus", "work")

            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(listOf("focus", "work"), viewModel.uiState.value.noteTagsById["n1"])
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `updateNote writes the edit and replaces tags`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.updateNote(
                noteId = "n9",
                title = "Edited",
                details = null,
                dimensionId = "dim_health",
                dimensionLabel = "Health",
                tags = listOf("a"),
            )
            advanceUntilIdle()
            assertEquals("n9", noteRepository.lastUpdateId)
            assertEquals(listOf("a"), tagRepository.noteTags["n9"])
        }

    @Test
    fun `deleteNote deletes through the repository`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.deleteNote("n5")
            advanceUntilIdle()
            assertEquals(listOf("n5"), noteRepository.deletedIds)
        }

    @Test
    fun `clearError resets the error field`() =
        runTest {
            noteRepository.failReads = true
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals("notes unavailable", viewModel.uiState.value.error)

            viewModel.clearError()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun `a repository failure surfaces as state instead of crashing`() =
        runTest {
            noteRepository.failReads = true
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals("notes unavailable", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `write failures surface as state`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            noteRepository.failWrites = true
            viewModel.createNote("t", null, "d", "L", emptyList())
            viewModel.updateNote("id", "t", null, "d", "L", emptyList())
            viewModel.deleteNote("id")
            advanceUntilIdle()
            assertEquals("write failed", viewModel.uiState.value.error)
        }

    @Test
    fun `dispose releases the scope it owns`() =
        runTest {
            val viewModel = NotesViewModel(noteRepository, tagRepository)
            viewModel.dispose()
            advanceUntilIdle()
            assertTrue(true)
        }

    @Test
    fun `the factory builds a ViewModel from the installed data layer`() =
        runTest {
            LocalDataLayer.install(
                object : PayanamDataLayer by UninstalledDataLayer {
                    override val notes: NoteRepository = noteRepository
                    override val tags: TagRepository = tagRepository
                },
            )
            val viewModel = notesViewModel(newViewModelScope())
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `reading a repository before install fails loudly`() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                UninstalledDataLayer.notes
            }
        assertTrue(failure.message!!.contains("before a data layer was installed"))
    }

    @Test
    fun `every entry of the uninstalled layer fails, and names itself`() {
        // Each entry is asserted separately: covering only `notes` leaves `tags` returning a silent default
        // if it is ever mistyped, and the message must name WHICH repository was read, or a stack trace
        // reports the holder rather than the entry that was missing.
        val tagsFailure =
            assertThrows(IllegalStateException::class.java) {
                UninstalledDataLayer.tags
            }
        assertTrue(tagsFailure.message!!.contains("before a data layer was installed"))
        assertTrue(tagsFailure.message!!.contains("LocalDataLayer.tags"))
    }

    @Test
    fun `LocalDataLayer exposes what was installed and can be reset`() {
        LocalDataLayer.install(
            object : PayanamDataLayer by UninstalledDataLayer {
                override val notes: NoteRepository = noteRepository
                override val tags: TagRepository = tagRepository
            },
        )
        assertEquals(noteRepository, LocalDataLayer.current.notes)
        LocalDataLayer.resetForTests()
        assertEquals(UninstalledDataLayer, LocalDataLayer.current)
    }

    private fun note(
        id: String,
        title: String,
        details: String? = null,
        dimensionId: String = "dim_learning_growth",
        now: LocalDateTime,
    ) = Note(
        id = id,
        title = title,
        details = details,
        lifeIntentionCategory = "Learning & Growth",
        dimensionId = dimensionId,
        createdAt = now,
        updatedAt = now,
    )

    private class FakeNoteRepository : NoteRepository {
        val notes = MutableStateFlow<List<Note>>(emptyList())
        var lastCreateInput: NoteInput? = null
        var lastUpdateId: String? = null
        val deletedIds = mutableListOf<String>()
        var failReads: Boolean = false
        var failWrites: Boolean = false

        override fun getAllNotes(): Flow<List<Note>> =
            if (failReads) {
                flow { throw IOException("notes unavailable") }
            } else {
                notes
            }

        override fun getNotesByDimension(dimension: String): Flow<List<Note>> = flowOf(emptyList())

        override fun getNotesForDate(date: LocalDate): Flow<List<Note>> = flowOf(emptyList())

        override suspend fun getNoteById(id: String): Note? = notes.value.firstOrNull { it.id == id }

        override suspend fun createNote(input: NoteInput): Note {
            if (failWrites) throw IOException("write failed")
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

        override suspend fun updateNote(
            id: String,
            input: NoteInput,
        ): Note {
            if (failWrites) throw IOException("write failed")
            lastUpdateId = id
            return createNote(input).copy(id = id)
        }

        override suspend fun deleteNote(id: String) {
            if (failWrites) throw IOException("write failed")
            deletedIds += id
        }
    }

    private class FakeTagRepository : TagRepository {
        val noteTags = mutableMapOf<String, List<String>>()

        override fun observeAllTags(): Flow<List<Tag>> = flowOf(emptyList())

        override fun searchTagsByPrefix(
            query: String,
            limit: Int,
        ): Flow<List<Tag>> = flowOf(emptyList())

        override fun observeTagsForTask(taskId: String): Flow<List<Tag>> = flowOf(emptyList())

        override fun observeTagsForNote(noteId: String): Flow<List<Tag>> = flowOf(emptyList())

        override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> =
            noteIds.associateWith { noteTags[it].orEmpty() }

        override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> = flowOf(emptyList())

        override suspend fun replaceTaskTags(
            taskId: String,
            tagNames: List<String>,
        ) = Unit

        override suspend fun replaceNoteTags(
            noteId: String,
            tagNames: List<String>,
        ) {
            noteTags[noteId] = tagNames
        }

        override suspend fun replaceTimeEntryTags(
            timeEntryId: String,
            tagNames: List<String>,
        ) = Unit
    }
}
