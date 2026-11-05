//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.day

import io.payanam.domain.model.DayJournalEntry
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DayJournalResponseInput
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.JournalPromptScope
import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import io.payanam.domain.model.Tag
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.DayPlanAllocationRecord
import io.payanam.domain.repository.DayPlanPolicyRecord
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.domain.repository.DayTypeTemplatePreferenceRecord
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Behavioural lock for the shared Day ViewModel — date navigation, journal
 * response updates, debounce persistence, and the `dayViewModel()` factory.
 *
 * Moved out of `:app` with the ViewModel itself: the original ran under
 * Robolectric + Mockito; this runs on a plain JVM with a fake
 * [JournalRepository] and the injected test-scope pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModelTest {
    private lateinit var fakeJournal: FakeJournalRepository
    private var viewModelScope: CoroutineScope? = null

    @Before
    fun setUp() {
        LocalDataLayer.resetForTests()
        fakeJournal = FakeJournalRepository()
    }

    @After
    fun tearDown() {
        viewModelScope?.cancel()
        viewModelScope = null
        LocalDataLayer.resetForTests()
    }

    /** A scope for the ViewModel under test, driven by the test scheduler. */
    private fun TestScope.newViewModelScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { viewModelScope = it }

    private fun TestScope.viewModel(): DayViewModel {
        val scope = newViewModelScope()
        return DayViewModel(fakeJournal, scope)
    }

    // ── Date Navigation ──────────────────────────────────────────────

    @Test
    fun `initial state is today with loading complete`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(LocalDate.now(), vm.uiState.value.selectedDate)
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `previousDay moves selection back one day`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val today = vm.uiState.value.selectedDate
            vm.previousDay()
            advanceUntilIdle()
            assertEquals(today.minusDays(1), vm.uiState.value.selectedDate)
        }

    @Test
    fun `nextDay moves forward when before today`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.previousDay()
            advanceUntilIdle()
            val yesterday = vm.uiState.value.selectedDate
            vm.nextDay()
            advanceUntilIdle()
            assertEquals(yesterday.plusDays(1), vm.uiState.value.selectedDate)
        }

    @Test
    fun `nextDay blocks navigation beyond today`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val today = vm.uiState.value.selectedDate
            vm.nextDay()
            advanceUntilIdle()
            assertEquals(today, vm.uiState.value.selectedDate)
        }

    @Test
    fun `goToToday resets to today from a past date`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.previousDay()
            advanceUntilIdle()
            vm.previousDay()
            advanceUntilIdle()
            assertEquals(LocalDate.now().minusDays(2), vm.uiState.value.selectedDate)
            vm.goToToday()
            advanceUntilIdle()
            assertEquals(LocalDate.now(), vm.uiState.value.selectedDate)
        }

    @Test
    fun `selectDate navigates to past date`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val pastDate = LocalDate.now().minusDays(5)
            vm.selectDate(pastDate)
            advanceUntilIdle()
            assertEquals(pastDate, vm.uiState.value.selectedDate)
        }

    @Test
    fun `selectDate clamps future date to today`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectDate(LocalDate.now().plusDays(5))
            advanceUntilIdle()
            assertEquals(LocalDate.now(), vm.uiState.value.selectedDate)
        }

    // ── Tab ──────────────────────────────────────────────────────────

    @Test
    fun `selectTab always sets SUMMARY`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.selectTab(DayTab.SUMMARY)
            assertEquals(DayTab.SUMMARY, vm.uiState.value.selectedTab)
        }

    // ── Date Formatting ──────────────────────────────────────────────

    @Test
    fun `getFormattedDate returns non-blank string`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            assertTrue(vm.getFormattedDate().isNotBlank())
        }

    @Test
    fun `isToday returns true when selected date is today`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            assertTrue(vm.isToday())
        }

    @Test
    fun `isToday returns false after navigating to past`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.previousDay()
            advanceUntilIdle()
            assertFalse(vm.isToday())
        }

    // ── Response Updates ─────────────────────────────────────────────

    @Test
    fun `updateOverallResponse updates state when source matches selected`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val sourceDate = vm.uiState.value.selectedDate

            vm.updateOverallResponse(sourceDate, "gratitude", "sunshine")
            advanceUntilIdle()

            assertEquals("sunshine", vm.uiState.value.overallResponses["gratitude"])
        }

    @Test
    fun `updateOverallResponse does not update state when source mismatches`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.updateOverallResponse(LocalDate.now().minusDays(3), "gratitude", "sunshine")
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.overallResponses
                    .isEmpty(),
            )
        }

    @Test
    fun `updateOverallResponse still saves when source mismatches`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val otherDate = LocalDate.now().minusDays(3)

            vm.updateOverallResponse(otherDate, "gratitude", "test")
            assertTrue(
                vm.uiState.value.pendingJournalSaveDates
                    .contains(otherDate),
            )

            advanceTimeBy(500)
            advanceUntilIdle()

            assertEquals(1, fakeJournal.upsertedResponses.size)
            assertEquals("test", fakeJournal.upsertedResponses.first().responseText)
        }

    @Test
    fun `updateDimensionResponse with canonical id updates state`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val sourceDate = vm.uiState.value.selectedDate

            vm.updateDimensionResponse(
                sourceDate,
                DimensionTaxonomyCatalog.MENTAL_HEALTH.id,
                "feeling",
                "steady",
            )
            advanceUntilIdle()

            assertEquals(
                "steady",
                vm.uiState.value.dimensionResponses[DimensionTaxonomyCatalog.MENTAL_HEALTH.id]
                    ?.get("feeling"),
            )
        }

    @Test
    fun `updateDimensionResponse with non-canonical id is ignored`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.updateDimensionResponse(
                vm.uiState.value.selectedDate,
                "not_real_dimension",
                "feeling",
                "steady",
            )
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.dimensionResponses
                    .isEmpty(),
            )
        }

    @Test
    fun `updateDimensionResponse does not update state when source mismatches`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.updateDimensionResponse(
                LocalDate.now().minusDays(3),
                DimensionTaxonomyCatalog.MENTAL_HEALTH.id,
                "feeling",
                "steady",
            )
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.dimensionResponses
                    .isEmpty(),
            )
        }

    // ── Debounce Scenarios (ported from Android Mockito test) ─────────

    @Test
    fun `two rapid overall updates coalesce into one upsert`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val sourceDate = vm.uiState.value.selectedDate

            vm.updateOverallResponse(sourceDate, "gratitude", "first")
            vm.updateOverallResponse(sourceDate, "gratitude", "second")

            // At 499 ms the debounce has not fired yet
            advanceTimeBy(499)
            runCurrent()
            assertTrue(fakeJournal.upsertedResponses.isEmpty())

            // At 500 ms it fires exactly once with the latest value
            advanceTimeBy(1)
            advanceUntilIdle()

            assertEquals(1, fakeJournal.upsertedResponses.size)
            val saved = fakeJournal.upsertedResponses.first()
            assertEquals("second", saved.responseText)
            assertEquals("overall", saved.scope)
            assertNull(saved.dimensionKey)
            assertEquals("gratitude", saved.promptKey)
        }

    @Test
    fun `independent prompts produce separate upserts`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val sourceDate = vm.uiState.value.selectedDate

            vm.updateOverallResponse(sourceDate, "gratitude", "a")
            vm.updateOverallResponse(sourceDate, "accomplishment", "b")

            advanceTimeBy(500)
            advanceUntilIdle()

            assertEquals(2, fakeJournal.upsertedResponses.size)
            assertEquals(
                setOf("gratitude", "accomplishment"),
                fakeJournal.upsertedResponses.map { it.promptKey }.toSet(),
            )
        }

    @Test
    fun `dimension response persists canonical dimension id`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val sourceDate = vm.uiState.value.selectedDate

            vm.updateDimensionResponse(
                sourceDate,
                DimensionTaxonomyCatalog.MENTAL_HEALTH.id,
                "feeling",
                "steady",
            )
            advanceTimeBy(500)
            advanceUntilIdle()

            assertEquals(1, fakeJournal.upsertedResponses.size)
            val saved = fakeJournal.upsertedResponses.first()
            assertEquals("dimension", saved.scope)
            assertEquals(DimensionTaxonomyCatalog.MENTAL_HEALTH.id, saved.dimensionKey)
            assertEquals("steady", saved.responseText)
        }

    @Test
    fun `source date is preserved after navigating away`() =
        runTest {
            val sourceDate = LocalDate.now().minusDays(1)
            fakeJournal.entryForDate[sourceDate.toString()] =
                DayJournalEntry(
                    id = "entry-source",
                    entryDate = sourceDate.toString(),
                    createdAt = "2026-03-10T00:00:00",
                    updatedAt = "2026-03-10T00:00:00",
                )
            val vm = viewModel()
            advanceUntilIdle()

            vm.selectDate(sourceDate)
            advanceUntilIdle()
            vm.updateOverallResponse(sourceDate, "gratitude", "kept")
            vm.selectDate(LocalDate.now())
            advanceUntilIdle()
            advanceTimeBy(500)
            advanceUntilIdle()

            assertEquals(1, fakeJournal.upsertedResponses.size)
            assertEquals("kept", fakeJournal.upsertedResponses.first().responseText)
            assertEquals(sourceDate, vm.uiState.value.lastSavedJournalDate)
        }

    @Test
    fun `nextDay blocks navigation beyond today (debounce scenario)`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val startingDate = vm.uiState.value.selectedDate
            assertEquals(LocalDate.now(), startingDate)
            assertFalse(startingDate.isBefore(LocalDate.now()))

            vm.nextDay()
            advanceUntilIdle()
            assertEquals(startingDate, vm.uiState.value.selectedDate)
        }

    // ── Load Day Data ────────────────────────────────────────────────

    @Test
    fun `loadDayData populates overall responses from existing entry`() =
        runTest {
            val todayStr = LocalDate.now().toString()
            val entry =
                DayJournalEntry(
                    id = "e1",
                    entryDate = todayStr,
                    createdAt = "...",
                    updatedAt = "...",
                )
            fakeJournal.entryForDate[todayStr] = entry
            fakeJournal.responsesByEntry["e1"] =
                listOf(
                    DayJournalResponse(
                        id = "r1",
                        entryId = "e1",
                        scope = "overall",
                        promptKey = "gratitude",
                        responseText = "flowers",
                    ),
                )
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(entry, vm.uiState.value.journalEntry)
            assertEquals("flowers", vm.uiState.value.overallResponses["gratitude"])
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `loadDayData populates dimension responses from existing entry`() =
        runTest {
            val todayStr = LocalDate.now().toString()
            val entry =
                DayJournalEntry(
                    id = "e2",
                    entryDate = todayStr,
                    createdAt = "...",
                    updatedAt = "...",
                )
            fakeJournal.entryForDate[todayStr] = entry
            fakeJournal.responsesByEntry["e2"] =
                listOf(
                    DayJournalResponse(
                        id = "r2",
                        entryId = "e2",
                        scope = "dimension",
                        dimensionKey = DimensionTaxonomyCatalog.MENTAL_HEALTH.id,
                        promptKey = "feeling",
                        responseText = "calm",
                    ),
                )
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(
                "calm",
                vm.uiState.value.dimensionResponses[DimensionTaxonomyCatalog.MENTAL_HEALTH.id]
                    ?.get("feeling"),
            )
        }

    @Test
    fun `loadDayData with no entry leaves state empty`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            assertNull(vm.uiState.value.journalEntry)
            assertTrue(
                vm.uiState.value.overallResponses
                    .isEmpty(),
            )
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `loadDayData repository error sets loading false`() =
        runTest {
            fakeJournal.failLoad = true
            val vm = viewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `loadDayData ignores non-canonical dimension key`() =
        runTest {
            val todayStr = LocalDate.now().toString()
            val entry =
                DayJournalEntry(
                    id = "e3",
                    entryDate = todayStr,
                    createdAt = "...",
                    updatedAt = "...",
                )
            fakeJournal.entryForDate[todayStr] = entry
            fakeJournal.responsesByEntry["e3"] =
                listOf(
                    DayJournalResponse(
                        id = "r3",
                        entryId = "e3",
                        scope = "dimension",
                        dimensionKey = "not_real_dimension",
                        promptKey = "feeling",
                        responseText = "calm",
                    ),
                )
            val vm = viewModel()
            advanceUntilIdle()

            // Non-canonical dimension key is silently ignored during load
            assertTrue(
                vm.uiState.value.dimensionResponses
                    .isEmpty(),
            )
        }

    // ── Save Behaviour ───────────────────────────────────────────────

    @Test
    fun `save creates new entry when none exists`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val sourceDate = vm.uiState.value.selectedDate

            vm.updateOverallResponse(sourceDate, "gratitude", "test")
            advanceTimeBy(500)
            advanceUntilIdle()

            assertEquals(1, fakeJournal.insertedEntries.size)
            assertEquals(1, fakeJournal.upsertedResponses.size)
            assertEquals(
                fakeJournal.insertedEntries.first().id,
                fakeJournal.upsertedResponses.first().entryId,
            )
        }

    @Test
    fun `save reuses existing entry`() =
        runTest {
            val todayStr = LocalDate.now().toString()
            fakeJournal.entryForDate[todayStr] =
                DayJournalEntry(
                    id = "existing",
                    entryDate = todayStr,
                    createdAt = "...",
                    updatedAt = "...",
                )
            val vm = viewModel()
            advanceUntilIdle()
            val sourceDate = vm.uiState.value.selectedDate

            vm.updateOverallResponse(sourceDate, "gratitude", "test")
            advanceTimeBy(500)
            advanceUntilIdle()

            assertEquals(0, fakeJournal.insertedEntries.size)
            assertEquals(1, fakeJournal.upsertedResponses.size)
            assertEquals("existing", fakeJournal.upsertedResponses.first().entryId)
        }

    @Test
    fun `save error does not crash and does not set lastSavedJournalDate`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val sourceDate = vm.uiState.value.selectedDate

            fakeJournal.failSave = true
            vm.updateOverallResponse(sourceDate, "gratitude", "test")
            advanceTimeBy(500)
            advanceUntilIdle()

            assertNull(vm.uiState.value.lastSavedJournalDate)
            assertTrue(fakeJournal.upsertedResponses.isEmpty())
        }

    // ── Pending Save Dates ───────────────────────────────────────────

    @Test
    fun `pending save date appears then clears after save completes`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            val sourceDate = vm.uiState.value.selectedDate

            vm.updateOverallResponse(sourceDate, "gratitude", "test")
            assertTrue(
                vm.uiState.value.pendingJournalSaveDates
                    .contains(sourceDate),
            )

            advanceTimeBy(500)
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.pendingJournalSaveDates
                    .isEmpty(),
            )
            assertEquals(sourceDate, vm.uiState.value.lastSavedJournalDate)
        }

    // ── Dispose ──────────────────────────────────────────────────────

    @Test
    fun `dispose cancels scope without error`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            vm.dispose()
            advanceUntilIdle()
            // No crash means success
            assertTrue(true)
        }

    // ── Factory ──────────────────────────────────────────────────────

    @Test
    fun `factory builds ViewModel from installed data layer`() =
        runTest {
            LocalDataLayer.install(
                object : PayanamDataLayer by UninstalledDataLayer {
                    override val notes: NoteRepository = stubNoteRepository()
                    override val tags: TagRepository = stubTagRepository()
                    override val tasks: TaskRepository = stubTaskRepository()
                    override val timeEntries: TimeEntryRepository = stubTimeEntryRepository()
                    override val dayPlans: DayPlanRepository = stubDayPlanRepository()
                    override val taskOccurrences: TaskOccurrenceRepository =
                        stubTaskOccurrenceRepository()
                    override val journal: JournalRepository = fakeJournal
                },
            )
            val vm = dayViewModel(newViewModelScope())
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
        }

    // ══════════════════════════════════════════════════════════════════
    //  Fakes
    // ══════════════════════════════════════════════════════════════════

    private class FakeJournalRepository : JournalRepository {
        val entryForDate = mutableMapOf<String, DayJournalEntry>()
        val responsesByEntry = mutableMapOf<String, List<DayJournalResponse>>()
        val upsertedResponses = mutableListOf<DayJournalResponse>()
        val insertedEntries = mutableListOf<DayJournalEntry>()
        var failLoad = false
        var failSave = false

        override suspend fun getEntryByDate(dateString: String): DayJournalEntry? {
            if (failLoad) throw IOException("load failed")
            return entryForDate[dateString]
        }

        override suspend fun insertEntry(entry: DayJournalEntry) {
            if (failLoad) throw IOException("load failed")
            insertedEntries += entry
        }

        override suspend fun getResponsesByEntryId(entryId: String): List<DayJournalResponse> {
            if (failLoad) throw IOException("load failed")
            return responsesByEntry[entryId].orEmpty()
        }

        override suspend fun upsertResponse(response: DayJournalResponse) {
            if (failSave) throw IOException("save failed")
            upsertedResponses += response
        }

        // ── Remaining JournalRepository members: no-op stubs ────────

        override suspend fun getOrCreateEntry(date: LocalDate): DayJournalEntry = TODO()

        override fun observeEntry(date: LocalDate): Flow<DayJournalEntry?> = flowOf(null)

        override fun getResponses(entryId: String): Flow<List<DayJournalResponse>> = flowOf(emptyList())

        override suspend fun saveResponse(
            entryId: String,
            input: DayJournalResponseInput,
        ): DayJournalResponse = TODO()

        override suspend fun getResponse(
            entryId: String,
            scope: JournalPromptScope,
            dimensionKey: String?,
            promptKey: String,
        ): DayJournalResponse? = null

        override fun getAllJournalEntries(): Flow<List<DayJournalEntry>> = flowOf(emptyList())

        override fun getTotalResponseCount(): Flow<Int> = flowOf(0)
    }

    // ── Stub Repositories (for the PayanamDataLayer factory test) ────

    private fun stubNoteRepository() =
        object : NoteRepository {
            override fun getAllNotes(): Flow<List<Note>> = flowOf(emptyList())

            override fun getNotesByDimension(dimension: String): Flow<List<Note>> = flowOf(emptyList())

            override fun getNotesForDate(date: LocalDate): Flow<List<Note>> = flowOf(emptyList())

            override suspend fun getNoteById(id: String): Note? = null

            override suspend fun createNote(input: NoteInput): Note = error("stub")

            override suspend fun updateNote(
                id: String,
                input: NoteInput,
            ): Note = error("stub")

            override suspend fun deleteNote(id: String) {
                error("stub")
            }
        }

    private fun stubTagRepository() =
        object : TagRepository {
            override fun observeAllTags(): Flow<List<Tag>> = flowOf(emptyList())

            override fun searchTagsByPrefix(
                query: String,
                limit: Int,
            ): Flow<List<Tag>> = flowOf(emptyList())

            override fun observeTagsForTask(taskId: String): Flow<List<Tag>> = flowOf(emptyList())

            override fun observeTagsForNote(noteId: String): Flow<List<Tag>> = flowOf(emptyList())

            override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> = emptyMap()

            override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> = flowOf(emptyList())

            override suspend fun replaceTaskTags(
                taskId: String,
                tagNames: List<String>,
            ) {
                error("stub")
            }

            override suspend fun replaceNoteTags(
                noteId: String,
                tagNames: List<String>,
            ) {
                error("stub")
            }

            override suspend fun replaceTimeEntryTags(
                timeEntryId: String,
                tagNames: List<String>,
            ) {
                error("stub")
            }
        }

    private fun stubTaskRepository() =
        object : TaskRepository {
            override fun getAllTasks(): Flow<List<Task>> = flowOf(emptyList())

            override fun getTasksByStatus(status: String): Flow<List<Task>> = flowOf(emptyList())

            override fun getTasksDueOn(date: LocalDate): Flow<List<Task>> = flowOf(emptyList())

            override suspend fun getTaskById(id: String): Task? = null

            override suspend fun createTask(input: TaskInput): Task = error("stub")

            override suspend fun updateTask(
                id: String,
                input: TaskInput,
            ): Task = error("stub")

            override suspend fun updateTaskScore(
                id: String,
                score: Double,
            ) {
                error("stub")
            }

            override suspend fun deleteTask(id: String) {
                error("stub")
            }

            override suspend fun completeTask(
                id: String,
                note: String?,
            ): Task = error("stub")

            override suspend fun skipTask(
                id: String,
                note: String?,
            ): Task = error("stub")

            override suspend fun missTask(
                id: String,
                note: String?,
            ): Task = error("stub")

            override suspend fun archiveTask(id: String): Task = error("stub")

            override fun getOverdueTasks(): Flow<List<Task>> = flowOf(emptyList())

            override fun getTodaysTasks(): Flow<List<Task>> = flowOf(emptyList())

            override suspend fun getRecurringTasks(): List<Task> = emptyList()

            override suspend fun updateRecurrenceState(
                taskId: String,
                newDueDate: LocalDateTime,
                lastOccurrenceDate: LocalDateTime,
            ) {
                error("stub")
            }
        }

    private fun stubTimeEntryRepository() =
        object : TimeEntryRepository {
            override suspend fun getActiveTimeEntry(): TimeEntry? = null

            override fun observeActiveTimeEntry(): Flow<TimeEntry?> = flowOf(null)

            override fun getTimeEntriesForRange(
                start: LocalDateTime,
                end: LocalDateTime,
            ): Flow<List<TimeEntry>> = flowOf(emptyList())

            override fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>> = flowOf(emptyList())

            override suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry = error("stub")

            override suspend fun stopActiveTimeEntry(): TimeEntry? = null

            override suspend fun stopActiveTimeEntryWithFocus(
                focusRating: Double,
                focusNote: String?,
            ): TimeEntry? = null

            override suspend fun updateTimeEntry(
                id: String,
                input: TimeEntryInput,
            ): TimeEntry = error("stub")

            override suspend fun deleteTimeEntry(id: String) {
                error("stub")
            }

            override suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry = error("stub")

            override fun getAllTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

            override fun getActiveTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

            override suspend fun updateTimeEntry(entry: TimeEntry) {
                error("stub")
            }
        }

    private fun stubDayPlanRepository() =
        object : DayPlanRepository {
            override fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>> = flowOf(emptyList())

            override suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> = emptyList()

            override suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> = emptyList()

            override suspend fun setAllocation(
                dayKey: String,
                dimensionId: String,
                plannedMinutes: Int,
                source: String,
                templateId: String?,
            ) {
                error("stub")
            }

            override suspend fun setAllocations(
                dayKey: String,
                allocations: Map<String, Int>,
                source: String,
                templateId: String?,
            ) {
                error("stub")
            }

            override suspend fun applyTemplateToDay(
                dayKey: String,
                templateId: String,
            ) {
                error("stub")
            }

            override suspend fun clearDayPlan(dayKey: String) {
                error("stub")
            }

            override suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord = error("stub")

            override suspend fun setDayMode(
                dayKey: String,
                mode: String,
                templateId: String?,
            ) {
                error("stub")
            }

            override suspend fun setDayStarred(
                dayKey: String,
                isStarred: Boolean,
            ) {
                error("stub")
            }

            override suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord = error("stub")

            override suspend fun setDayTypeTemplatePreference(
                dayType: String,
                templateId: String?,
            ) {
                error("stub")
            }

            override suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord? = null

            override fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>> = flowOf(emptyList())

            override fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>> = flowOf(emptyList())

            override suspend fun getTemplateById(id: String): DayPlanTemplateRecord? = null

            override suspend fun createTemplate(
                name: String,
                description: String?,
                allocations: Map<String, Int>,
            ): String = error("stub")

            override suspend fun updateTemplate(
                id: String,
                name: String,
                description: String?,
                allocations: Map<String, Int>,
            ) {
                error("stub")
            }

            override suspend fun deleteTemplate(id: String) {
                error("stub")
            }
        }

    private fun stubTaskOccurrenceRepository() =
        object : TaskOccurrenceRepository {
            override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> = emptyList()

            override fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrence>> = flowOf(emptyList())

            override suspend fun getOccurrencesForLastNDays(
                taskId: String,
                days: Int,
            ): List<TaskOccurrence> = emptyList()

            override suspend fun getOccurrencesForTasksInLastNDays(
                taskIds: List<String>,
                days: Int,
            ): Map<String, List<TaskOccurrence>> = emptyMap()

            override suspend fun getOccurrenceForDate(
                taskId: String,
                date: LocalDate,
            ): TaskOccurrence? = null

            override suspend fun toggleOccurrence(
                taskId: String,
                date: LocalDate,
                newStatus: String,
                note: String?,
                reason: String?,
                actualCompletedAt: LocalDateTime?,
                actualDurationMinutes: Int?,
            ): TaskOccurrence = error("stub")

            override fun getOccurrencesForDate(date: LocalDate): Flow<List<TaskOccurrence>> = flowOf(emptyList())

            override suspend fun deleteOccurrence(
                taskId: String,
                date: LocalDate,
            ) {
                error("stub")
            }

            override suspend fun recordOccurrence(occurrence: TaskOccurrence) {
                error("stub")
            }

            override suspend fun recordOccurrence(
                taskId: String,
                dueDate: LocalDateTime,
                status: String,
                note: String?,
                completionRate: Double?,
            ): TaskOccurrence = error("stub")

            override suspend fun deleteOccurrence(id: String) {
                error("stub")
            }
        }
}
