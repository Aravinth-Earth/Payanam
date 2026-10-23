//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import io.payanam.R
import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.preferences.TimeFormatOption
import io.payanam.core.ui.preferences.labelKey
import org.junit.Test

/**
 * The label keys are the part of these enums that moves to the shared module, so the bridge between a key and
 * its string resource must be total and must agree with the key's name.
 *
 * NOT asserted through `Resources.getIdentifier`: that lookup answers about the compiled resource table and
 * its package resolution rather than about this code, and it reported zero for names that demonstrably exist
 * — an early version of this test failed for that reason alone. The `R.string.*` names are instead referenced
 * literally below, which is the compiler's job to verify.
 *
 * The failures worth guarding are the silent ones: a key list that misses an enum entry, two entries sharing
 * a key, and a `when` branch pointing at the wrong label. All three render as plausible-looking copy.
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
    fun `keys are unique across all four enums`() {
        // Two entries sharing a key would make one of them render the other's label.
        assertThat(allKeys()).containsNoDuplicates()
    }

    @Test
    fun `every key resolves, rather than falling into the failure branch`() {
        allKeys().forEach { key ->
            assertThat(settingsLabelResId(key)).isNotEqualTo(0)
        }
    }

    @Test
    fun `the bridge maps each key to the resource it names`() {
        // The name is the contract, so the branch must agree with it: a copy-paste slip would otherwise map
        // "theme_dark" onto the light label and nothing would report it.
        assertThat(settingsLabelResId("settings_option_theme_dark")).isEqualTo(R.string.settings_option_theme_dark)
        assertThat(settingsLabelResId("settings_option_theme_light")).isEqualTo(R.string.settings_option_theme_light)
        assertThat(settingsLabelResId("settings_option_time_format_12h"))
            .isEqualTo(R.string.settings_option_time_format_12h)
        assertThat(settingsLabelResId("settings_option_backup_daily"))
            .isEqualTo(R.string.settings_option_backup_daily)
        assertThat(settingsLabelResId("settings_language_option_tamil"))
            .isEqualTo(R.string.settings_language_option_tamil)
    }

    @Test
    fun `an unmapped key fails loudly rather than rendering nothing`() {
        val failure = runCatching { settingsLabelResId("settings_option_not_a_real_key") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("No string resource mapped")
    }

    private fun allKeys(): List<String> =
        ThemeModeOption.entries.map { it.labelKey } +
            FontFamilyOption.entries.map { it.labelKey } +
            TimeFormatOption.entries.map { it.labelKey } +
            BackupInterval.entries.map { it.labelKey } +
            AppLanguageOption.entries.map { it.labelKey }
}
