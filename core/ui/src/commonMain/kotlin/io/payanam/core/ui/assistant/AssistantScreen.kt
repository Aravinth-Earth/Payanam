//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package io.payanam.core.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.domain.assistant.AssistantBusy
import io.payanam.domain.assistant.AssistantChatMessage
import io.payanam.domain.assistant.AssistantErrorKind
import io.payanam.domain.assistant.AssistantText
import io.payanam.domain.assistant.MdBlock
import io.payanam.presentation.assistant.AssistantUiState
import io.payanam.shared.logging.AppLogger

/**
 * The assistant chat surface: the key setup form when no key is stored, the conversation once one is.
 *
 * ## WHAT MOVED HERE AND WHY IT IS STILL ONE SCREEN
 *
 * The three states are driven by [AssistantUiState], which the shared ViewModel already produces, and
 * every control reports through [AssistantScreenActions] rather than touching a store. The alternative -
 * three separate destinations - would mean re-deciding "is this a setup screen or a chat screen?" on
 * each platform, and the answer depends on stored state that only the shared ViewModel knows.
 *
 * The screen READS no storage of its own. That is the point of the promotion: the platform answers
 * [AssistantScreenActions.onScreenEntered] and the callbacks, and the screen renders whatever state
 * arrives, so the desktop renders this exact arrangement from the same ViewModel rather than a copy of it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssistantScreenContent(
    uiState: AssistantUiState,
    actions: AssistantScreenActions,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val listState = rememberLazyListState()
    // Re-read the stored key + settings on entry: the settings editor is a separate destination with its
    // own scope, so a key removed or a model changed over there must be visible on the way back.
    LaunchedEffect(Unit) { actions.onScreenEntered() }
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem((uiState.messages.size - 1).coerceAtLeast(0))
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(strings.assistantTitle)
                        if (isConfigured(uiState)) {
                            Text(
                                text = uiState.settings.model,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.locBack,
                        )
                    }
                },
                actions = {
                    if (uiState.hasKey) {
                        IconButton(
                            // The chat screen also shows the app's bottom nav, whose "Settings" tab
                            // carries the same label; a testTag keeps the two apart for tests and gives
                            // screen readers the same unambiguous target.
                            modifier = Modifier.testTag(ASSISTANT_SETTINGS_BUTTON),
                            onClick = {
                                AppLogger.current.d(
                                    "AssistantScreen",
                                    "Settings opened",
                                    mapOf("model" to uiState.settings.model),
                                )
                                actions.onOpenSettings()
                            },
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = strings.settingsTitle,
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        when {
            uiState.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            !isConfigured(uiState) -> {
                SetupContent(
                    uiState = uiState,
                    strings = strings,
                    modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                    onKeyChange = actions.onKeyChange,
                    onLoadModels = actions.onLoadModels,
                    onSelectModel = actions.onSelectModel,
                    onSaveAndStart = actions.onSaveAndStart,
                    onOpenSettings = actions.onOpenSettings,
                    onDismissError = actions.onDismissError,
                )
            }

            else -> {
                ChatContent(
                    uiState = uiState,
                    strings = strings,
                    modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                    listState = listState,
                    onSendQuestion = actions.onSendQuestion,
                    onDismissError = actions.onDismissError,
                )
            }
        }
    }
    if (uiState.noticeVisible) {
        PayanamAlertDialog(
            onDismissRequest = {
                // Deliberately non-dismissable: the disclosure must be acknowledged before the user can
                // reach anything that could make a network call.
            },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(strings.assistantNoticeTitle) },
            text = { Text(strings.assistantNoticeBody) },
            confirmButton = {
                TextButton(onClick = actions.onAcknowledgeNotice) {
                    Text(strings.assistantNoticeOk)
                }
            },
        )
    }
}

@Composable
private fun SetupContent(
    uiState: AssistantUiState,
    strings: UiStrings,
    modifier: Modifier,
    onKeyChange: (String) -> Unit,
    onLoadModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSaveAndStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
) {
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(strings.assistantSetupHeadline, style = MaterialTheme.typography.titleLarge)
        Text(
            text = strings.assistantSetupCaption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = strings.assistantSetupOfflineNote,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        val storedKeyHint = uiState.hasKey && uiState.keyDraft.isBlank()
        OutlinedTextField(
            value = uiState.keyDraft,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.assistantKeyLabel) },
            placeholder =
                if (storedKeyHint) {
                    null
                } else {
                    { Text(strings.assistantKeyHint) }
                },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            isError = uiState.keyInvalid,
            supportingText =
                if (uiState.keyInvalid) {
                    { Text(strings.assistantErrorKeyRejected) }
                } else if (storedKeyHint) {
                    { Text(strings.assistantKeyStoredHint) }
                } else {
                    null
                },
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (keyVisible) strings.assistantKeyHide else strings.assistantKeyShow,
                    )
                }
            },
        )
        Button(
            onClick = onLoadModels,
            enabled = (uiState.keyDraft.isNotBlank() || uiState.hasKey) && !uiState.modelsLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.modelsLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.assistantLoadingModels)
            } else {
                Text(strings.assistantKeyLoadModels)
            }
        }
        if (uiState.modelOptions.isNotEmpty()) {
            Text(strings.assistantModelLabel, style = MaterialTheme.typography.titleSmall)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                onClick = { pickerOpen = true },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(uiState.settings.model.ifEmpty { strings.assistantNoModel })
                    Text(strings.assistantModelPick, style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(
                onClick = onSaveAndStart,
                enabled = uiState.settings.model.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(strings.assistantSaveStart)
            }
        }
        uiState.error?.let { kind ->
            ErrorBanner(
                kind = kind,
                detail = uiState.errorDetail,
                strings = strings,
                onDismiss = onDismissError,
            )
        }
        TextButton(onClick = onOpenSettings) {
            Text(strings.assistantAdvancedEntry)
        }
    }
    if (pickerOpen) {
        PayanamAlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text(strings.assistantModelLabel) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    uiState.modelOptions.forEach { model ->
                        TextButton(
                            onClick = {
                                onSelectModel(model)
                                pickerOpen = false
                            },
                        ) {
                            Text(model)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text(strings.locBack)
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatContent(
    uiState: AssistantUiState,
    strings: UiStrings,
    modifier: Modifier,
    listState: LazyListState,
    onSendQuestion: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    // The draft lives inside ChatContent so typing recomposes only this subtree, not the whole
    // screen's Scaffold content lambda.
    var input by rememberSaveable { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }
    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Empty-state hints scroll with the message list so they can never crowd the input row out
            // of the screen (the input stays pinned at the bottom).
            if (uiState.messages.isEmpty() && uiState.busy == null) {
                item {
                    if (uiState.settings.chipsEnabled) {
                        ChipsBlock(
                            strings = strings,
                            onChipTap = { chip ->
                                input = chip
                                inputFocus.requestFocus()
                            },
                        )
                    } else {
                        Text(
                            text = strings.assistantEmptyPrompt,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            items(uiState.messages, key = { message -> message.id }) { message ->
                MessageBubble(message = message, strings = strings)
            }
            uiState.busy?.let { busy ->
                item { BusyRow(busy = busy, strings = strings) }
            }
        }
        uiState.error?.let { kind ->
            ErrorBanner(
                kind = kind,
                detail = uiState.errorDetail,
                strings = strings,
                onDismiss = onDismissError,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { updated -> input = updated },
                modifier = Modifier.weight(1f).focusRequester(inputFocus),
                placeholder = { Text(strings.assistantInputHint) },
                maxLines = 3,
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    onSendQuestion(input)
                    input = ""
                },
                enabled = input.isNotBlank() && uiState.busy == null,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = strings.assistantSend,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: AssistantChatMessage,
    strings: UiStrings,
) {
    when {
        message.isError -> {
            Unit
        }

        message.role == AssistantChatMessage.ROLE_USER -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(0.9f),
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        else -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MarkdownText(text = message.text)
                    // Bound to a local because `model` is a property of a class in another module and
                    // Kotlin cannot smart-cast a cross-module property.
                    val receiptModel = message.model
                    if (receiptModel != null) {
                        Text(
                            text =
                                strings.assistantReceipt.formatArgs(
                                    receiptModel,
                                    message.queryCount,
                                    message.rowCount,
                                ) + " · " + strings.assistantAiGenerated,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    val blocks = remember(text) { AssistantText.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    Text(
                        text = block.text,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is MdBlock.Bullet -> {
                    Text("• ${block.text}", style = MaterialTheme.typography.bodyMedium)
                }

                is MdBlock.Numbered -> {
                    Text(block.text, style = MaterialTheme.typography.bodyMedium)
                }

                is MdBlock.TableRow -> {
                    Text(
                        text = block.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is MdBlock.Plain -> {
                    InlineText(block.text)
                }

                MdBlock.Blank -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun InlineText(text: String) {
    val segments = remember(text) { AssistantText.segments(text) }
    Text(
        text =
            buildAnnotatedString {
                segments.forEach { segment ->
                    if (segment.bold) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(segment.text) }
                    } else {
                        append(segment.text)
                    }
                }
            },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * The starter questions, as COPY rather than as resource ids.
 *
 * WHY A LIST OF `UiStrings` PROPERTIES AND NOT A LIST OF STRING VALUES: the chips have to come from the
 * same suggestion strings the rest of the app already ships in both languages. Naming the keys here
 * keeps the order a decision this file makes, and keeps every chip a compile-checked property, so
 * removing a suggestion is a compile error rather than a chip that silently renders an empty label.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsBlock(
    strings: UiStrings,
    onChipTap: (String) -> Unit,
) {
    val chips =
        listOf(
            strings.sugHabitsMissed,
            strings.sugNeverCompleted,
            strings.sugHabitsImproved,
            strings.sugStreaks,
            strings.sugPlanVsActualTime,
            strings.sugUnassignedTime,
            strings.sugLeastTimeGoal,
            strings.sugReschedules,
            strings.sugCompletedMissed,
            strings.sugDataSays,
            strings.sugPlanWeak,
            strings.sugGaps,
            strings.sugRealityCheck,
        )
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(
            text = strings.assistantEmptyHint,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            chips.forEach { label ->
                AssistChip(onClick = { onChipTap(label) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun BusyRow(
    busy: AssistantBusy,
    strings: UiStrings,
) {
    val label =
        when (busy) {
            AssistantBusy.Preparing -> strings.assistantBusyPreparing
            AssistantBusy.Thinking -> strings.assistantBusyThinking
            AssistantBusy.Finalizing -> strings.assistantBusyFinalizing
            is AssistantBusy.Querying -> strings.assistantBusyQuerying.formatArgs(busy.round, busy.max)
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The failure notice. Shared so both platforms can only word it one way.
 *
 * A provider-side failure quotes the provider's own message because that is the only thing that tells a
 * user whether the problem is the key, the model name or a rate limit; the canned line is the fallback
 * for when there is nothing to quote.
 */
@Composable
internal fun ErrorBanner(
    kind: AssistantErrorKind,
    detail: String?,
    strings: UiStrings,
    onDismiss: () -> Unit,
) {
    val canned =
        when (kind) {
            AssistantErrorKind.KEY_REJECTED -> strings.assistantErrorKeyRejected
            AssistantErrorKind.NETWORK -> strings.assistantErrorNetwork
            AssistantErrorKind.SERVER -> strings.assistantErrorServer
            AssistantErrorKind.DB_LOCKED -> strings.assistantErrorDbClosed
            AssistantErrorKind.NO_MODEL -> strings.assistantErrorNoModel
        }
    val message = if (kind == AssistantErrorKind.SERVER && !detail.isNullOrBlank()) detail else canned
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) {
                Text(strings.assistantErrorDismiss, color = Color.Unspecified)
            }
        }
    }
}

/** The settings gear in the chat screen's top bar. Stable so a test need not repeat the literal. */
const val ASSISTANT_SETTINGS_BUTTON: String = "assistant_settings_button"

/**
 * Whether the chat surface can open at all: a stored key AND a chosen model.
 *
 * Deliberately recomputed here rather than imported from the ViewModel file: the extension's receiver is
 * a type in `:core:presentation`, and this module already depends on that module, so the import would
 * work - but the rule the screen renders on is a rule about the SCREEN, and a screen whose "can I open?"
 * test lives in a ViewModel file has to be read before it can be trusted.
 */
private fun isConfigured(uiState: AssistantUiState): Boolean = uiState.hasKey && uiState.settings.model.isNotEmpty()
