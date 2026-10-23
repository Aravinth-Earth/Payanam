//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import io.payanam.core.ui.assistant.AssistantScreenActions
import io.payanam.core.ui.assistant.AssistantScreenContent
import io.payanam.core.ui.assistant.AssistantSettingsScreenActions
import io.payanam.core.ui.assistant.AssistantSettingsScreenContent
import io.payanam.platform.AssistantEntryPoint
import io.payanam.presentation.assistant.AssistantViewModel
import io.payanam.presentation.assistant.assistantViewModel
import io.payanam.ui.retainSharedViewModel

/**
 * Builds the assistant screen's shared ViewModel, or hands back the one already retained.
 *
 * The two assistant destinations - the chat surface and the settings editor - are separate navigation
 * entries that must read and write ONE configuration, so they share a retention key on purpose: a key per
 * screen would give each its own store handle, and a model chosen in the settings editor would not be the
 * model the chat surface then loads.
 *
 * `dispose()` is registered as the release action so the shared class's scope is cancelled when the store
 * owner clears, exactly as the passphrase-change ViewModel's retention does.
 */
@Composable
internal fun retainAssistantViewModel(): AssistantViewModel {
    // Read the Context in the composable scope: the factory below is an ordinary function, and a
    // `Local` read inside it would be a composable call from a non-composable lambda.
    val context = LocalContext.current
    return retainSharedViewModel(key = "assistant", onRelease = { it.dispose() }) {
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext as Application,
                AssistantEntryPoint::class.java,
            )
        assistantViewModel(
            settingsStore = entryPoint.assistantSettingsStore(),
            schemaReader = entryPoint.assistantSchemaReader(),
            query = entryPoint.assistantReadOnlyQuery(),
            client = entryPoint.openCodeGoClient(),
        )
    }
}

/**
 * The Android destination for the assistant chat surface.
 *
 * The screen itself is shared: this is the half that resolves the ViewModel through Hilt and turns each
 * shared callback into the call the shared ViewModel exposes. Nothing here decides what the screen looks
 * like or which state it renders, so the desktop can render the same content from the same ViewModel.
 */
@Composable
fun AssistantScreen(
    onNavigateBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val viewModel = retainAssistantViewModel()
    val uiState by viewModel.uiState.collectAsState()
    AssistantScreenContent(
        uiState = uiState,
        actions =
        AssistantScreenActions(
            // The shared screen asks for the re-read on entry rather than doing it itself: reading
            // storage is a platform act, and the settings editor is a different destination with its own
            // scope, so a key removed over there has to reach this one on the way back.
            onScreenEntered = viewModel::refresh,
            onNavigateBack = onNavigateBack,
            onOpenSettings = onOpenSettings,
            onKeyChange = viewModel::updateKeyDraft,
            onLoadModels = viewModel::loadModels,
            onSelectModel = viewModel::selectModel,
            onSaveAndStart = viewModel::saveAndStart,
            onSendQuestion = viewModel::send,
            onDismissError = viewModel::dismissError,
            onAcknowledgeNotice = viewModel::acknowledgeNotice,
        ),
    )
}

/**
 * The Android destination for the assistant's advanced settings.
 *
 * Every field's write is routed to the shared ViewModel through the field's OWN callback rather than a
 * merged "settings changed" lambda, because each one writes a different column of the configuration row
 * and a merged callback would let an edit to one field persist a stale value for another.
 */
@Composable
fun AssistantSettingsScreen(onNavigateBack: () -> Unit = {}) {
    val viewModel = retainAssistantViewModel()
    val uiState by viewModel.uiState.collectAsState()
    AssistantSettingsScreenContent(
        uiState = uiState,
        actions =
        AssistantSettingsScreenActions(
            onFlushRequested = viewModel::flushSettings,
            onNavigateBack = onNavigateBack,
            onChangeProviderRequested = viewModel::changeProvider,
            onRemoveKeyRequested = viewModel::removeProviderKey,
            onLengthChange = { preset -> viewModel.updateSettings { it.copy(length = preset) } },
            onChipsEnabledChange = { value -> viewModel.updateSettings { it.copy(chipsEnabled = value) } },
            onShowMethodChange = { value -> viewModel.updateSettings { it.copy(showMethod = value) } },
            onMaxRowsChange = { value -> viewModel.updateSettings { it.copy(maxRows = value) } },
            onRoundsChange = { value -> viewModel.updateSettings { it.copy(rounds = value) } },
            onHistoryDepthChange = { value -> viewModel.updateSettings { it.copy(historyDepth = value) } },
            onPromptAdditionsChange = { text -> viewModel.updateSettings { it.copy(promptAdditions = text) } },
            onAboutGoalsChange = { text ->
                viewModel.updateSettings { it.copy(aboutMe = it.aboutMe.copy(goals = text)) }
            },
            onAboutWantsChange = { text ->
                viewModel.updateSettings { it.copy(aboutMe = it.aboutMe.copy(wants = text)) }
            },
            onAboutNeedsChange = { text ->
                viewModel.updateSettings { it.copy(aboutMe = it.aboutMe.copy(needs = text)) }
            },
            onAboutStrengthsChange = { text ->
                viewModel.updateSettings { it.copy(aboutMe = it.aboutMe.copy(strengths = text)) }
            },
            onAboutFocusAreasChange = { text ->
                viewModel.updateSettings { it.copy(aboutMe = it.aboutMe.copy(focusAreas = text)) }
            },
            onAboutNoteChange = { text ->
                viewModel.updateSettings { it.copy(aboutMe = it.aboutMe.copy(note = text)) }
            },
        ),
    )
}
