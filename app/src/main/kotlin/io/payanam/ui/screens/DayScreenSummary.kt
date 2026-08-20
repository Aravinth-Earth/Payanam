//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.ui.components.DimensionBadgeLabelRow
import io.payanam.ui.viewmodel.DIMENSION_JOURNAL_PROMPTS
import io.payanam.ui.viewmodel.DayUiState
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.OVERALL_JOURNAL_PROMPTS
import io.payanam.ui.viewmodel.visibleDimensions
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun SummaryTabContent(
    /** Ui state. */
    uiState: DayUiState,
    onOverallResponseChange: (String) -> (String) -> Unit,
    onDimensionResponseChange: (String, String) -> (String) -> Unit,
) {
    /** Prefs. */
    val prefs = LocalAppPreferences.current
    /** Dimension options. */
    val dimensionOptions = prefs.visibleDimensions()
    var expandedDimensionId by remember { mutableStateOf<String?>(null) }
    /** Status date formatter. */
    val statusDateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    /** Selected date. */
    val selectedDate = uiState.selectedDate
    /** Is saving selected date. */
    val isSavingSelectedDate = uiState.pendingJournalSaveDates.contains(selectedDate)
    /** Has saved selected date. */
    val hasSavedSelectedDate = uiState.lastSavedJournalDate == selectedDate

    /** Column. */
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Saving indicator
        /** If. */
        if (isSavingSelectedDate || hasSavedSelectedDate) {
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /** If. */
                if (isSavingSelectedDate) {
                    /** Circular progress indicator. */
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(8.dp))
                }
                /** Text. */
                Text(
                    text = if (isSavingSelectedDate) {
                        androidx.compose.ui.res.stringResource(
                            id = io.payanam.R.string.loc_journal_saving_for_date,
                            selectedDate.format(statusDateFormatter),
                        )
                    } else {
                        androidx.compose.ui.res.stringResource(
                            id = io.payanam.R.string.loc_journal_saved_for_date,
                            selectedDate.format(statusDateFormatter),
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Overall reflections
        /** Card. */
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
        ) {
            /** Column. */
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_daily_reflection),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                OVERALL_JOURNAL_PROMPTS.forEach { (key, prompt) ->
                    /** Journal prompt field. */
                    JournalPromptField(
                        prompt = prompt,
                        value = uiState.overallResponses[key] ?: "",
                        onValueChange = onOverallResponseChange(key),
                    )
                }
            }
        }

        // Per-dimension reflections
        /** Text. */
        Text(
            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_dimension_reflections),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        dimensionOptions.forEach { dimensionPref ->
            /** Dimension id. */
            val dimensionId = dimensionPref.id
            /** Is expanded. */
            val isExpanded = expandedDimensionId == dimensionId
            /** Color. */
            val color = dimensionPref.color

            /** Card. */
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expandedDimensionId = if (isExpanded) null else dimensionId
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isExpanded) {
                        color.copy(alpha = 0.1f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    },
                ),
            ) {
                /** Column. */
                Column(modifier = Modifier.padding(16.dp)) {
                    /** Row. */
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        /** Dimension badge label row. */
                        DimensionBadgeLabelRow(
                            prefs = prefs,
                            dimensionId = dimensionId,
                            fallbackLabel = dimensionPref.label,
                            fallbackColor = color,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            badgeSize = 24.dp,
                        )

                        /** Response count. */
                        val responseCount = uiState.dimensionResponses[dimensionId]?.count { it.value.isNotBlank() } ?: 0
                        /** If. */
                        if (responseCount > 0) {
                            /** Text. */
                            Text(
                                text = "$responseCount/${DIMENSION_JOURNAL_PROMPTS.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    /** If. */
                    if (isExpanded) {
                        /** Spacer. */
                        Spacer(modifier = Modifier.height(16.dp))

                        DIMENSION_JOURNAL_PROMPTS.forEach { (key, prompt) ->
                            /** Journal prompt field. */
                            JournalPromptField(
                                prompt = prompt,
                                value = uiState.dimensionResponses[dimensionId]?.get(key) ?: "",
                                onValueChange = onDimensionResponseChange(dimensionId, key),
                            )
                            /** Spacer. */
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }

        /** Spacer. */
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun JournalPromptField(
    /** Prompt. */
    prompt: String,
    /** Value. */
    value: String,
    onValueChange: (String) -> Unit,
) {
    /** Column. */
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        /** Text. */
        Text(
            text = prompt,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Outlined text field. */
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_your_thoughts)) },
            minLines = 2,
            maxLines = 4,
        )
    }
}
