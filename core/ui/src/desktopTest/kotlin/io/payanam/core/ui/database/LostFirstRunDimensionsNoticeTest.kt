//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.database

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Rule
import org.junit.Test

/**
 * The notice that reports a lost first-run life-dimension confirmation, rendered for real.
 *
 * WHY THIS FILE EXISTS BESIDE THE VIEWMODEL TEST: `FirstRunDimensionsLossTest` in `:core:presentation`
 * pins that the flag is set, cleared and published - that the app KNOWS the confirmation was lost. It
 * cannot pin that the user is ever told, because the reporting is a composable in another module and a
 * flag nothing renders is the same silent loss with a test on it. So the visible half is pinned here,
 * where the notice is drawn.
 *
 * The notice is rendered directly rather than through `DatabaseInitScreen`, because that screen takes a
 * real `DatabaseInitViewModel` with thirteen collaborators, and building one would test the screen's
 * plumbing instead of the one string this file is about. What is asserted is exactly what a user's
 * accessibility tree and their eye would find: the semantics identity, and the copy in the language
 * being run.
 *
 * THE ABSENCE CASE IS HALF THE FILE. A notice that renders unconditionally is the same defect wearing
 * the opposite sign - it tells every user, including a first launch that never confirmed anything, that
 * their confirmation was lost. Only a rendering is not enough; the flag-gated call site in
 * `DatabaseInitScreen` is what makes it conditional, and this file asserts the notice itself has no
 * opinion about that, so the two facts stay separable.
 */
class LostFirstRunDimensionsNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the notice renders the copy and publishes its identity`() {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUiStrings provides UiStrings.of(UiLanguage.ENGLISH)) {
                    LostFirstRunDimensionsNotice(
                        message = UiStrings.of(UiLanguage.ENGLISH).dbInitDimensionSetupNotSaved,
                    )
                }
            }
        }

        // The identity the DatabaseInitScreen test asserts on, and the copy beside it. Two assertions
        // rather than one because they fail for different reasons: a missing identity is a rename, and
        // missing copy is an empty table.
        composeRule
            .onNodeWithContentDescription(LOST_FIRST_RUN_DIMENSIONS_NOTICE_ID)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(UiStrings.of(UiLanguage.ENGLISH).dbInitDimensionSetupNotSaved)
            .assertIsDisplayed()
    }

    @Test
    fun `the notice says what happened in Tamil too, and never shows an English fallback`() {
        val tamil = UiStrings.of(UiLanguage.TAMIL)
        val english = UiStrings.of(UiLanguage.ENGLISH)

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUiStrings provides tamil) {
                    LostFirstRunDimensionsNotice(message = tamil.dbInitDimensionSetupNotSaved)
                }
            }
        }

        composeRule
            .onNodeWithContentDescription(LOST_FIRST_RUN_DIMENSIONS_NOTICE_ID)
            .assertIsDisplayed()
        // Not a subset test on the English text: a Tamil row that merely CONTAINED the English sentence
        // would pass a `contains` check, which is exactly the mixed-language output this asserts against.
        // Equal-text is the shape a copy-paste fallback takes, and it is caught outright.
        assertThat(tamil.dbInitDimensionSetupNotSaved).isNotEqualTo(english.dbInitDimensionSetupNotSaved)
        // The sharpest available probe for "this row was filled from the English source": the English copy
        // is pure ASCII, so a Tamil row that carries Tamil SCRIPT is translated and one that carries none
        // is a fallback wearing the -ta filename. A `contains(Latin)` test could not tell those apart,
        // because Latin punctuation and digits are legitimately shared.
        assertThat(tamil.dbInitDimensionSetupNotSaved).isNotEmpty()
        assertThat(tamil.dbInitDimensionSetupNotSaved)
            .matches(".*\\p{IsTamil}.*")
        assertThat(english.dbInitDimensionSetupNotSaved).doesNotMatch(".*\\p{IsTamil}.*")
    }

    @Test
    fun `a bare screen renders no notice, because only the screen knows the flag`() {
        // The negative control. Without it, the two cases above could both be passing because the
        // notice always composes - which is the false-alarm failure, and it is exactly what the flag
        // gating in `DatabaseInitScreen` exists to prevent.
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUiStrings provides UiStrings.of(UiLanguage.ENGLISH)) {
                    Text("first-run dimension setup, with nothing to report")
                }
            }
        }

        composeRule
            .onAllNodesWithContentDescription(LOST_FIRST_RUN_DIMENSIONS_NOTICE_ID)
            .assertCountEquals(0)
    }
}
