//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming", "UndocumentedPublicProperty")

package io.payanam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger

/**
 * Status change action type
 */
enum class StatusAction(@androidx.annotation.StringRes val labelRes: Int) {
    SKIP(io.payanam.R.string.task_notification_action_skip),
    MISS(io.payanam.R.string.loc_miss),
    COMPLETE(io.payanam.R.string.task_notification_action_complete),
}

/**
 * Predefined reasons for skipping/missing tasks
 */
enum class SkipReason(@androidx.annotation.StringRes val labelRes: Int) {
    NO_TIME(io.payanam.R.string.loc_not_enough_time),
    LOW_ENERGY(io.payanam.R.string.loc_low_energy_today),
    BLOCKED(io.payanam.R.string.loc_blocked_by_something),
    RESCHEDULED(io.payanam.R.string.loc_rescheduled_to_later),
    NOT_RELEVANT(io.payanam.R.string.loc_no_longer_relevant),
    OTHER(io.payanam.R.string.loc_other_specify),
}

/**
 * Result returned from the StatusNoteDialog
 */
data class StatusNoteResult(
    val action: StatusAction,
    val reason: SkipReason?,
    val note: String,
    // "planned" or "actual" for recurring tasks
    val nextDueStrategy: String? = null,
)

/**
 * Dialog shown when user skips or misses a task.
 * Allows optional reason selection and note input.
 *
 * @param isVisible Whether the dialog is shown
 * @param action The status action being taken (SKIP, MISS, or COMPLETE)
 * @param taskTitle Title of the task for display
 * @param isRecurring Whether this is a recurring task (shows next due strategy option)
 * @param onConfirm Callback with the result when confirmed
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
/**
 * Status note dialog.
 */
fun StatusNoteDialog(
    isVisible: Boolean,
    action: StatusAction,
    taskTitle: String,
    isRecurring: Boolean = false,
    onConfirm: (StatusNoteResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val logger = UnifiedLogger.getInstance()
    if (!isVisible) return

    var selectedReason by remember { mutableStateOf<SkipReason?>(null) }
    var noteText by remember { mutableStateOf("") }
    var nextDueStrategy by remember { mutableStateOf("planned") } // Default to planned
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (action) {
                    StatusAction.SKIP -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_skip_task)
                    StatusAction.MISS -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mark_as_missed)
                    StatusAction.COMPLETE -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_complete_task)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (action != StatusAction.COMPLETE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            id = if (action == StatusAction.SKIP) {
                                io.payanam.R.string.loc_why_are_you_skipping_this_task
                            } else {
                                io.payanam.R.string.loc_why_are_you_missing_this_task
                            },
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )

                    // Reason selection
                    Column(modifier = Modifier.selectableGroup()) {
                        SkipReason.entries.forEach { reason ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selectedReason == reason,
                                        onClick = { selectedReason = reason },
                                        role = Role.RadioButton,
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedReason == reason,
                                    onClick = null, // handled by row
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = androidx.compose.ui.res.stringResource(id = reason.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                // Note input (always shown, but label changes based on context)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            when {
                                action == StatusAction.COMPLETE -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_completion_note_optional)
                                selectedReason == SkipReason.OTHER -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_please_specify)
                                else -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_additional_notes_optional)
                            },
                        )
                    },
                    placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_add_any_details)) },
                    minLines = 2,
                    maxLines = 4,
                )

                // Next due date strategy for recurring tasks
                if (isRecurring && (action == StatusAction.COMPLETE || action == StatusAction.SKIP)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_next_occurrence_timing),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Column(modifier = Modifier.selectableGroup()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = nextDueStrategy == "planned",
                                    onClick = { nextDueStrategy = "planned" },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = nextDueStrategy == "planned",
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_follow_original_schedule),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = nextDueStrategy == "actual",
                                    onClick = { nextDueStrategy = "actual" },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = nextDueStrategy == "actual",
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_based_on_completion_time),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    logger.i("StatusNoteDialog", "Confirming status change", mapOf("taskTitle" to taskTitle, "action" to action.name, "reason" to selectedReason?.name, "note" to noteText.trim(), "nextDueStrategy" to nextDueStrategy))
                    onConfirm(
                        StatusNoteResult(
                            action = action,
                            reason = if (action != StatusAction.COMPLETE) selectedReason else null,
                            note = noteText.trim(),
                            nextDueStrategy = if (isRecurring && (action == StatusAction.COMPLETE || action == StatusAction.SKIP)) nextDueStrategy else null,
                        ),
                    )
                },
            ) {
                Text(
                    when (action) {
                        StatusAction.SKIP -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.task_notification_action_skip)
                        StatusAction.MISS -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mark_missed)
                        StatusAction.COMPLETE -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.task_notification_action_complete)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
    )
}

/**
 * Quick action dialog for completing a task without detailed notes.
 * Just shows a minimal confirmation.
 */
@Composable
/**
 * Quick complete dialog.
 */
fun QuickCompleteDialog(
    isVisible: Boolean,
    taskTitle: String,
    onConfirm: () -> Unit,
    onConfirmWithNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isVisible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_complete_task)) },
        text = {
            Column {
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onConfirmWithNote) {
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_add_note))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onConfirm) {
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_done))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
    )
}
