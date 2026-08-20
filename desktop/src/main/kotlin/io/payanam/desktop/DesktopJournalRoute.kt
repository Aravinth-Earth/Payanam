//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.shared.journal.JournalReflectionContracts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun desktopJournalRoute(
    state: DesktopJournalState,
    onSelectDate: (String) -> Unit,
    onSaveOverallResponse: (String, String) -> Unit,
    onSaveDimensionResponse: (String, String, String) -> Unit,
) {
    val selectedDate = remember(state.selectedDateIso) { LocalDate.parse(state.selectedDateIso) }
    val statusDateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val fullDateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val selectedDay =
        remember(state.snapshot, state.selectedDateIso) {
            JournalReflectionContracts.dayForDate(state.snapshot, state.selectedDateIso)
        }
    var expandedDimensionId by remember(state.selectedDateIso) { mutableStateOf<String?>(null) }

    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .semantics { contentDescription = "Desktop journal route surface" }
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Desktop journal",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Backed by the local desktop database.",
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            desktopJournalStatus(state = state, selectedDate = selectedDate, statusDateFormatter = statusDateFormatter)
            desktopJournalDateHeader(
                selectedDate = selectedDate,
                fullDateFormatter = fullDateFormatter,
                onSelectDate = onSelectDate,
            )
            desktopJournalPromptCard(
                title = "Daily reflection",
                prompts = JournalReflectionContracts.overallPrompts,
                responses = selectedDay?.overallResponses.orEmpty(),
                onResponseChange = onSaveOverallResponse,
            )
            desktopJournalDimensionsSection(
                selectedDay = selectedDay,
                expandedDimensionId = expandedDimensionId,
                onExpandedDimensionChange = { expandedDimensionId = it },
                onSaveDimensionResponse = onSaveDimensionResponse,
            )
        }
    }
}

@Composable
private fun desktopJournalStatus(
    state: DesktopJournalState,
    selectedDate: LocalDate,
    statusDateFormatter: DateTimeFormatter,
) {
    state.errorMessage?.let { errorMessage ->
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.body2,
            color = desktopBodyTextColor(),
        )
    }
    if (state.lastSavedDateIso == state.selectedDateIso) {
        Text(
            text = "Saved for ${selectedDate.format(statusDateFormatter)}",
            style = MaterialTheme.typography.caption,
            color = desktopMutedTextColor(),
        )
    }
}

@Composable
private fun desktopJournalDateHeader(
    selectedDate: LocalDate,
    fullDateFormatter: DateTimeFormatter,
    onSelectDate: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = { onSelectDate(selectedDate.minusDays(1).toString()) }) {
            Text("Previous")
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = selectedDate.format(fullDateFormatter),
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Reflect on this day the same way Android journal mode does.",
                style = MaterialTheme.typography.caption,
                color = desktopMutedTextColor(),
            )
        }
        Row {
            TextButton(onClick = { onSelectDate(LocalDate.now().toString()) }) {
                Text("Today")
            }
            TextButton(
                enabled = selectedDate.isBefore(LocalDate.now()),
                onClick = { onSelectDate(selectedDate.plusDays(1).toString()) },
            ) {
                Text("Next")
            }
        }
    }
}

@Composable
private fun desktopJournalDimensionsSection(
    selectedDay: io.payanam.shared.journal.JournalDayRecord?,
    expandedDimensionId: String?,
    onExpandedDimensionChange: (String?) -> Unit,
    onSaveDimensionResponse: (String, String, String) -> Unit,
) {
    Text(
        text = "Dimension reflections",
        style = MaterialTheme.typography.subtitle1,
        fontWeight = FontWeight.SemiBold,
    )
    desktopJournalDimensionOptions().forEach { dimension ->
        val isExpanded = expandedDimensionId == dimension.id
        val responseCount = selectedDay?.dimensionResponses?.get(dimension.id)?.count { it.value.isNotBlank() } ?: 0
        desktopJournalDimensionCard(
            dimension = dimension,
            isExpanded = isExpanded,
            responseCount = responseCount,
            responses = selectedDay?.dimensionResponses?.get(dimension.id).orEmpty(),
            onToggleExpanded = {
                onExpandedDimensionChange(if (isExpanded) null else dimension.id)
            },
            onSaveDimensionResponse = { promptKey, response ->
                onSaveDimensionResponse(dimension.id, promptKey, response)
            },
        )
    }
}

@Composable
private fun desktopJournalDimensionCard(
    dimension: DesktopJournalDimensionOption,
    isExpanded: Boolean,
    responseCount: Int,
    responses: Map<String, String>,
    onToggleExpanded: () -> Unit,
    onSaveDimensionResponse: (String, String) -> Unit,
) {
    Card(
        backgroundColor = if (isExpanded) desktopSurfaceColor() else desktopCardColor(),
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .semantics { contentDescription = "Desktop journal dimension ${dimension.label}" },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = dimension.label,
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                if (responseCount > 0) {
                    Text(
                        text = "$responseCount/${JournalReflectionContracts.dimensionPrompts.size}",
                        style = MaterialTheme.typography.caption,
                        color = desktopMutedTextColor(),
                    )
                }
            }
            if (isExpanded) {
                desktopJournalPromptFields(
                    prompts = JournalReflectionContracts.dimensionPrompts,
                    responses = responses,
                    onResponseChange = onSaveDimensionResponse,
                )
            }
        }
    }
}

@Composable
private fun desktopJournalPromptCard(
    title: String,
    prompts: List<io.payanam.shared.journal.JournalPromptDefinition>,
    responses: Map<String, String>,
    onResponseChange: (String, String) -> Unit,
) {
    Card(
        backgroundColor = desktopSurfaceColor(),
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.SemiBold,
            )
            desktopJournalPromptFields(
                prompts = prompts,
                responses = responses,
                onResponseChange = onResponseChange,
            )
        }
    }
}

@Composable
private fun desktopJournalPromptFields(
    prompts: List<io.payanam.shared.journal.JournalPromptDefinition>,
    responses: Map<String, String>,
    onResponseChange: (String, String) -> Unit,
) {
    prompts.forEach { prompt ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = prompt.prompt,
                style = MaterialTheme.typography.caption,
                color = desktopMutedTextColor(),
            )
            TextField(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Desktop journal prompt ${prompt.key}" },
                value = responses[prompt.key].orEmpty(),
                onValueChange = { onResponseChange(prompt.key, it) },
            )
        }
    }
}

private data class DesktopJournalDimensionOption(
    /** Id. */
    val id: String,
    /** Label. */
    val label: String,
)

private fun desktopJournalDimensionOptions(): List<DesktopJournalDimensionOption> =
    listOf(
        DesktopJournalDimensionOption("dim_physical_health", "Physical Health"),
        DesktopJournalDimensionOption("dim_mental_health", "Mental Health"),
        DesktopJournalDimensionOption("dim_family_relationships", "Family & Relationships"),
        DesktopJournalDimensionOption("dim_home_environment", "Home & Environment"),
        DesktopJournalDimensionOption("dim_work_livelihood", "Work & Livelihood"),
        DesktopJournalDimensionOption("dim_money_finance", "Money & Finance"),
        DesktopJournalDimensionOption("dim_learning_growth", "Learning & Growth"),
        DesktopJournalDimensionOption("dim_recreation_leisure", "Recreation & Leisure"),
        DesktopJournalDimensionOption("dim_community_service", "Community & Service"),
    )
