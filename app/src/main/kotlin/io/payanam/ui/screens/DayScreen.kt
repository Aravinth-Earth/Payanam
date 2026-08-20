//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.DayViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * DayScreenMode.
 */
enum class DayScreenMode {
    /** Journal only. */
    JOURNAL_ONLY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * Day screen.
 */
fun DayScreen(
    mode: DayScreenMode = DayScreenMode.JOURNAL_ONLY,
    viewModel: DayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    remember { UnifiedLogger.getInstance() }
    var showDatePicker by remember { mutableStateOf(false) }
    /** Journal only. */
    val journalOnly = mode == DayScreenMode.JOURNAL_ONLY

    /** Scaffold. */
    Scaffold(
        topBar = {
            /** Top app bar. */
            TopAppBar(
                title = {
                    /** Text. */
                    Text(
                        androidx.compose.ui.res.stringResource(
                            id = if (journalOnly) io.payanam.R.string.loc_journal else io.payanam.R.string.loc_day_view,
                        ),
                    )
                },
                actions = {
                    /** If. */
                    if (!viewModel.isToday()) {
                        /** Text button. */
                        TextButton(onClick = { viewModel.goToToday() }) {
                            /** Text. */
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_today))
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        /** Column. */
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Date navigation bar
            /** Date navigation bar. */
            DateNavigationBar(
                dateText = viewModel.getFormattedDate(),
                isToday = viewModel.isToday(),
                onPreviousDay = { viewModel.previousDay() },
                onNextDay = { viewModel.nextDay() },
                onDateClick = { showDatePicker = true },
            )

            /** If. */
            if (uiState.isLoading) {
                /** Box. */
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    /** Circular progress indicator. */
                    CircularProgressIndicator()
                }
            } else {
                /** Summary tab content. */
                SummaryTabContent(
                    uiState = uiState,
                    onOverallResponseChange = { promptKey ->
                        { response -> viewModel.updateOverallResponse(uiState.selectedDate, promptKey, response) }
                    },
                    onDimensionResponseChange = { dimension, promptKey ->
                        { response ->
                            viewModel.updateDimensionResponse(
                                sourceDate = uiState.selectedDate,
                                dimensionId = dimension,
                                promptKey = promptKey,
                                response = response,
                            )
                        }
                    },
                )
            }
        }
    }

    /** If. */
    if (showDatePicker) {
        /** Selected date millis. */
        val selectedDateMillis = uiState.selectedDate
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        /** Today. */
        val today = LocalDate.now()
        /** Selectable dates. */
        val selectableDates = remember(today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    /** Candidate. */
                    val candidate = Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    return !candidate.isAfter(today)
                }

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            }
        }
        /** Date picker state. */
        val datePickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = selectedDateMillis,
            selectableDates = selectableDates,
        )
        /** Date picker dialog. */
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                /** Text button. */
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            /** Selected date. */
                            val selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            viewModel.selectDate(selectedDate)
                        }
                        showDatePicker = false
                    },
                ) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
                }
            },
            dismissButton = {
                /** Text button. */
                TextButton(onClick = { showDatePicker = false }) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                }
            },
        ) {
            /** Date picker. */
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun DateNavigationBar(
    /** Date text. */
    dateText: String,
    /** Is today. */
    isToday: Boolean,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onDateClick: () -> Unit,
) {
    /** Row. */
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /** Icon button. */
        IconButton(onClick = onPreviousDay) {
            /** Icon. */
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_previous_day),
            )
        }

        /** Row. */
        Row(
            modifier = Modifier
                .clickable { onDateClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /** Icon. */
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            /** Spacer. */
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                /** Text. */
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                /** If. */
                if (isToday) {
                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_today),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        /** Icon button. */
        IconButton(
            onClick = onNextDay,
            enabled = !isToday,
        ) {
            /** Icon. */
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_next_day),
            )
        }
    }
}
