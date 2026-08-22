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
fun TaskDetailScreen(
    taskId: String,
    viewModel: TaskDetailViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onNavigateToEdit: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val logger = remember { UnifiedLogger.getInstance() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var currentStatusAction by remember { mutableStateOf<StatusAction?>(null) }
    var showRescheduleDialog by remember { mutableStateOf(false) }
    LaunchedEffect(taskId) {
        logger.d("TaskDetailScreen.LaunchedEffect", "Loading task", mapOf("taskId" to taskId))
        viewModel.loadTask(taskId)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_details)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToEdit) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_edit),
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.task == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_not_found))
                }
            }

            else -> {
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
                        onNavigateBack()
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }

        // Status note dialog
        currentStatusAction?.let { action ->
            StatusNoteDialog(
                isVisible = true,
                action = action,
                taskTitle = uiState.task?.title ?: "",
                isRecurring = uiState.task?.recurrenceEnabled == true,
                onConfirm = { result ->
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
                    onNavigateBack()
                },
                onDismiss = { currentStatusAction = null },
            )
        }

        // Delete confirmation dialog
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_delete_task)) },
                text = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_this_action_cannot_be_undone)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteTask()
                            onNavigateBack()
                        },
                    ) {
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                    }
                },
            )
        }
        if (showRescheduleDialog) {
            val task = uiState.task
            val dueDate = task?.dueDate
            if (dueDate != null) {
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
