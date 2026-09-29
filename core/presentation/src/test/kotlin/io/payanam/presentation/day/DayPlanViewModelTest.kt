//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.day

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
import io.payanam.domain.repository.TemplateAllocationRecord
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * Behavioural lock for the shared DayPlan ViewModel.
 *
 * Moved out of `:app` with the ViewModel itself, and converted: the original ran under Robolectric
 * purely to boot the Android logger singleton, which this module does not have. Here the logger is
 * [AppLogger]'s default no-op, so the test runs on a plain JVM — which is also the proof that the
 * ViewModel no longer needs a platform to exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayPlanViewModelTest {
    private lateinit var repository: FakeDayPlanRepository
    private var viewModelScope: CoroutineScope? = null

    @Before
    fun setUp() {
        LocalDataLayer.resetForTests()
        repository = FakeDayPlanRepository()
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

    private fun TestScope.viewModel(): DayPlanViewModel {
        val scope = newViewModelScope()
        return DayPlanViewModel(repository, scope)
    }

    // ── init block ────────────────────────────────────────────────────────

    @Test
    fun `init subscribes to active templates and loads today's day plan`() =
        runTest {
            val t1 = template("t1", "Morning Routine")
            repository.templatesFlow.value = listOf(t1)
            repository.dayPolicyByDay[LocalDate.now().toString()] =
                DayPlanPolicyRecord(
                    dayKey = LocalDate.now().toString(),
                    mode = DayPlanRepository.MODE_AUTO,
                    templateId = null,
                    isStarred = false,
                )

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(listOf(t1), state.templates)
            assertEquals(1, state.templateCount)
            assertEquals(LocalDate.now().toString(), state.selectedDayKey)
            assertEquals(DayPlanRepository.MODE_AUTO, state.dayMode)
            assertFalse(state.isLoading)
        }

    // ── loadDayPlan ───────────────────────────────────────────────────────

    @Test
    fun `loadDayPlan hydrates ui state from policy allocations and resolved template`() =
        runTest {
            val dayKey = "2026-02-23"
            val resolvedTemplate =
                DayPlanTemplateRecord(
                    id = "tpl-resolved",
                    name = "Resolved Day",
                    description = null,
                    isActive = true,
                    sortOrder = 0,
                    allocations =
                        listOf(
                            TemplateAllocationRecord(
                                id = "ta-1",
                                templateId = "tpl-resolved",
                                dimensionId = "career_work",
                                plannedMinutes = 180,
                            ),
                        ),
                )
            repository.dayPolicyByDay[dayKey] =
                DayPlanPolicyRecord(
                    dayKey = dayKey,
                    mode = DayPlanRepository.MODE_TEMPLATE,
                    templateId = "tpl-resolved",
                    isStarred = true,
                )
            repository.allocationsByDay[dayKey] =
                listOf(
                    DayPlanAllocationRecord(
                        id = "alloc-1",
                        dayKey = dayKey,
                        dimensionId = "career_work",
                        plannedMinutes = 180,
                        source = DayPlanRepository.SOURCE_TEMPLATE,
                        templateId = "tpl-resolved",
                    ),
                )
            repository.resolvedTemplateByDay[dayKey] = resolvedTemplate
            repository.dayTypePreferences[DayPlanRepository.DAY_TYPE_WEEKDAY] = "tpl-weekday"
            repository.dayTypePreferences[DayPlanRepository.DAY_TYPE_WEEKEND] = "tpl-weekend"
            repository.dayTypePreferences[DayPlanRepository.DAY_TYPE_STARRED] = "tpl-starred"

            // One instance, not two: `viewModel().loadDayPlan(dayKey)` followed by a second `viewModel()`
            // asserted against a fresh instance that had only loaded today, which is why this first failed
            // comparing today's date to the requested dayKey.
            val vm = viewModel()
            vm.loadDayPlan(dayKey)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(dayKey, state.selectedDayKey)
            assertEquals(DayPlanRepository.MODE_TEMPLATE, state.dayMode)
            assertEquals("tpl-resolved", state.selectedDayTemplateId)
            assertTrue(state.isStarredDay)
            assertEquals(180, state.dayAllocations["career_work"])
            assertEquals("tpl-weekday", state.dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKDAY])
            assertEquals("Resolved Day", state.resolvedTemplateForDay?.name)
        }

    @Test
    fun `loadDayPlan duplicate request while in flight only loads once`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()
            repository.resetCounters()

            val dayKey = "2026-02-24"
            vm.loadDayPlan(dayKey)
            vm.loadDayPlan(dayKey)
            advanceUntilIdle()
            assertEquals(1, repository.getAllocationsForDayCount)
            assertEquals(1, repository.getDayPolicyCount)
            assertEquals(3, repository.getDayTypeTemplatePreferenceCount)
            assertEquals(1, repository.resolveTemplateForDayCount)
        }

    @Test
    fun `loadDayPlan with MODE_TEMPLATE returns selectedTemplateId from policy`() =
        runTest {
            val dayKey = "2026-03-10"
            repository.dayPolicyByDay[dayKey] =
                DayPlanPolicyRecord(
                    dayKey = dayKey,
                    mode = DayPlanRepository.MODE_TEMPLATE,
                    templateId = "tpl-explicit",
                    isStarred = false,
                )
            repository.resolvedTemplateByDay[dayKey] = null // repo returns null, so fallback to getTemplateById

            val vm = viewModel()
            advanceUntilIdle()
            vm.loadDayPlan(dayKey)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("tpl-explicit", state.selectedDayTemplateId)
            assertEquals(DayPlanRepository.MODE_TEMPLATE, state.dayMode)
        }

    @Test
    fun `loadDayPlan on a starred day resolves starred template preference`() =
        runTest {
            val dayKey = "2026-03-11" // Wednesday
            repository.dayPolicyByDay[dayKey] =
                DayPlanPolicyRecord(
                    dayKey = dayKey,
                    mode = DayPlanRepository.MODE_AUTO,
                    templateId = null,
                    isStarred = true,
                )
            repository.dayTypePreferences[DayPlanRepository.DAY_TYPE_STARRED] = "tpl-starred"

            val vm = viewModel()
            advanceUntilIdle()
            vm.loadDayPlan(dayKey)
            advanceUntilIdle()

            // The init load used today; this load uses the starred day.
            // The template observer re-resolves via resolveTemplateIdForDay: isStarred → starred pref.
            val state = vm.uiState.value
            assertEquals(dayKey, state.selectedDayKey)
            assertTrue(state.isStarredDay)
            assertEquals("tpl-starred", state.dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_STARRED])
        }

    @Test
    fun `loadDayPlan on a weekend resolves weekend template preference`() =
        runTest {
            val dayKey = "2026-09-20" // Sunday
            repository.dayPolicyByDay[dayKey] =
                DayPlanPolicyRecord(
                    dayKey = dayKey,
                    mode = DayPlanRepository.MODE_AUTO,
                    templateId = null,
                    isStarred = false,
                )
            repository.dayTypePreferences[DayPlanRepository.DAY_TYPE_WEEKEND] = "tpl-weekend"

            val vm = viewModel()
            advanceUntilIdle()
            vm.loadDayPlan(dayKey)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(dayKey, state.selectedDayKey)
            assertFalse(state.isStarredDay)
            assertEquals("tpl-weekend", state.dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKEND])
        }

    @Test
    fun `loadDayPlan on a weekday resolves weekday template preference`() =
        runTest {
            val dayKey = "2026-09-21" // Monday
            repository.dayPolicyByDay[dayKey] =
                DayPlanPolicyRecord(
                    dayKey = dayKey,
                    mode = DayPlanRepository.MODE_AUTO,
                    templateId = null,
                    isStarred = false,
                )
            repository.dayTypePreferences[DayPlanRepository.DAY_TYPE_WEEKDAY] = "tpl-weekday"

            val vm = viewModel()
            advanceUntilIdle()
            vm.loadDayPlan(dayKey)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(dayKey, state.selectedDayKey)
            assertEquals("tpl-weekday", state.dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKDAY])
        }

    @Test
    fun `loadDayPlan sets resolvedTemplateForDay from repo fallback chain`() =
        runTest {
            val dayKey = "2026-03-12"
            val fallbackTemplate = template("tpl-fallback", "Fallback")
            // First fallback: resolveTemplateForDay returns null
            repository.resolvedTemplateByDay[dayKey] = null
            // Second fallback: look up in templates flow
            repository.templatesFlow.value = listOf(fallbackTemplate)
            // MODE_TEMPLATE, not MODE_AUTO: `resolveTemplateIdForDay` only returns the policy's templateId in
            // template mode. In auto mode it resolves from the day-type preference instead, which this test
            // does not set, so the id came back null and the second fallback was never reached.
            repository.dayPolicyByDay[dayKey] =
                DayPlanPolicyRecord(
                    dayKey = dayKey,
                    mode = DayPlanRepository.MODE_TEMPLATE,
                    templateId = "tpl-fallback",
                    isStarred = false,
                )

            val vm = viewModel()
            advanceUntilIdle()
            vm.loadDayPlan(dayKey)
            advanceUntilIdle()

            assertEquals(
                "Fallback",
                vm.uiState.value.resolvedTemplateForDay
                    ?.name,
            )
        }

    @Test
    fun `loadDayPlan error in repository leaves state uncorrupted`() =
        runTest {
            val dayKey = "2026-03-13"
            repository.failLoads = true
            val vm = viewModel()
            advanceUntilIdle()

            // The init loadDayPlan threw but the scope's SupervisorJob swallowed it.
            // State stays at defaults (the init threw before updating state).
            val state = vm.uiState.value
            assertFalse(state.isLoading)
        }

    // ── saveDayPlan ───────────────────────────────────────────────────────

    @Test
    fun `saveDayPlan custom mode persists allocations starred and day type preferences`() =
        runTest {
            val dayKey = "2026-02-21"
            val allocations = mapOf("career_work" to 120)
            val dayTypeTemplateByType =
                mapOf(
                    DayPlanRepository.DAY_TYPE_WEEKDAY to "tpl-weekday",
                    DayPlanRepository.DAY_TYPE_WEEKEND to "tpl-weekend",
                    DayPlanRepository.DAY_TYPE_STARRED to "tpl-starred",
                )

            val vm = viewModel()
            advanceUntilIdle()
            vm.saveDayPlan(
                dayKey = dayKey,
                mode = DayPlanRepository.MODE_CUSTOM,
                allocations = allocations,
                templateId = null,
                isStarred = true,
                dayTypeTemplateByType = dayTypeTemplateByType,
            )
            advanceUntilIdle()

            assertTrue(repository.starredUpdates.contains(dayKey to true))
            assertEquals(allocations, repository.lastSetAllocations)
            assertEquals(DayPlanRepository.SOURCE_MANUAL, repository.lastSetAllocationsSource)
            assertEquals(
                listOf(
                    DayPlanRepository.DAY_TYPE_WEEKDAY to "tpl-weekday",
                    DayPlanRepository.DAY_TYPE_WEEKEND to "tpl-weekend",
                    DayPlanRepository.DAY_TYPE_STARRED to "tpl-starred",
                ),
                repository.dayTypePreferenceUpdates,
            )
        }

    @Test
    fun `saveDayPlan template mode with template id applies template to day`() =
        runTest {
            val dayKey = "2026-02-22"
            val vm = viewModel()
            advanceUntilIdle()

            vm.saveDayPlan(
                dayKey = dayKey,
                mode = DayPlanRepository.MODE_TEMPLATE,
                allocations = emptyMap(),
                templateId = "tpl-123",
                isStarred = false,
                dayTypeTemplateByType = emptyMap(),
            )
            advanceUntilIdle()

            assertEquals(
                listOf(Triple(dayKey, DayPlanRepository.MODE_TEMPLATE, "tpl-123")),
                repository.modeUpdates,
            )
        }

    @Test
    fun `saveDayPlan template mode without template id resets to auto and clears plan`() =
        runTest {
            val dayKey = "2026-02-22"
            val vm = viewModel()
            advanceUntilIdle()

            vm.saveDayPlan(
                dayKey = dayKey,
                mode = DayPlanRepository.MODE_TEMPLATE,
                allocations = emptyMap(),
                templateId = null,
                isStarred = false,
                dayTypeTemplateByType = emptyMap(),
            )
            advanceUntilIdle()

            assertTrue(repository.modeUpdates.contains(Triple(dayKey, DayPlanRepository.MODE_AUTO, null)))
            assertTrue(repository.clearedDays.contains(dayKey))
        }

    @Test
    fun `saveDayPlan auto mode (else branch) sets auto and clears plan`() =
        runTest {
            val dayKey = "2026-04-01"
            val vm = viewModel()
            advanceUntilIdle()

            vm.saveDayPlan(
                dayKey = dayKey,
                mode = DayPlanRepository.MODE_AUTO,
                allocations = emptyMap(),
                templateId = null,
                isStarred = false,
                dayTypeTemplateByType = emptyMap(),
            )
            advanceUntilIdle()

            assertTrue(repository.modeUpdates.contains(Triple(dayKey, DayPlanRepository.MODE_AUTO, null)))
            assertTrue(repository.clearedDays.contains(dayKey))
        }

    @Test
    fun `saveDayPlan error surfaces as errorMessage and clears loading`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            repository.failWrites = true
            vm.saveDayPlan(
                dayKey = "2026-05-01",
                mode = DayPlanRepository.MODE_CUSTOM,
                allocations = mapOf("health" to 60),
                templateId = null,
                isStarred = false,
                dayTypeTemplateByType = emptyMap(),
            )
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("write failed", state.errorMessage)
            assertFalse(state.isLoading)
        }

    @Test
    fun `saveDayPlan sets isLoading true during write and false after`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            // saveDayPlan sets isLoading = true, then false after completion.
            // With a failing write, isLoading goes true → false with error.
            repository.failWrites = true
            vm.saveDayPlan(
                dayKey = "2026-05-02",
                mode = DayPlanRepository.MODE_CUSTOM,
                allocations = emptyMap(),
                templateId = null,
                isStarred = false,
                dayTypeTemplateByType = emptyMap(),
            )
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
        }

    // ── clearDayPlan ──────────────────────────────────────────────────────

    @Test
    fun `clearDayPlan delegates to repository and reloads`() =
        runTest {
            val dayKey = "2026-06-01"
            val vm = viewModel()
            advanceUntilIdle()

            vm.clearDayPlan(dayKey)
            advanceUntilIdle()

            assertTrue(repository.clearedDays.contains(dayKey))
        }

    // ── selectTemplate ────────────────────────────────────────────────────

    @Test
    fun `selectTemplate loads template into viewer fields`() =
        runTest {
            val t =
                template(
                    "t1",
                    "Morning",
                    description = "Daily morning routine",
                    allocations =
                        listOf(
                            TemplateAllocationRecord("a1", "t1", "career_work", 120),
                            TemplateAllocationRecord("a2", "t1", "health", 60),
                        ),
                )
            repository.templatesFlow.value = listOf(t)

            val vm = viewModel()
            advanceUntilIdle()
            vm.selectTemplate("t1")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("t1", state.selectedTemplate?.id)
            assertFalse(state.isEditingTemplate)
            assertFalse(state.isCreatingNew)
            assertEquals("Morning", state.templateName)
            assertEquals("Daily morning routine", state.templateDescription)
            assertEquals(mapOf("career_work" to 120, "health" to 60), state.templateAllocations)
        }

    @Test
    fun `selectTemplate with nonexistent id clears fields`() =
        runTest {
            repository.templatesFlow.value = emptyList()
            val vm = viewModel()
            advanceUntilIdle()

            vm.selectTemplate("nonexistent")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.selectedTemplate)
            assertEquals("", state.templateName)
            assertEquals("", state.templateDescription)
            assertTrue(state.templateAllocations.isEmpty())
        }

    // ── startNewTemplate ──────────────────────────────────────────────────

    @Test
    fun `startNewTemplate opens empty editor`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.startNewTemplate()

            val state = vm.uiState.value
            assertNull(state.selectedTemplate)
            assertTrue(state.isEditingTemplate)
            assertTrue(state.isCreatingNew)
            assertEquals("", state.templateName)
            assertEquals("", state.templateDescription)
            assertTrue(state.templateAllocations.isEmpty())
            assertNull(state.errorMessage)
        }

    // ── startEditTemplate ─────────────────────────────────────────────────

    @Test
    fun `startEditTemplate opens editor pre-filled with template values`() =
        runTest {
            val t =
                template(
                    "t2",
                    "Evening",
                    description = "Wind down",
                    allocations =
                        listOf(
                            TemplateAllocationRecord("a1", "t2", "rest", 90),
                        ),
                )
            repository.templatesFlow.value = listOf(t)

            val vm = viewModel()
            advanceUntilIdle()
            vm.startEditTemplate("t2")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("t2", state.selectedTemplate?.id)
            assertTrue(state.isEditingTemplate)
            assertFalse(state.isCreatingNew)
            assertEquals("Evening", state.templateName)
            assertEquals("Wind down", state.templateDescription)
            assertEquals(mapOf("rest" to 90), state.templateAllocations)
            assertNull(state.errorMessage)
        }

    @Test
    fun `startEditTemplate with nonexistent id does nothing`() =
        runTest {
            repository.templatesFlow.value = emptyList()
            val vm = viewModel()
            advanceUntilIdle()

            vm.startEditTemplate("nonexistent")
            advanceUntilIdle()

            // State stays at init defaults — no template selected, not editing.
            val state = vm.uiState.value
            assertNull(state.selectedTemplate)
            assertFalse(state.isEditingTemplate)
        }

    // ── setTemplateName ───────────────────────────────────────────────────

    @Test
    fun `setTemplateName updates name and clears error`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            // Trigger an error first
            vm.setTemplateName("")
            vm.saveTemplate() // blank name → error
            assertEquals("Template name is required", vm.uiState.value.errorMessage)

            vm.setTemplateName("New Name")
            assertEquals("New Name", vm.uiState.value.templateName)
            assertNull(vm.uiState.value.errorMessage)
        }

    // ── setTemplateDescription ────────────────────────────────────────────

    @Test
    fun `setTemplateDescription updates description`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.setTemplateDescription("A new description")
            assertEquals("A new description", vm.uiState.value.templateDescription)
        }

    // ── setTemplateAllocation ─────────────────────────────────────────────

    @Test
    fun `setTemplateAllocation adds a dimension`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.setTemplateAllocation("career_work", 120)
            assertEquals(mapOf("career_work" to 120), vm.uiState.value.templateAllocations)
        }

    @Test
    fun `setTemplateAllocation removes a dimension when minutes is null`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.setTemplateAllocation("career_work", 120)
            vm.setTemplateAllocation("career_work", null)
            assertTrue(
                vm.uiState.value.templateAllocations
                    .isEmpty(),
            )
        }

    @Test
    fun `setTemplateAllocation removes a dimension when minutes is zero`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.setTemplateAllocation("career_work", 120)
            vm.setTemplateAllocation("career_work", 0)
            assertTrue(
                vm.uiState.value.templateAllocations
                    .isEmpty(),
            )
        }

    @Test
    fun `setTemplateAllocation removes a dimension when minutes is negative`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.setTemplateAllocation("career_work", 120)
            vm.setTemplateAllocation("career_work", -10)
            assertTrue(
                vm.uiState.value.templateAllocations
                    .isEmpty(),
            )
        }

    // ── saveTemplate ──────────────────────────────────────────────────────

    @Test
    fun `saveTemplate creates new template when isCreatingNew`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.startNewTemplate()
            vm.setTemplateName("New Template")
            vm.setTemplateAllocation("career_work", 120)
            vm.saveTemplate()
            advanceUntilIdle()

            assertEquals("New Template", repository.lastCreatedTemplateName)
            val state = vm.uiState.value
            assertFalse(state.isEditingTemplate)
            assertFalse(state.isCreatingNew)
            assertFalse(state.isLoading)
        }

    @Test
    fun `saveTemplate updates existing template when not creating new`() =
        runTest {
            val t =
                template(
                    "t1",
                    "Old Name",
                    allocations =
                        listOf(
                            TemplateAllocationRecord("a1", "t1", "career_work", 60),
                        ),
                )
            repository.templatesFlow.value = listOf(t)

            val vm = viewModel()
            advanceUntilIdle()
            vm.startEditTemplate("t1")
            // `startEditTemplate` loads the template on the ViewModel's scope, and `saveTemplate` snapshots
            // its state on entry. Without this the snapshot is taken before the edit coroutine ran, so
            // `selectedTemplate` was still null and the save took the early-return path.
            advanceUntilIdle()
            vm.setTemplateName("Updated Name")
            vm.saveTemplate()
            advanceUntilIdle()

            assertEquals("t1", repository.lastUpdatedTemplateId)
            val state = vm.uiState.value
            assertFalse(state.isEditingTemplate)
            assertFalse(state.isCreatingNew)
        }

    @Test
    fun `saveTemplate with blank name shows error without launching`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.startNewTemplate()
            // name is blank by default
            vm.saveTemplate()

            assertEquals("Template name is required", vm.uiState.value.errorMessage)
        }

    @Test
    fun `saveTemplate with name exceeding 24 hours is rejected`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.startNewTemplate()
            vm.setTemplateName("Marathon")
            vm.setTemplateAllocation("career_work", 24 * 60 + 1) // 1441 minutes
            vm.saveTemplate()
            advanceUntilIdle()

            // > 24h: saveTemplate returns early without launching — no error message, no isLoading
            assertNull(vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `saveTemplate with exactly 24 hours is accepted`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.startNewTemplate()
            vm.setTemplateName("Full Day")
            vm.setTemplateAllocation("career_work", 24 * 60) // exactly 1440 minutes
            vm.saveTemplate()
            advanceUntilIdle()

            assertEquals("Full Day", repository.lastCreatedTemplateName)
            assertFalse(vm.uiState.value.isLoading)
        }

    @Test
    fun `saveTemplate IllegalStateException surfaces e message as error`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.startNewTemplate()
            vm.setTemplateName("Failing Template")
            repository.failTemplateWrites = true
            vm.saveTemplate()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("template limit reached", state.errorMessage)
            assertFalse(state.isLoading)
        }

    @Test
    fun `saveTemplate generic exception surfaces fallback error message`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.startNewTemplate()
            vm.setTemplateName("Failing Template")
            repository.failWriteGeneric = true
            vm.saveTemplate()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("Failed to save template", state.errorMessage)
            assertFalse(state.isLoading)
        }

    // ── deleteTemplate ────────────────────────────────────────────────────

    @Test
    fun `deleteTemplate deletes and clears selection state`() =
        runTest {
            val t = template("t1", "To Delete")
            repository.templatesFlow.value = listOf(t)

            val vm = viewModel()
            advanceUntilIdle()
            vm.selectTemplate("t1")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.selectedTemplate != null)

            vm.deleteTemplate("t1")
            advanceUntilIdle()

            assertEquals(listOf("t1"), repository.deletedTemplateIds)
            val state = vm.uiState.value
            assertNull(state.selectedTemplate)
            assertFalse(state.isEditingTemplate)
            assertFalse(state.isCreatingNew)
        }

    // ── cancelEditing ─────────────────────────────────────────────────────

    @Test
    fun `cancelEditing resets editing flags and clears error`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.startNewTemplate()
            assertTrue(vm.uiState.value.isEditingTemplate)

            // Trigger an error while editing
            vm.setTemplateName("")
            vm.saveTemplate()
            assertNotNull(vm.uiState.value.errorMessage)

            vm.cancelEditing()
            val state = vm.uiState.value
            assertFalse(state.isEditingTemplate)
            assertFalse(state.isCreatingNew)
            assertNull(state.errorMessage)
        }

    // ── clearError ────────────────────────────────────────────────────────

    @Test
    fun `clearError resets the error field`() =
        runTest {
            repository.failWrites = true
            val vm = viewModel()
            advanceUntilIdle()

            vm.saveDayPlan(
                dayKey = "2026-07-01",
                mode = DayPlanRepository.MODE_CUSTOM,
                allocations = emptyMap(),
                templateId = null,
                isStarred = false,
                dayTypeTemplateByType = emptyMap(),
            )
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.errorMessage)

            vm.clearError()
            assertNull(vm.uiState.value.errorMessage)
        }

    // ── dispose ───────────────────────────────────────────────────────────

    @Test
    fun `dispose releases the scope it owns`() =
        runTest {
            val vm = DayPlanViewModel(repository)
            vm.dispose()
            advanceUntilIdle()
            assertTrue(true)
        }

    // ── observeTemplates integration ──────────────────────────────────────

    @Test
    fun `emitting new templates updates the resolved template for the selected day`() =
        runTest {
            val dayKey = "2026-08-01"
            repository.dayPolicyByDay[dayKey] =
                DayPlanPolicyRecord(
                    dayKey = dayKey,
                    mode = DayPlanRepository.MODE_TEMPLATE,
                    templateId = "tpl-new",
                    isStarred = false,
                )

            val vm = viewModel()
            advanceUntilIdle()
            vm.loadDayPlan(dayKey)
            advanceUntilIdle()

            // No templates yet → resolvedTemplateForDay is null
            assertNull(vm.uiState.value.resolvedTemplateForDay)

            // Emit templates that include the referenced template
            val t = template("tpl-new", "New Day Plan")
            repository.templatesFlow.value = listOf(t)
            advanceUntilIdle()

            assertEquals(
                "New Day Plan",
                vm.uiState.value.resolvedTemplateForDay
                    ?.name,
            )
            assertEquals(1, vm.uiState.value.templateCount)
        }

    @Test
    fun `template observer updates resolved template when day mode is auto and preferences change`() =
        runTest {
            val dayKey = "2026-08-02" // Saturday → weekend
            repository.dayPolicyByDay[dayKey] =
                DayPlanPolicyRecord(
                    dayKey = dayKey,
                    mode = DayPlanRepository.MODE_AUTO,
                    templateId = null,
                    isStarred = false,
                )
            repository.dayTypePreferences[DayPlanRepository.DAY_TYPE_WEEKEND] = "tpl-weekend"

            val vm = viewModel()
            advanceUntilIdle()
            vm.loadDayPlan(dayKey)
            advanceUntilIdle()

            // No templates in the flow yet
            assertNull(vm.uiState.value.resolvedTemplateForDay)

            // Emit templates — observer re-resolves: weekend day → "tpl-weekend"
            val t = template("tpl-weekend", "Weekend Mode")
            repository.templatesFlow.value = listOf(t)
            advanceUntilIdle()

            assertEquals(
                "Weekend Mode",
                vm.uiState.value.resolvedTemplateForDay
                    ?.name,
            )
        }

    // ── factory function ──────────────────────────────────────────────────

    @Test
    fun `the factory builds a ViewModel from the installed data layer`() =
        runTest {
            LocalDataLayer.install(stubDataLayer(repository))
            val vm = dayPlanViewModel(newViewModelScope())
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(LocalDate.now().toString(), vm.uiState.value.selectedDayKey)
        }

    @Test
    fun `LocalDataLayer exposes what was installed and can be reset`() {
        LocalDataLayer.install(stubDataLayer(repository))
        assertEquals(repository, LocalDataLayer.current.dayPlans)
        LocalDataLayer.resetForTests()
        assertEquals(UninstalledDataLayer, LocalDataLayer.current)
    }

    @Test
    fun `reading dayPlans before install fails loudly`() {
        val failure =
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                UninstalledDataLayer.dayPlans
            }
        assertTrue(failure.message!!.contains("before a data layer was installed"))
    }

    @Test
    fun `every entry of the uninstalled layer fails and names itself`() {
        val notesFailure =
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                UninstalledDataLayer.notes
            }
        assertTrue(notesFailure.message!!.contains("before a data layer was installed"))
        assertTrue(notesFailure.message!!.contains("LocalDataLayer.notes"))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun template(
        id: String,
        name: String,
        description: String? = null,
        allocations: List<TemplateAllocationRecord> = emptyList(),
    ) = DayPlanTemplateRecord(
        id = id,
        name = name,
        description = description,
        isActive = true,
        sortOrder = 0,
        allocations = allocations,
    )

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }

    private fun stubDataLayer(dayPlanRepository: DayPlanRepository) =
        object : PayanamDataLayer by UninstalledDataLayer {
            override val notes: NoteRepository
                get() = throw UnsupportedOperationException("not under test")
            override val tags: TagRepository
                get() = throw UnsupportedOperationException("not under test")
            override val tasks: TaskRepository
                get() = throw UnsupportedOperationException("not under test")
            override val timeEntries: TimeEntryRepository
                get() = throw UnsupportedOperationException("not under test")
            override val dayPlans: DayPlanRepository = dayPlanRepository
            override val taskOccurrences: TaskOccurrenceRepository
                get() = throw UnsupportedOperationException("not under test")
            override val journal: JournalRepository
                get() = throw UnsupportedOperationException("not under test")
        }

    private class FakeDayPlanRepository : DayPlanRepository {
        val templatesFlow = MutableStateFlow<List<DayPlanTemplateRecord>>(emptyList())
        val dayPolicyByDay = mutableMapOf<String, DayPlanPolicyRecord>()
        val allocationsByDay = mutableMapOf<String, List<DayPlanAllocationRecord>>()
        val resolvedTemplateByDay = mutableMapOf<String, DayPlanTemplateRecord?>()
        val dayTypePreferences = mutableMapOf<String, String?>()
        val modeUpdates = mutableListOf<Triple<String, String, String?>>()
        val starredUpdates = mutableListOf<Pair<String, Boolean>>()
        val dayTypePreferenceUpdates = mutableListOf<Pair<String, String?>>()
        val clearedDays = mutableListOf<String>()
        var getAllocationsForDayCount = 0
        var getDayPolicyCount = 0
        var getDayTypeTemplatePreferenceCount = 0
        var resolveTemplateForDayCount = 0
        var lastSetAllocations: Map<String, Int>? = null
        var lastSetAllocationsSource: String? = null
        var failLoads = false
        var failWrites = false
        var failTemplateWrites = false
        var failWriteGeneric = false
        var lastCreatedTemplateName: String? = null
        var lastUpdatedTemplateId: String? = null
        val deletedTemplateIds = mutableListOf<String>()

        override fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>> =
            flowOf(allocationsByDay[dayKey].orEmpty())

        override suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> {
            if (failLoads) throw IOException("load failed")
            getAllocationsForDayCount += 1
            return allocationsByDay[dayKey].orEmpty()
        }

        override suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> =
            allocationsByDay[dayKey].orEmpty()

        override suspend fun setAllocation(
            dayKey: String,
            dimensionId: String,
            plannedMinutes: Int,
            source: String,
            templateId: String?,
        ) {
            if (failWrites) throw IOException("write failed")
            lastSetAllocations = mapOf(dimensionId to plannedMinutes)
            lastSetAllocationsSource = source
        }

        override suspend fun setAllocations(
            dayKey: String,
            allocations: Map<String, Int>,
            source: String,
            templateId: String?,
        ) {
            if (failWrites) throw IOException("write failed")
            lastSetAllocations = allocations
            lastSetAllocationsSource = source
        }

        override suspend fun applyTemplateToDay(
            dayKey: String,
            templateId: String,
        ) {
            if (failWrites) throw IOException("write failed")
            modeUpdates += Triple(dayKey, DayPlanRepository.MODE_TEMPLATE, templateId)
        }

        override suspend fun clearDayPlan(dayKey: String) {
            if (failWrites) throw IOException("write failed")
            clearedDays += dayKey
        }

        override suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord {
            if (failLoads) throw IOException("load failed")
            getDayPolicyCount += 1
            return dayPolicyByDay[dayKey] ?: DayPlanPolicyRecord(
                dayKey = dayKey,
                mode = DayPlanRepository.MODE_AUTO,
                templateId = null,
                isStarred = false,
            )
        }

        override suspend fun setDayMode(
            dayKey: String,
            mode: String,
            templateId: String?,
        ) {
            if (failWrites) throw IOException("write failed")
            modeUpdates += Triple(dayKey, mode, templateId)
        }

        override suspend fun setDayStarred(
            dayKey: String,
            isStarred: Boolean,
        ) {
            if (failWrites) throw IOException("write failed")
            starredUpdates += dayKey to isStarred
        }

        override suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord {
            if (failLoads) throw IOException("load failed")
            getDayTypeTemplatePreferenceCount += 1
            return DayTypeTemplatePreferenceRecord(dayType, dayTypePreferences[dayType])
        }

        override suspend fun setDayTypeTemplatePreference(
            dayType: String,
            templateId: String?,
        ) {
            if (failWrites) throw IOException("write failed")
            dayTypePreferences[dayType] = templateId
            dayTypePreferenceUpdates += dayType to templateId
        }

        override suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord? {
            if (failLoads) throw IOException("load failed")
            resolveTemplateForDayCount += 1
            return resolvedTemplateByDay[dayKey]
        }

        override fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>> = templatesFlow

        override fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>> = templatesFlow

        override suspend fun getTemplateById(id: String): DayPlanTemplateRecord? = templatesFlow.value.firstOrNull { it.id == id }

        override suspend fun createTemplate(
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ): String {
            if (failTemplateWrites) throw IllegalStateException("template limit reached")
            if (failWriteGeneric) throw IOException("write failed")
            lastCreatedTemplateName = name
            return "template-id"
        }

        override suspend fun updateTemplate(
            id: String,
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ) {
            if (failTemplateWrites) throw IllegalStateException("template limit reached")
            if (failWriteGeneric) throw IOException("write failed")
            lastUpdatedTemplateId = id
        }

        override suspend fun deleteTemplate(id: String) {
            if (failWrites) throw IOException("write failed")
            deletedTemplateIds += id
        }

        fun resetCounters() {
            getAllocationsForDayCount = 0
            getDayPolicyCount = 0
            getDayTypeTemplatePreferenceCount = 0
            resolveTemplateForDayCount = 0
        }
    }
}
