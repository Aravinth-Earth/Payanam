//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanTemplateRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * DayPlanUiState.
 */
data class DayPlanUiState(
    /** Templates. */
    val templates: List<DayPlanTemplateRecord> = emptyList(),
    /** Selected template. */
    val selectedTemplate: DayPlanTemplateRecord? = null,
    /** Is editing template. */
    val isEditingTemplate: Boolean = false,
    /** Is creating new. */
    val isCreatingNew: Boolean = false,
    /** Template name. */
    val templateName: String = "",
    /** Template description. */
    val templateDescription: String = "",
    /** Template allocations. */
    val templateAllocations: Map<String, Int> = emptyMap(),
    /** Day allocations. */
    val dayAllocations: Map<String, Int> = emptyMap(),
    /** Selected day key. */
    val selectedDayKey: String = LocalDate.now().toString(),
    /** Day mode. */
    val dayMode: String = DayPlanRepository.MODE_AUTO,
    /** Selected day template id. */
    val selectedDayTemplateId: String? = null,
    /** Is starred day. */
    val isStarredDay: Boolean = false,
    /** Day type template by type. */
    val dayTypeTemplateByType: Map<String, String?> = mapOf(
        DayPlanRepository.DAY_TYPE_WEEKDAY to null,
        DayPlanRepository.DAY_TYPE_WEEKEND to null,
        DayPlanRepository.DAY_TYPE_STARRED to null,
    ),
    /** Resolved template for day. */
    val resolvedTemplateForDay: DayPlanTemplateRecord? = null,
    /** Template count. */
    val templateCount: Int = 0,
    /** Max templates. */
    val maxTemplates: Int = DayPlanRepository.MAX_TEMPLATE_COUNT,
    /** Error message. */
    val errorMessage: String? = null,
    /** Is loading. */
    val isLoading: Boolean = false,
)

@HiltViewModel
/**
 * DayPlanViewModel.
 */
class DayPlanViewModel @Inject constructor(
    private val dayPlanRepository: DayPlanRepository,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(DayPlanUiState())
    /** Ui state. */
    val uiState: StateFlow<DayPlanUiState> = _uiState.asStateFlow()
    private var inFlightDayKey: String? = null

    init {
        /** Observe templates. */
        observeTemplates()
        /** Load day plan. */
        loadDayPlan(LocalDate.now().toString())
    }

    private fun observeTemplates() {
        logger.d("DayPlanViewModel.observeTemplates", "Subscribing to active templates")
        viewModelScope.launch {
            dayPlanRepository.observeActiveTemplates().collect { templates ->
                _uiState.update { state ->
                    /** Resolved template id. */
                    val resolvedTemplateId = resolveTemplateIdForDay(
                        dayKey = state.selectedDayKey,
                        mode = state.dayMode,
                        selectedTemplateId = state.selectedDayTemplateId,
                        isStarredDay = state.isStarredDay,
                        dayTypeTemplateByType = state.dayTypeTemplateByType,
                    )
                    state.copy(
                        templates = templates,
                        templateCount = templates.size,
                        resolvedTemplateForDay = templates.firstOrNull { template ->
                            template.id == resolvedTemplateId
                        },
                    )
                }
            }
        }
    }

    /**
     * Load day plan.
     */
    fun loadDayPlan(dayKey: String) {
        viewModelScope.launch {
            /** If. */
            if (inFlightDayKey == dayKey) {
                logger.d(
                    "DayPlanViewModel.loadDayPlan",
                    "Skipped duplicate in-flight day plan load",
                    /** Map of. */
                    mapOf("dayKey" to dayKey),
                )
                return@launch
            }
            inFlightDayKey = dayKey
            try {
                /** Started at. */
                val startedAt = LocalDateTime.now()
                /** Val. */
                val (allocations, policy, dayTypeTemplateByType) = coroutineScope {
                    /** Allocations deferred. */
                    val allocationsDeferred = async {
                        dayPlanRepository.getAllocationsForDay(dayKey)
                            .associate { it.dimensionId to it.plannedMinutes }
                    }
                    /** Policy deferred. */
                    val policyDeferred = async { dayPlanRepository.getDayPolicy(dayKey) }
                    /** Day type preferences deferred. */
                    val dayTypePreferencesDeferred = async {
                        /** List of. */
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
                    /** Triple. */
                    Triple(
                        allocationsDeferred.await(),
                        policyDeferred.await(),
                        dayTypePreferencesDeferred.await(),
                    )
                }
                /** Resolved template id. */
                val resolvedTemplateId = resolveTemplateIdForDay(
                    dayKey = dayKey,
                    mode = policy.mode,
                    selectedTemplateId = policy.templateId,
                    isStarredDay = policy.isStarred,
                    dayTypeTemplateByType = dayTypeTemplateByType,
                )
                /** Resolved template. */
                val resolvedTemplate = dayPlanRepository.resolveTemplateForDay(dayKey)
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
                    /** Map of. */
                    mapOf(
                        "dayKey" to dayKey,
                        "templateCount" to _uiState.value.templates.size.toString(),
                        "resolvedTemplateId" to (resolvedTemplate?.id ?: "null"),
                        "elapsedMs" to java.time.Duration.between(startedAt, LocalDateTime.now()).toMillis().toString(),
                    ),
                )
            } finally {
                inFlightDayKey = null
            }
        }
    }

    /**
     * Save day plan.
     */
    fun saveDayPlan(
        /** Day key. */
        dayKey: String,
        /** Mode. */
        mode: String,
        allocations: Map<String, Int>,
        templateId: String?,
        /** Is starred. */
        isStarred: Boolean,
        dayTypeTemplateByType: Map<String, String?>,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                dayPlanRepository.setDayStarred(dayKey = dayKey, isStarred = isStarred)
                dayTypeTemplateByType.forEach { (dayType, preferenceTemplateId) ->
                    dayPlanRepository.setDayTypeTemplatePreference(dayType, preferenceTemplateId)
                }
                /** When. */
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
                        /** If. */
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
                /** Load day plan. */
                loadDayPlan(dayKey)
                logger.i(
                    "DayPlanViewModel.saveDayPlan",
                    "Saved day plan mode",
                    /** Map of. */
                    mapOf("dayKey" to dayKey, "mode" to mode, "templateId" to (templateId ?: "null")),
                )
                _uiState.update { it.copy(isLoading = false) }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("DayPlanViewModel.saveDayPlan", "Failed to save day plan", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    /**
     * Clear day plan.
     */
    fun clearDayPlan(dayKey: String) {
        logger.i("DayPlanViewModel.clearDayPlan", "Clearing day plan", mapOf("dayKey" to dayKey))
        viewModelScope.launch {
            dayPlanRepository.clearDayPlan(dayKey)
            /** Load day plan. */
            loadDayPlan(dayKey)
        }
    }

    /**
     * Select template.
     */
    fun selectTemplate(id: String) {
        logger.d("DayPlanViewModel.selectTemplate", "Selecting template", mapOf("id" to id))
        viewModelScope.launch {
            /** Template. */
            val template = dayPlanRepository.getTemplateById(id)
            _uiState.update {
                it.copy(
                    selectedTemplate = template,
                    isEditingTemplate = false,
                    isCreatingNew = false,
                    templateName = template?.name ?: "",
                    templateDescription = template?.description ?: "",
                    templateAllocations = template?.allocations
                        ?.associate { a -> a.dimensionId to a.plannedMinutes }
                        ?: emptyMap(),
                )
            }
        }
    }

    /**
     * Start new template.
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
     * Start edit template.
     */
    fun startEditTemplate(id: String) {
        logger.d("DayPlanViewModel.startEditTemplate", "Starting template edit", mapOf("id" to id))
        viewModelScope.launch {
            /** Template. */
            val template = dayPlanRepository.getTemplateById(id)
            /** If. */
            if (template != null) {
                _uiState.update {
                    it.copy(
                        selectedTemplate = template,
                        isEditingTemplate = true,
                        isCreatingNew = false,
                        templateName = template.name,
                        templateDescription = template.description ?: "",
                        templateAllocations = template.allocations
                            .associate { a -> a.dimensionId to a.plannedMinutes },
                        errorMessage = null,
                    )
                }
            }
        }
    }

    /**
     * Set template name.
     */
    fun setTemplateName(name: String) {
        _uiState.update { it.copy(templateName = name, errorMessage = null) }
    }

    /**
     * Set template description.
     */
    fun setTemplateDescription(description: String) {
        _uiState.update { it.copy(templateDescription = description) }
    }

    /**
     * Set template allocation.
     */
    fun setTemplateAllocation(dimensionId: String, minutes: Int?) {
        _uiState.update { state ->
            /** Allocs. */
            val allocs = state.templateAllocations.toMutableMap()
            /** If. */
            if (minutes != null && minutes > 0) {
                allocs[dimensionId] = minutes
            } else {
                allocs.remove(dimensionId)
            }
            state.copy(templateAllocations = allocs)
        }
    }

    /**
     * Save template.
     */
    fun saveTemplate() {
        /** State. */
        val state = _uiState.value
        /** Name. */
        val name = state.templateName.trim()
        /** Total template minutes. */
        val totalTemplateMinutes = state.templateAllocations.values.sum()
        /** If. */
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Template name is required") }
            /** Return. */
            return
        }
        /** If. */
        if (totalTemplateMinutes > MAX_TEMPLATE_MINUTES_PER_DAY) {
            logger.w(
                "DayPlanViewModel.saveTemplate",
                "Rejected template save because total planned time exceeds one day",
                /** Map of. */
                mapOf("totalMinutes" to totalTemplateMinutes.toString()),
            )
            /** Return. */
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                /** If. */
                if (state.isCreatingNew) {
                    dayPlanRepository.createTemplate(
                        name = name,
                        description = state.templateDescription.ifBlank { null },
                        allocations = state.templateAllocations,
                    )
                    logger.i(
                        "DayPlanViewModel.saveTemplate",
                        "Created new template",
                        /** Map of. */
                        mapOf("name" to name),
                    )
                } else {
                    /** Id. */
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
                        /** Map of. */
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
     * Delete template.
     */
    fun deleteTemplate(id: String) {
        viewModelScope.launch {
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
     * Cancel editing.
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
     * Clear error.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        private const val MAX_TEMPLATE_MINUTES_PER_DAY = 24 * 60
    }
}

private fun resolveTemplateIdForDay(
    /** Day key. */
    dayKey: String,
    /** Mode. */
    mode: String,
    selectedTemplateId: String?,
    /** Is starred day. */
    isStarredDay: Boolean,
    dayTypeTemplateByType: Map<String, String?>,
): String? {
    /** If. */
    if (mode == DayPlanRepository.MODE_TEMPLATE) {
        return selectedTemplateId
    }
    /** If. */
    if (isStarredDay) {
        dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_STARRED]?.let { return it }
    }
    /** Day type. */
    val dayType = when (LocalDate.parse(dayKey).dayOfWeek.value) {
        6, 7 -> DayPlanRepository.DAY_TYPE_WEEKEND
        else -> DayPlanRepository.DAY_TYPE_WEEKDAY
    }
    return dayTypeTemplateByType[dayType]
}
