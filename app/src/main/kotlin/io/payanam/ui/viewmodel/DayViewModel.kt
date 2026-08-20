//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DayJournalEntry
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.JournalRepository
import io.payanam.shared.journal.JournalReflectionContracts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import javax.inject.Inject

/**
 * DayTab.
 */
enum class DayTab {
    /** Summary. */
    SUMMARY,
}

/**
 * DayUiState.
 */
data class DayUiState(
    /** Is loading. */
    val isLoading: Boolean = true,
    /** Selected date. */
    val selectedDate: LocalDate = LocalDate.now(),
    /** Selected tab. */
    val selectedTab: DayTab = DayTab.SUMMARY,
    /** Journal entry. */
    val journalEntry: DayJournalEntry? = null,
    /** Overall responses. */
    val overallResponses: Map<String, String> = emptyMap(),
    /** Dimension responses. */
    val dimensionResponses: Map<String, Map<String, String>> = emptyMap(),
    /** Pending journal save dates. */
    val pendingJournalSaveDates: Set<LocalDate> = emptySet(),
    /** Last saved journal date. */
    val lastSavedJournalDate: LocalDate? = null,
)

/** Overall journal prompts. */
val OVERALL_JOURNAL_PROMPTS = JournalReflectionContracts.overallPrompts.map { it.key to it.prompt }

/** Dimension journal prompts. */
val DIMENSION_JOURNAL_PROMPTS = JournalReflectionContracts.dimensionPrompts.map { it.key to it.prompt }

@HiltViewModel
/**
 * DayViewModel.
 */
class DayViewModel @Inject constructor(
    private val journalRepository: JournalRepository,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val pendingJournalSaves = mutableMapOf<JournalResponseKey, Job>()

    private val _uiState = MutableStateFlow(DayUiState())
    /** Ui state. */
    val uiState: StateFlow<DayUiState> = _uiState.asStateFlow()

    private val displayDateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    private val isoDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val journalSaveDebounceMillis = 500L

    private data class JournalResponseKey(
        /** Date string. */
        val dateString: String,
        /** Scope. */
        val scope: String,
        /** Dimension key. */
        val dimensionKey: String?,
        /** Prompt key. */
        val promptKey: String,
    )

    init {
        /** Load day data. */
        loadDayData()
    }

    /**
     * Previous day.
     */
    fun previousDay() {
        /** New date. */
        val newDate = _uiState.value.selectedDate.minusDays(1)
        logger.d("DayViewModel.previousDay", "Navigating to previous day", mapOf("date" to newDate.toString()))
        _uiState.update { it.copy(selectedDate = newDate) }
        /** Load day data. */
        loadDayData()
    }

    /**
     * Next day.
     */
    fun nextDay() {
        /** Current date. */
        val currentDate = _uiState.value.selectedDate
        /** Today. */
        val today = LocalDate.now()
        /** If. */
        if (!currentDate.isBefore(today)) {
            logger.i(
                "DayViewModel.nextDay",
                "Blocked navigation beyond today",
                /** Map of. */
                mapOf("selectedDate" to currentDate.toString(), "today" to today.toString()),
            )
            /** Return. */
            return
        }

        /** New date. */
        val newDate = currentDate.plusDays(1)
        logger.d("DayViewModel.nextDay", "Navigating to next day", mapOf("date" to newDate.toString()))
        _uiState.update { it.copy(selectedDate = newDate) }
        /** Load day data. */
        loadDayData()
    }

    /**
     * Go to today.
     */
    fun goToToday() {
        logger.d("DayViewModel.goToToday", "Navigating to today")
        _uiState.update { it.copy(selectedDate = LocalDate.now()) }
        /** Load day data. */
        loadDayData()
    }

    /**
     * Select date.
     */
    fun selectDate(date: LocalDate) {
        /** Today. */
        val today = LocalDate.now()
        /** Selected date. */
        val selectedDate = if (date.isAfter(today)) today else date
        logger.d(
            "DayViewModel.selectDate",
            "Date selected",
            /** Map of. */
            mapOf(
                "requestedDate" to date.toString(),
                "selectedDate" to selectedDate.toString(),
                "today" to today.toString(),
            ),
        )
        _uiState.update { it.copy(selectedDate = selectedDate) }
        /** Load day data. */
        loadDayData()
    }

    /**
     * Select tab.
     */
    fun selectTab(tab: DayTab) {
        logger.d("DayViewModel.selectTab", "Tab selected", mapOf("tab" to tab.name))
        _uiState.update { it.copy(selectedTab = DayTab.SUMMARY) }
    }

    /**
     * Get formatted date.
     */
    fun getFormattedDate(): String = _uiState.value.selectedDate.format(displayDateFormatter)

    /**
     * Is today.
     */
    fun isToday(): Boolean = _uiState.value.selectedDate == LocalDate.now()

    /**
     * Update overall response.
     */
    fun updateOverallResponse(sourceDate: LocalDate, promptKey: String, response: String) {
        logger.d(
            "DayViewModel.updateOverallResponse",
            "Updating response",
            /** Map of. */
            mapOf(
                "promptKey" to promptKey,
                "length" to response.length,
                "sourceDate" to sourceDate.toString(),
                "selectedDate" to _uiState.value.selectedDate.toString(),
            ),
        )

        _uiState.update { state ->
            /** If. */
            if (state.selectedDate != sourceDate) {
                /** State. */
                state
            } else {
                state.copy(overallResponses = state.overallResponses + (promptKey to response))
            }
        }

        /** Schedule journal response save. */
        scheduleJournalResponseSave(
            sourceDate = sourceDate,
            scope = "overall",
            dimensionKey = null,
            promptKey = promptKey,
            response = response,
        )
    }

    /**
     * Update dimension response.
     */
    fun updateDimensionResponse(
        /** Source date. */
        sourceDate: LocalDate,
        /** Dimension id. */
        dimensionId: String,
        /** Prompt key. */
        promptKey: String,
        /** Response. */
        response: String,
    ) {
        /** Resolved dimension id. */
        val resolvedDimensionId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id
        /** If. */
        if (resolvedDimensionId == null) {
            logger.w(
                "DayViewModel.updateDimensionResponse",
                "Ignoring non-canonical dimension id",
                /** Map of. */
                mapOf("dimensionId" to dimensionId, "promptKey" to promptKey),
            )
            /** Return. */
            return
        }
        logger.d(
            "DayViewModel.updateDimensionResponse",
            "Updating dimension response",
            /** Map of. */
            mapOf(
                "dimensionId" to resolvedDimensionId,
                "promptKey" to promptKey,
                "length" to response.length,
                "sourceDate" to sourceDate.toString(),
                "selectedDate" to _uiState.value.selectedDate.toString(),
            ),
        )

        _uiState.update { state ->
            /** If. */
            if (state.selectedDate != sourceDate) {
                /** State. */
                state
            } else {
                /** Current dimension responses. */
                val currentDimensionResponses = state.dimensionResponses[resolvedDimensionId] ?: emptyMap()
                /** Updated dimension responses. */
                val updatedDimensionResponses = currentDimensionResponses + (promptKey to response)
                state.copy(dimensionResponses = state.dimensionResponses + (resolvedDimensionId to updatedDimensionResponses))
            }
        }

        /** Schedule journal response save. */
        scheduleJournalResponseSave(
            sourceDate = sourceDate,
            scope = "dimension",
            dimensionKey = resolvedDimensionId,
            promptKey = promptKey,
            response = response,
        )
    }

    private fun scheduleJournalResponseSave(
        /** Source date. */
        sourceDate: LocalDate,
        /** Scope. */
        scope: String,
        dimensionKey: String?,
        /** Prompt key. */
        promptKey: String,
        /** Response. */
        response: String,
    ) {
        /** Selected date at queue. */
        val selectedDateAtQueue = _uiState.value.selectedDate
        /** Date string. */
        val dateString = sourceDate.format(isoDateFormatter)
        /** Key. */
        val key = JournalResponseKey(
            dateString = dateString,
            scope = scope,
            dimensionKey = dimensionKey,
            promptKey = promptKey,
        )

        pendingJournalSaves.remove(key)?.cancel()
        _uiState.update { state ->
            state.copy(pendingJournalSaveDates = state.pendingJournalSaveDates + sourceDate)
        }
        logger.d(
            "DayViewModel.scheduleJournalResponseSave",
            "Queued journal response save",
            /** Map of. */
            mapOf(
                "sourceDate" to sourceDate.toString(),
                "selectedDateAtQueue" to selectedDateAtQueue.toString(),
                "scope" to scope,
                "dimensionKey" to (dimensionKey ?: ""),
                "promptKey" to promptKey,
                "responseLength" to response.length,
            ),
        )

        /** Save job. */
        val saveJob = viewModelScope.launch {
            try {
                /** Delay. */
                delay(journalSaveDebounceMillis)
                /** Save journal response. */
                saveJournalResponse(
                    sourceDate = sourceDate,
                    dateString = dateString,
                    scope = scope,
                    dimensionKey = dimensionKey,
                    promptKey = promptKey,
                    response = response,
                )
            } finally {
                pendingJournalSaves.remove(key)
                /** Still pending for source date. */
                val stillPendingForSourceDate = pendingJournalSaves.keys.any { it.dateString == dateString }
                _uiState.update { state ->
                    /** Pending dates. */
                    val pendingDates = if (stillPendingForSourceDate) {
                        state.pendingJournalSaveDates
                    } else {
                        state.pendingJournalSaveDates - sourceDate
                    }
                    state.copy(pendingJournalSaveDates = pendingDates)
                }
            }
        }
        pendingJournalSaves[key] = saveJob
    }

    private suspend fun saveJournalResponse(
        /** Source date. */
        sourceDate: LocalDate,
        /** Date string. */
        dateString: String,
        /** Scope. */
        scope: String,
        dimensionKey: String?,
        /** Prompt key. */
        promptKey: String,
        /** Response. */
        response: String,
    ) {
        try {
            logger.d(
                "DayViewModel.saveJournalResponse",
                "Persisting journal response",
                /** Map of. */
                mapOf(
                    "sourceDate" to sourceDate.toString(),
                    "dateString" to dateString,
                    "selectedDateAtSave" to _uiState.value.selectedDate.toString(),
                    "scope" to scope,
                    "dimensionKey" to (dimensionKey ?: ""),
                    "promptKey" to promptKey,
                    "responseLength" to response.length,
                ),
            )
            /** Now string. */
            val nowString = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            /** Entry. */
            var entry = journalRepository.getEntryByDate(dateString)
            /** If. */
            if (entry == null) {
                /** New entry. */
                val newEntry = DayJournalEntry(
                    id = UUID.randomUUID().toString(),
                    entryDate = dateString,
                    createdAt = nowString,
                    updatedAt = nowString,
                )
                journalRepository.insertEntry(newEntry)
                entry = newEntry
                logger.i(
                    "DayViewModel.saveJournalResponse",
                    "Created new journal entry",
                    /** Map of. */
                    mapOf("entryId" to entry.id, "date" to dateString),
                )
            }

            /** Response entity. */
            val responseEntity = DayJournalResponse(
                id = UUID.randomUUID().toString(),
                entryId = entry.id,
                scope = scope,
                dimensionKey = dimensionKey,
                promptKey = promptKey,
                responseText = response,
            )

            journalRepository.upsertResponse(responseEntity)
            _uiState.update { state -> state.copy(lastSavedJournalDate = sourceDate) }
            logger.d(
                "DayViewModel.saveJournalResponse",
                "Saved response",
                /** Map of. */
                mapOf(
                    "sourceDate" to sourceDate.toString(),
                    "selectedDateAfterSave" to _uiState.value.selectedDate.toString(),
                    "scope" to scope,
                    "promptKey" to promptKey,
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("DayViewModel.saveJournalResponse", "Failed to save response", e)
        }
    }

    override fun onCleared() {
        pendingJournalSaves.values.forEach { it.cancel() }
        pendingJournalSaves.clear()
        super.onCleared()
    }

    private fun loadDayData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            /** Selected date. */
            val selectedDate = _uiState.value.selectedDate
            /** Date string. */
            val dateString = selectedDate.format(isoDateFormatter)
            logger.d("DayViewModel.loadDayData", "Loading day data", mapOf("date" to dateString))

            try {
                /** Journal entry. */
                val journalEntry = journalRepository.getEntryByDate(dateString)
                /** Overall responses. */
                val overallResponses = mutableMapOf<String, String>()
                /** Dimension responses. */
                val dimensionResponses = mutableMapOf<String, MutableMap<String, String>>()

                /** If. */
                if (journalEntry != null) {
                    /** Responses. */
                    val responses = journalRepository.getResponsesByEntryId(journalEntry.id)
                    responses.forEach { resp ->
                        /** When. */
                        when (resp.scope) {
                            "overall" -> overallResponses[resp.promptKey] = resp.responseText

                            "dimension" -> {
                                resp.dimensionKey?.let { dimKey ->
                                    /** Resolved dimension id. */
                                    val resolvedDimensionId = DimensionTaxonomyCatalog.fromCanonicalId(dimKey)?.id
                                    try {
                                        /** Dimension id. */
                                        val dimensionId = resolvedDimensionId ?: throw IllegalArgumentException("Unknown dimension key: $dimKey")
                                        /** Dim map. */
                                        val dimMap = dimensionResponses.getOrPut(dimensionId) { mutableMapOf() }
                                        dimMap[resp.promptKey] = resp.responseText
                                    } catch (_: Exception) {
                                        logger.w("DayViewModel.loadDayData", "Unknown dimension: $dimKey")
                                    }
                                }
                            }
                        }
                    }
                }

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        journalEntry = journalEntry,
                        overallResponses = overallResponses,
                        dimensionResponses = dimensionResponses.mapValues { it.value.toMap() },
                    )
                }
                logger.i(
                    "DayViewModel.loadDayData",
                    "Day journal data loaded",
                    /** Map of. */
                    mapOf(
                        "date" to dateString,
                        "hasEntry" to (journalEntry != null).toString(),
                        "overallResponses" to overallResponses.size,
                        "dimensionResponses" to dimensionResponses.values.sumOf { it.size },
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("DayViewModel.loadDayData", "Failed to load day journal data", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
