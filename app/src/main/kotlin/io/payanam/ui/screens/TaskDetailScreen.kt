//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package io.payanam.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.components.StatusAction
import io.payanam.ui.components.StatusNoteDialog
import io.payanam.ui.viewmodel.TaskDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * Task detail screen.
 */
fun TaskDetailScreen(
    /** Task id. */
    taskId: String,
    viewModel: TaskDetailViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onNavigateToEdit: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var currentStatusAction by remember { mutableStateOf<StatusAction?>(null) }
    var showRescheduleDialog by remember { mutableStateOf(false) }

    /** Launched effect. */
    LaunchedEffect(taskId) {
        logger.d("TaskDetailScreen.LaunchedEffect", "Loading task", mapOf("taskId" to taskId))
        viewModel.loadTask(taskId)
    }

    /** Scaffold. */
    Scaffold(
        topBar = {
            /** Top app bar. */
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_details)) },
                navigationIcon = {
                    /** Icon button. */
                    IconButton(onClick = onNavigateBack) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_back),
                        )
                    }
                },
                actions = {
                    /** Icon button. */
                    IconButton(onClick = onNavigateToEdit) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_edit),
                        )
                    }
                    /** Icon button. */
                    IconButton(onClick = { showDeleteDialog = true }) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> {
                /** Box. */
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    /** Circular progress indicator. */
                    CircularProgressIndicator()
                }
            }

            uiState.task == null -> {
                /** Box. */
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_not_found))
                }
            }

            else -> {
                /** Task detail content. */
                TaskDetailContent(
                    task = uiState.task!!,
                    recurrenceRule = uiState.task!!.recurrenceRule,
                    occurrenceHistory = uiState.occurrenceHistory,
                    isLoadingOccurrences = uiState.isLoadingOccurrences,
                    rescheduleHistory = uiState.rescheduleHistory,
                    isLoadingReschedules = uiState.isLoadingReschedules,
                    completionStats = uiState.completionStats,
                    latestL1 = uiState.latestL1,
                    windowSizeDays = uiState.windowSizeDays,
                    windowEnd = uiState.windowEnd,
                    windowRows = uiState.windowRows,
                    windowOccurrences = uiState.windowOccurrences,
                    isLoadingWindow = uiState.isLoadingWindow,
                    showChartView = uiState.showChartView,
                    onWindowSizeChange = viewModel::setWindowSizeDays,
                    onWindowBack = viewModel::shiftWindowBack,
                    onWindowForward = viewModel::shiftWindowForward,
                    onWindowToday = viewModel::jumpWindowToToday,
                    onChartViewChange = viewModel::setChartView,
                    onComplete = { currentStatusAction = StatusAction.COMPLETE },
                    onSkip = { currentStatusAction = StatusAction.SKIP },
                    onMiss = { currentStatusAction = StatusAction.MISS },
                    onReschedule = { showRescheduleDialog = true },
                    onArchive = {
                        viewModel.archiveTask()
                        /** On navigate back. */
                        onNavigateBack()
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }

        // Status note dialog
        currentStatusAction?.let { action ->
            /** Status note dialog. */
            StatusNoteDialog(
                isVisible = true,
                action = action,
                taskTitle = uiState.task?.title ?: "",
                isRecurring = uiState.task?.recurrenceEnabled == true,
                onConfirm = { result ->
                    /** When. */
                    when (result.action) {
                        StatusAction.COMPLETE -> {
                            viewModel.completeTask(
                                note = result.note.ifBlank { null },
                                reason = null,
                                nextDueStrategy = result.nextDueStrategy,
                            )
                        }

                        StatusAction.SKIP -> {
                            viewModel.skipTask(
                                note = result.note.ifBlank { null },
                                reason = result.reason?.name,
                                nextDueStrategy = result.nextDueStrategy,
                            )
                        }

                        StatusAction.MISS -> {
                            viewModel.missTask(
                                note = result.note.ifBlank { null },
                                reason = result.reason?.name,
                            )
                        }
                    }
                    currentStatusAction = null
                    /** On navigate back. */
                    onNavigateBack()
                },
                onDismiss = { currentStatusAction = null },
            )
        }

        // Delete confirmation dialog
        /** If. */
        if (showDeleteDialog) {
            /** Alert dialog. */
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_delete_task)) },
                text = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_this_action_cannot_be_undone)) },
                confirmButton = {
                    /** Text button. */
                    TextButton(
                        onClick = {
                            viewModel.deleteTask()
                            /** On navigate back. */
                            onNavigateBack()
                        },
                    ) {
                        /** Text. */
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    /** Text button. */
                    TextButton(onClick = { showDeleteDialog = false }) {
                        /** Text. */
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                    }
                },
            )
        }

        /** If. */
        if (showRescheduleDialog) {
            /** Task. */
            val task = uiState.task
            /** Due date. */
            val dueDate = task?.dueDate
            /** If. */
            if (dueDate != null) {
                /** Reschedule dialog. */
                RescheduleDialog(
                    currentDueDate = dueDate,
                    onConfirm = { newDue ->
                        showRescheduleDialog = false
                        viewModel.rescheduleTask(newDue)
                    },
                    onDismiss = { showRescheduleDialog = false },
                )
            } else {
                showRescheduleDialog = false
            }
        }
    }
}
