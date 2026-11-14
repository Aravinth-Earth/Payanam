//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.navigation

import androidx.compose.runtime.compositionLocalOf

/** One-shot request to open [taskId]'s status dialog; [onHandled] clears it before the dialog opens. */
class TaskStatusDialogRequest(
    val taskId: String,
    val requestId: Long,
    val onHandled: (Long) -> Unit,
)

/** Null everywhere except the task-detail destination of a graph handling an external request. */
val LocalTaskStatusDialogRequest = compositionLocalOf<TaskStatusDialogRequest?> { null }
