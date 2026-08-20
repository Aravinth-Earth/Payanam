//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.DayPlanAllocationRecord
import io.payanam.domain.repository.DayPlanPolicyRecord
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.domain.repository.DayTypeTemplatePreferenceRecord
import io.payanam.domain.repository.TemplateAllocationRecord
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
/**
 * DayPlanViewModelModeTransitionTest.
 */
class DayPlanViewModelModeTransitionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val logger: UnifiedLogger? by lazy { runCatching { UnifiedLogger.getInstance() }.getOrNull() }
    private lateinit var repository: FakeDayPlanRepository
    private lateinit var viewModel: DayPlanViewModel

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        Dispatchers.setMain(testDispatcher)
        repository = FakeDayPlanRepository()
        viewModel = DayPlanViewModel(repository)
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
     * Save day plan custom mode persists allocations starred and day type preferences.
     */
    fun saveDayPlan_customMode_persists_allocations_starred_and_day_type_preferences() = runTest {
        /** Day key. */
        val dayKey = "2026-02-21"
        /** Allocations. */
        val allocations = mapOf("career_work" to 120)
        /** Day type template by type. */
        val dayTypeTemplateByType = mapOf(
            DayPlanRepository.DAY_TYPE_WEEKDAY to "tpl-weekday",
            DayPlanRepository.DAY_TYPE_WEEKEND to "tpl-weekend",
            DayPlanRepository.DAY_TYPE_STARRED to "tpl-starred",
        )

        viewModel.saveDayPlan(
            dayKey = dayKey,
            mode = DayPlanRepository.MODE_CUSTOM,
            allocations = allocations,
            templateId = null,
            isStarred = true,
            dayTypeTemplateByType = dayTypeTemplateByType,
        )
        /** Advance until idle. */
        advanceUntilIdle()

        logger?.d("DayPlanViewModelModeTransitionTest.custom", "Verified custom mode write path")
        /** Assert true. */
        assertTrue(repository.starredUpdates.contains(dayKey to true))
        /** Assert equals. */
        assertEquals(allocations, repository.lastSetAllocations)
        /** Assert equals. */
        assertEquals(DayPlanRepository.SOURCE_MANUAL, repository.lastSetAllocationsSource)
        /** Assert equals. */
        assertEquals(
            /** List of. */
            listOf(
                DayPlanRepository.DAY_TYPE_WEEKDAY to "tpl-weekday",
                DayPlanRepository.DAY_TYPE_WEEKEND to "tpl-weekend",
                DayPlanRepository.DAY_TYPE_STARRED to "tpl-starred",
            ),
            repository.dayTypePreferenceUpdates,
        )
    }

    @Test
    /**
     * Save day plan template mode without template id resets to auto and clears plan.
     */
    fun saveDayPlan_templateMode_withoutTemplateId_resets_to_auto_and_clears_plan() = runTest {
        /** Day key. */
        val dayKey = "2026-02-22"

        viewModel.saveDayPlan(
            dayKey = dayKey,
            mode = DayPlanRepository.MODE_TEMPLATE,
            allocations = emptyMap(),
            templateId = null,
            isStarred = false,
            dayTypeTemplateByType = emptyMap(),
        )
        /** Advance until idle. */
        advanceUntilIdle()

        logger?.d("DayPlanViewModelModeTransitionTest.templateNull", "Verified template-null fallback path")
        /** Assert true. */
        assertTrue(repository.modeUpdates.contains(Triple(dayKey, DayPlanRepository.MODE_AUTO, null)))
        /** Assert true. */
        assertTrue(repository.clearedDays.contains(dayKey))
    }

    @Test
    /**
     * Load day plan hydrates ui state from policy allocations and resolved template.
     */
    fun loadDayPlan_hydrates_ui_state_from_policy_allocations_and_resolved_template() = runTest {
        /** Day key. */
        val dayKey = "2026-02-23"
        /** Resolved template. */
        val resolvedTemplate = DayPlanTemplateRecord(
            id = "tpl-resolved",
            name = "Resolved Day",
            description = null,
            isActive = true,
            sortOrder = 0,
            allocations = listOf(
                /** Template allocation record. */
                TemplateAllocationRecord(
                    id = "ta-1",
                    templateId = "tpl-resolved",
                    dimensionId = "career_work",
                    plannedMinutes = 180,
                ),
            ),
        )
        repository.dayPolicyByDay[dayKey] = DayPlanPolicyRecord(
            dayKey = dayKey,
            mode = DayPlanRepository.MODE_TEMPLATE,
            templateId = "tpl-resolved",
            isStarred = true,
        )
        repository.allocationsByDay[dayKey] = listOf(
            /** Day plan allocation record. */
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

        viewModel.loadDayPlan(dayKey)
        /** Advance until idle. */
        advanceUntilIdle()

        /** State. */
        val state = viewModel.uiState.value
        /** Assert equals. */
        assertEquals(dayKey, state.selectedDayKey)
        /** Assert equals. */
        assertEquals(DayPlanRepository.MODE_TEMPLATE, state.dayMode)
        /** Assert equals. */
        assertEquals("tpl-resolved", state.selectedDayTemplateId)
        /** Assert true. */
        assertTrue(state.isStarredDay)
        /** Assert equals. */
        assertEquals(180, state.dayAllocations["career_work"])
        /** Assert equals. */
        assertEquals("tpl-weekday", state.dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKDAY])
        /** Assert equals. */
        assertEquals("Resolved Day", state.resolvedTemplateForDay?.name)
    }

    @Test
    /**
     * Load day plan duplicate request while in flight only loads once.
     */
    fun loadDayPlan_duplicateRequestWhileInFlight_onlyLoadsOnce() = runTest {
        /** Day key. */
        val dayKey = "2026-02-24"
        /** Advance until idle. */
        advanceUntilIdle()
        repository.resetCounters()

        viewModel.loadDayPlan(dayKey)
        viewModel.loadDayPlan(dayKey)
        /** Advance until idle. */
        advanceUntilIdle()

        /** Assert equals. */
        assertEquals(1, repository.getAllocationsForDayCount)
        /** Assert equals. */
        assertEquals(1, repository.getDayPolicyCount)
        /** Assert equals. */
        assertEquals(3, repository.getDayTypeTemplatePreferenceCount)
        /** Assert equals. */
        assertEquals(1, repository.resolveTemplateForDayCount)
    }
}

private class FakeDayPlanRepository : DayPlanRepository {
    /** Templates flow. */
    val templatesFlow = MutableStateFlow<List<DayPlanTemplateRecord>>(emptyList())
    /** Day policy by day. */
    val dayPolicyByDay = mutableMapOf<String, DayPlanPolicyRecord>()
    /** Allocations by day. */
    val allocationsByDay = mutableMapOf<String, List<DayPlanAllocationRecord>>()
    /** Resolved template by day. */
    val resolvedTemplateByDay = mutableMapOf<String, DayPlanTemplateRecord?>()
    /** Day type preferences. */
    val dayTypePreferences = mutableMapOf<String, String?>()
    /** Mode updates. */
    val modeUpdates = mutableListOf<Triple<String, String, String?>>()
    /** Starred updates. */
    val starredUpdates = mutableListOf<Pair<String, Boolean>>()
    /** Day type preference updates. */
    val dayTypePreferenceUpdates = mutableListOf<Pair<String, String?>>()
    /** Cleared days. */
    val clearedDays = mutableListOf<String>()
    /** Get allocations for day count. */
    var getAllocationsForDayCount = 0
    /** Get day policy count. */
    var getDayPolicyCount = 0
    /** Get day type template preference count. */
    var getDayTypeTemplatePreferenceCount = 0
    /** Resolve template for day count. */
    var resolveTemplateForDayCount = 0
    /** Last set allocations. */
    var lastSetAllocations: Map<String, Int>? = null
    /** Last set allocations source. */
    var lastSetAllocationsSource: String? = null

    override fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>> = flowOf(allocationsByDay[dayKey].orEmpty())

    override suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> {
        getAllocationsForDayCount += 1
        return allocationsByDay[dayKey].orEmpty()
    }

    override suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> = allocationsByDay[dayKey].orEmpty()

    override suspend fun setAllocation(
        /** Day key. */
        dayKey: String,
        /** Dimension id. */
        dimensionId: String,
        /** Planned minutes. */
        plannedMinutes: Int,
        /** Source. */
        source: String,
        templateId: String?,
    ) {
        lastSetAllocations = mapOf(dimensionId to plannedMinutes)
        lastSetAllocationsSource = source
    }

    override suspend fun setAllocations(
        /** Day key. */
        dayKey: String,
        allocations: Map<String, Int>,
        /** Source. */
        source: String,
        templateId: String?,
    ) {
        lastSetAllocations = allocations
        lastSetAllocationsSource = source
    }

    override suspend fun applyTemplateToDay(dayKey: String, templateId: String) {
        modeUpdates += Triple(dayKey, DayPlanRepository.MODE_TEMPLATE, templateId)
    }

    override suspend fun clearDayPlan(dayKey: String) {
        clearedDays += dayKey
    }

    override suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord {
        getDayPolicyCount += 1
        return dayPolicyByDay[dayKey] ?: DayPlanPolicyRecord(
            dayKey = dayKey,
            mode = DayPlanRepository.MODE_AUTO,
            templateId = null,
            isStarred = false,
        )
    }

    override suspend fun setDayMode(dayKey: String, mode: String, templateId: String?) {
        modeUpdates += Triple(dayKey, mode, templateId)
    }

    override suspend fun setDayStarred(dayKey: String, isStarred: Boolean) {
        starredUpdates += dayKey to isStarred
    }

    override suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord {
        getDayTypeTemplatePreferenceCount += 1
        return DayTypeTemplatePreferenceRecord(dayType, dayTypePreferences[dayType])
    }

    override suspend fun setDayTypeTemplatePreference(dayType: String, templateId: String?) {
        dayTypePreferences[dayType] = templateId
        dayTypePreferenceUpdates += dayType to templateId
    }

    override suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord? {
        resolveTemplateForDayCount += 1
        return resolvedTemplateByDay[dayKey]
    }

    override fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>> = templatesFlow

    override fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>> = templatesFlow

    override suspend fun getTemplateById(id: String): DayPlanTemplateRecord? = templatesFlow.value.firstOrNull { it.id == id }

    override suspend fun createTemplate(
        /** Name. */
        name: String,
        description: String?,
        allocations: Map<String, Int>,
    ): String = "template-id"

    override suspend fun updateTemplate(
        /** Id. */
        id: String,
        /** Name. */
        name: String,
        description: String?,
        allocations: Map<String, Int>,
    ) = Unit

    override suspend fun deleteTemplate(id: String) = Unit

    /**
     * Reset counters.
     */
    fun resetCounters() {
        getAllocationsForDayCount = 0
        getDayPolicyCount = 0
        getDayTypeTemplatePreferenceCount = 0
        resolveTemplateForDayCount = 0
    }
}
