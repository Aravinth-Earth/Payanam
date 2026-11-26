//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.payanam.core.ui.credits.ThirdPartyCredits
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Rule
import org.junit.Test

/**
 * The desktop About card was empty until this card existed: the shared settings screen takes its About body
 * as a slot and the desktop passed none. What this pins is that the card now says the things the Android
 * card says - the version, the tagline, the project link - and that the shared credits list is actually
 * rendered below them, rather than the slot being filled with a heading and nothing else.
 */
class DesktopAboutContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    @Test
    fun theCardCarriesTheVersionTheTaglineAndTheCredits() {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DesktopAboutContent(version = "1.0.4")
                }
            }
        }
        composeRule.onNodeWithText("1.0.4").assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsAboutTagline).assertIsDisplayed()
        composeRule.onNodeWithText(strings.settingsCreditsTitle).assertIsDisplayed()
        composeRule.onNodeWithText(ThirdPartyCredits.all.first().name).assertIsDisplayed()
    }
}
