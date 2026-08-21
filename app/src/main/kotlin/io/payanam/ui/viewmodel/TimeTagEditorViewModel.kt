//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.TagRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * TimeTagEditorUiState.
 */
data class TimeTagEditorUiState(
    val tagSuggestions: List<String> = emptyList(),
    val editingEntryTags: List<String> = emptyList(),
    val editingTaskTags: List<String> = emptyList(),
)

@HiltViewModel
/**
 * TimeTagEditorViewModel.
 */
class TimeTagEditorViewModel @Inject constructor(
    private val tagRepository: TagRepository,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(TimeTagEditorUiState())
    val uiState: StateFlow<TimeTagEditorUiState> = _uiState.asStateFlow()

    private var entryTagsJob: Job? = null
    private var taskTagsJob: Job? = null

    init {
        observeTagSuggestions()
    }

    private fun observeTagSuggestions() {
        viewModelScope.launch {
            tagRepository.observeAllTags()
                .map { tags -> tags.map { it.name } }
                .collect { names ->
                    _uiState.update { it.copy(tagSuggestions = names) }
                }
        }
    }

    /**
     * Load entry tags.
     */
    fun loadEntryTags(entryId: String?) {
        entryTagsJob?.cancel()
        if (entryId.isNullOrBlank()) {
            _uiState.update { it.copy(editingEntryTags = emptyList()) }
            return
        }
        entryTagsJob = viewModelScope.launch {
            tagRepository.observeTagsForTimeEntry(entryId).collect { tags ->
                _uiState.update { it.copy(editingEntryTags = tags.map { tag -> tag.name }) }
            }
        }
    }

    /**
     * Save entry tags.
     */
    fun saveEntryTags(entryId: String, tags: List<String>) {
        viewModelScope.launch {
            tagRepository.replaceTimeEntryTags(entryId, tags)
            logger.i(
                "TimeTagEditorViewModel.saveEntryTags",
                "Updated time entry tags",
                mapOf(
                    "entryId" to entryId,
                    "tagCount" to tags.size,
                ),
            )
        }
    }

    /**
     * Load task tags.
     */
    fun loadTaskTags(taskId: String?) {
        taskTagsJob?.cancel()
        if (taskId.isNullOrBlank()) {
            _uiState.update { it.copy(editingTaskTags = emptyList()) }
            return
        }
        taskTagsJob = viewModelScope.launch {
            tagRepository.observeTagsForTask(taskId).collect { tags ->
                _uiState.update { it.copy(editingTaskTags = tags.map { tag -> tag.name }) }
            }
        }
    }

    /**
     * Save task tags.
     */
    fun saveTaskTags(taskId: String, tags: List<String>) {
        viewModelScope.launch {
            tagRepository.replaceTaskTags(taskId, tags)
            logger.i(
                "TimeTagEditorViewModel.saveTaskTags",
                "Updated task tags",
                mapOf(
                    "taskId" to taskId,
                    "tagCount" to tags.size,
                ),
            )
        }
    }
}
