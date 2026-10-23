//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package io.payanam.presentation.day

import io.payanam.domain.model.DayJournalEntry
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.JournalRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.day.DayUiState
import io.payanam.shared.journal.JournalReflectionContracts
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * Day-screen ViewModel: date navigation (never into the future) and the
 * day-journal editor — overall + dimension prompt responses persisted with a
 * 500 ms debounce, keyed per date/scope/prompt.
 */
class DayViewModel(
    private val journalRepository: JournalRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current
    private val pendingJournalSaves = mutableMapOf<JournalResponseKey, Job>()

    private val _uiState = MutableStateFlow(DayUiState())
    val uiState: StateFlow<DayUiState> = _uiState.asStateFlow()

    private val displayDateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    private val isoDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val journalSaveDebounceMillis = 500L

    private data class JournalResponseKey(
        val dateString: String,
        val scope: String,
        val dimensionKey: String?,
        val promptKey: String,
    )

    init {
        loadDayData()
    }

    /**
     * Moves the selection back one day and reloads its journal data.
     */
    fun previousDay() {
        val newDate = _uiState.value.selectedDate.minusDays(1)
        logger.d("DayViewModel.previousDay", "Navigating to previous day", mapOf("date" to newDate.toString()))
        _uiState.update { it.copy(selectedDate = newDate) }
        loadDayData()
    }

    /**
     * Moves the selection forward one day (never beyond today) and reloads.
     */
    fun nextDay() {
        val currentDate = _uiState.value.selectedDate
        val today = LocalDate.now()
        if (!currentDate.isBefore(today)) {
            logger.i(
                "DayViewModel.nextDay",
                "Blocked navigation beyond today",
                mapOf("selectedDate" to currentDate.toString(), "today" to today.toString()),
            )
            return
        }
        val newDate = currentDate.plusDays(1)
        logger.d("DayViewModel.nextDay", "Navigating to next day", mapOf("date" to newDate.toString()))
        _uiState.update { it.copy(selectedDate = newDate) }
        loadDayData()
    }

    /**
     * Jumps the selection back to today and reloads its journal data.
     */
    fun goToToday() {
        logger.d("DayViewModel.goToToday", "Navigating to today")
        _uiState.update { it.copy(selectedDate = LocalDate.now()) }
        loadDayData()
    }

    /**
     * Selects [date] (clamped to today if in the future) and reloads its data.
     */
    fun selectDate(date: LocalDate) {
        val today = LocalDate.now()
        val selectedDate = if (date.isAfter(today)) today else date
        logger.d(
            "DayViewModel.selectDate",
            "Date selected",
            mapOf(
                "requestedDate" to date.toString(),
                "selectedDate" to selectedDate.toString(),
                "today" to today.toString(),
            ),
        )
        _uiState.update { it.copy(selectedDate = selectedDate) }
        loadDayData()
    }

    /**
     * Switches tabs; currently pinned to [DayTab.SUMMARY] (the only tab).
     */
    fun selectTab(tab: DayTab) {
        logger.d("DayViewModel.selectTab", "Tab selected", mapOf("tab" to tab.name))
        _uiState.update { it.copy(selectedTab = DayTab.SUMMARY) }
    }

    /**
     * Locale-formatted full date label for the header.
     */
    fun getFormattedDate(): String = _uiState.value.selectedDate.format(displayDateFormatter)

    /**
     * True when the selected date is today (controls forward-navigation).
     */
    fun isToday(): Boolean = _uiState.value.selectedDate == LocalDate.now()

    /**
     * Records the user's answer [response] to overall prompt [promptKey] for
     * [sourceDate] (only applied if that date is still selected) and queues a
     * debounced persistence.
     */
    fun updateOverallResponse(
        sourceDate: LocalDate,
        promptKey: String,
        response: String,
    ) {
        logger.d(
            "DayViewModel.updateOverallResponse",
            "Updating response",
            mapOf(
                "promptKey" to promptKey,
                "length" to response.length,
                "sourceDate" to sourceDate.toString(),
                "selectedDate" to _uiState.value.selectedDate.toString(),
            ),
        )

        _uiState.update { state ->
            if (state.selectedDate != sourceDate) {
                state
            } else {
                state.copy(overallResponses = state.overallResponses + (promptKey to response))
            }
        }
        scheduleJournalResponseSave(
            sourceDate = sourceDate,
            responseScope = "overall",
            dimensionKey = null,
            promptKey = promptKey,
            response = response,
        )
    }

    /**
     * Records the user's answer to a dimension-scoped prompt (canonicalizing
     * [dimensionId]; non-canonical ids are ignored) and queues a debounced save.
     */
    fun updateDimensionResponse(
        sourceDate: LocalDate,
        dimensionId: String,
        promptKey: String,
        response: String,
    ) {
        val resolvedDimensionId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id
        if (resolvedDimensionId == null) {
            logger.w(
                "DayViewModel.updateDimensionResponse",
                "Ignoring non-canonical dimension id",
                mapOf("dimensionId" to dimensionId, "promptKey" to promptKey),
            )
            return
        }
        logger.d(
            "DayViewModel.updateDimensionResponse",
            "Updating dimension response",
            mapOf(
                "dimensionId" to resolvedDimensionId,
                "promptKey" to promptKey,
                "length" to response.length,
                "sourceDate" to sourceDate.toString(),
                "selectedDate" to _uiState.value.selectedDate.toString(),
            ),
        )

        _uiState.update { state ->
            if (state.selectedDate != sourceDate) {
                state
            } else {
                val currentDimensionResponses = state.dimensionResponses[resolvedDimensionId] ?: emptyMap()
                val updatedDimensionResponses = currentDimensionResponses + (promptKey to response)
                state.copy(dimensionResponses = state.dimensionResponses + (resolvedDimensionId to updatedDimensionResponses))
            }
        }
        scheduleJournalResponseSave(
            sourceDate = sourceDate,
            responseScope = "dimension",
            dimensionKey = resolvedDimensionId,
            promptKey = promptKey,
            response = response,
        )
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    private fun scheduleJournalResponseSave(
        sourceDate: LocalDate,
        responseScope: String,
        dimensionKey: String?,
        promptKey: String,
        response: String,
    ) {
        val selectedDateAtQueue = _uiState.value.selectedDate
        val dateString = sourceDate.format(isoDateFormatter)
        val key =
            JournalResponseKey(
                dateString = dateString,
                scope = responseScope,
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
            mapOf(
                "sourceDate" to sourceDate.toString(),
                "selectedDateAtQueue" to selectedDateAtQueue.toString(),
                "scope" to responseScope,
                "dimensionKey" to (dimensionKey ?: ""),
                "promptKey" to promptKey,
                "responseLength" to response.length,
            ),
        )
        val saveJob =
            scope.launch {
                try {
                    delay(journalSaveDebounceMillis)
                    saveJournalResponse(
                        sourceDate = sourceDate,
                        dateString = dateString,
                        responseScope = responseScope,
                        dimensionKey = dimensionKey,
                        promptKey = promptKey,
                        response = response,
                    )
                } finally {
                    pendingJournalSaves.remove(key)
                    val stillPendingForSourceDate = pendingJournalSaves.keys.any { it.dateString == dateString }
                    _uiState.update { state ->
                        val pendingDates =
                            if (stillPendingForSourceDate) {
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
        sourceDate: LocalDate,
        dateString: String,
        responseScope: String,
        dimensionKey: String?,
        promptKey: String,
        response: String,
    ) {
        try {
            logger.d(
                "DayViewModel.saveJournalResponse",
                "Persisting journal response",
                mapOf(
                    "sourceDate" to sourceDate.toString(),
                    "dateString" to dateString,
                    "selectedDateAtSave" to _uiState.value.selectedDate.toString(),
                    "scope" to responseScope,
                    "dimensionKey" to (dimensionKey ?: ""),
                    "promptKey" to promptKey,
                    "responseLength" to response.length,
                ),
            )
            val nowString = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            var entry = journalRepository.getEntryByDate(dateString)
            if (entry == null) {
                val newEntry =
                    DayJournalEntry(
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
                    mapOf("entryId" to entry.id, "date" to dateString),
                )
            }
            val responseEntity =
                DayJournalResponse(
                    id = UUID.randomUUID().toString(),
                    entryId = entry.id,
                    scope = responseScope,
                    dimensionKey = dimensionKey,
                    promptKey = promptKey,
                    responseText = response,
                )

            journalRepository.upsertResponse(responseEntity)
            _uiState.update { state -> state.copy(lastSavedJournalDate = sourceDate) }
            logger.d(
                "DayViewModel.saveJournalResponse",
                "Saved response",
                mapOf(
                    "sourceDate" to sourceDate.toString(),
                    "selectedDateAfterSave" to _uiState.value.selectedDate.toString(),
                    "scope" to responseScope,
                    "promptKey" to promptKey,
                ),
            )
        } catch (e: Exception) {
            logger.e("DayViewModel.saveJournalResponse", "Failed to save response", e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    private fun loadDayData() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val selectedDate = _uiState.value.selectedDate
            val dateString = selectedDate.format(isoDateFormatter)
            logger.d("DayViewModel.loadDayData", "Loading day data", mapOf("date" to dateString))

            try {
                val journalEntry = journalRepository.getEntryByDate(dateString)
                val overallResponses = mutableMapOf<String, String>()
                val dimensionResponses = mutableMapOf<String, MutableMap<String, String>>()
                if (journalEntry != null) {
                    val responses = journalRepository.getResponsesByEntryId(journalEntry.id)
                    responses.forEach { resp ->
                        when (resp.scope) {
                            "overall" -> {
                                overallResponses[resp.promptKey] = resp.responseText
                            }

                            "dimension" -> {
                                resp.dimensionKey?.let { dimKey ->
                                    val resolvedDimensionId = DimensionTaxonomyCatalog.fromCanonicalId(dimKey)?.id
                                    try {
                                        val dimensionId =
                                            resolvedDimensionId ?: throw IllegalArgumentException("Unknown dimension key: $dimKey")
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
                    mapOf(
                        "date" to dateString,
                        "hasEntry" to (journalEntry != null).toString(),
                        "overallResponses" to overallResponses.size,
                        "dimensionResponses" to dimensionResponses.values.sumOf { it.size },
                    ),
                )
            } catch (e: Exception) {
                logger.e("DayViewModel.loadDayData", "Failed to load day journal data", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Releases the coroutine scope this ViewModel created, cancelling any debounced journal save still
     * pending first.
     *
     * The pending-save cancellation used to live in `onCleared`, the androidx lifecycle hook this class no
     * longer has: `onCleared` ran when the framework dropped the ViewModel, and `dispose` is what the
     * platform now calls at the same point. The scope is cancelled unconditionally: a caller that passed its
     * own [scope] has that same scope cancelled here, since the ViewModel cannot release it selectively, and
     * must not reuse it afterwards.
     */
    fun dispose() {
        pendingJournalSaves.values.forEach { it.cancel() }
        pendingJournalSaves.clear()
        scope.cancel()
    }
}

/**
 * The one-line entry point a platform's destination body calls to get the Day ViewModel.
 *
 * The repositories come from [LocalDataLayer], which the platform installed at startup with its own
 * implementation. Call sites on both platforms are therefore identical and contain no wiring.
 */
fun dayViewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): DayViewModel =
    DayViewModel(
        journalRepository = LocalDataLayer.current.journal,
        scope = scope,
    )
