//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.MetricWindowRow
import io.payanam.domain.repository.ScoreWindowRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel

/** Detail-page source: DAY (day_metrics) or a dimension (dimension_metrics). */
enum class ScoreDetailType  {
    DAY,
    DIMENSION,
}
/**
 * UI state for the score-detail screen: loading/error flags, metric rows for
 * the selected layer, and the current window (size + end date) plus view mode.
 */
data class ScoreDetailUiState(
    val isLoading: Boolean = true,
    val rows: List<MetricWindowRow> = emptyList(),
    val windowSizeDays: Int = 7,
    val windowEnd: LocalDate = LocalDate.now(),
    val showChartView: Boolean = true,
    val error: String? = null,
)

/**
 * Score-detail ViewModel: loads day/dimension metric windows, shifts the
 * window or changes its size, and toggles the chart/table view.
 */
@HiltViewModel
class ScoreDetailViewModel
    @Inject
    constructor(
        private val scoreWindowRepository: ScoreWindowRepository,
    ) : ViewModel() {
        private val logger = UnifiedLogger.getInstance()

        private val _uiState = MutableStateFlow(ScoreDetailUiState())
        val uiState: StateFlow<ScoreDetailUiState> = _uiState.asStateFlow()
        /**
         * Loads the metric rows for [type]/[key] over the current window.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun load(type: ScoreDetailType, key: String) {
            viewModelScope.launch {
                val state = _uiState.value
                val end = state.windowEnd
                val start = scoreWindowRepository.resolveWindowStart(end, state.windowSizeDays, currentType, currentKey)
                val startStr = start.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val endStr = end.format(DateTimeFormatter.ISO_LOCAL_DATE)
                _uiState.update { it.copy(isLoading = true, error = null) }
                logger.d(
                    "ScoreDetailViewModel.load",
                    "Loading score detail window",
                    mapOf("type" to type.name, "key" to key, "start" to startStr, "end" to endStr),
                )
                try {
                    val rows =
                        when (type) {
                            ScoreDetailType.DAY -> scoreWindowRepository.getDayWindow(startStr, endStr)
                            ScoreDetailType.DIMENSION -> {
                                scoreWindowRepository
                                    .getDimensionWindow(startStr, endStr)
                                    .filter { it.key == key }
                            }
                        }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            rows = rows,
                            windowSizeDays = state.windowSizeDays,
                            windowEnd = state.windowEnd,
                        )
                    }
                    logger.d(
                        "ScoreDetailViewModel.load",
                        "Score detail loaded",
                        mapOf("type" to type.name, "rows" to rows.size),
                    )
                } catch (e: Exception) {
                    logger.e("ScoreDetailViewModel.load", "Failed to load score detail", e)
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            }
        }
        /**
         * Slides the window by [days] and reloads its rows.
         */
        fun shiftWindow(days: Int) {
            logger.d(
                "ScoreDetailViewModel.shiftWindow",
                "Window shifted",
                mapOf("days" to days, "type" to currentType.name, "key" to currentKey),
            )
            _uiState.update { it.copy(windowEnd = it.windowEnd.plusDays(days.toLong())) }
            // Reload with the new end date: re-enter load with current state
            viewModelScope.launch {
                val state = _uiState.value
                val end = state.windowEnd
                val start = scoreWindowRepository.resolveWindowStart(end, state.windowSizeDays, currentType, currentKey)
                val rows =
                    when (currentType) {
                        ScoreDetailType.DAY -> scoreWindowRepository.getDayWindow(
                            start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        )
                        ScoreDetailType.DIMENSION -> scoreWindowRepository
                            .getDimensionWindow(
                                start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            )
                            .filter { it.key == currentKey }
                    }
                _uiState.update { it.copy(rows = rows, isLoading = false) }
            }
        }
        /**
         * Sets the visible range to [days] days (0 = all-time) and reloads.
         */
        fun setWindowSize(days: Int) {
            logger.d(
                "ScoreDetailViewModel.setWindowSize",
                "Range size selected",
                mapOf("days" to days, "type" to currentType.name, "key" to currentKey),
            )
            _uiState.update { it.copy(windowSizeDays = days) }
            viewModelScope.launch {
                val state = _uiState.value
                val end = state.windowEnd
                val start = scoreWindowRepository.resolveWindowStart(end, state.windowSizeDays, currentType, currentKey)
                val rows =
                    when (currentType) {
                        ScoreDetailType.DAY -> scoreWindowRepository.getDayWindow(
                            start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        )
                        ScoreDetailType.DIMENSION -> scoreWindowRepository
                            .getDimensionWindow(
                                start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            )
                            .filter { it.key == currentKey }
                    }
                _uiState.update { it.copy(rows = rows, isLoading = false) }
            }
        }
        /**
         * Toggles the activity section between chart and table rendering.
         */
        fun setChartView(show: Boolean) {
            _uiState.update { it.copy(showChartView = show) }
        }
        /**
         * Snaps the window back to end at today and reloads.
         */
        fun goToday() {
            logger.d(
                "ScoreDetailViewModel.goToday",
                "Window reset to today",
                mapOf("type" to currentType.name, "key" to currentKey),
            )
            _uiState.update { it.copy(windowEnd = LocalDate.now()) }
            viewModelScope.launch {
                val state = _uiState.value
                val end = state.windowEnd
                val start = scoreWindowRepository.resolveWindowStart(end, state.windowSizeDays, currentType, currentKey)
                val rows =
                    when (currentType) {
                        ScoreDetailType.DAY -> scoreWindowRepository.getDayWindow(
                            start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        )
                        ScoreDetailType.DIMENSION -> scoreWindowRepository
                            .getDimensionWindow(
                                start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                end.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            )
                            .filter { it.key == currentKey }
                    }
                _uiState.update { it.copy(rows = rows, isLoading = false) }
            }
        }

        // Set by the screen on first composition; used by window shifts.
        var currentType: ScoreDetailType = ScoreDetailType.DAY
        var currentKey: String = "DAY"
    }

/** Window start: 0 days = "All" → earliest logged day for the layer
 *  (resolved from the repository — per-dimension mapped habits for the
 *  dimension layer, all habits for the DAY layer); otherwise end minus
 *  (days-1). Hard date fallback only when no metrics exist at all. */
private suspend fun ScoreWindowRepository.resolveWindowStart(
    end: LocalDate,
    windowSizeDays: Int,
    type: ScoreDetailType,
    key: String,
): LocalDate {
    if (windowSizeDays > 0) return end.minusDays((windowSizeDays - 1).toLong())
    val earliest =
        when (type) {
            ScoreDetailType.DAY -> earliestDayKey()
            ScoreDetailType.DIMENSION -> earliestDimensionDayKey(key)
        }
    return earliest?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.of(2020, 1, 1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreDetailScreen(
    type: ScoreDetailType,
    key: String,
    onNavigateBack: () -> Unit,
    viewModel: ScoreDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val logger = remember { UnifiedLogger.getInstance() }
    LaunchedEffect(type, key) {
        viewModel.currentType = type
        viewModel.currentKey = key
        viewModel.load(type, key)
    }
    val title =
        if (type == ScoreDetailType.DAY) {
            stringResource(id = R.string.loc_lens_score_detail_day, key)
        } else {
            stringResource(id = R.string.loc_lens_score_detail_dimension, key)
        }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.loc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // Side margins: content must not touch screen edges.
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            when {
                uiState.isLoading && uiState.rows.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.error != null -> {
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
                else -> {
                    HabitActivityDetailSection(
                        windowSizeDays = uiState.windowSizeDays,
                        windowEnd = uiState.windowEnd,
                        rows = uiState.rows,
                        occurrences = emptyMap(),
                        isLoading = uiState.isLoading,
                        showChartView = uiState.showChartView,
                        onWindowSizeChange = viewModel::setWindowSize,
                        onWindowBack = { viewModel.shiftWindow(-1 * uiState.windowSizeDays) },
                        onWindowForward = { viewModel.shiftWindow(uiState.windowSizeDays) },
                        onWindowToday = viewModel::goToday,
                        onChartViewChange = viewModel::setChartView,
                    )
                }
            }
        }
    }
}
