//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.DimensionCompactBadge
import io.payanam.core.ui.components.DimensionIdentityRow
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.model.DimensionIconCatalog
import io.payanam.core.ui.preferences.DimensionOption
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.colorFor
import io.payanam.core.ui.preferences.colorForDimensionId
import io.payanam.core.ui.preferences.labelFor
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.core.ui.preferences.optionsForSelection
import io.payanam.core.ui.preferences.visibleDimensionOptions
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Note
import io.payanam.presentation.notes.NotesScreenUiState
import io.payanam.presentation.notes.NotesViewModel
import io.payanam.shared.logging.AppLogger
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(viewModel: NotesViewModel) {
    val strings = LocalUiStrings.current
    val uiState by viewModel.uiState.collectAsState()
    val logger = AppLogger.current
    val prefs = LocalAppPreferences.current
    val dimensionOptions = prefs.visibleDimensionOptions()
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var showEditNoteDialog by remember { mutableStateOf<Note?>(null) }
    LaunchedEffect(dimensionOptions, uiState.selectedDimensionId) {
        val visibleIds = dimensionOptions.map { it.id }.toSet()
        if (uiState.selectedDimensionId != null && uiState.selectedDimensionId !in visibleIds) {
            viewModel.setDimensionFilter(null)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.settingsDatabaseNotes) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddNoteDialog = true }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = strings.locAddNote,
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // Search bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text(strings.locSearchNotes) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(
                                Icons.Default.Clear,
                                strings.locClearSearch,
                            )
                        }
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
            )

            // Dimension filter chips
            LazyRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = uiState.selectedDimensionId == null,
                        onClick = { viewModel.setDimensionFilter(null) },
                        label = { Text(strings.locAll) },
                    )
                }
                items(dimensionOptions) { dimension ->
                    FilterChip(
                        selected = uiState.selectedDimensionId == dimension.id,
                        onClick = {
                            viewModel.setDimensionFilter(
                                if (uiState.selectedDimensionId == dimension.id) null else dimension.id,
                            )
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                DimensionCompactBadge(
                                    label = dimension.label,
                                    color = dimension.color,
                                    iconOption = DimensionIconCatalog.resolve(dimension.iconKey, dimension.id),
                                    size = 22.dp,
                                )
                                Text(dimension.label.split(" ").first())
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Notes list
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.filteredNotes.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = strings.locNotesEmptyIcon,
                                style = MaterialTheme.typography.displayLarge,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text =
                                    if (uiState.notes.isEmpty()) {
                                        strings.locNoNotesYet
                                    } else {
                                        strings.locNoMatchingNotes
                                    },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text =
                                    if (uiState.notes.isEmpty()) {
                                        strings.locTapAddFirstNote
                                    } else {
                                        strings.locTryAdjustingSearch
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            androidx.compose.foundation.layout
                                .PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = uiState.filteredNotes,
                            key = { note -> note.id },
                        ) { note ->
                            NoteCard(
                                note = note,
                                tags = uiState.noteTagsById[note.id].orEmpty(),
                                onClick = { showEditNoteDialog = note },
                            )
                        }
                    }
                }
            }
        }
    }

    // Add note dialog
    if (showAddNoteDialog) {
        NoteDialog(
            note = null,
            tagSuggestions = uiState.tagSuggestions,
            initialTags = emptyList(),
            onSave = { title, details, dimensionId, dimensionLabel, tags ->
                logger.i("NotesScreen.createNote", "Creating note", mapOf("dimensionId" to dimensionId))
                viewModel.createNote(title, details, dimensionId, dimensionLabel, tags)
                showAddNoteDialog = false
            },
            onDismiss = { showAddNoteDialog = false },
        )
    }

    // Edit note dialog
    showEditNoteDialog?.let { note ->
        NoteDialog(
            note = note,
            tagSuggestions = uiState.tagSuggestions,
            initialTags = uiState.noteTagsById[note.id].orEmpty(),
            onSave = { title, details, dimensionId, dimensionLabel, tags ->
                logger.i("NotesScreen.updateNote", "Updating note", mapOf("noteId" to note.id))
                viewModel.updateNote(note.id, title, details, dimensionId, dimensionLabel, tags)
                showEditNoteDialog = null
            },
            onDelete = {
                logger.i("NotesScreen.deleteNote", "Deleting note", mapOf("noteId" to note.id))
                viewModel.deleteNote(note.id)
                showEditNoteDialog = null
            },
            onDismiss = { showEditNoteDialog = null },
        )
    }
}

@Composable
private fun NoteCard(
    note: Note,
    tags: List<String>,
    onClick: () -> Unit,
) {
    val dateFormatter =
        remember {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        }
    val prefs = LocalAppPreferences.current
    val dimensionColor = prefs.colorForDimensionId(note.dimensionId) ?: prefs.colorFor(note.lifeIntentionCategory)
    val dimensionLabel = prefs.labelForDimensionId(note.dimensionId) ?: prefs.labelFor(note.lifeIntentionCategory)
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("notes_note_card")
                .clickable { onClick() },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Dimension badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DimensionIdentityRow(
                        prefs = prefs,
                        dimensionId = note.dimensionId,
                        fallbackLabel = dimensionLabel,
                        fallbackColor = dimensionColor,
                        iconTint = dimensionColor,
                        labelColor = dimensionColor,
                        dotSize = 8.dp,
                        showLabel = false,
                    )
                }

                // Date
                Text(
                    text = note.updatedAt.format(dateFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = note.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Details preview
            note.details?.let { details ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tags.take(4)) { tag ->
                        FilterChip(
                            selected = false,
                            onClick = {},
                            enabled = false,
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDialog(
    note: Note?,
    initialTags: List<String>,
    tagSuggestions: List<String>,
    onSave: (String, String?, String, String, List<String>) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    val prefs = LocalAppPreferences.current
    val fallbackDimension =
        prefs.visibleDimensionOptions().firstOrNull() ?: DimensionOption(
            id = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            label = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel,
            color = MaterialTheme.colorScheme.primary,
            isVisible = true,
            iconKey = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.defaultIconKey,
            canonicalId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
        )
    var title by remember { mutableStateOf(note?.title ?: "") }
    var details by remember { mutableStateOf(note?.details ?: "") }
    var tagsInput by remember(initialTags) { mutableStateOf(initialTags.joinToString(", ")) }
    var selectedDimensionId by remember {
        mutableStateOf(
            note?.dimensionId
                ?: fallbackDimension.id,
        )
    }
    val dimensionOptions = prefs.optionsForSelection(selectedDimensionId)
    var dimensionExpanded by remember { mutableStateOf(false) }
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (note == null) {
                    strings.locAddNote
                } else {
                    strings.locEditNote
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(strings.locTitle) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Details
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text(strings.locDetailsOptional) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    maxLines = 5,
                )
                val selectedTags = parseTagsInput(tagsInput)
                val matchingTagSuggestions =
                    tagSuggestions
                        .filter { suggestion ->
                            suggestion.contains(tagsInput.trim(), ignoreCase = true) && suggestion !in selectedTags
                        }.take(6)
                OutlinedTextField(
                    value = tagsInput,
                    onValueChange = { tagsInput = it },
                    label = { Text(strings.locTagsOptional) },
                    placeholder = { Text(strings.locTagsHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (matchingTagSuggestions.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(matchingTagSuggestions) { suggestion ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val newTags = (selectedTags + suggestion).distinct()
                                    tagsInput = newTags.joinToString(", ")
                                },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                }

                // Dimension picker
                Text(strings.locLifeDimension, style = MaterialTheme.typography.labelMedium)
                ExposedDropdownMenuBox(
                    expanded = dimensionExpanded,
                    onExpandedChange = { dimensionExpanded = it },
                ) {
                    val selectedDimensionLabel =
                        dimensionOptions.firstOrNull { it.id == selectedDimensionId }?.label
                            ?: prefs.labelForDimensionId(selectedDimensionId)
                            ?: fallbackDimension.label
                    OutlinedTextField(
                        value = selectedDimensionLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dimensionExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .testTag("notes_dimension_picker"),
                    )
                    ExposedDropdownMenu(
                        expanded = dimensionExpanded,
                        onDismissRequest = { dimensionExpanded = false },
                    ) {
                        dimensionOptions.forEach { dim ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(12.dp)
                                                    .background(
                                                        dim.color,
                                                        CircleShape,
                                                    ),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(dim.label)
                                    }
                                },
                                onClick = {
                                    selectedDimensionId = dim.id
                                    dimensionExpanded = false
                                },
                            )
                        }
                    }
                }

                // Delete button (for edit mode only)
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.locDeleteNote, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val selectedDimensionLabel =
                        dimensionOptions.firstOrNull { it.id == selectedDimensionId }?.label
                            ?: prefs.labelForDimensionId(selectedDimensionId)
                            ?: fallbackDimension.label
                    onSave(
                        title,
                        details.ifBlank { null },
                        selectedDimensionId,
                        selectedDimensionLabel,
                        parseTagsInput(tagsInput),
                    )
                },
                enabled = title.isNotBlank(),
            ) {
                Text(strings.locSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}

private fun parseTagsInput(rawTags: String): List<String> =
    rawTags
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
