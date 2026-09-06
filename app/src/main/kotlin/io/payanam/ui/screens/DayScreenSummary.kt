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
    uiState: DayUiState,
    onOverallResponseChange: (String) -> (String) -> Unit,
    onDimensionResponseChange: (String, String) -> (String) -> Unit,
) {
    val prefs = LocalAppPreferences.current
    val dimensionOptions = prefs.visibleDimensions()
    var expandedDimensionId by remember { mutableStateOf<String?>(null) }
    val statusDateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val selectedDate = uiState.selectedDate
    val isSavingSelectedDate = uiState.pendingJournalSaveDates.contains(selectedDate)
    val hasSavedSelectedDate = uiState.lastSavedJournalDate == selectedDate
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Saving indicator
        if (isSavingSelectedDate || hasSavedSelectedDate) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSavingSelectedDate) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_daily_reflection),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                OVERALL_JOURNAL_PROMPTS.forEach { (key, prompt) ->
                    JournalPromptField(
                        prompt = prompt,
                        value = uiState.overallResponses[key] ?: "",
                        onValueChange = onOverallResponseChange(key),
                    )
                }
            }
        }

        // Per-dimension reflections
        Text(
            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_dimension_reflections),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        dimensionOptions.forEach { dimensionPref ->
            val dimensionId = dimensionPref.id
            val isExpanded = expandedDimensionId == dimensionId
            val color = dimensionPref.color
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
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DimensionBadgeLabelRow(
                            prefs = prefs,
                            dimensionId = dimensionId,
                            fallbackLabel = dimensionPref.label,
                            fallbackColor = color,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            badgeSize = 24.dp,
                        )
                        val responseCount = uiState.dimensionResponses[dimensionId]?.count { it.value.isNotBlank() } ?: 0
                        if (responseCount > 0) {
                            Text(
                                text = "$responseCount/${DIMENSION_JOURNAL_PROMPTS.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(16.dp))

                        DIMENSION_JOURNAL_PROMPTS.forEach { (key, prompt) ->
                            JournalPromptField(
                                prompt = prompt,
                                value = uiState.dimensionResponses[dimensionId]?.get(key) ?: "",
                                onValueChange = onDimensionResponseChange(dimensionId, key),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun JournalPromptField(
    prompt: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = prompt,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
