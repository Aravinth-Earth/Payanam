//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import io.payanam.domain.repository.TagRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the time-entry tag editor: global tag suggestions plus the tag
 * sets of the entry/task currently being edited.
 */
data class TimeTagEditorUiState(
    val tagSuggestions: List<String> = emptyList(),
    val editingEntryTags: List<String> = emptyList(),
    val editingTaskTags: List<String> = emptyList(),
)

/**
 * Tag-editor ViewModel shared by the Time screen: streams global tag
 * suggestions and the live tag sets of the entry/task under edit, and
 * persists tag replacements.
 */
class TimeTagEditorViewModel(
    private val tagRepository: TagRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current
    private val _uiState = MutableStateFlow(TimeTagEditorUiState())
    val uiState: StateFlow<TimeTagEditorUiState> = _uiState.asStateFlow()

    private var entryTagsJob: Job? = null
    private var taskTagsJob: Job? = null

    init {
        observeTagSuggestions()
    }

    private fun observeTagSuggestions() {
        scope.launch {
            tagRepository
                .observeAllTags()
                .map { tags -> tags.map { it.name } }
                .collect { names ->
                    _uiState.update { it.copy(tagSuggestions = names) }
                }
        }
    }

    /**
     * Streams the tags of [entryId] into state (cancels any previous stream).
     */
    fun loadEntryTags(entryId: String?) {
        entryTagsJob?.cancel()
        if (entryId.isNullOrBlank()) {
            _uiState.update { it.copy(editingEntryTags = emptyList()) }
            return
        }
        entryTagsJob =
            scope.launch {
                tagRepository.observeTagsForTimeEntry(entryId).collect { tags ->
                    _uiState.update { it.copy(editingEntryTags = tags.map { tag -> tag.name }) }
                }
            }
    }

    /**
     * Replaces the time entry's full tag set.
     */
    fun saveEntryTags(
        entryId: String,
        tags: List<String>,
    ) {
        scope.launch {
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
     * Streams the tags of [taskId] into state (cancels any previous stream).
     */
    fun loadTaskTags(taskId: String?) {
        taskTagsJob?.cancel()
        if (taskId.isNullOrBlank()) {
            _uiState.update { it.copy(editingTaskTags = emptyList()) }
            return
        }
        taskTagsJob =
            scope.launch {
                tagRepository.observeTagsForTask(taskId).collect { tags ->
                    _uiState.update { it.copy(editingTaskTags = tags.map { tag -> tag.name }) }
                }
            }
    }

    /**
     * Replaces the task's full tag set.
     */
    fun saveTaskTags(
        taskId: String,
        tags: List<String>,
    ) {
        scope.launch {
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

    /**
     * Cancels the scope this ViewModel runs on.
     *
     * The platform calls this at the point it drops the ViewModel, so the collectors started here stop with
     * it. Through the default factory the scope is this ViewModel's own, which is the case [dispose] exists
     * for. A caller that passed its own [scope] has that scope cancelled here too - it is the same scope the
     * ViewModel is running on, so it cannot be released selectively - and must not reuse it afterwards.
     */
    fun dispose() {
        scope.cancel()
    }
}

/**
 * The one-line entry point a platform's destination body calls to get the TimeTagEditor ViewModel.
 *
 * The repositories come from [LocalDataLayer], which the platform installed at startup with its own
 * implementation. Call sites on both platforms are therefore identical and contain no wiring.
 */
fun timeTagEditorViewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): TimeTagEditorViewModel =
    TimeTagEditorViewModel(
        tagRepository = LocalDataLayer.current.tags,
        scope = scope,
    )
