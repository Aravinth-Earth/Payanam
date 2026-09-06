//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.shared.notes.DesktopNoteContracts
import io.payanam.shared.notes.DesktopNoteRecord

/** Offset used to advance to the next dimension option in the filter cycle. */
@Suppress("MagicNumber")
private const val NEXT_INDEX_OFFSET = 1

@Composable
internal fun desktopNotesRoute(
    state: DesktopNotesState,
    onCreateNote: (String, String?, String?, String?, List<String>) -> Unit,
    onUpdateNote: (String, String, String?, String?, String?, List<String>) -> Unit,
    onDeleteNote: (String) -> Unit,
) {
    val dimensionOptions = remember { desktopNoteDimensionOptions() }
    var searchQuery by remember(state.snapshot.notes.size) { mutableStateOf("") }
    var selectedDimensionId by remember(state.snapshot.notes.size) { mutableStateOf<String?>(null) }
    var dialogState by remember(state.snapshot.notes.size) { mutableStateOf(DesktopNoteDialogState.hidden()) }
    val visibleNotes =
        remember(state.snapshot.notes, searchQuery, selectedDimensionId) {
            state.snapshot.notes.filter { note ->
                val matchesQuery =
                    searchQuery.isBlank() ||
                        note.title.contains(searchQuery, ignoreCase = true) ||
                        (note.details?.contains(searchQuery, ignoreCase = true) == true) ||
                        note.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
                val matchesDimension = selectedDimensionId == null || note.dimensionId == selectedDimensionId
                matchesQuery && matchesDimension
            }
        }

    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .semantics {
                        contentDescription = "Notes route surface"
                        stateDescription = "${visibleNotes.size} visible notes"
                    }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Desktop notes",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Backed by the local desktop database.",
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            state.errorMessage?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.body2,
                    color = desktopBodyTextColor(),
                )
            }
            desktopNotesToolbar(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                selectedDimensionId = selectedDimensionId,
                dimensionOptions = dimensionOptions,
                onAdvanceDimensionFilter = {
                    selectedDimensionId = advanceDesktopNoteFilter(selectedDimensionId, dimensionOptions)
                },
                onCreateNote = {
                    dialogState = DesktopNoteDialogState.create()
                },
            )
            desktopNotesList(
                allNotesCount = state.snapshot.notes.size,
                visibleNotes = visibleNotes,
                onEditNote = { note ->
                    dialogState = DesktopNoteDialogState.edit(note)
                },
            )
        }
    }

    desktopNoteDialog(
        dialogState = dialogState,
        dimensionOptions = dimensionOptions,
        onDialogStateChange = { dialogState = it },
        onDismiss = { dialogState = DesktopNoteDialogState.hidden() },
        onCreateNote = onCreateNote,
        onUpdateNote = onUpdateNote,
        onDeleteNote = onDeleteNote,
    )
}

@Composable
private fun desktopNotesToolbar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedDimensionId: String?,
    dimensionOptions: List<DesktopNoteDimensionOption>,
    onAdvanceDimensionFilter: () -> Unit,
    onCreateNote: () -> Unit,
) {
    OutlinedTextField(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Desktop notes search field" },
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        label = { Text("Search notes") },
        singleLine = true,
    )
    desktopChoiceRow(
        label = "Dimension filter",
        value =
            dimensionOptions
                .firstOrNull { it.id == selectedDimensionId }
                ?.fallbackLabel ?: "All dimensions",
        onAdvance = onAdvanceDimensionFilter,
    )
    Button(
        modifier = Modifier.semantics { contentDescription = "Create desktop note action" },
        onClick = onCreateNote,
    ) {
        Text("Add note")
    }
}

@Composable
private fun desktopNotesList(
    allNotesCount: Int,
    visibleNotes: List<DesktopNoteRecord>,
    onEditNote: (DesktopNoteRecord) -> Unit,
) {
    if (visibleNotes.isEmpty()) {
        Text(
            text = if (allNotesCount == 0) "No desktop notes yet." else "No notes match the current filters.",
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.SemiBold,
        )
        return
    }
    visibleNotes.forEach { note ->
        desktopNoteCard(
            note = note,
            onEdit = { onEditNote(note) },
        )
    }
}

@Composable
private fun desktopNoteDialog(
    dialogState: DesktopNoteDialogState,
    dimensionOptions: List<DesktopNoteDimensionOption>,
    onDialogStateChange: (DesktopNoteDialogState) -> Unit,
    onDismiss: () -> Unit,
    onCreateNote: (String, String?, String?, String?, List<String>) -> Unit,
    onUpdateNote: (String, String, String?, String?, String?, List<String>) -> Unit,
    onDeleteNote: (String) -> Unit,
) {
    if (!dialogState.isVisible) {
        return
    }
    val selectedDefinition =
        dimensionOptions.firstOrNull { it.id == dialogState.dimensionId } ?: desktopDefaultNoteDimension()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (dialogState.editingNoteId == null) "Add note" else "Edit note") },
        text = {
            desktopNoteDialogContent(
                dialogState = dialogState,
                dimensionOptions = dimensionOptions,
                onDialogStateChange = onDialogStateChange,
            )
        },
        confirmButton = {
            TextButton(
                enabled = dialogState.title.isNotBlank(),
                onClick = {
                    val parsedTags =
                        dialogState.tagsRaw
                            .split(",")
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                    if (dialogState.editingNoteId == null) {
                        onCreateNote(
                            dialogState.title,
                            dialogState.details,
                            selectedDefinition.id,
                            selectedDefinition.fallbackLabel,
                            parsedTags,
                        )
                    } else {
                        onUpdateNote(
                            dialogState.editingNoteId,
                            dialogState.title,
                            dialogState.details,
                            selectedDefinition.id,
                            selectedDefinition.fallbackLabel,
                            parsedTags,
                        )
                    }
                    onDismiss()
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dialogState.editingNoteId != null) {
                    TextButton(
                        modifier = Modifier.semantics { contentDescription = "Delete desktop note action" },
                        onClick = {
                            onDeleteNote(dialogState.editingNoteId)
                            onDismiss()
                        },
                    ) {
                        Text("Delete note")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun desktopNoteDialogContent(
    dialogState: DesktopNoteDialogState,
    dimensionOptions: List<DesktopNoteDimensionOption>,
    onDialogStateChange: (DesktopNoteDialogState) -> Unit,
) {
    val selectedDefinition =
        dimensionOptions.firstOrNull { it.id == dialogState.dimensionId } ?: desktopDefaultNoteDimension()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Desktop note title field" },
            value = dialogState.title,
            onValueChange = { onDialogStateChange(dialogState.copy(title = it)) },
            label = { Text("Title") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .semantics { contentDescription = "Desktop note details field" },
            value = dialogState.details,
            onValueChange = { onDialogStateChange(dialogState.copy(details = it)) },
            label = { Text("Details") },
        )
        OutlinedTextField(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Desktop note tags field" },
            value = dialogState.tagsRaw,
            onValueChange = { onDialogStateChange(dialogState.copy(tagsRaw = it)) },
            label = { Text("Tags") },
            singleLine = true,
        )
        desktopChoiceRow(
            label = "Dimension",
            value = selectedDefinition.fallbackLabel,
            onAdvance = {
                onDialogStateChange(
                    dialogState.copy(
                        dimensionId = advanceDesktopNoteDialogDimension(dialogState.dimensionId, dimensionOptions),
                    ),
                )
            },
        )
    }
}

@Composable
private fun desktopNoteCard(
    note: DesktopNoteRecord,
    onEdit: () -> Unit,
) {
    Card(
        backgroundColor = desktopSurfaceColor(),
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Desktop note ${note.title}"
                        stateDescription = note.dimensionLabel
                    }.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    modifier = Modifier.semantics { contentDescription = "Edit desktop note ${note.title}" },
                    onClick = onEdit,
                ) {
                    Text("Edit")
                }
            }
            Text(
                text = note.dimensionLabel,
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            note.details?.takeIf(String::isNotBlank)?.let { details ->
                Text(
                    text = details,
                    style = MaterialTheme.typography.body2,
                    color = desktopBodyTextColor(),
                )
            }
            if (note.tags.isNotEmpty()) {
                Text(
                    text = note.tags.joinToString(" • "),
                    style = MaterialTheme.typography.caption,
                    color = desktopMutedTextColor(),
                )
            }
        }
    }
}

@Suppress("MagicNumber")
private fun advanceDesktopNoteFilter(
    selectedDimensionId: String?,
    dimensionOptions: List<DesktopNoteDimensionOption>,
): String? =
    if (selectedDimensionId == null) {
        dimensionOptions.firstOrNull()?.id
    } else {
        val currentIndex = dimensionOptions.indexOfFirst { it.id == selectedDimensionId }
        if (currentIndex < 0 || currentIndex == dimensionOptions.lastIndex) {
            null
        } else {
            dimensionOptions[currentIndex + NEXT_INDEX_OFFSET].id
        }
    }

private fun advanceDesktopNoteDialogDimension(
    noteDimensionId: String,
    dimensionOptions: List<DesktopNoteDimensionOption>,
): String {
    val currentIndex = dimensionOptions.indexOfFirst { it.id == noteDimensionId }
    return if (currentIndex < 0 || currentIndex == dimensionOptions.lastIndex) {
        dimensionOptions.first().id
    } else {
        dimensionOptions[currentIndex + NEXT_INDEX_OFFSET].id
    }
}

private data class DesktopNoteDialogState(
    val isVisible: Boolean,
    val editingNoteId: String?,
    val title: String,
    val details: String,
    val tagsRaw: String,
    val dimensionId: String,
) {
    companion object {
        /**
         * Closed dialog state.
         */
        fun hidden(): DesktopNoteDialogState =
            DesktopNoteDialogState(
                isVisible = false,
                editingNoteId = null,
                title = "",
                details = "",
                tagsRaw = "",
                dimensionId = DesktopNoteContracts.DEFAULT_DIMENSION_ID,
            )
        /**
         * Blank dialog for creating a note.
         */
        fun create(): DesktopNoteDialogState = hidden().copy(isVisible = true)
        /**
         * Dialog pre-filled with [note]'s current values.
         */
        fun edit(note: DesktopNoteRecord): DesktopNoteDialogState =
            DesktopNoteDialogState(
                isVisible = true,
                editingNoteId = note.id,
                title = note.title,
                details = note.details.orEmpty(),
                tagsRaw = note.tags.joinToString(", "),
                dimensionId = note.dimensionId,
            )
    }
}

private data class DesktopNoteDimensionOption(
    val id: String,
    val fallbackLabel: String,
)

private fun desktopNoteDimensionOptions(): List<DesktopNoteDimensionOption> =
    listOf(
        DesktopNoteDimensionOption("dim_physical_health", "Physical Health"),
        DesktopNoteDimensionOption("dim_mental_health", "Mental Health"),
        DesktopNoteDimensionOption("dim_family_relationships", "Family & Relationships"),
        DesktopNoteDimensionOption("dim_home_environment", "Home & Environment"),
        DesktopNoteDimensionOption(DesktopNoteContracts.DEFAULT_DIMENSION_ID, DesktopNoteContracts.DEFAULT_DIMENSION_LABEL),
        DesktopNoteDimensionOption("dim_money_finance", "Money & Finance"),
        DesktopNoteDimensionOption("dim_learning_growth", "Learning & Growth"),
        DesktopNoteDimensionOption("dim_recreation_leisure", "Recreation & Leisure"),
        DesktopNoteDimensionOption("dim_community_service", "Community & Service"),
    )

private fun desktopDefaultNoteDimension(): DesktopNoteDimensionOption =
    DesktopNoteDimensionOption(
        id = DesktopNoteContracts.DEFAULT_DIMENSION_ID,
        fallbackLabel = DesktopNoteContracts.DEFAULT_DIMENSION_LABEL,
    )
