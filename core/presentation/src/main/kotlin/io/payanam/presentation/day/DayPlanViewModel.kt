//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.day

import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * UI state for the Day Plan screen: available templates, the selected/edited
 * template draft, the selected day's allocations and policy (auto/template/
 * custom mode, starred flag, weekday/weekend/starred template preferences),
 * the resolved effective template, and loading/error flags.
 */
data class DayPlanUiState(
    val templates: List<DayPlanTemplateRecord> = emptyList(),
    val selectedTemplate: DayPlanTemplateRecord? = null,
    val isEditingTemplate: Boolean = false,
    val isCreatingNew: Boolean = false,
    val templateName: String = "",
    val templateDescription: String = "",
    val templateAllocations: Map<String, Int> = emptyMap(),
    val dayAllocations: Map<String, Int> = emptyMap(),
    val selectedDayKey: String = LocalDate.now().toString(),
    val dayMode: String = DayPlanRepository.MODE_AUTO,
    val selectedDayTemplateId: String? = null,
    val isStarredDay: Boolean = false,
    val dayTypeTemplateByType: Map<String, String?> =
        mapOf(
            DayPlanRepository.DAY_TYPE_WEEKDAY to null,
            DayPlanRepository.DAY_TYPE_WEEKEND to null,
            DayPlanRepository.DAY_TYPE_STARRED to null,
        ),
    val resolvedTemplateForDay: DayPlanTemplateRecord? = null,
    val templateCount: Int = 0,
    val maxTemplates: Int = DayPlanRepository.MAX_TEMPLATE_COUNT,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
)

/**
 * ViewModel for the Day Plan screen: observes available templates, loads and
 * saves a day's plan (auto/template/custom mode with weekday/weekend/starred
 * preferences), and manages the template CRUD editor.
 */
class DayPlanViewModel(
    private val dayPlanRepository: DayPlanRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current
    private val _uiState = MutableStateFlow(DayPlanUiState())
    val uiState: StateFlow<DayPlanUiState> = _uiState.asStateFlow()
    private var inFlightDayKey: String? = null

    init {
        observeTemplates()
        loadDayPlan(LocalDate.now().toString())
    }

    private fun observeTemplates() {
        logger.d("DayPlanViewModel.observeTemplates", "Subscribing to active templates")
        scope.launch {
            dayPlanRepository.observeActiveTemplates().collect { templates ->
                _uiState.update { state ->
                    val resolvedTemplateId =
                        resolveTemplateIdForDay(
                            dayKey = state.selectedDayKey,
                            mode = state.dayMode,
                            selectedTemplateId = state.selectedDayTemplateId,
                            isStarredDay = state.isStarredDay,
                            dayTypeTemplateByType = state.dayTypeTemplateByType,
                        )
                    state.copy(
                        templates = templates,
                        templateCount = templates.size,
                        resolvedTemplateForDay =
                            templates.firstOrNull { template ->
                                template.id == resolvedTemplateId
                            },
                    )
                }
            }
        }
    }

    /**
     * Loads the full day-plan context for [dayKey] (allocations, mode policy, and
     * the three day-type template preferences in parallel) and resolves which
     * template is effective for that day.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun loadDayPlan(dayKey: String) {
        scope.launch {
            if (inFlightDayKey == dayKey) {
                logger.d(
                    "DayPlanViewModel.loadDayPlan",
                    "Skipped duplicate in-flight day plan load",
                    mapOf("dayKey" to dayKey),
                )
                return@launch
            }
            inFlightDayKey = dayKey
            try {
                val startedAt = LocalDateTime.now()
                val (allocations, policy, dayTypeTemplateByType) =
                    coroutineScope {
                        val allocationsDeferred =
                            async {
                                dayPlanRepository
                                    .getAllocationsForDay(dayKey)
                                    .associate { it.dimensionId to it.plannedMinutes }
                            }
                        val policyDeferred = async { dayPlanRepository.getDayPolicy(dayKey) }
                        val dayTypePreferencesDeferred =
                            async {
                                listOf(
                                    DayPlanRepository.DAY_TYPE_WEEKDAY,
                                    DayPlanRepository.DAY_TYPE_WEEKEND,
                                    DayPlanRepository.DAY_TYPE_STARRED,
                                ).map { dayType ->
                                    dayPlanRepository.getDayTypeTemplatePreference(dayType)
                                }.associate { preference ->
                                    preference.dayType to preference.templateId
                                }
                            }
                        Triple(
                            allocationsDeferred.await(),
                            policyDeferred.await(),
                            dayTypePreferencesDeferred.await(),
                        )
                    }
                val resolvedTemplateId =
                    resolveTemplateIdForDay(
                        dayKey = dayKey,
                        mode = policy.mode,
                        selectedTemplateId = policy.templateId,
                        isStarredDay = policy.isStarred,
                        dayTypeTemplateByType = dayTypeTemplateByType,
                    )
                val resolvedTemplate =
                    dayPlanRepository.resolveTemplateForDay(dayKey)
                        ?: _uiState.value.templates.firstOrNull { it.id == resolvedTemplateId }
                        ?: resolvedTemplateId?.let { dayPlanRepository.getTemplateById(it) }
                _uiState.update {
                    it.copy(
                        selectedDayKey = dayKey,
                        dayAllocations = allocations,
                        dayMode = policy.mode,
                        selectedDayTemplateId = policy.templateId,
                        isStarredDay = policy.isStarred,
                        dayTypeTemplateByType = dayTypeTemplateByType,
                        resolvedTemplateForDay = resolvedTemplate,
                    )
                }
                logger.d(
                    "DayPlanViewModel.loadDayPlan",
                    "Loaded day plan context",
                    mapOf(
                        "dayKey" to dayKey,
                        "templateCount" to
                            _uiState.value.templates.size
                                .toString(),
                        "resolvedTemplateId" to (resolvedTemplate?.id ?: "null"),
                        "elapsedMs" to
                            java.time.Duration
                                .between(startedAt, LocalDateTime.now())
                                .toMillis()
                                .toString(),
                    ),
                )
            } catch (e: Exception) {
                logger.e("DayPlanViewModel.loadDayPlan", "Failed to load day plan", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            } finally {
                inFlightDayKey = null
            }
        }
    }

    /**
     * Persists a full day-plan edit for [dayKey]: starred flag, per-day-type
     * template preferences, and then the mode-specific payload (custom
     * allocations, applied template, or cleared back to auto).
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun saveDayPlan(
        dayKey: String,
        mode: String,
        allocations: Map<String, Int>,
        templateId: String?,
        isStarred: Boolean,
        dayTypeTemplateByType: Map<String, String?>,
    ) {
        scope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                dayPlanRepository.setDayStarred(dayKey = dayKey, isStarred = isStarred)
                dayTypeTemplateByType.forEach { (dayType, preferenceTemplateId) ->
                    dayPlanRepository.setDayTypeTemplatePreference(dayType, preferenceTemplateId)
                }
                when (mode) {
                    DayPlanRepository.MODE_CUSTOM -> {
                        dayPlanRepository.setAllocations(
                            dayKey = dayKey,
                            allocations = allocations,
                            source = DayPlanRepository.SOURCE_MANUAL,
                            templateId = null,
                        )
                    }

                    DayPlanRepository.MODE_TEMPLATE -> {
                        if (!templateId.isNullOrBlank()) {
                            dayPlanRepository.applyTemplateToDay(dayKey = dayKey, templateId = templateId)
                        } else {
                            dayPlanRepository.setDayMode(dayKey = dayKey, mode = DayPlanRepository.MODE_AUTO)
                            dayPlanRepository.clearDayPlan(dayKey)
                        }
                    }

                    else -> {
                        dayPlanRepository.setDayMode(dayKey = dayKey, mode = DayPlanRepository.MODE_AUTO)
                        dayPlanRepository.clearDayPlan(dayKey)
                    }
                }
                loadDayPlan(dayKey)
                logger.i(
                    "DayPlanViewModel.saveDayPlan",
                    "Saved day plan mode",
                    mapOf("dayKey" to dayKey, "mode" to mode, "templateId" to (templateId ?: "null")),
                )
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                logger.e("DayPlanViewModel.saveDayPlan", "Failed to save day plan", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    /**
     * Removes all planned allocations for [dayKey] and reloads its context.
     */
    fun clearDayPlan(dayKey: String) {
        logger.i("DayPlanViewModel.clearDayPlan", "Clearing day plan", mapOf("dayKey" to dayKey))
        scope.launch {
            dayPlanRepository.clearDayPlan(dayKey)
            loadDayPlan(dayKey)
        }
    }

    /**
     * Selects [id] into the viewer (name/description/allocation draft fields),
     * without entering edit mode.
     */
    fun selectTemplate(id: String) {
        logger.d("DayPlanViewModel.selectTemplate", "Selecting template", mapOf("id" to id))
        scope.launch {
            val template = dayPlanRepository.getTemplateById(id)
            _uiState.update {
                it.copy(
                    selectedTemplate = template,
                    isEditingTemplate = false,
                    isCreatingNew = false,
                    templateName = template?.name ?: "",
                    templateDescription = template?.description ?: "",
                    templateAllocations =
                        template
                            ?.allocations
                            ?.associate { a -> a.dimensionId to a.plannedMinutes }
                            ?: emptyMap(),
                )
            }
        }
    }

    /**
     * Opens an empty template editor for creating a brand-new template.
     */
    fun startNewTemplate() {
        _uiState.update {
            it.copy(
                selectedTemplate = null,
                isEditingTemplate = true,
                isCreatingNew = true,
                templateName = "",
                templateDescription = "",
                templateAllocations = emptyMap(),
                errorMessage = null,
            )
        }
    }

    /**
     * Opens the editor pre-filled with template [id]'s current values for editing.
     */
    fun startEditTemplate(id: String) {
        logger.d("DayPlanViewModel.startEditTemplate", "Starting template edit", mapOf("id" to id))
        scope.launch {
            val template = dayPlanRepository.getTemplateById(id)
            if (template != null) {
                _uiState.update {
                    it.copy(
                        selectedTemplate = template,
                        isEditingTemplate = true,
                        isCreatingNew = false,
                        templateName = template.name,
                        templateDescription = template.description ?: "",
                        templateAllocations =
                            template.allocations
                                .associate { a -> a.dimensionId to a.plannedMinutes },
                        errorMessage = null,
                    )
                }
            }
        }
    }

    /**
     * Updates the in-progress template's name (clearing any prior error).
     */
    fun setTemplateName(name: String) {
        _uiState.update { it.copy(templateName = name, errorMessage = null) }
    }

    /**
     * Updates the in-progress template's description.
     */
    fun setTemplateDescription(description: String) {
        _uiState.update { it.copy(templateDescription = description) }
    }

    /**
     * Updates one dimension's planned minutes in the draft (null/zero removes it).
     */
    fun setTemplateAllocation(
        dimensionId: String,
        minutes: Int?,
    ) {
        _uiState.update { state ->
            val allocs = state.templateAllocations.toMutableMap()
            if (minutes != null && minutes > 0) {
                allocs[dimensionId] = minutes
            } else {
                allocs.remove(dimensionId)
            }
            state.copy(templateAllocations = allocs)
        }
    }

    /**
     * Validates (non-blank name, total ≤ 24h) and persists the draft — creating
     * a new template or updating the selected one — then closes the editor.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun saveTemplate() {
        val state = _uiState.value
        val name = state.templateName.trim()
        val totalTemplateMinutes = state.templateAllocations.values.sum()
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Template name is required") }
            return
        }
        if (totalTemplateMinutes > MAX_TEMPLATE_MINUTES_PER_DAY) {
            logger.w(
                "DayPlanViewModel.saveTemplate",
                "Rejected template save because total planned time exceeds one day",
                mapOf("totalMinutes" to totalTemplateMinutes.toString()),
            )
            return
        }

        scope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                if (state.isCreatingNew) {
                    dayPlanRepository.createTemplate(
                        name = name,
                        description = state.templateDescription.ifBlank { null },
                        allocations = state.templateAllocations,
                    )
                    logger.i(
                        "DayPlanViewModel.saveTemplate",
                        "Created new template",
                        mapOf("name" to name),
                    )
                } else {
                    val id = state.selectedTemplate?.id ?: return@launch
                    dayPlanRepository.updateTemplate(
                        id = id,
                        name = name,
                        description = state.templateDescription.ifBlank { null },
                        allocations = state.templateAllocations,
                    )
                    logger.i(
                        "DayPlanViewModel.saveTemplate",
                        "Updated template",
                        mapOf("id" to id, "name" to name),
                    )
                }
                _uiState.update {
                    it.copy(
                        isEditingTemplate = false,
                        isCreatingNew = false,
                        isLoading = false,
                    )
                }
            } catch (e: IllegalStateException) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message,
                        isLoading = false,
                    )
                }
                logger.w(
                    "DayPlanViewModel.saveTemplate",
                    "Failed to save template: ${e.message}",
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to save template",
                        isLoading = false,
                    )
                }
                logger.e("DayPlanViewModel.saveTemplate", "Unexpected error saving template", e)
            }
        }
    }

    /**
     * Deletes template [id] and clears any open selection/editor state.
     */
    fun deleteTemplate(id: String) {
        scope.launch {
            dayPlanRepository.deleteTemplate(id)
            _uiState.update {
                it.copy(
                    selectedTemplate = null,
                    isEditingTemplate = false,
                    isCreatingNew = false,
                )
            }
            logger.i("DayPlanViewModel.deleteTemplate", "Deleted template", mapOf("id" to id))
        }
    }

    /**
     * Closes the template editor/viewer discarding unsaved draft changes.
     */
    fun cancelEditing() {
        _uiState.update {
            it.copy(
                isEditingTemplate = false,
                isCreatingNew = false,
                errorMessage = null,
            )
        }
    }

    /**
     * Clears the current error message shown on the screen.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        private const val MAX_TEMPLATE_MINUTES_PER_DAY = 24 * 60
    }

    /**
     * Cancels the scope this ViewModel runs on.
     *
     * The platform calls this at the point it drops the ViewModel, so the collectors started here stop with
     * it. Through the default factory the scope is this ViewModel's own, which is the case [dispose] exists
     * for. A caller that passed its own [scope] has that scope cancelled here too - it is the same scope the
     * ViewModel is running on, so it cannot be released selectively - and must not reuse it afterwards.
     */
    fun dispose() {
        scope.cancel()
    }
}

private fun resolveTemplateIdForDay(
    dayKey: String,
    mode: String,
    selectedTemplateId: String?,
    isStarredDay: Boolean,
    dayTypeTemplateByType: Map<String, String?>,
): String? {
    if (mode == DayPlanRepository.MODE_TEMPLATE) {
        return selectedTemplateId
    }
    if (isStarredDay) {
        dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_STARRED]?.let { return it }
    }
    val dayType =
        when (LocalDate.parse(dayKey).dayOfWeek.value) {
            6, 7 -> DayPlanRepository.DAY_TYPE_WEEKEND
            else -> DayPlanRepository.DAY_TYPE_WEEKDAY
        }
    return dayTypeTemplateByType[dayType]
}

/**
 * The one-line entry point a platform's destination body calls to get the DayPlan ViewModel.
 *
 * The repositories come from [LocalDataLayer], which the platform installed at startup with its own
 * implementation. Call sites on both platforms are therefore identical and contain no wiring.
 */
fun dayPlanViewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): DayPlanViewModel =
    DayPlanViewModel(
        dayPlanRepository = LocalDataLayer.current.dayPlans,
        scope = scope,
    )
