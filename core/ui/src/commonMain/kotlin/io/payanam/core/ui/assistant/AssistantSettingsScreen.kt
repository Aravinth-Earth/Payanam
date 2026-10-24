//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package io.payanam.core.ui.assistant

import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.domain.assistant.AssistantBackend
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantError
import io.payanam.domain.assistant.AssistantErrorKind
import io.payanam.domain.assistant.AssistantLength
import io.payanam.domain.assistant.AssistantProvider
import io.payanam.domain.assistant.AssistantSystemPrompt
import io.payanam.presentation.assistant.AssistantUiState
import io.payanam.shared.logging.AppLogger

/**
 * Advanced settings for the assistant: the connection (where the model runs, the provider, its address
 * and key, the model, and a Verify check), response length, chips, the method line, the numeric caps,
 * the System-prompt additions editor (built-in shown locked), and the structured About-me notes.
 *
 * ## WHAT THIS SCREEN OWNS AND WHAT IT DOES NOT
 *
 * It owns the arrangement and nothing else. The values come from [AssistantUiState.settings] and every
 * edit leaves as an [AssistantSettingsScreenActions] callback, so the screen cannot write a preference
 * the desktop does not have a column for, and cannot render a field whose value nothing stores.
 *
 * ## WHY THE EDITS ARE FLUSHED ON THE WAY OUT AND NOT PER KEYSTROKE
 *
 * The store's write is a transaction over a fixed set of rows. A per-keystroke flush would write the
 * whole configuration on every character of a long prompt addition; instead the platform decides when to
 * persist, and the screen asks on both exits - the back arrow and disposal - so an edit made in the last
 * moment before the destination is popped is not lost. The screen cannot flush on disposal alone: a
 * platform that keeps the composable alive across a configuration change would then flush on every
 * rotation, and one that pops the destination would lose the edit to the race between disposal and the
 * back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreenContent(
    uiState: AssistantUiState,
    actions: AssistantSettingsScreenActions,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    var builtinExpanded by rememberSaveable { mutableStateOf(false) }
    // Flushed on the way out of composition, so an edit made in the last moment before the destination
    // is popped is not lost even when the platform pops the entry without routing through the back
    // arrow. The back arrow asks for its own flush, because a platform that keeps the composable alive
    // across a configuration change would otherwise write the whole configuration on every rotation.
    DisposableEffect(actions) { onDispose { actions.onFlushRequested() } }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(strings.assistantSettingsTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            actions.onFlushRequested()
                            actions.onNavigateBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.locBack,
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    // The last control on this page (Verify, and its success line) sits below the fold on a
                    // phone, and the navigation bar drew over it: the text was in the tree and unreadable on
                    // screen. Reserve the bar's inset instead of assuming the scaffold already did.
                    .navigationBarsPadding()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = strings.assistantSettingsSavedNote,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.assistantSettingsPrivacyNote,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            // Keyed on the model alone: this line says "no model selected yet", and it used to appear
            // beside a model that WAS selected but whose key was still an unsaved draft.
            if (uiState.settings.model.isEmpty()) {
                Text(
                    text = strings.assistantNoModel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.hasKey) {
                TextButton(
                    onClick = {
                        AppLogger.current.i(
                            "AssistantSettingsScreen",
                            "Key removal requested from settings",
                        )
                        actions.onRemoveKeyRequested()
                        actions.onNavigateBack()
                    },
                ) {
                    Text(strings.assistantRemoveKey)
                }
            }

            ConnectionSection(uiState = uiState, actions = actions, strings = strings)

            SectionTitle(strings.assistantSettingsLengthTitle)
            AssistantLength.entries.forEach { preset ->
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = uiState.settings.length == preset,
                        onClick = {
                            AppLogger.current.d(
                                "AssistantSettingsScreen",
                                "Length preset changed",
                                mapOf("preset" to preset.name),
                            )
                            actions.onLengthChange(preset)
                        },
                    )
                    Text(text = lengthLabel(preset, strings))
                }
            }

            SwitchRow(
                label = strings.assistantSettingsChips,
                checked = uiState.settings.chipsEnabled,
                onChange = actions.onChipsEnabledChange,
            )
            SwitchRow(
                label = strings.assistantSettingsShowMethod,
                checked = uiState.settings.showMethod,
                onChange = actions.onShowMethodChange,
            )

            NumberField(
                label = strings.assistantSettingsMaxRows,
                value = uiState.settings.maxRows,
                onChange = actions.onMaxRowsChange,
            )
            NumberField(
                label = strings.assistantSettingsRounds,
                value = uiState.settings.rounds,
                onChange = actions.onRoundsChange,
            )
            NumberField(
                label = strings.assistantSettingsHistory,
                value = uiState.settings.historyDepth,
                onChange = actions.onHistoryDepthChange,
            )

            SectionTitle(strings.assistantSettingsPromptTitle)
            Text(
                text = strings.assistantSettingsPromptHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.settings.promptAdditions,
                onValueChange = actions.onPromptAdditionsChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = strings.assistantSettingsPromptTitle },
                minLines = 3,
                maxLines = 8,
            )
            Text(
                text =
                    strings.assistantSettingsPromptBudget.formatArgs(
                        uiState.settings.promptAdditions.length,
                        AssistantDefaults.MAX_PROMPT_ADDITIONS,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { builtinExpanded = !builtinExpanded }) {
                Text(
                    strings.assistantSettingsBuiltinTitle +
                        if (builtinExpanded) " ▾" else " ▸",
                )
            }
            if (builtinExpanded) {
                Text(
                    text = AssistantSystemPrompt.BASE_INSTRUCTIONS,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = strings.assistantSettingsBuiltinCaption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle(strings.assistantSettingsAboutTitle)
            Text(
                text = strings.assistantSettingsAboutCaption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Each About-me field reports its own value rather than a merged "about me" object, so a
            // caller persists the one field the user touched. Six fields sharing one callback would be
            // the shape that lets a goals edit overwrite needs.
            AboutField(
                label = strings.assistantAboutGoals,
                value = uiState.settings.aboutMe.goals,
                onChange = actions.onAboutGoalsChange,
            )
            AboutField(
                label = strings.assistantAboutWants,
                value = uiState.settings.aboutMe.wants,
                onChange = actions.onAboutWantsChange,
            )
            AboutField(
                label = strings.assistantAboutNeeds,
                value = uiState.settings.aboutMe.needs,
                onChange = actions.onAboutNeedsChange,
            )
            AboutField(
                label = strings.assistantAboutStrengths,
                value = uiState.settings.aboutMe.strengths,
                onChange = actions.onAboutStrengthsChange,
            )
            AboutField(
                label = strings.assistantAboutFocus,
                value = uiState.settings.aboutMe.focusAreas,
                onChange = actions.onAboutFocusAreasChange,
            )
            AboutField(
                label = strings.assistantAboutNote,
                value = uiState.settings.aboutMe.note,
                onChange = actions.onAboutNoteChange,
            )
        }
    }
}

/**
 * The connection: where the model runs, which provider, its address, its key, the model, and whether
 * that combination actually answers.
 *
 * IT IS A SECTION AND NOT A DESTINATION. The settings editor is where a user already looks when the
 * assistant refuses to start, so the thing that was missing (which host, which model) belongs beside
 * the caps and the prompt, not behind a second screen. Every field is a callback, so nothing here can
 * write a value the ViewModel does not own, and the one save button is the only path that persists the
 * quartet - the screen never writes storage itself.
 *
 * NOTHING BELOW THE BACKEND CHOICE RENDERS UNTIL A PROVIDER EXISTS. The backend choice has no state of
 * its own: the ViewModel stores the provider and the provider already knows its backend, so "where does
 * it run" is answered by reading the chosen provider's group. Until one is chosen there is no default
 * address to prefill, no `requiresKey` to ask, and no model list to key a request to, so the fields
 * would be asking for values with nothing to check them against.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSection(
    uiState: AssistantUiState,
    actions: AssistantSettingsScreenActions,
    strings: UiStrings,
) {
    var modelPickerOpen by rememberSaveable { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }

    SectionTitle(strings.assistantProviderWhere)
    // Each backend is a radio over the PROVIDER's own group, not a second stored field: re-tapping the
    // group the current provider already belongs to is a no-op, so a user whose provider is OLLAMA
    // cannot lose it by tapping "On this device". Tapping the OTHER group selects that group's first
    // provider in declaration order, which the dropdown below can then refine - the ViewModel's own
    // `selectProvider` decides what that does to the address.
    AssistantBackend.entries.forEach { backend ->
        val firstInGroup = AssistantProvider.forBackend(backend).firstOrNull()
        // The WHOLE row is the control, not just the circle: tapping the words "On this device" used
        // to do nothing, which left a touch target the size of a radio button and made the local
        // option feel inert. `onClick = null` on the RadioButton keeps one gesture and one label for
        // accessibility instead of two nested click targets.
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .selectableRow(selected = uiState.provider?.backend == backend) {
                    if (firstInGroup != null && uiState.provider?.backend != backend) {
                        AppLogger.current.d(
                            "AssistantSettingsScreen",
                            "Backend chosen",
                            mapOf("backend" to backend.name, "provider" to firstInGroup.name),
                        )
                        actions.onProviderSelected(firstInGroup)
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = uiState.provider?.backend == backend,
                onClick = null,
            )
            Text(text = backendLabel(backend, strings))
        }
    }

    val provider = uiState.provider ?: return
    ExposedDropdownMenuBox(
        expanded = providerExpanded,
        onExpandedChange = { providerExpanded = it },
    ) {
        OutlinedTextField(
            value = providerLabel(provider, strings),
            onValueChange = {},
            readOnly = true,
            label = { Text(strings.assistantProvider) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = providerExpanded,
            onDismissRequest = { providerExpanded = false },
        ) {
            AssistantProvider.forBackend(provider.backend).forEach { option ->
                DropdownMenuItem(
                    text = { Text(providerLabel(option, strings)) },
                    onClick = {
                        if (option != provider) actions.onProviderSelected(option)
                        providerExpanded = false
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = uiState.baseUrl,
        onValueChange = actions.onBaseUrlChange,
        modifier =
            Modifier.fillMaxWidth(),
        // No contentDescription: the visible label names the field, and repeating it made a screen
        // reader announce "Address, Address".
        label = { Text(strings.assistantAddress) },
        supportingText = { Text(strings.assistantAddressHint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )

    // Always masked, with no reveal toggle: a stored key is never rendered, and this editor is not the
    // place to inspect one. The chat surface is where a key is pasted and can be eyeballed.
    val storedKeyHint = uiState.hasKey && uiState.keyDraft.isBlank()
    OutlinedTextField(
        value = uiState.keyDraft,
        onValueChange = actions.onKeyChange,
        modifier =
            Modifier.fillMaxWidth(),
        label = { Text(strings.assistantApiKey) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = uiState.keyInvalid,
        supportingText = {
            Text(
                when {
                    // A rejected key is the one state that must outrank "already stored": the chip that
                    // says a key is in use would otherwise sit beside an error saying it was refused.
                    uiState.keyInvalid -> strings.assistantErrorKeyRejected

                    !provider.requiresKey -> strings.assistantApiKeyOptional

                    storedKeyHint -> strings.assistantKeyStoredHint

                    // A draft that is already typed is not something to paste: saying so beside a filled
                    // field reads as if nothing was entered.
                    uiState.keyDraft.isNotBlank() -> strings.assistantKeyStoredHint

                    else -> strings.assistantKeyHint
                },
            )
        },
    )

    Button(
        onClick = actions.onLoadModels,
        // A provider that needs no key must still be able to list its models: the button used to
        // require a key here while verification next to it did not.
        enabled =
            (uiState.keyDraft.isNotBlank() || uiState.hasKey || !provider.requiresKey) &&
                !uiState.modelsLoading,
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
            onClick = { modelPickerOpen = true },
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
    } else {
        // The fallback for an endpoint that lists no models - and the only input there is while a load
        // is running or after it failed. Typed exactly as entered: the store is the one that normalises.
        OutlinedTextField(
            value = uiState.settings.model,
            onValueChange = actions.onSelectModel,
            modifier =
                Modifier.fillMaxWidth(),
            label = { Text(strings.assistantModelManual) },
            singleLine = true,
        )
    }

    Button(
        onClick = actions.onVerifyConnection,
        enabled = !uiState.verifying,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (uiState.verifying) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(strings.assistantVerify)
    }
    if (uiState.verified) {
        Text(
            text =
                if (uiState.modelOptions.isEmpty()) {
                    // Verified, but the endpoint listed nothing — Claude's compatibility layer cannot
                    // list models at all, so this is a usable setup with a name still to be typed.
                    strings.assistantVerifyNoModels
                } else {
                    strings.assistantVerifySuccess.formatArgs(uiState.modelOptions.size)
                },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    uiState.error?.let { kind ->
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = connectionFailureText(uiState.failure, kind, strings),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = actions.onDismissError) {
                Text(strings.assistantErrorDismiss)
            }
        }
    }

    // The only writer of the provider/address/key/model quartet. Without it the fields above would
    // collect edits nothing persists, and the key-removal confirmation could never be raised.
    Button(
        onClick = actions.onSaveAndStart,
        enabled = uiState.settings.model.isNotEmpty() && !uiState.verifying,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(strings.assistantSaveStart)
    }

    if (modelPickerOpen) {
        PayanamAlertDialog(
            onDismissRequest = { modelPickerOpen = false },
            title = { Text(strings.assistantModelLabel) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    uiState.modelOptions.forEach { model ->
                        TextButton(
                            onClick = {
                                actions.onSelectModel(model)
                                modelPickerOpen = false
                            },
                        ) {
                            Text(model)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelPickerOpen = false }) {
                    Text(strings.locBack)
                }
            },
        )
    }
    if (uiState.keyRemovalPending) {
        PayanamAlertDialog(
            onDismissRequest = actions.onKeyRemovalCancel,
            title = { Text(strings.assistantKeyRemovalTitle) },
            text = { Text(strings.assistantKeyRemovalBody) },
            confirmButton = {
                TextButton(onClick = actions.onKeyRemovalConfirm) {
                    Text(strings.assistantKeyRemovalConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onKeyRemovalCancel) {
                    Text(strings.assistantKeyRemovalCancel)
                }
            },
        )
    }
}

/**
 * The label for one backend group.
 *
 * A `when` on the enum rather than a name lookup, so a third backend added to the domain is a compile
 * error here rather than a radio button with no text next to it.
 */
private fun backendLabel(
    backend: AssistantBackend,
    strings: UiStrings,
): String =
    when (backend) {
        AssistantBackend.LOCAL -> strings.assistantProviderLocal
        AssistantBackend.CLOUD -> strings.assistantProviderCloud
    }

/**
 * The product name of one provider.
 *
 * These are BRAND NAMES, so they are not string resources: "OpenRouter" is spelled the same in every
 * language and translating it would misname a service. The two "your own endpoint" entries have no
 * brand at all, so those are the one place the copy comes from the string table. A `when` rather than
 * a `name` lookup, for the same reason [backendLabel] is one.
 */
private fun providerLabel(
    provider: AssistantProvider,
    strings: UiStrings,
): String =
    when (provider) {
        AssistantProvider.OLLAMA -> PROVIDER_NAME_OLLAMA
        AssistantProvider.LM_STUDIO -> PROVIDER_NAME_LM_STUDIO
        AssistantProvider.CUSTOM_LOCAL -> strings.assistantProviderCustom
        AssistantProvider.OPENCODE -> PROVIDER_NAME_OPENCODE
        AssistantProvider.OPENROUTER -> PROVIDER_NAME_OPENROUTER
        AssistantProvider.OPENAI -> PROVIDER_NAME_OPENAI
        AssistantProvider.CLAUDE -> PROVIDER_NAME_CLAUDE
        AssistantProvider.CUSTOM_CLOUD -> strings.assistantProviderCustom
    }

/**
 * The failure line for one error kind, in the words the user needs to act on it.
 *
 * The ViewModel keeps both the coarse kind and, where the client classified one, the fine failure. The
 * fine case wins because it is what lets a rate limit, a timeout, a context overflow and a refused
 * request each say their own thing instead of all reading as "the provider had a problem".
 * [AssistantErrorKind.SERVER] carries the provider's own words in `errorDetail` on the chat surface;
 * here it is the canned server line, because the detail may quote the endpoint and this editor is
 * where the endpoint is being configured.
 */
private fun connectionFailureText(
    failure: AssistantError?,
    kind: AssistantErrorKind,
    strings: UiStrings,
): String =
    when (failure) {
        is AssistantError.Auth -> {
            strings.assistantErrorAuth
        }

        is AssistantError.Transport -> {
            strings.assistantErrorTransport
        }

        is AssistantError.Timeout -> {
            strings.assistantErrorTimeout
        }

        is AssistantError.RateLimit -> {
            strings.assistantErrorRateLimit
        }

        is AssistantError.Server -> {
            strings.assistantErrorServer
        }

        is AssistantError.ModelNotFound -> {
            strings.assistantErrorModelMissing
        }

        is AssistantError.ContextOverflow -> {
            strings.assistantErrorContext
        }

        is AssistantError.InvalidRequest -> {
            strings.assistantErrorInvalid
        }

        null -> {
            when (kind) {
                AssistantErrorKind.KEY_REJECTED -> strings.assistantErrorAuth
                AssistantErrorKind.NETWORK -> strings.assistantErrorTransport
                AssistantErrorKind.SERVER -> strings.assistantErrorServer
                AssistantErrorKind.NO_MODEL -> strings.assistantErrorModelMissing
                AssistantErrorKind.DB_LOCKED -> strings.assistantErrorDbClosed
            }
        }
    }

/** Brand names, not copy: see [providerLabel]. */
private const val PROVIDER_NAME_OLLAMA: String = "Ollama"
private const val PROVIDER_NAME_LM_STUDIO: String = "LM Studio"
private const val PROVIDER_NAME_OPENCODE: String = "OpenCode"
private const val PROVIDER_NAME_OPENROUTER: String = "OpenRouter"
private const val PROVIDER_NAME_OPENAI: String = "OpenAI"
private const val PROVIDER_NAME_CLAUDE: String = "Claude"

/**
 * The copy for one length preset.
 *
 * A `when` on the enum rather than a name lookup, so a fourth preset added to the domain is a compile
 * error here rather than a radio button that renders an empty label.
 */
private fun lengthLabel(
    preset: AssistantLength,
    strings: UiStrings,
): String =
    when (preset) {
        AssistantLength.SHORT -> strings.assistantLengthShort
        AssistantLength.BALANCED -> strings.assistantLengthBalanced
        AssistantLength.ELABORATED -> strings.assistantLengthElaborated
    }

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        // Merged so TalkBack reads the label and the switch as one control instead of an
        // unlabelled toggle.
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    var text by rememberSaveable(label) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            text = updated.filter { it.isDigit() }.take(NUMBER_FIELD_DIGIT_LIMIT)
            text.toIntOrNull()?.let(onChange)
        },
        // Re-syncs on focus loss so a value the store clamped (or a blank field) shows what is
        // actually stored instead of what was typed.
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (!state.isFocused) text = value.toString()
                },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun AboutField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = 1,
        maxLines = 3,
    )
}

/**
 * How many digits a numeric cap will accept as it is typed.
 *
 * Five, because that is the widest value any cap can hold once clamped: the largest of them is
 * `HARD_MAX_ROWS`, five digits. Typing a longer number is truncated rather than rejected, and the value
 * still passes through the store's own clamp, so the field cannot end up holding something no range
 * accepts.
 */
private const val NUMBER_FIELD_DIGIT_LIMIT: Int = 5

/** The same configured-ness the chat surface opens on, recomputed for the reason given there. */

/**
 * Whether the assistant can be used: a model is chosen, and a key exists where the provider needs one.
 *
 * The provider decides, not the key alone: a model server on the user's own machine answers with no
 * credential, so demanding a key here would leave a finished local setup stuck on the setup pointer.
 * Mirrors `AssistantUiState.isConfigured` in the presentation module, which this module cannot import.
 */
private fun isConfigured(uiState: AssistantUiState): Boolean =
    (uiState.hasKey || uiState.provider?.requiresKey == false) && uiState.settings.model.isNotEmpty()

/** Makes a whole row behave as one selectable control, so its label is part of the target. */
private fun Modifier.selectableRow(
    selected: Boolean,
    onClick: () -> Unit,
): Modifier =
    this
        .semantics(mergeDescendants = true) { this.selected = selected }
        .clickable(onClick = onClick)
