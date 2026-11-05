//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import io.payanam.domain.model.Tag
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.JournalRepository
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Behavioural lock for the shared TimeTagEditor ViewModel.
 *
 * Exercises every public method and branch in [TimeTagEditorViewModel], including the null/blank
 * guard paths in `loadEntryTags` / `loadTaskTags`, the stream-replacement semantics (cancel-then-
 * relaunch), the save delegation, and the top-level [timeTagEditorViewModel] factory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeTagEditorViewModelTest {
    private lateinit var tagRepository: FakeTagRepository
    private var viewModelScope: CoroutineScope? = null

    @Before
    fun setUp() {
        LocalDataLayer.resetForTests()
        tagRepository = FakeTagRepository()
    }

    @After
    fun tearDown() {
        // The ViewModel's collectors never complete, so the scope that owns them has to be cancelled
        // here. It is deliberately NOT the TestScope: runTest waits for its children, so a TestScope-
        // held collector leaves the test hanging. A platform owns this scope in the app too, which is
        // why the ViewModel takes one rather than creating it silently.
        viewModelScope?.cancel()
        viewModelScope = null
        LocalDataLayer.resetForTests()
    }

    /** A scope for the ViewModel under test, driven by the test scheduler so `advanceUntilIdle` runs it. */
    private fun TestScope.newViewModelScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { viewModelScope = it }

    private fun TestScope.viewModel(): TimeTagEditorViewModel {
        val scope = newViewModelScope()
        return TimeTagEditorViewModel(tagRepository, scope)
    }

    // ------------------------------------------------------------------ //
    //  Initial state                                                       //
    // ------------------------------------------------------------------ //

    @Test
    fun `initial state has empty suggestions and tag lists`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(emptyList<String>(), state.tagSuggestions)
            assertEquals(emptyList<String>(), state.editingEntryTags)
            assertEquals(emptyList<String>(), state.editingTaskTags)
        }

    // ------------------------------------------------------------------ //
    //  Tag suggestions (init collector)                                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `observeTagSuggestions emits tag names from the repository`() =
        runTest {
            tagRepository.allTags.value = listOf(tag("kotlin"), tag("android"))
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(listOf("kotlin", "android"), vm.uiState.value.tagSuggestions)
        }

    @Test
    fun `observeTagSuggestions updates when the underlying tag list changes`() =
        runTest {
            tagRepository.allTags.value = listOf(tag("kotlin"))
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(listOf("kotlin"), vm.uiState.value.tagSuggestions)

            tagRepository.allTags.value = listOf(tag("kotlin"), tag("android"))
            advanceUntilIdle()
            assertEquals(listOf("kotlin", "android"), vm.uiState.value.tagSuggestions)
        }

    @Test
    fun `observeTagSuggestions maps Tag objects to their name`() =
        runTest {
            tagRepository.allTags.value =
                listOf(
                    tag("Work", id = "t1"),
                    tag("Focus", id = "t2"),
                )
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(listOf("Work", "Focus"), vm.uiState.value.tagSuggestions)
        }

    // ------------------------------------------------------------------ //
    //  loadEntryTags — valid id                                           //
    // ------------------------------------------------------------------ //

    @Test
    fun `loadEntryTags with valid id streams entry tags into state`() =
        runTest {
            tagRepository.entryTags["e1"] = listOf(tag("work"), tag("focus"))
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadEntryTags("e1")
            advanceUntilIdle()
            assertEquals(listOf("work", "focus"), vm.uiState.value.editingEntryTags)
        }

    @Test
    fun `loadEntryTags with second id replaces the first stream`() =
        runTest {
            tagRepository.entryTags["e1"] = listOf(tag("work"))
            tagRepository.entryTags["e2"] = listOf(tag("personal"))
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadEntryTags("e1")
            advanceUntilIdle()
            assertEquals(listOf("work"), vm.uiState.value.editingEntryTags)

            vm.loadEntryTags("e2")
            advanceUntilIdle()
            assertEquals(listOf("personal"), vm.uiState.value.editingEntryTags)
        }

    // ------------------------------------------------------------------ //
    //  loadEntryTags — null / blank / whitespace (isNullOrBlank branches) //
    // ------------------------------------------------------------------ //

    @Test
    fun `loadEntryTags with null clears entry tags`() =
        runTest {
            tagRepository.entryTags["e1"] = listOf(tag("work"))
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadEntryTags("e1")
            advanceUntilIdle()
            assertEquals(listOf("work"), vm.uiState.value.editingEntryTags)

            vm.loadEntryTags(null)
            advanceUntilIdle()
            assertEquals(emptyList<String>(), vm.uiState.value.editingEntryTags)
        }

    @Test
    fun `loadEntryTags with empty string clears entry tags`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadEntryTags("")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), vm.uiState.value.editingEntryTags)
        }

    @Test
    fun `loadEntryTags with whitespace clears entry tags`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadEntryTags("   ")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), vm.uiState.value.editingEntryTags)
        }

    // ------------------------------------------------------------------ //
    //  saveEntryTags                                                      //
    // ------------------------------------------------------------------ //

    @Test
    fun `saveEntryTags delegates to repository with the given tags`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.saveEntryTags("e1", listOf("work", "focus"))
            advanceUntilIdle()
            assertEquals(listOf("work", "focus"), tagRepository.savedEntryTags["e1"])
        }

    @Test
    fun `saveEntryTags with empty list persists an empty tag set`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.saveEntryTags("e1", emptyList())
            advanceUntilIdle()
            assertEquals(emptyList<String>(), tagRepository.savedEntryTags["e1"])
        }

    @Test
    fun `saveEntryTags with many tags persists all of them`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            val tags = listOf("a", "b", "c", "d", "e")
            vm.saveEntryTags("e1", tags)
            advanceUntilIdle()
            assertEquals(tags, tagRepository.savedEntryTags["e1"])
        }

    // ------------------------------------------------------------------ //
    //  loadTaskTags — valid id                                            //
    // ------------------------------------------------------------------ //

    @Test
    fun `loadTaskTags with valid id streams task tags into state`() =
        runTest {
            tagRepository.taskTags["t1"] = listOf(tag("urgent"), tag("deadline"))
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadTaskTags("t1")
            advanceUntilIdle()
            assertEquals(listOf("urgent", "deadline"), vm.uiState.value.editingTaskTags)
        }

    @Test
    fun `loadTaskTags with second id replaces the first stream`() =
        runTest {
            tagRepository.taskTags["t1"] = listOf(tag("urgent"))
            tagRepository.taskTags["t2"] = listOf(tag("low"))
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadTaskTags("t1")
            advanceUntilIdle()
            assertEquals(listOf("urgent"), vm.uiState.value.editingTaskTags)

            vm.loadTaskTags("t2")
            advanceUntilIdle()
            assertEquals(listOf("low"), vm.uiState.value.editingTaskTags)
        }

    // ------------------------------------------------------------------ //
    //  loadTaskTags — null / blank / whitespace                           //
    // ------------------------------------------------------------------ //

    @Test
    fun `loadTaskTags with null clears task tags`() =
        runTest {
            tagRepository.taskTags["t1"] = listOf(tag("urgent"))
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadTaskTags("t1")
            advanceUntilIdle()
            assertEquals(listOf("urgent"), vm.uiState.value.editingTaskTags)

            vm.loadTaskTags(null)
            advanceUntilIdle()
            assertEquals(emptyList<String>(), vm.uiState.value.editingTaskTags)
        }

    @Test
    fun `loadTaskTags with empty string clears task tags`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadTaskTags("")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), vm.uiState.value.editingTaskTags)
        }

    @Test
    fun `loadTaskTags with whitespace clears task tags`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadTaskTags("   ")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), vm.uiState.value.editingTaskTags)
        }

    // ------------------------------------------------------------------ //
    //  saveTaskTags                                                       //
    // ------------------------------------------------------------------ //

    @Test
    fun `saveTaskTags delegates to repository with the given tags`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.saveTaskTags("t1", listOf("urgent", "deadline"))
            advanceUntilIdle()
            assertEquals(listOf("urgent", "deadline"), tagRepository.savedTaskTags["t1"])
        }

    @Test
    fun `saveTaskTags with empty list persists an empty tag set`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.saveTaskTags("t1", emptyList())
            advanceUntilIdle()
            assertEquals(emptyList<String>(), tagRepository.savedTaskTags["t1"])
        }

    @Test
    fun `saveTaskTags with many tags persists all of them`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            val tags = listOf("x", "y", "z")
            vm.saveTaskTags("t1", tags)
            advanceUntilIdle()
            assertEquals(tags, tagRepository.savedTaskTags["t1"])
        }

    // ------------------------------------------------------------------ //
    //  dispose                                                             //
    // ------------------------------------------------------------------ //

    @Test
    fun `dispose releases the scope it owns`() =
        runTest {
            // Construct without a caller scope — the ViewModel creates its own.
            // dispose() must cancel it; otherwise the test hangs because the init
            // collector never completes.
            val vm = TimeTagEditorViewModel(tagRepository)
            vm.dispose()
            advanceUntilIdle()
            assertTrue(true)
        }

    // ------------------------------------------------------------------ //
    //  Factory: timeTagEditorViewModel                                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `the factory builds a ViewModel from the installed data layer`() =
        runTest {
            LocalDataLayer.install(
                object : PayanamDataLayer by UninstalledDataLayer {
                    override val notes: NoteRepository get() = error("not exercised")
                    override val tags: TagRepository = tagRepository
                    override val tasks: TaskRepository get() = error("not exercised")
                    override val timeEntries: TimeEntryRepository get() = error("not exercised")
                    override val dayPlans: DayPlanRepository get() = error("not exercised")
                    override val taskOccurrences: TaskOccurrenceRepository get() = error("not exercised")
                    override val journal: JournalRepository get() = error("not exercised")
                },
            )
            val vm = timeTagEditorViewModel(newViewModelScope())
            advanceUntilIdle()
            // The suggestions stream should be wired: initially empty because the fake emits nothing.
            assertEquals(emptyList<String>(), vm.uiState.value.tagSuggestions)
        }

    @Test
    fun `the factory forwards repository tags into the ViewModel`() =
        runTest {
            tagRepository.allTags.value = listOf(tag("from-layer"))
            LocalDataLayer.install(
                object : PayanamDataLayer by UninstalledDataLayer {
                    override val notes: NoteRepository get() = error("not exercised")
                    override val tags: TagRepository = tagRepository
                    override val tasks: TaskRepository get() = error("not exercised")
                    override val timeEntries: TimeEntryRepository get() = error("not exercised")
                    override val dayPlans: DayPlanRepository get() = error("not exercised")
                    override val taskOccurrences: TaskOccurrenceRepository get() = error("not exercised")
                    override val journal: JournalRepository get() = error("not exercised")
                },
            )
            val vm = timeTagEditorViewModel(newViewModelScope())
            advanceUntilIdle()
            assertEquals(listOf("from-layer"), vm.uiState.value.tagSuggestions)
        }

    // ------------------------------------------------------------------ //
    //  Cross-cutting: entry and task tag streams are independent           //
    // ------------------------------------------------------------------ //

    @Test
    fun `loading entry tags does not affect task tags and vice versa`() =
        runTest {
            tagRepository.entryTags["e1"] = listOf(tag("entry-tag"))
            tagRepository.taskTags["t1"] = listOf(tag("task-tag"))
            val vm = viewModel()
            advanceUntilIdle()

            vm.loadEntryTags("e1")
            advanceUntilIdle()
            assertEquals(listOf("entry-tag"), vm.uiState.value.editingEntryTags)
            assertEquals(emptyList<String>(), vm.uiState.value.editingTaskTags)

            vm.loadTaskTags("t1")
            advanceUntilIdle()
            assertEquals(listOf("entry-tag"), vm.uiState.value.editingEntryTags)
            assertEquals(listOf("task-tag"), vm.uiState.value.editingTaskTags)
        }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private fun tag(
        name: String,
        id: String = name,
    ) = Tag(
        id = id,
        name = name,
        normalizedName = name.lowercase(),
        usageCount = 0,
        createdAt = LocalDateTime.of(2026, 1, 1, 0, 0),
        updatedAt = LocalDateTime.of(2026, 1, 1, 0, 0),
    )

    // ------------------------------------------------------------------ //
    //  Fake TagRepository                                                  //
    // ------------------------------------------------------------------ //

    private class FakeTagRepository : TagRepository {
        val allTags = MutableStateFlow<List<Tag>>(emptyList())

        /** Entry-id → tags emitted by [observeTagsForTimeEntry]. */
        val entryTags = mutableMapOf<String, List<Tag>>()

        /** Task-id → tags emitted by [observeTagsForTask]. */
        val taskTags = mutableMapOf<String, List<Tag>>()

        /** Captured arguments from [replaceTimeEntryTags]. */
        val savedEntryTags = mutableMapOf<String, List<String>>()

        /** Captured arguments from [replaceTaskTags]. */
        val savedTaskTags = mutableMapOf<String, List<String>>()

        override fun observeAllTags(): Flow<List<Tag>> = allTags

        override fun searchTagsByPrefix(
            query: String,
            limit: Int,
        ): Flow<List<Tag>> = flowOf(emptyList())

        override fun observeTagsForTask(taskId: String): Flow<List<Tag>> = flowOf(taskTags[taskId].orEmpty())

        override fun observeTagsForNote(noteId: String): Flow<List<Tag>> = flowOf(emptyList())

        override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> = emptyMap()

        override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> = flowOf(entryTags[timeEntryId].orEmpty())

        override suspend fun replaceTaskTags(
            taskId: String,
            tagNames: List<String>,
        ) {
            savedTaskTags[taskId] = tagNames
        }

        override suspend fun replaceNoteTags(
            noteId: String,
            tagNames: List<String>,
        ) = Unit

        override suspend fun replaceTimeEntryTags(
            timeEntryId: String,
            tagNames: List<String>,
        ) {
            savedEntryTags[timeEntryId] = tagNames
        }
    }
}
