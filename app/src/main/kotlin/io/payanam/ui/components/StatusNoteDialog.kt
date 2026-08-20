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
    /** Skip. */
    SKIP(io.payanam.R.string.task_notification_action_skip),
    /** Miss. */
    MISS(io.payanam.R.string.loc_miss),
    /** Complete. */
    COMPLETE(io.payanam.R.string.task_notification_action_complete),
}

/**
 * Predefined reasons for skipping/missing tasks
 */
enum class SkipReason(@androidx.annotation.StringRes val labelRes: Int) {
    /** No time. */
    NO_TIME(io.payanam.R.string.loc_not_enough_time),
    /** Low energy. */
    LOW_ENERGY(io.payanam.R.string.loc_low_energy_today),
    /** Blocked. */
    BLOCKED(io.payanam.R.string.loc_blocked_by_something),
    /** Rescheduled. */
    RESCHEDULED(io.payanam.R.string.loc_rescheduled_to_later),
    /** Not relevant. */
    NOT_RELEVANT(io.payanam.R.string.loc_no_longer_relevant),
    /** Other. */
    OTHER(io.payanam.R.string.loc_other_specify),
}

/**
 * Result returned from the StatusNoteDialog
 */
data class StatusNoteResult(
    /** Action. */
    val action: StatusAction,
    /** Reason. */
    val reason: SkipReason?,
    /** Note. */
    val note: String,
    // "planned" or "actual" for recurring tasks
    /** Next due strategy. */
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
    /** Is visible. */
    isVisible: Boolean,
    /** Action. */
    action: StatusAction,
    /** Task title. */
    taskTitle: String,
    isRecurring: Boolean = false,
    onConfirm: (StatusNoteResult) -> Unit,
    onDismiss: () -> Unit,
) {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** If. */
    if (!isVisible) return

    var selectedReason by remember { mutableStateOf<SkipReason?>(null) }
    var noteText by remember { mutableStateOf("") }
    var nextDueStrategy by remember { mutableStateOf("planned") } // Default to planned

    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            /** Text. */
            Text(
                text = when (action) {
                    StatusAction.SKIP -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_skip_task)
                    StatusAction.MISS -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mark_as_missed)
                    StatusAction.COMPLETE -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_complete_task)
                },
            )
        },
        text = {
            /** Column. */
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                /** Text. */
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                /** If. */
                if (action != StatusAction.COMPLETE) {
                    /** Spacer. */
                    Spacer(modifier = Modifier.height(8.dp))

                    /** Text. */
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
                    /** Column. */
                    Column(modifier = Modifier.selectableGroup()) {
                        SkipReason.entries.forEach { reason ->
                            /** Row. */
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
                                /** Radio button. */
                                RadioButton(
                                    selected = selectedReason == reason,
                                    onClick = null, // handled by row
                                )
                                /** Spacer. */
                                Spacer(modifier = Modifier.width(8.dp))
                                /** Text. */
                                Text(
                                    text = androidx.compose.ui.res.stringResource(id = reason.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                // Note input (always shown, but label changes based on context)
                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))

                /** Outlined text field. */
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        /** Text. */
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
                /** If. */
                if (isRecurring && (action == StatusAction.COMPLETE || action == StatusAction.SKIP)) {
                    /** Spacer. */
                    Spacer(modifier = Modifier.height(16.dp))

                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_next_occurrence_timing),
                        style = MaterialTheme.typography.labelLarge,
                    )

                    /** Column. */
                    Column(modifier = Modifier.selectableGroup()) {
                        /** Row. */
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
                            /** Radio button. */
                            RadioButton(
                                selected = nextDueStrategy == "planned",
                                onClick = null,
                            )
                            /** Spacer. */
                            Spacer(modifier = Modifier.width(8.dp))
                            /** Text. */
                            Text(
                                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_follow_original_schedule),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        /** Row. */
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
                            /** Radio button. */
                            RadioButton(
                                selected = nextDueStrategy == "actual",
                                onClick = null,
                            )
                            /** Spacer. */
                            Spacer(modifier = Modifier.width(8.dp))
                            /** Text. */
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
            /** Text button. */
            TextButton(
                onClick = {
                    logger.i("StatusNoteDialog", "Confirming status change", mapOf("taskTitle" to taskTitle, "action" to action.name, "reason" to selectedReason?.name, "note" to noteText.trim(), "nextDueStrategy" to nextDueStrategy))
                    /** On confirm. */
                    onConfirm(
                        /** Status note result. */
                        StatusNoteResult(
                            action = action,
                            reason = if (action != StatusAction.COMPLETE) selectedReason else null,
                            note = noteText.trim(),
                            nextDueStrategy = if (isRecurring && (action == StatusAction.COMPLETE || action == StatusAction.SKIP)) nextDueStrategy else null,
                        ),
                    )
                },
            ) {
                /** Text. */
                Text(
                    /** When. */
                    when (action) {
                        StatusAction.SKIP -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.task_notification_action_skip)
                        StatusAction.MISS -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mark_missed)
                        StatusAction.COMPLETE -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.task_notification_action_complete)
                    },
                )
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(onClick = onDismiss) {
                /** Text. */
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
    /** Is visible. */
    isVisible: Boolean,
    /** Task title. */
    taskTitle: String,
    onConfirm: () -> Unit,
    onConfirmWithNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    /** If. */
    if (!isVisible) return

    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_complete_task)) },
        text = {
            Column {
                /** Text. */
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            Row {
                /** Text button. */
                TextButton(onClick = onConfirmWithNote) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_add_note))
                }
                /** Spacer. */
                Spacer(modifier = Modifier.width(8.dp))
                /** Text button. */
                TextButton(onClick = onConfirm) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_done))
                }
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(onClick = onDismiss) {
                /** Text. */
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
    )
}
