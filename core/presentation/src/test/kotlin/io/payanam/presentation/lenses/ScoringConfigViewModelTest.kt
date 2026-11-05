//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.domain.model.LifeDimension
import io.payanam.domain.model.ScoringConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scoring-configuration editor: the load, every edit, and the three actions.
 *
 * The ViewModel is constructed with `scope = CoroutineScope(dispatcher)` because its own default scope is
 * `Dispatchers.Default`, which `Dispatchers.setMain` does not drive - a test that only sets Main would assert
 * against a load that has not run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoringConfigViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeScoringConfigRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeScoringConfigRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): ScoringConfigViewModel = ScoringConfigViewModel(repository, scope = CoroutineScope(dispatcher))

    @Test
    fun `init loads the stored configuration`() =
        runTest(dispatcher) {
            val stored = ScoringConfig.defaults().copy(dimensionWeight = 3.5)
            repository = FakeScoringConfigRepository(stored)

            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(stored, model.uiState.value.config)
            assertFalse("loading to have finished", model.uiState.value.isLoading)
            assertFalse("a freshly loaded config has no pending edits", model.uiState.value.hasChanges)
        }

    @Test
    fun `the six factor-weight setters each record an edit`() =
        runTest(dispatcher) {
            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()

            model.setDimensionWeight(2.5)
            assertEquals(2.5, model.uiState.value.config.dimensionWeight, 1e-9)
            assertTrue(model.uiState.value.hasChanges)

            model.setImpactWeight(1.75)
            assertEquals(1.75, model.uiState.value.config.impactWeight, 1e-9)

            model.setAlignmentWeight(1.25)
            assertEquals(1.25, model.uiState.value.config.alignmentWeight, 1e-9)

            model.setEnergyWeight(0.9)
            assertEquals(0.9, model.uiState.value.config.energyWeight, 1e-9)

            model.setControlWeight(1.1)
            assertEquals(1.1, model.uiState.value.config.controlWeight, 1e-9)

            model.setDurationWeight(0.7)
            assertEquals(0.7, model.uiState.value.config.durationWeight, 1e-9)
        }

    @Test
    fun `the level setters each update their own map`() =
        runTest(dispatcher) {
            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()
            val config = model.uiState.value.config

            model.setImpactLevelValue("High Impact", 0.42)
            assertEquals(0.42, model.uiState.value.config.impactLevelWeights["High Impact"] ?: -1.0, 1e-9)
            assertEquals(
                "the other levels are untouched",
                config.impactLevelWeights["Critical Impact"],
                model.uiState.value.config.impactLevelWeights["Critical Impact"],
            )

            model.setAlignmentValue("Strong Alignment", 0.33)
            assertEquals(0.33, model.uiState.value.config.alignmentWeights["Strong Alignment"] ?: -1.0, 1e-9)

            model.setEnergyLevelValue("Moderate", 0.55)
            assertEquals(0.55, model.uiState.value.config.energyLevelWeights["Moderate"] ?: -1.0, 1e-9)

            model.setControlLevelValue("No Control", 0.05)
            assertEquals(0.05, model.uiState.value.config.controlLevelWeights["No Control"] ?: -1.0, 1e-9)
        }

    @Test
    fun `the dimension setters update the per-dimension weights`() =
        runTest(dispatcher) {
            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()

            model.setDimensionValue(LifeDimension.CAREER_WORK, 0.25)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(
                "the id-based map carries the new weight",
                model.uiState.value.config.dimensionWeightsById.values
                    .any { it == 0.25 },
            )
            assertTrue(model.uiState.value.hasChanges)
        }

    @Test
    fun `saveConfig persists the edit and clears the pending flag`() =
        runTest(dispatcher) {
            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()

            model.setDimensionWeight(4.0)
            model.saveConfig()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(4.0, repository.saved?.dimensionWeight ?: -1.0, 1e-9)
            assertFalse("after saving there is nothing pending", model.uiState.value.hasChanges)
            assertFalse("and the save indicator stops", model.uiState.value.isSaving)
        }

    @Test
    fun `resetToDefaults persists the defaults`() =
        runTest(dispatcher) {
            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()
            model.setDimensionWeight(9.0)

            model.resetToDefaults()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, repository.resetCount)
            assertEquals(ScoringConfig.defaults(), model.uiState.value.config)
            assertFalse(model.uiState.value.hasChanges)
        }

    @Test
    fun `discardChanges restores the last loaded configuration without saving`() =
        runTest(dispatcher) {
            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()
            val loaded = model.uiState.value.config

            model.setDimensionWeight(9.0)
            assertTrue(model.uiState.value.hasChanges)

            model.discardChanges()

            assertEquals(loaded, model.uiState.value.config)
            assertFalse(model.uiState.value.hasChanges)
            assertNull("discarding does not write to the repository", repository.saved)
        }

    @Test
    fun `an edit back to the loaded value clears the pending flag`() =
        runTest(dispatcher) {
            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()
            val original = model.uiState.value.config.dimensionWeight

            model.setDimensionWeight(original + 1.0)
            assertTrue(model.uiState.value.hasChanges)

            model.setDimensionWeight(original)
            assertFalse("back at the loaded value, nothing is pending", model.uiState.value.hasChanges)
        }

    @Test
    fun `dispose cancels the work the ViewModel started`() =
        runTest(dispatcher) {
            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()

            model.dispose()
            dispatcher.scheduler.advanceUntilIdle()

            // A setter still updates state (it is a plain state edit, not launched work), but nothing the
            // ViewModel launches after disposal reaches the repository.
            model.setDimensionWeight(7.0)
            model.saveConfig()
            dispatcher.scheduler.advanceUntilIdle()

            assertNull("a disposed ViewModel does not persist anything", repository.saved)
        }

    @Test
    fun `the state carries the loaded config rather than the defaults`() =
        runTest(dispatcher) {
            repository = FakeScoringConfigRepository(ScoringConfig.defaults().copy(durationWeight = 0.15))

            val model = viewModel()
            dispatcher.scheduler.advanceUntilIdle()

            assertNotNull(model.uiState.value.config)
            assertEquals(0.15, model.uiState.value.config.durationWeight, 1e-9)
        }
}
