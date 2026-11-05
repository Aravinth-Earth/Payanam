//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.preferences.TimeFormatOption
import io.payanam.core.ui.preferences.labelKey
import io.payanam.core.ui.text.UiStrings

/**
 * Resolves a label KEY to copy, off the shared copy table rather than a resource id.
 *
 * WHY STRINGS AND NOT RESOURCE IDS: the table this replaces was a `when (key)` in the app module
 * returning `R.string.*`, so it could only be read by a module that has an `R` - that is, Android. The
 * label a settings row shows is COPY, and copy already has a platform-neutral home in `UiStrings`, which
 * every platform builds from the same string files. Handing the section a `UiStrings` and reading
 * `strings.settingsOptionThemeDark` keeps one source of truth for the words; the old shape would have
 * meant a second table, which is the divergence this promotion exists to end.
 *
 * The switch is a `when` on the key rather than a runtime lookup by name for the same reason the app's
 * was: every key is named literally, so a key that loses its entry is a compile error here and the
 * completeness test below is the belt to that braces.
 *
 * An unmapped key fails loudly rather than rendering nothing. A blank label is the hardest kind of bug
 * to notice - the row is still there, still the right height, still clickable.
 */
internal fun UiStrings.settingsLabel(key: String): String =
    when (key) {
        "settings_option_theme_system" -> settingsOptionThemeSystem
        "settings_option_theme_light" -> settingsOptionThemeLight
        "settings_option_theme_dark" -> settingsOptionThemeDark
        "settings_option_font_sans_serif" -> settingsOptionFontSansSerif
        "settings_option_font_monospace" -> settingsOptionFontMonospace
        "settings_option_font_serif" -> settingsOptionFontSerif
        "settings_option_font_cursive" -> settingsOptionFontCursive
        "settings_option_time_format_24h" -> settingsOptionTimeFormat24h
        "settings_option_time_format_12h" -> settingsOptionTimeFormat12h
        "settings_option_backup_15_min" -> settingsOptionBackup15Min
        "settings_option_backup_30_min" -> settingsOptionBackup30Min
        "settings_option_backup_1_hour" -> settingsOptionBackup1Hour
        "settings_option_backup_2_hours" -> settingsOptionBackup2Hours
        "settings_option_backup_6_hours" -> settingsOptionBackup6Hours
        "settings_option_backup_12_hours" -> settingsOptionBackup12Hours
        "settings_option_backup_daily" -> settingsOptionBackupDaily
        "settings_language_option_system" -> settingsLanguageOptionSystem
        "settings_language_option_english" -> settingsLanguageOptionEnglish
        "settings_language_option_tamil" -> settingsLanguageOptionTamil
        else -> error("No string mapped for label key: $key")
    }

/**
 * The label for a theme-mode option, in the given language.
 *
 * The `labelKey` properties carry the RESOURCE NAME rather than its id precisely so this lookup can be
 * spelled in shared code; that is why the enums themselves were already platform-neutral.
 */
fun UiStrings.themeModeLabel(option: ThemeModeOption): String = settingsLabel(option.labelKey)

/** The label for a font-family option. */
fun UiStrings.fontFamilyLabel(option: FontFamilyOption): String = settingsLabel(option.labelKey)

/** The label for a time-format option. */
fun UiStrings.timeFormatLabel(option: TimeFormatOption): String = settingsLabel(option.labelKey)

/** The label for a backup-interval option. */
fun UiStrings.backupIntervalLabel(interval: BackupInterval): String = settingsLabel(interval.labelKey)

/**
 * The fifth label mapping, which lived inline in the appearance section rather than as a property - so a
 * move that looked only for `labelResId` properties would have left this one behind in the app module.
 */
fun UiStrings.appLanguageLabel(option: AppLanguageOption): String = settingsLabel(option.labelKey)

/**
 * The font family the preferences state has selected, as its label.
 *
 * Separate from [fontFamilyLabel] because the dropdown's READONLY field shows the selection while the menu
 * shows every option; both need the same words, from one place.
 */
fun UiStrings.selectedFontFamilyLabel(prefsState: AppPreferencesState): String = fontFamilyLabel(prefsState.fontFamily)
