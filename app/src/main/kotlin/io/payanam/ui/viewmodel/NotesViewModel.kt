//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TagRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
/**
 * Holds the notes screen ui state.
 */
data class NotesScreenUiState(
    val notes: List<Note> = emptyList(),
    val filteredNotes: List<Note> = emptyList(),
    val noteTagsById: Map<String, List<String>> = emptyMap(),
    val tagSuggestions: List<String> = emptyList(),
    val searchQuery: String = "",
    val selectedDimensionId: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
/**
 * Provides the notes view model.
 */
class NotesViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    private val tagRepository: TagRepository,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(NotesScreenUiState())
    val uiState: StateFlow<NotesScreenUiState> = _uiState.asStateFlow()

    init {
        observeTagSuggestions()
        loadNotes()
    }

    private fun observeTagSuggestions() {
        logger.d("NotesViewModel.observeTagSuggestions", "Subscribing to tag suggestions")
        viewModelScope.launch {
            tagRepository.observeAllTags()
                .map { tags -> tags.map { it.name } }
                .collect { names ->
                    _uiState.update { it.copy(tagSuggestions = names) }
                }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun loadNotes() {
        logger.d("NotesViewModel.loadNotes", "Loading notes")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                noteRepository.getAllNotes().collect { notes ->
                    val noteTagsById = tagRepository.getTagNamesForNotes(notes.map { it.id })
                    _uiState.update { state ->
                        state.copy(
                            notes = notes,
                            filteredNotes = filterNotes(notes, state.searchQuery, state.selectedDimensionId),
                            noteTagsById = noteTagsById,
                            isLoading = false,
                        )
                    }
                }
            } catch (e: Exception) {
                logger.e("NotesViewModel.loadNotes", "Error loading notes", e, null)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
    /**
     * Updates the update search query.
     */
    fun updateSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredNotes = filterNotes(state.notes, query, state.selectedDimensionId),
            )
        }
    }
    /**
     * Updates the set dimension filter.
     */
    fun setDimensionFilter(dimensionId: String?) {
        _uiState.update { state ->
            state.copy(
                selectedDimensionId = dimensionId,
                filteredNotes = filterNotes(state.notes, state.searchQuery, dimensionId),
            )
        }
    }

    private fun filterNotes(
        notes: List<Note>,
        query: String,
        dimensionId: String?,
    ): List<Note> = notes.filter { note ->
        val matchesQuery = query.isBlank() ||
            note.title.contains(query, ignoreCase = true) ||
            note.details?.contains(query, ignoreCase = true) == true
        val selectedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id
        val noteCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(note.dimensionId)?.id
        val matchesDimension = dimensionId == null ||
            note.dimensionId == dimensionId ||
            (selectedCanonicalId != null && selectedCanonicalId == noteCanonicalId)

        matchesQuery && matchesDimension
    }
    /**
     * Creates the create note.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun createNote(title: String, details: String?, dimensionId: String, dimensionLabel: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                val input = NoteInput(
                    title = title,
                    details = details,
                    dimensionId = dimensionId,
                    lifeIntentionCategory = dimensionLabel,
                )
                val note = noteRepository.createNote(input)
                if (tags.isNotEmpty()) {
                    tagRepository.replaceNoteTags(note.id, tags)
                }
                logger.i("NotesViewModel.createNote", "Note created", mapOf("tagCount" to tags.size))
            } catch (e: Exception) {
                logger.e("NotesViewModel.createNote", "Error creating note", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Updates the update note.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun updateNote(noteId: String, title: String, details: String?, dimensionId: String, dimensionLabel: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                val input = NoteInput(
                    title = title,
                    details = details,
                    dimensionId = dimensionId,
                    lifeIntentionCategory = dimensionLabel,
                )
                noteRepository.updateNote(noteId, input)
                tagRepository.replaceNoteTags(noteId, tags)
                logger.i(
                    "NotesViewModel.updateNote",
                    "Note updated",
                    mapOf(
                        "noteId" to noteId,
                        "tagCount" to tags.size,
                    ),
                )
            } catch (e: Exception) {
                logger.e("NotesViewModel.updateNote", "Error updating note", e, mapOf("noteId" to noteId))
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Removes the delete note.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            try {
                noteRepository.deleteNote(noteId)
                logger.i("NotesViewModel.deleteNote", "Note deleted", mapOf("noteId" to noteId))
            } catch (e: Exception) {
                logger.e("NotesViewModel.deleteNote", "Error deleting note", e, mapOf("noteId" to noteId))
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Removes the clear error.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
