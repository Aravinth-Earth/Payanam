//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.notes

import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Notes screen: all notes + the search/dimension-filtered
 * subset, per-note tag names, tag suggestions for the editor, and
 * loading/error flags.
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

/**
 * Notes-screen ViewModel: observes notes + tags from the repositories,
 * applies search/dimension filtering, and drives note CRUD with tag
 * replacement.
 *
 * Moved from `:app` with its logic unchanged. What changed in the move, and why:
 *
 *  - **No `@HiltViewModel`, no `androidx.lifecycle.ViewModel`.** Hilt is Android-only and this class is
 *    constructed by the desktop too. It is a plain class; the platform decides how it is retained.
 *  - **[scope] replaces `viewModelScope`.** The Android lifecycle is not available here, so the scope is
 *    explicit. The default is owned by this ViewModel and released by [dispose]; a platform that passes
 *    its own scope owns the cancellation instead.
 *  - **Logging goes through [AppLogger]**, the process-wide holder each platform installs, instead of the
 *    Android `UnifiedLogger` singleton. Event names and payloads are unchanged, so existing log greps
 *    still match.
 *  - The repositories are constructor parameters rather than injected fields - see [notesViewModel] for
 *    the call sites' one-line entry point.
 */
class NotesViewModel(
    private val noteRepository: NoteRepository,
    private val tagRepository: TagRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current

    private val _uiState = MutableStateFlow(NotesScreenUiState())

    /** The state the Notes screen renders. */
    val uiState: StateFlow<NotesScreenUiState> = _uiState.asStateFlow()

    init {
        observeTagSuggestions()
        loadNotes()
    }

    private fun observeTagSuggestions() {
        logger.d("NotesViewModel.observeTagSuggestions", "Subscribing to tag suggestions")
        scope.launch {
            tagRepository
                .observeAllTags()
                .map { tags -> tags.map { it.name } }
                .collect { names ->
                    _uiState.update { it.copy(tagSuggestions = names) }
                }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    private fun loadNotes() {
        logger.d("NotesViewModel.loadNotes", "Loading notes")
        scope.launch {
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
            } catch (e: CancellationException) {
                // A cancelled collection is NOT a load failure. Rethrowing keeps structured
                // concurrency intact, and stops a normal cancellation being logged as an ERROR -
                // which is exactly what the cross-platform log-parity check caught on the desktop.
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("NotesViewModel.loadNotes", "Error loading notes", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Filters the visible notes by [query] (title/details substring).
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
     * Restricts the visible notes to one life dimension (null = all).
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
    ): List<Note> =
        notes.filter { note ->
            val matchesQuery =
                query.isBlank() ||
                    note.title.contains(query, ignoreCase = true) ||
                    note.details?.contains(query, ignoreCase = true) == true
            val selectedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id
            val noteCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(note.dimensionId)?.id
            val matchesDimension =
                dimensionId == null ||
                    note.dimensionId == dimensionId ||
                    (selectedCanonicalId != null && selectedCanonicalId == noteCanonicalId)

            matchesQuery && matchesDimension
        }

    /**
     * Creates a note in [dimensionId] and attaches its initial [tags].
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun createNote(
        title: String,
        details: String?,
        dimensionId: String,
        dimensionLabel: String,
        tags: List<String>,
    ) {
        scope.launch {
            try {
                val input =
                    NoteInput(
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
            } catch (e: CancellationException) {
                // A cancelled collection is NOT a load failure. Rethrowing keeps structured
                // concurrency intact, and stops a normal cancellation being logged as an ERROR -
                // which is exactly what the cross-platform log-parity check caught on the desktop.
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("NotesViewModel.createNote", "Error creating note", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Saves edits to an existing note and replaces its tag set.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun updateNote(
        noteId: String,
        title: String,
        details: String?,
        dimensionId: String,
        dimensionLabel: String,
        tags: List<String>,
    ) {
        scope.launch {
            try {
                val input =
                    NoteInput(
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
            } catch (e: CancellationException) {
                // A cancelled collection is NOT a load failure. Rethrowing keeps structured
                // concurrency intact, and stops a normal cancellation being logged as an ERROR -
                // which is exactly what the cross-platform log-parity check caught on the desktop.
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("NotesViewModel.updateNote", "Error updating note", e, mapOf("noteId" to noteId))
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Permanently deletes a note.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun deleteNote(noteId: String) {
        scope.launch {
            try {
                noteRepository.deleteNote(noteId)
                logger.i("NotesViewModel.deleteNote", "Note deleted", mapOf("noteId" to noteId))
            } catch (e: CancellationException) {
                // A cancelled collection is NOT a load failure. Rethrowing keeps structured
                // concurrency intact, and stops a normal cancellation being logged as an ERROR -
                // which is exactly what the cross-platform log-parity check caught on the desktop.
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("NotesViewModel.deleteNote", "Error deleting note", e, mapOf("noteId" to noteId))
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Clears the current error message shown on the screen.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Releases the coroutine scope this ViewModel created.
     *
     * A no-op if the caller passed its own [scope] - that scope is the caller's to cancel.
     */
    fun dispose() {
        scope.cancel()
    }
}

/**
 * The one-line entry point a platform's destination body calls to get the Notes ViewModel.
 *
 * The repositories come from [LocalDataLayer], which the platform installed at startup with its own
 * implementation. Call sites on both platforms are therefore identical and contain no wiring.
 */
fun notesViewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): NotesViewModel =
    NotesViewModel(
        noteRepository = LocalDataLayer.current.notes,
        tagRepository = LocalDataLayer.current.tags,
        scope = scope,
    )
