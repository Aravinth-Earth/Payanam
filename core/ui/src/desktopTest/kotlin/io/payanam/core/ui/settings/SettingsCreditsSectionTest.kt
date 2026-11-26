//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The credits block, rendered for real: its heading, its first block, and the promise that the row itself
 * is the link. The list's own shape is pinned by `ThirdPartyCreditsTest`; what this proves is that a row
 * a person sees is a row a person can tap, and that the tap carries the library's own url - not the
 * project's, and not a search page.
 */
class SettingsCreditsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    @Test
    fun theSectionNamesItselfAndItsFirstBlock() {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SettingsCreditsSection(onOpenUrl = {})
                }
            }
        }
        composeRule.onNodeWithText(strings.settingsCreditsTitle).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsCreditsIntro).assertIsDisplayed()
        composeRule.onNodeWithText("Android runtime").assertIsDisplayed()
        composeRule.onNodeWithText("AndroidX Core KTX").assertIsDisplayed()
    }

    @Test
    fun tappingARowOpensThatLibrariesPage() {
        var opened: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SettingsCreditsSection(onOpenUrl = { opened = it })
                }
            }
        }
        composeRule.onNodeWithTag("settings-credit-AndroidX Core KTX").performClick()
        assertEquals("https://developer.android.com/kotlin/ktx", opened)
    }
}
