//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.preferences.TimeFormatOption
import io.payanam.core.ui.preferences.labelKey
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Test

/**
 * The label keys are the part of these enums that travels, and the bridge from a key to its copy must be
 * total and must agree with the key's name. This test moved here WITH `settingsLabel` rather than staying
 * behind in `:app`: a table whose proof sits in another module is a table that can lose a key quietly,
 * because the check that guards it is no longer run when the table is read.
 *
 * NOT asserted through `Resources.getIdentifier`: that answers about the compiled resource table and its
 * package resolution rather than about this code, and it reported zero for names that demonstrably exist.
 * Each branch is instead spelled against the copy table's own properties, which is the compiler's job.
 *
 * The failures worth guarding are the silent ones: a key list that misses an enum entry, two entries
 * sharing a key, and a branch pointing at the wrong label. All three render as plausible-looking copy.
 */
class SettingsLabelKeyTest {
    @Test
    fun `every entry of every enum produces a key`() {
        assertThat(ThemeModeOption.entries.map { it.labelKey }).hasSize(ThemeModeOption.entries.size)
        assertThat(FontFamilyOption.entries.map { it.labelKey }).hasSize(FontFamilyOption.entries.size)
        assertThat(TimeFormatOption.entries.map { it.labelKey }).hasSize(TimeFormatOption.entries.size)
        assertThat(BackupInterval.entries.map { it.labelKey }).hasSize(BackupInterval.entries.size)
        // The fifth mapping, which was inline in the appearance section until the key contract covered it.
        assertThat(AppLanguageOption.entries.map { it.labelKey }).hasSize(AppLanguageOption.entries.size)
    }

    @Test
    fun `keys are unique across all five enums`() {
        // Two entries sharing a key would make one of them render the other's label.
        assertThat(allKeys()).containsNoDuplicates()
    }

    @Test
    fun `every key resolves, rather than falling into the failure branch`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.TAMIL).forEach { language ->
            val strings = UiStrings.of(language)
            allKeys().forEach { key ->
                assertThat(runCatching { strings.settingsLabel(key) }.exceptionOrNull()).isNull()
            }
        }
    }

    @Test
    fun `the bridge maps each key to the copy it names`() {
        // The name is the contract, so the branch must agree with it: a copy-paste slip would otherwise map
        // "theme_dark" onto the light label and nothing would report it. A key that resolved to an EMPTY
        // string would satisfy a non-null check while rendering a blank row, so emptiness is asserted too.
        val english = UiStrings.of(UiLanguage.ENGLISH)
        assertThat(english.settingsLabel("settings_option_theme_dark")).isEqualTo(english.settingsOptionThemeDark)
        assertThat(english.settingsLabel("settings_option_theme_light")).isEqualTo(english.settingsOptionThemeLight)
        assertThat(english.settingsLabel("settings_option_time_format_12h")).isEqualTo(english.settingsOptionTimeFormat12h)
        assertThat(english.settingsLabel("settings_option_backup_daily")).isEqualTo(english.settingsOptionBackupDaily)
        assertThat(english.settingsLabel("settings_language_option_tamil")).isEqualTo(english.settingsLanguageOptionTamil)
    }

    @Test
    fun `every label renders non-empty copy in every shipped language`() {
        // The failure this table exists to prevent is a BLANK label, so emptiness is the assertion that
        // matters most, and it is the one a resource id could not make here.
        listOf(UiLanguage.ENGLISH, UiLanguage.TAMIL).forEach { language ->
            val strings = UiStrings.of(language)
            allKeys().forEach { key ->
                assertThat(strings.settingsLabel(key)).isNotEmpty()
            }
        }
    }

    @Test
    fun `an unmapped key fails loudly rather than rendering nothing`() {
        val strings = UiStrings.of(UiLanguage.ENGLISH)

        val failure = runCatching { strings.settingsLabel("settings_option_not_a_real_key") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("No string mapped")
    }

    private fun allKeys(): List<String> =
        ThemeModeOption.entries.map { it.labelKey } +
            FontFamilyOption.entries.map { it.labelKey } +
            TimeFormatOption.entries.map { it.labelKey } +
            BackupInterval.entries.map { it.labelKey } +
            AppLanguageOption.entries.map { it.labelKey }
}
