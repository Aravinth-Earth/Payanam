//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming", "UndocumentedPublicProperty")

package io.payanam.core.ui.components

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
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger

/**
 * Status change action type
 */
enum class StatusAction {
    SKIP,
    MISS,
    COMPLETE,
}

/**
 * The label for this statusaction, from the generated copy table.
 *
 * A `when` rather than a stored resource id: `commonMain` has no `R`, and the `when` makes a
 * renamed or dropped string key a compile error instead of a blank label.
 */
fun StatusAction.label(strings: UiStrings): String =
    when (this) {
        StatusAction.SKIP -> strings.taskNotificationActionSkip
        StatusAction.MISS -> strings.locMiss
        StatusAction.COMPLETE -> strings.taskNotificationActionComplete
    }

/**
 * Why a task was skipped or missed, as offered by [StatusNoteDialog].
 *
 * The constants are a single-choice radio group shown only for `SKIP` and `MISS` - completing a
 * task reports no reason - and the choice is OPTIONAL: the dialog opens with nothing selected and
 * confirms either way, so a caller must treat the stored reason as nullable rather than reading a
 * "no reason given" case out of the enum.
 *
 * What is persisted is the enum NAME, never the localized label, so the stored string is a fixed
 * contract that survives a copy change; [label] exists only for display, and because it is a
 * `when` over every constant, adding or dropping one here is a compile error in it rather than a
 * silently blank option.
 *
 * [OTHER] is the only constant that obliges a note. It is the escape hatch for a reason that did
 * not earn its own option, and selecting it switches the note field's label to "please specify" -
 * the other five are self-explanatory on their own.
 */
enum class SkipReason {
    NO_TIME,
    LOW_ENERGY,
    BLOCKED,
    RESCHEDULED,
    NOT_RELEVANT,
    OTHER,
}

/**
 * The label for this skipreason, from the generated copy table.
 *
 * A `when` rather than a stored resource id: `commonMain` has no `R`, and the `when` makes a
 * renamed or dropped string key a compile error instead of a blank label.
 */
fun SkipReason.label(strings: UiStrings): String =
    when (this) {
        SkipReason.NO_TIME -> strings.locNotEnoughTime
        SkipReason.LOW_ENERGY -> strings.locLowEnergyToday
        SkipReason.BLOCKED -> strings.locBlockedBySomething
        SkipReason.RESCHEDULED -> strings.locRescheduledToLater
        SkipReason.NOT_RELEVANT -> strings.locNoLongerRelevant
        SkipReason.OTHER -> strings.locOtherSpecify
    }

/**
 * Result returned from the statusNoteDialog
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
fun StatusNoteDialog(
    isVisible: Boolean,
    action: StatusAction,
    taskTitle: String,
    isRecurring: Boolean = false,
    onConfirm: (StatusNoteResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = AppLogger.current
    if (!isVisible) return

    var selectedReason by remember { mutableStateOf<SkipReason?>(null) }
    var noteText by remember { mutableStateOf("") }
    var nextDueStrategy by remember { mutableStateOf("planned") } // Default to planned
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text =
                    when (action) {
                        StatusAction.SKIP -> strings.locSkipTask
                        StatusAction.MISS -> strings.locMarkAsMissed
                        StatusAction.COMPLETE -> strings.locCompleteTask
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
                        text =
                            if (action == StatusAction.SKIP) {
                                strings.locWhyAreYouSkippingThisTask
                            } else {
                                strings.locWhyAreYouMissingThisTask
                            },
                        style = MaterialTheme.typography.labelLarge,
                    )

                    // Reason selection
                    Column(modifier = Modifier.selectableGroup()) {
                        SkipReason.entries.forEach { reason ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = selectedReason == reason,
                                            onClick = { selectedReason = reason },
                                            role = Role.RadioButton,
                                        ).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedReason == reason,
                                    onClick = null, // handled by row
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = reason.label(strings),
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
                                action == StatusAction.COMPLETE -> strings.locCompletionNoteOptional
                                selectedReason == SkipReason.OTHER -> strings.locPleaseSpecify
                                else -> strings.locAdditionalNotesOptional
                            },
                        )
                    },
                    placeholder = { Text(strings.locAddAnyDetails) },
                    minLines = 2,
                    maxLines = 4,
                )

                // Next due date strategy for recurring tasks
                if (isRecurring && (action == StatusAction.COMPLETE || action == StatusAction.SKIP)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = strings.locNextOccurrenceTiming,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Column(modifier = Modifier.selectableGroup()) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = nextDueStrategy == "planned",
                                        onClick = { nextDueStrategy = "planned" },
                                        role = Role.RadioButton,
                                    ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = nextDueStrategy == "planned",
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.locFollowOriginalSchedule,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = nextDueStrategy == "actual",
                                        onClick = { nextDueStrategy = "actual" },
                                        role = Role.RadioButton,
                                    ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = nextDueStrategy == "actual",
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.locBasedOnCompletionTime,
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
                    logger.i(
                        "StatusNoteDialog",
                        "Confirming status change",
                        mapOf(
                            "taskTitle" to taskTitle,
                            "action" to action.name,
                            "reason" to selectedReason?.name,
                            "note" to noteText.trim(),
                            "nextDueStrategy" to nextDueStrategy,
                        ),
                    )
                    onConfirm(
                        StatusNoteResult(
                            action = action,
                            reason = if (action != StatusAction.COMPLETE) selectedReason else null,
                            note = noteText.trim(),
                            nextDueStrategy =
                                if (isRecurring &&
                                    (action == StatusAction.COMPLETE || action == StatusAction.SKIP)
                                ) {
                                    nextDueStrategy
                                } else {
                                    null
                                },
                        ),
                    )
                },
            ) {
                Text(
                    when (action) {
                        StatusAction.SKIP -> strings.taskNotificationActionSkip
                        StatusAction.MISS -> strings.locMarkMissed
                        StatusAction.COMPLETE -> strings.taskNotificationActionComplete
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}

/**
 * Quick action dialog for completing a task without detailed notes.
 * Just shows a minimal confirmation.
 */
@Composable
fun QuickCompleteDialog(
    isVisible: Boolean,
    taskTitle: String,
    onConfirm: () -> Unit,
    onConfirmWithNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    if (!isVisible) return
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.locCompleteTask) },
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
                    Text(strings.locAddNote)
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onConfirm) {
                    Text(strings.locDone)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}
