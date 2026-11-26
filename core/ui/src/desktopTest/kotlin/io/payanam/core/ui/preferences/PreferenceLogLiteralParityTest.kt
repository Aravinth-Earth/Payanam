//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.preferences

import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.presentation.preferences.NonContentPreferenceValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Keeps the log allowlist honest about the enums that live in THIS module.
 *
 * `AppPreferencesWriter` (and its literal table) sits below `:core:ui`, so it cannot import
 * [ThemeModeOption], [AppLanguageOption], [FontFamilyOption] or [TimeFormatOption]; their stored keys are
 * therefore spelled out there with a citation. That copy is the drift risk this test removes: adding an
 * option to one of these enums without adding its key to the table fails here, instead of silently
 * producing a preference whose value can never be asserted from the log.
 */
class PreferenceLogLiteralParityTest {
    private fun literal(
        key: String,
        value: String,
    ): String? = NonContentPreferenceValues.literalFor(key, value)

    @Test
    fun `every theme mode the enum can store is a loggable literal`() {
        ThemeModeOption.entries.forEach { option ->
            assertEquals(option.key, literal(AppPreferenceKeys.KEY_THEME_MODE, option.key))
        }
    }

    @Test
    fun `every app language the enum can store is a loggable literal`() {
        AppLanguageOption.entries.forEach { option ->
            assertEquals(option.key, literal(AppPreferenceKeys.KEY_APP_LANGUAGE, option.key))
        }
    }

    @Test
    fun `every font family the enum can store is a loggable literal`() {
        FontFamilyOption.entries.forEach { option ->
            assertEquals(option.key, literal(AppPreferenceKeys.KEY_FONT_FAMILY, option.key))
        }
    }

    @Test
    fun `every time format the enum can store is a loggable literal`() {
        TimeFormatOption.entries.forEach { option ->
            assertEquals(option.key, literal(AppPreferenceKeys.KEY_TIME_FORMAT, option.key))
        }
    }

    @Test
    fun `the table does not become a blanket admission`() {
        // Parity alone would still pass if the table admitted every string, so one out-of-domain value is
        // pinned here as well.
        assertNull(literal(AppPreferenceKeys.KEY_THEME_MODE, "my favourite theme"))
    }
}
