//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import io.payanam.domain.model.TaskInput
import io.payanam.domain.port.ScoreCalculator
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Add-task ViewModel: streams tag suggestions and creates tasks — persisting
 * tags, computing the initial score, and arming the reminder.
 */
class AddTaskViewModel(
    private val taskRepository: TaskRepository,
    private val tagRepository: TagRepository,
    private val notificationScheduler: TaskReminderScheduler,
    private val scoreCalculator: ScoreCalculator,
    private val resultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current
    private val _tagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val tagSuggestions: StateFlow<List<String>> = _tagSuggestions.asStateFlow()

    init {
        logger.d("AddTaskViewModel.init", "ViewModel initialized")
        observeTagSuggestions()
    }

    private fun observeTagSuggestions() {
        scope.launch {
            tagRepository
                .observeAllTags()
                .map { tags -> tags.map { it.name } }
                .collect { names ->
                    _tagSuggestions.value = names
                    logger.d(
                        "AddTaskViewModel.observeTagSuggestions",
                        "Tag suggestion stream updated",
                        mapOf("count" to names.size),
                    )
                }
        }
    }

    /**
     * Creates a task from the form fields (defaulting a recurring task's due
     * date), attaches tags, scores it, and schedules its reminder.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun createTask(
        title: String,
        description: String? = null,
        dimensionId: String,
        dimensionLabel: String,
        dueDate: LocalDateTime? = null,
        impactLevel: String = "Moderate Impact",
        goalAlignment: String = "Moderate Alignment",
        energyLevel: String = "Moderate",
        controlLevel: String = "Office/Colleagues Dependent",
        durationMinutes: Int = 60,
        recurrenceEnabled: Boolean = false,
        recurrenceRule: String? = null,
        notificationMode: String? = null,
        customNotificationMinutes: Int? = null,
        // POC Priority Fields
        explicitUrgency: Double? = null,
        focusRequired: Double? = null,
        blockedReason: String? = null,
        externalDependency: String? = null,
        tags: List<String> = emptyList(),
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        // For recurring tasks, set a default due date if none provided
        val effectiveDueDate =
            if (recurrenceEnabled && dueDate == null) {
                // Set default due date to today at the specified time, or 9 AM if no time
                val defaultTime = LocalTime.of(9, 0) // Default 9 AM
                LocalDateTime.of(LocalDate.now(), defaultTime)
            } else {
                dueDate
            }

        logger.i(
            "AddTaskViewModel.createTask",
            "Creating new task",
            mapOf(
                "title" to title,
                "dimensionId" to dimensionId,
                "hasDueDate" to (dueDate != null),
                "dueDate" to (dueDate?.toString() ?: "none"),
                "effectiveDueDate" to (effectiveDueDate?.toString() ?: "none"),
                "recurring" to recurrenceEnabled,
                "recurrenceRule" to (recurrenceRule ?: "none"),
            ),
        )

        scope.launch {
            try {
                val input =
                    TaskInput(
                        title = title,
                        description = description,
                        dueDate = effectiveDueDate,
                        dimensionId = dimensionId,
                        lifeIntentionCategory = dimensionLabel,
                        impactLevel = impactLevel,
                        goalAlignment = goalAlignment,
                        energyLevel = energyLevel,
                        controlLevel = controlLevel,
                        durationMinutes = durationMinutes,
                        recurrenceEnabled = recurrenceEnabled,
                        recurrenceRule = recurrenceRule,
                        status = "pending",
                        notificationMode = notificationMode,
                        customNotificationMinutes = customNotificationMinutes,
                        explicitUrgency = explicitUrgency,
                        focusRequired = focusRequired,
                        blockedReason = blockedReason,
                        externalDependency = externalDependency,
                    )
                val task = taskRepository.createTask(input)
                if (tags.isNotEmpty()) {
                    tagRepository.replaceTaskTags(task.id, tags)
                    logger.i(
                        "AddTaskViewModel.createTask",
                        "Task tags replaced",
                        mapOf("taskId" to task.id, "tagCount" to tags.size),
                    )
                }

                // Calculate and update score
                if (PlatformFeatureFlags.current.scoringEnabled) {
                    val score = scoreCalculator.calculateScore(task)
                    taskRepository.updateTaskScore(task.id, score)
                    logger.d(
                        "AddTaskViewModel.createTask",
                        "Task score updated after create",
                        mapOf("taskId" to task.id, "score" to score),
                    )
                }

                // Schedule reminder if applicable
                if (PlatformFeatureFlags.current.remindersEnabled) {
                    try {
                        notificationScheduler.scheduleForTask(task)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.e(
                            "AddTaskViewModel.createTask",
                            "Failed to schedule reminder",
                            e,
                            mapOf(
                                "taskId" to task.id,
                            ),
                        )
                    }
                }

                logger.i(
                    "AddTaskViewModel.createTask",
                    "Task created successfully",
                    mapOf(
                        "id" to task.id,
                        "title" to title,
                        "tagCount" to tags.size,
                        "remindersEnabled" to PlatformFeatureFlags.current.remindersEnabled,
                    ),
                )
                logger.d("AddTaskViewModel.createTask", "Task created", mapOf("title" to title))
                launch(resultDispatcher) { onResult(Result.success(Unit)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("AddTaskViewModel.createTask", "Failed to create task", e)
                launch(resultDispatcher) { onResult(Result.failure(e)) }
            }
        }
    }

    /**
     * Releases the coroutine scope this ViewModel created.
     *
     * `onCleared` used to do this: it was the androidx lifecycle hook that ran when the framework dropped the
     * ViewModel, and this class no longer has one. The platform calls this at the same point - Android through
     * the release action it registers when it retains the ViewModel, the desktop from a `DisposableEffect`.
     */
    fun dispose() {
        scope.cancel()
    }
}

/**
 * The one-line entry point a platform's destination body calls to get the AddTaskViewModel.
 *
 * Every collaborator comes from [LocalDataLayer], which the platform installed at startup with its own
 * implementation, so a call site on either platform contains no wiring and cannot supply a different data
 * source by accident.
 */
fun addTaskViewModel(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    resultDispatcher: CoroutineDispatcher = Dispatchers.Default,
): AddTaskViewModel =
    AddTaskViewModel(
        taskRepository = LocalDataLayer.current.tasks,
        tagRepository = LocalDataLayer.current.tags,
        notificationScheduler = LocalDataLayer.current.reminderScheduler,
        scoreCalculator = LocalDataLayer.current.scoreCalculator,
        resultDispatcher = resultDispatcher,
        scope = scope,
    )
