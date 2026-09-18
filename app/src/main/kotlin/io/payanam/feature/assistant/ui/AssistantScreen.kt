//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
// Two screens' worth of small composables live in this file and the root chat surface is long
// by design (one scrolling surface); TooManyFunctions is file-level by nature, so the exemption
// is deliberate here rather than an oversight.
@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package io.payanam.feature.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.assistant.AssistantBusy
import io.payanam.feature.assistant.AssistantChatMessage
import io.payanam.feature.assistant.AssistantErrorKind
import io.payanam.feature.assistant.AssistantText
import io.payanam.feature.assistant.AssistantUiState
import io.payanam.feature.assistant.AssistantViewModel
import io.payanam.feature.assistant.MdBlock
import io.payanam.feature.assistant.isConfigured

/**
 * The in-app AI assistant surface (chat + setup states).
 *
 * Entry: Lenses → AI Assistance. Read-only v1: every query is guarded in code; replies
 * carry a receipt (model, query count, row count) and the first open shows the
 * generational-AI disclaimer.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val logger = remember { UnifiedLogger.getInstance() }
    val listState = rememberLazyListState()
    // Re-derive key + settings from the DB whenever this screen re-enters composition. The
    // settings screen is a separate navigation entry with its own ViewModel, so a key removal
    // or settings change made there must be picked up here on return (otherwise this instance
    // would keep serving a removed key / stale settings for the rest of the session).
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem((uiState.messages.size - 1).coerceAtLeast(0))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(id = R.string.assistant_title))
                        if (uiState.isConfigured) {
                            Text(
                                text = uiState.settings.model,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.loc_back))
                    }
                },
                actions = {
                    if (uiState.hasKey) {
                        IconButton(
                            // The chat screen also shows the app's bottom nav, whose "Settings"
                            // tab carries the same label; a testTag keeps the two apart for tests
                            // and gives screen readers the same unambiguous target.
                            modifier = Modifier.testTag("assistant_settings_button"),
                            onClick = {
                                logger.d("AssistantScreen", "Settings opened", mapOf("model" to uiState.settings.model))
                                onOpenSettings()
                            },
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(id = R.string.settings_title))
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

            !uiState.isConfigured -> SetupContent(
                uiState = uiState,
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                onKeyChange = viewModel::updateKeyDraft,
                onLoadModels = viewModel::loadModels,
                onSelectModel = viewModel::selectModel,
                onSaveAndStart = viewModel::saveAndStart,
                onOpenSettings = onOpenSettings,
                onDismissError = viewModel::dismissError,
            )

            else -> ChatContent(
                uiState = uiState,
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                listState = listState,
                onSendQuestion = viewModel::send,
                onDismissError = viewModel::dismissError,
            )
        }
    }
    if (uiState.noticeVisible) {
        AlertDialog(
            onDismissRequest = {
                // Deliberately non-dismissable: the disclosure must be acknowledged
                // before the user can reach anything that could make a network call.
            },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(id = R.string.assistant_notice_title)) },
            text = { Text(stringResource(id = R.string.assistant_notice_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.acknowledgeNotice() }) {
                    Text(stringResource(id = R.string.assistant_notice_ok))
                }
            },
        )
    }
}

@Composable
private fun SetupContent(
    uiState: AssistantUiState,
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
        Text(stringResource(id = R.string.assistant_setup_headline), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(id = R.string.assistant_setup_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(id = R.string.assistant_setup_offline_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        val storedKeyHint = uiState.hasKey && uiState.keyDraft.isBlank()
        OutlinedTextField(
            value = uiState.keyDraft,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(id = R.string.assistant_key_label)) },
            placeholder =
                if (storedKeyHint) {
                    null
                } else {
                    @Composable { Text(stringResource(id = R.string.assistant_key_hint)) }
                },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            isError = uiState.keyInvalid,
            supportingText =
                if (uiState.keyInvalid) {
                    { Text(stringResource(id = R.string.assistant_error_key_rejected)) }
                } else if (storedKeyHint) {
                    { Text(stringResource(id = R.string.assistant_key_stored_hint)) }
                } else {
                    null
                },
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            id = if (keyVisible) R.string.assistant_key_hide else R.string.assistant_key_show,
                        ),
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
                Text(stringResource(id = R.string.assistant_loading_models))
            } else {
                Text(stringResource(id = R.string.assistant_key_load_models))
            }
        }
        if (uiState.modelOptions.isNotEmpty()) {
            Text(stringResource(id = R.string.assistant_model_label), style = MaterialTheme.typography.titleSmall)
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
                    Text(uiState.settings.model.ifEmpty { stringResource(id = R.string.assistant_no_model) })
                    Text(stringResource(id = R.string.assistant_model_pick), style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(
                onClick = onSaveAndStart,
                enabled = uiState.settings.model.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(id = R.string.assistant_save_start))
            }
        }
        uiState.error?.let { kind ->
            ErrorBanner(kind = kind, detail = uiState.errorDetail, onDismiss = onDismissError)
        }
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(id = R.string.assistant_advanced_entry))
        }
    }
    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text(stringResource(id = R.string.assistant_model_label)) },
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
                    Text(stringResource(id = R.string.loc_back))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatContent(
    uiState: AssistantUiState,
    modifier: Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSendQuestion: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    // The draft lives inside ChatContent so typing recomposes only this subtree, not the
    // whole screen's Scaffold content lambda.
    var input by rememberSaveable { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }
    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Empty-state hints scroll with the message list so they can never crowd the
            // input row out of the screen (the input stays pinned at the bottom).
            if (uiState.messages.isEmpty() && uiState.busy == null) {
                item {
                    if (uiState.settings.chipsEnabled) {
                        ChipsBlock(
                            onChipTap = { chip ->
                                input = chip
                                inputFocus.requestFocus()
                            },
                        )
                    } else {
                        Text(
                            text = stringResource(id = R.string.assistant_empty_prompt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            items(uiState.messages, key = { message -> message.id }) { message ->
                MessageBubble(message)
            }
            uiState.busy?.let { busy ->
                item { BusyRow(busy) }
            }
        }
        uiState.error?.let { kind ->
            ErrorBanner(kind = kind, detail = uiState.errorDetail, onDismiss = onDismissError)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { updated -> input = updated },
                modifier = Modifier.weight(1f).focusRequester(inputFocus),
                placeholder = { Text(stringResource(id = R.string.assistant_input_hint)) },
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
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(id = R.string.assistant_send))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AssistantChatMessage) {
    when {
        message.isError -> Unit
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
                    if (message.model != null) {
                        Text(
                            text = stringResource(
                                id = R.string.assistant_receipt,
                                message.model,
                                message.queryCount,
                                message.rowCount,
                            ) + " · " + stringResource(id = R.string.assistant_ai_generated),
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
                is MdBlock.Heading -> Text(
                    text = block.text,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )

                is MdBlock.Bullet -> Text("• ${block.text}", style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Numbered -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                is MdBlock.TableRow -> Text(
                    text = block.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )

                is MdBlock.Plain -> InlineText(block.text)
                MdBlock.Blank -> Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun InlineText(text: String) {
    val segments = remember(text) { AssistantText.segments(text) }
    Text(
        text = buildAnnotatedString {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsBlock(onChipTap: (String) -> Unit) {
    val chips = listOf(
        R.string.sug_habits_missed,
        R.string.sug_never_completed,
        R.string.sug_habits_improved,
        R.string.sug_streaks,
        R.string.sug_plan_vs_actual_time,
        R.string.sug_unassigned_time,
        R.string.sug_least_time_goal,
        R.string.sug_reschedules,
        R.string.sug_completed_missed,
        R.string.sug_data_says,
        R.string.sug_plan_weak,
        R.string.sug_gaps,
        R.string.sug_reality_check,
    )
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(
            text = stringResource(id = R.string.assistant_empty_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            chips.forEach { chipRes ->
                val label = stringResource(id = chipRes)
                AssistChip(onClick = { onChipTap(label) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun BusyRow(busy: AssistantBusy) {
    val label = when (busy) {
        AssistantBusy.Preparing -> stringResource(id = R.string.assistant_busy_preparing)
        AssistantBusy.Thinking -> stringResource(id = R.string.assistant_busy_thinking)
        AssistantBusy.Finalizing -> stringResource(id = R.string.assistant_busy_finalizing)
        is AssistantBusy.Querying -> stringResource(id = R.string.assistant_busy_querying, busy.round, busy.max)
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

@Composable
internal fun ErrorBanner(kind: AssistantErrorKind, detail: String?, onDismiss: () -> Unit) {
    val canned = when (kind) {
        AssistantErrorKind.KEY_REJECTED -> stringResource(id = R.string.assistant_error_key_rejected)
        AssistantErrorKind.NETWORK -> stringResource(id = R.string.assistant_error_network)
        AssistantErrorKind.SERVER -> stringResource(id = R.string.assistant_error_server)
        AssistantErrorKind.DB_LOCKED -> stringResource(id = R.string.assistant_error_db_closed)
        AssistantErrorKind.NO_MODEL -> stringResource(id = R.string.assistant_error_no_model)
    }
    // Provider-side failures show the provider's own message, exactly as it came back — the canned
    // line is only a fallback for when there is nothing to quote.
    val message =
        if (kind == AssistantErrorKind.SERVER && !detail.isNullOrBlank()) detail else canned
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
                Text(stringResource(id = R.string.assistant_error_dismiss), color = Color.Unspecified)
            }
        }
    }
}
