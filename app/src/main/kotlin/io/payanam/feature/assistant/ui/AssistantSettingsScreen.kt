//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
// One settings surface: the root composable is long by design and the file holds more small
// composables than the TooManyFunctions threshold (a file-level rule that cannot be scoped to a
// single function), so both exemptions are deliberate here.
@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package io.payanam.feature.assistant.ui

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.assistant.AssistantAboutMe
import io.payanam.feature.assistant.AssistantDefaults
import io.payanam.feature.assistant.AssistantLength
import io.payanam.feature.assistant.AssistantSystemPrompt
import io.payanam.feature.assistant.AssistantViewModel
import io.payanam.feature.assistant.isConfigured

/**
 * Advanced settings for the assistant (A8/A9/A10 merged): response length, chips, method
 * line, numeric caps, the System-prompt additions editor (built-in shown locked), and the
 * structured About-me notes. Everything auto-saves (footer note states this).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(
    viewModel: AssistantViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val logger = remember { UnifiedLogger.getInstance() }
    var builtinExpanded by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { viewModel.flushSettings() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.assistant_settings_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.flushSettings()
                            onNavigateBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.loc_back))
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
                text = stringResource(id = R.string.assistant_settings_saved_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(id = R.string.assistant_settings_privacy_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(
                onClick = {
                    logger.i("AssistantSettingsScreen", "Provider change requested from settings")
                    viewModel.changeProvider()
                    onNavigateBack()
                },
                modifier = Modifier.testTag("assistant_change_provider_button"),
            ) {
                Text(
                    stringResource(id = R.string.assistant_change_provider) +
                        if (uiState.isConfigured) {
                            " · " + uiState.settings.model
                        } else {
                            ""
                        },
                )
            }
            if (!uiState.isConfigured) {
                Text(
                    text = stringResource(id = R.string.assistant_no_model),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.hasKey) {
                TextButton(
                    onClick = {
                        logger.i("AssistantSettingsScreen", "Key removal requested from settings")
                        viewModel.removeProviderKey()
                        onNavigateBack()
                    },
                ) {
                    Text(stringResource(id = R.string.assistant_remove_key))
                }
            }

            SectionTitle(stringResource(id = R.string.assistant_settings_length_title))
            AssistantLength.entries.forEach { preset ->
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = uiState.settings.length == preset,
                        onClick = {
                            logger.d("AssistantSettingsScreen", "Length preset changed", mapOf("preset" to preset.name))
                            viewModel.updateSettings { it.copy(length = preset) }
                        },
                    )
                    Text(
                        text = stringResource(
                            when (preset) {
                                AssistantLength.SHORT -> R.string.assistant_length_short
                                AssistantLength.BALANCED -> R.string.assistant_length_balanced
                                AssistantLength.ELABORATED -> R.string.assistant_length_elaborated
                            },
                        ),
                    )
                }
            }

            SwitchRow(
                label = stringResource(id = R.string.assistant_settings_chips),
                checked = uiState.settings.chipsEnabled,
                onChange = { value -> viewModel.updateSettings { it.copy(chipsEnabled = value) } },
            )
            SwitchRow(
                label = stringResource(id = R.string.assistant_settings_show_method),
                checked = uiState.settings.showMethod,
                onChange = { value -> viewModel.updateSettings { it.copy(showMethod = value) } },
            )

            NumberField(
                label = stringResource(id = R.string.assistant_settings_max_rows),
                value = uiState.settings.maxRows,
                onChange = { value -> viewModel.updateSettings { it.copy(maxRows = value) } },
            )
            NumberField(
                label = stringResource(id = R.string.assistant_settings_rounds),
                value = uiState.settings.rounds,
                onChange = { value -> viewModel.updateSettings { it.copy(rounds = value) } },
            )
            NumberField(
                label = stringResource(id = R.string.assistant_settings_history),
                value = uiState.settings.historyDepth,
                onChange = { value -> viewModel.updateSettings { it.copy(historyDepth = value) } },
            )

            SectionTitle(stringResource(id = R.string.assistant_settings_prompt_title))
            Text(
                text = stringResource(id = R.string.assistant_settings_prompt_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.settings.promptAdditions,
                onValueChange = { text -> viewModel.updateSettings { it.copy(promptAdditions = text) } },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
            Text(
                text = stringResource(
                    id = R.string.assistant_settings_prompt_budget,
                    uiState.settings.promptAdditions.length,
                    AssistantDefaults.MAX_PROMPT_ADDITIONS,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { builtinExpanded = !builtinExpanded }) {
                Text(
                    stringResource(id = R.string.assistant_settings_builtin_title) +
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
                    text = stringResource(id = R.string.assistant_settings_builtin_caption),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle(stringResource(id = R.string.assistant_settings_about_title))
            Text(
                text = stringResource(id = R.string.assistant_settings_about_caption),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val aboutSpecs = listOf(
                Triple<Int, String, (AssistantAboutMe, String) -> AssistantAboutMe>(
                    R.string.assistant_about_goals,
                    uiState.settings.aboutMe.goals,
                ) { about, value -> about.copy(goals = value) },
                Triple(R.string.assistant_about_wants, uiState.settings.aboutMe.wants) { about, value ->
                    about.copy(wants = value)
                },
                Triple(R.string.assistant_about_needs, uiState.settings.aboutMe.needs) { about, value ->
                    about.copy(needs = value)
                },
                Triple(R.string.assistant_about_strengths, uiState.settings.aboutMe.strengths) { about, value ->
                    about.copy(strengths = value)
                },
                Triple(R.string.assistant_about_focus, uiState.settings.aboutMe.focusAreas) { about, value ->
                    about.copy(focusAreas = value)
                },
                Triple(R.string.assistant_about_note, uiState.settings.aboutMe.note) { about, value ->
                    about.copy(note = value)
                },
            )
            aboutSpecs.forEach { (labelRes, value, update) ->
                AboutField(
                    label = stringResource(id = labelRes),
                    value = value,
                    onChange = { text ->
                        viewModel.updateSettings { it.copy(aboutMe = update(it.aboutMe, text)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by rememberSaveable(label) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            text = updated.filter { it.isDigit() }.take(5)
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
private fun AboutField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = 1,
        maxLines = 3,
    )
}
