//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package io.payanam.core.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.domain.assistant.AssistantDefaults
import io.payanam.domain.assistant.AssistantLength
import io.payanam.domain.assistant.AssistantSystemPrompt
import io.payanam.presentation.assistant.AssistantUiState
import io.payanam.shared.logging.AppLogger

/**
 * Advanced settings for the assistant: response length, chips, the method line, the numeric caps, the
 * System-prompt additions editor (built-in shown locked), and the structured About-me notes.
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
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
            TextButton(
                onClick = {
                    AppLogger.current.i(
                        "AssistantSettingsScreen",
                        "Provider change requested from settings",
                    )
                    actions.onChangeProviderRequested()
                    actions.onNavigateBack()
                },
                modifier = Modifier.testTag(ASSISTANT_CHANGE_PROVIDER_BUTTON),
            ) {
                Text(
                    strings.assistantChangeProvider +
                        if (isConfigured(uiState)) {
                            " · " + uiState.settings.model
                        } else {
                            ""
                        },
                )
            }
            if (!isConfigured(uiState)) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = strings.assistantSettingsPromptTitle },
                minLines = 3,
                maxLines = 8,
            )
            Text(
                text = strings.assistantSettingsPromptBudget.formatArgs(
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

/** The provider-change row. Stable so a test need not repeat the literal. */
const val ASSISTANT_CHANGE_PROVIDER_BUTTON: String = "assistant_change_provider_button"

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
private fun isConfigured(uiState: AssistantUiState): Boolean = uiState.hasKey && uiState.settings.model.isNotEmpty()
