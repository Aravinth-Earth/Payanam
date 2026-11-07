//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.lenses

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.repository.ScoringConfigRepository
import io.payanam.presentation.lenses.ScoringConfigViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The scoring editor rendered for real on the JVM, against a repository whose read can be made to fail.
 *
 * A ViewModel test can prove that the writes are refused; only a rendered screen can prove that the user is
 * not offered them. Both halves matter here: a Slider that still looks editable over defaults promotes the
 * defaults to "the user's weights", and a Reset button that still presses deletes the configuration this
 * screen never managed to read.
 *
 * The real ViewModel is constructed over the fake repository - the same fake's
 * [ControllableScoringConfigRepository.saved] and [ControllableScoringConfigRepository.resetCount] are
 * asserted, so "the control is disabled" and "nothing was written" are checked against the same run.
 */
class ScoringConfigScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a failed read disables every slider, reset and save`() {
        val repository = ControllableScoringConfigRepository(failing = true)
        val viewModel = ScoringConfigViewModel(repository)

        render(viewModel)
        composeRule.waitUntil(WAIT_MS) { viewModel.uiState.value.loadFailed }
        composeRule.waitForIdle()

        val sliders = composeRule.onAllNodesWithTag(SLIDER_TAG)
        val sliderCount = sliders.fetchSemanticsNodes().size
        assertTrue("the fixture must draw sliders for this to be a test of them", sliderCount > 0)
        for (index in 0 until sliderCount) {
            sliders[index].assertIsNotEnabled()
        }

        composeRule.onNodeWithContentDescription(strings.locResetToDefaults).assertIsNotEnabled()
        composeRule.onNodeWithText(strings.locSave).assertIsNotEnabled()
        assertNull("no write is offered over a failed read", repository.saved)
        assertEquals(0, repository.resetCount)
    }

    @Test
    fun `a retry re-enables the editor only after the read succeeds`() {
        val repository = ControllableScoringConfigRepository(failing = true)
        val viewModel = ScoringConfigViewModel(repository)

        render(viewModel)
        composeRule.waitUntil(WAIT_MS) { viewModel.uiState.value.loadFailed }

        repository.failing = false
        composeRule.onNodeWithText(strings.settingsUpdateRetryButton).performClick()

        composeRule.waitUntil(WAIT_MS) { !viewModel.uiState.value.loadFailed }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(SLIDER_TAG)[0].assertIsEnabled()
        composeRule.onNodeWithContentDescription(strings.locResetToDefaults).assertIsEnabled()
        assertNull("the retry itself writes nothing", repository.saved)
        assertEquals(0, repository.resetCount)
    }

    private fun render(viewModel: ScoringConfigViewModel) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalUiStrings provides strings,
                LocalAppPreferences provides
                    AppPreferencesState(
                        isLoading = false,
                        dimensionPreferences =
                            listOf(
                                DimensionPreference(
                                    key = "dim_health",
                                    label = "Health",
                                    color = Color(0xFF4CAF50),
                                    isVisible = true,
                                ),
                            ),
                    ),
            ) {
                ScoringConfigScreen(viewModel = viewModel)
            }
        }
    }

    private companion object {
        /** Long enough for a real dispatcher hop on a loaded CI box, short enough to fail rather than hang. */
        const val WAIT_MS = 10_000L

        /** The tag `WeightSlider` puts on every `Slider` on this screen. */
        const val SLIDER_TAG = "scoring_config_slider"

        val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)
    }
}

/**
 * The scoring-configuration repository with a read that can be failed and then repaired, so one test can walk
 * the failure and its retry in the order the user does.
 */
private class ControllableScoringConfigRepository(
    var failing: Boolean,
) : ScoringConfigRepository {
    /** The last config passed to [saveConfig], or null when nothing was written. */
    var saved: ScoringConfig? = null

    /** How many times a reset was requested. */
    var resetCount: Int = 0

    private var stored: ScoringConfig = ScoringConfig.defaults().copy(dimensionWeight = 2.25)

    override suspend fun getConfig(): ScoringConfig {
        if (failing) throw IllegalStateException("the database session is closed")
        return stored
    }

    override fun observeConfig(): Flow<ScoringConfig> = flowOf(stored)

    override suspend fun saveConfig(config: ScoringConfig) {
        saved = config
        stored = config
    }

    override suspend fun resetToDefaults() {
        resetCount++
        stored = ScoringConfig.defaults()
    }
}
