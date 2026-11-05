//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.preferences.TimeFormatOption
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.shared.logging.AppLogger

/**
 * Appearance settings section: language, theme mode, font family and time format.
 *
 * WHY THE WRITES ARE PARAMETERS: there is no platform behaviour in this section. It reads the shared
 * [AppPreferencesState] and reports each choice to a callback, so one rendering serves Android and the
 * desktop - the arrangement already used by the Time screen and the auto-tracking section. Taking an
 * Android ViewModel here would put the platform back in the file for no gain.
 *
 * The four choices are SEPARATE callbacks rather than one, because they are four different rows writing
 * four different preferences. Collapsing them into a single `onPreferenceChanged` would push a `when` over
 * the row identity onto every caller, and the distinction is the section's whole content.
 *
 * The font family's dropdown expansion is hoisted state rather than local: the section is rebuilt inside
 * a collapsing card, so an expanded menu owned here would close on every unrelated recomposition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearanceSection(
    prefsState: AppPreferencesState,
    onAppLanguageChange: (AppLanguageOption) -> Unit,
    onThemeModeChange: (ThemeModeOption) -> Unit,
    onFontFamilyChange: (FontFamilyOption) -> Unit,
    onTimeFormatChange: (TimeFormatOption) -> Unit,
    fontFamilyExpanded: Boolean,
    onFontFamilyExpandedChange: (Boolean) -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    if (prefsState.isLoading) {
        Text(
            text = strings.settingsLoadingPreferences,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = strings.settingsAppLanguage,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppLanguageOption.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = AppLanguageOption.entries.size),
                    onClick = {
                        onAppLanguageChange(option)
                        logger.d("SettingsScreen.appLanguage", "App language updated", mapOf("language" to option.key))
                    },
                    selected = prefsState.appLanguage == option,
                ) {
                    Text(
                        text = strings.appLanguageLabel(option),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Text(
            text = strings.settingsAppLanguageHelp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = strings.settingsThemeMode,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeModeOption.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeModeOption.entries.size),
                    onClick = {
                        onThemeModeChange(option)
                        logger.d("SettingsScreen.themeMode", "Theme mode updated", mapOf("mode" to option.key))
                    },
                    selected = prefsState.themeMode == option,
                ) {
                    Text(text = strings.themeModeLabel(option), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            text = strings.settingsFontFamily,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(
            expanded = fontFamilyExpanded,
            onExpandedChange = onFontFamilyExpandedChange,
        ) {
            OutlinedTextField(
                value = strings.selectedFontFamilyLabel(prefsState),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fontFamilyExpanded) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            DropdownMenu(
                expanded = fontFamilyExpanded,
                onDismissRequest = { onFontFamilyExpandedChange(false) },
            ) {
                FontFamilyOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(strings.fontFamilyLabel(option)) },
                        onClick = {
                            onFontFamilyChange(option)
                            onFontFamilyExpandedChange(false)
                            logger.d("SettingsScreen.fontFamily", "Font family updated", mapOf("family" to option.key))
                        },
                    )
                }
            }
        }
        Text(
            text = strings.settingsTimeFormat,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TimeFormatOption.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TimeFormatOption.entries.size),
                    onClick = {
                        onTimeFormatChange(option)
                        logger.d("SettingsScreen.timeFormat", "Time format updated", mapOf("format" to option.key))
                    },
                    selected = prefsState.timeFormat == option,
                ) {
                    Text(text = strings.timeFormatLabel(option), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
