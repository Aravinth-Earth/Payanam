//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.AppLanguageOption
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.AppPreferencesViewModel
import io.payanam.ui.viewmodel.FontFamilyOption
import io.payanam.ui.viewmodel.ThemeModeOption
import io.payanam.ui.viewmodel.TimeFormatOption
import io.payanam.ui.viewmodel.labelResId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun settingsAppearanceSection(
    prefsState: AppPreferencesState,
    prefsViewModel: AppPreferencesViewModel,
    logger: UnifiedLogger,
    fontFamilyExpanded: Boolean,
    onFontFamilyExpandedChange: (Boolean) -> Unit,
) {
    if (prefsState.isLoading) {
        Text(
            text = stringResource(id = R.string.settings_loading_preferences),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(id = R.string.settings_app_language),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppLanguageOption.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = AppLanguageOption.entries.size),
                    onClick = {
                        prefsViewModel.setAppLanguage(option)
                        logger.d("SettingsScreen.appLanguage", "App language updated", mapOf("language" to option.key))
                    },
                    selected = prefsState.appLanguage == option,
                ) {
                    val labelRes = when (option) {
                        AppLanguageOption.SYSTEM -> R.string.settings_language_option_system
                        AppLanguageOption.ENGLISH -> R.string.settings_language_option_english
                        AppLanguageOption.TAMIL -> R.string.settings_language_option_tamil
                    }
                    Text(text = stringResource(id = labelRes), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            text = stringResource(id = R.string.settings_app_language_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(id = R.string.settings_theme_mode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeModeOption.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeModeOption.entries.size),
                    onClick = {
                        prefsViewModel.setThemeMode(option)
                        logger.d("SettingsScreen.themeMode", "Theme mode updated", mapOf("mode" to option.key))
                    },
                    selected = prefsState.themeMode == option,
                ) {
                    Text(text = stringResource(id = option.labelResId), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            text = stringResource(id = R.string.settings_font_family),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(
            expanded = fontFamilyExpanded,
            onExpandedChange = onFontFamilyExpandedChange,
        ) {
            OutlinedTextField(
                value = stringResource(id = prefsState.fontFamily.labelResId),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fontFamilyExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            DropdownMenu(
                expanded = fontFamilyExpanded,
                onDismissRequest = { onFontFamilyExpandedChange(false) },
            ) {
                FontFamilyOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(id = option.labelResId)) },
                        onClick = {
                            prefsViewModel.setFontFamily(option)
                            onFontFamilyExpandedChange(false)
                            logger.d("SettingsScreen.fontFamily", "Font family updated", mapOf("family" to option.key))
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(id = R.string.settings_time_format),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TimeFormatOption.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TimeFormatOption.entries.size),
                    onClick = {
                        prefsViewModel.setTimeFormat(option)
                        logger.d("SettingsScreen.timeFormat", "Time format updated", mapOf("format" to option.key))
                    },
                    selected = prefsState.timeFormat == option,
                ) {
                    Text(text = stringResource(id = option.labelResId), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
