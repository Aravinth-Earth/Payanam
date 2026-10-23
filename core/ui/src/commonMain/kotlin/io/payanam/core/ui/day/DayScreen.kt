//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.day

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
import androidx.compose.material3.rememberDatePickerState
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
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.time.SummaryTabContent
import io.payanam.presentation.day.DayViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Day screen modes (currently journal-only).
 */
enum class DayScreenMode {
    JOURNAL_ONLY,
}

/**
 * The Day screen: date navigation (never into the future) over the day-journal editor.
 *
 * Shared by Android and the desktop. The [DayViewModel] is a required parameter rather than something this
 * screen fetches, because obtaining it differs per platform - Android retains it across configuration
 * changes (`retainSharedViewModel`), the desktop holds it in a `remember` - and a screen that fetched its own
 * would have to know which platform it is on. Same seam as [io.payanam.core.ui.notes.NotesScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    viewModel: DayViewModel,
    mode: DayScreenMode = DayScreenMode.JOURNAL_ONLY,
) {
    val uiState by viewModel.uiState.collectAsState()
    val strings = LocalUiStrings.current
    var showDatePicker by remember { mutableStateOf(false) }
    val journalOnly = mode == DayScreenMode.JOURNAL_ONLY
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (journalOnly) strings.locJournal else strings.locDayView,
                    )
                },
                actions = {
                    if (!viewModel.isToday()) {
                        TextButton(onClick = { viewModel.goToToday() }) {
                            Text(strings.locToday)
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Date navigation bar
            DateNavigationBar(
                dateText = viewModel.getFormattedDate(),
                isToday = viewModel.isToday(),
                onPreviousDay = { viewModel.previousDay() },
                onNextDay = { viewModel.nextDay() },
                onDateClick = { showDatePicker = true },
            )
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
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
    if (showDatePicker) {
        val selectedDateMillis = uiState.selectedDate
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val today = LocalDate.now()
        val selectableDates = remember(today) {
            object : SelectableDates {
                /**
                 * Allows only dates up to today (no future journaling).
                 */
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val candidate = Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    return !candidate.isAfter(today)
                }

                /**
                 * Allows only years up to the current year.
                 */
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            }
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDateMillis,
            selectableDates = selectableDates,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            viewModel.selectDate(selectedDate)
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(strings.locOk)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(strings.settingsActionCancel)
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun DateNavigationBar(
    dateText: String,
    isToday: Boolean,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onDateClick: () -> Unit,
) {
    val strings = LocalUiStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousDay) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = strings.locPreviousDay,
            )
        }
        Row(
            modifier = Modifier
                .clickable { onDateClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (isToday) {
                    Text(
                        text = strings.locToday,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        IconButton(
            onClick = onNextDay,
            enabled = !isToday,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = strings.locNextDay,
            )
        }
    }
}
