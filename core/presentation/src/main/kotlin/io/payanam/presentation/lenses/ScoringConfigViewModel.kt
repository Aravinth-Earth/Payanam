//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.repository.ScoringConfigRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for scoring configuration screen.
 */
data class ScoringConfigUiState(
    val config: ScoringConfig = ScoringConfig.defaults(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val hasChanges: Boolean = false,
    /**
     * The stored configuration could not be read, so [config] holds defaults rather than the user's values.
     * Saving in this state would overwrite every stored weight with a default the user never chose, so the
     * screen disables save and offers a retry until the read succeeds.
     *
     * The flag stays raised for the WHOLE retry window, not just until the retry starts: it records that the
     * last read did not succeed, so only a successful read may clear it. While it is set the ViewModel also
     * refuses edits (the values on screen are defaults, not the user's) and refuses a reset (that would
     * delete the stored configuration nobody has managed to read).
     */
    val loadFailed: Boolean = false,
)

/**
 * ViewModel for the scoring configuration screen.
 */
class ScoringConfigViewModel(
    private val scoringConfigRepository: ScoringConfigRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current

    private val _uiState = MutableStateFlow(ScoringConfigUiState())
    val uiState: StateFlow<ScoringConfigUiState> = _uiState.asStateFlow()

    private var originalConfig: ScoringConfig = ScoringConfig.defaults()

    init {
        loadConfig()
    }

    /**
     * Re-reads the stored configuration; also the retry action after a failed read.
     *
     * [ScoringConfigUiState.loadFailed] is deliberately NOT cleared on entry. Only a successful read may lift
     * it: clearing it here would re-open editing, Reset and Save for the whole retry window, during which
     * [ScoringConfigUiState.config] still holds defaults rather than the user's stored values.
     */
    @Suppress("TooGenericExceptionCaught") // A closed DB session surfaces as an arbitrary repository exception
    fun reloadConfig() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        loadConfig()
    }

    @Suppress("TooGenericExceptionCaught") // A closed DB session surfaces as an arbitrary repository exception
    private fun loadConfig() {
        scope.launch {
            logger.d("ScoringConfigViewModel.loadConfig", "Loading scoring config")
            try {
                val config = scoringConfigRepository.getConfig()
                originalConfig = config
                _uiState.value =
                    _uiState.value.copy(
                        config = config,
                        isLoading = false,
                        hasChanges = false,
                        loadFailed = false,
                    )
                logger.i("ScoringConfigViewModel.loadConfig", "Config loaded")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("ScoringConfigViewModel.loadConfig", "Failed to load scoring config", e)
                val defaults = ScoringConfig.defaults()
                // The defaults are shown for orientation only; `loadFailed` keeps them from being saved over
                // the user's stored weights.
                _uiState.value =
                    _uiState.value.copy(
                        config = defaults,
                        isLoading = false,
                        hasChanges = false,
                        loadFailed = true,
                    )
            }
        }
    }

    // ===== Factor Weight Updates =====

    /**
     * Sets the overall weight of the dimension-allocation scoring factor.
     */
    fun setDimensionWeight(value: Double) {
        updateConfig { it.copy(dimensionWeight = value) }
    }

    /**
     * Configures the weight of the impact level in the overall score.
     */
    fun setImpactWeight(value: Double) {
        updateConfig { it.copy(impactWeight = value) }
    }

    /**
     * Configures the weight of the alignment level in the overall score.
     */
    fun setAlignmentWeight(value: Double) {
        updateConfig { it.copy(alignmentWeight = value) }
    }

    /**
     * Configures the weight of the energy level in the overall score.
     */
    fun setEnergyWeight(value: Double) {
        updateConfig { it.copy(energyWeight = value) }
    }

    /**
     * Configures the weight of the control level in the overall score.
     */
    fun setControlWeight(value: Double) {
        updateConfig { it.copy(controlWeight = value) }
    }

    /**
     * Sets the weight of the time-duration factor in the overall score.
     */
    fun setDurationWeight(value: Double) {
        updateConfig { it.copy(durationWeight = value) }
    }

    // ===== Impact Level Updates =====

    /**
     * Sets the numeric weight mapped to impact [level] (e.g. "high"/"medium"/"low").
     */
    fun setImpactLevelValue(
        level: String,
        value: Double,
    ) {
        updateConfig {
            it.copy(impactLevelWeights = it.impactLevelWeights + (level to value))
        }
    }

    // ===== Alignment Level Updates =====

    /**
     * Sets the numeric weight mapped to alignment [level].
     */
    fun setAlignmentValue(
        level: String,
        value: Double,
    ) {
        updateConfig {
            it.copy(alignmentWeights = it.alignmentWeights + (level to value))
        }
    }

    // ===== Energy Level Updates =====

    /**
     * Sets the numeric weight mapped to energy [level].
     */
    fun setEnergyLevelValue(
        level: String,
        value: Double,
    ) {
        updateConfig {
            it.copy(energyLevelWeights = it.energyLevelWeights + (level to value))
        }
    }

    // ===== Control Level Updates =====

    /**
     * Sets the numeric weight mapped to control [level].
     */
    fun setControlLevelValue(
        level: String,
        value: Double,
    ) {
        updateConfig {
            it.copy(controlLevelWeights = it.controlLevelWeights + (level to value))
        }
    }

    // ===== Dimension Weight Updates =====

    /**
     * Sets the weight for a life dimension identified by [dimensionId] (canonicalized
     * via the taxonomy before storing).
     */
    fun setDimensionValue(
        dimensionId: String,
        value: Double,
    ) {
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id ?: dimensionId
        updateConfig {
            it.copy(
                dimensionWeightsById = it.dimensionWeightsById + (canonicalId to value),
            )
        }
    }

    /**
     * Sets the weight for a life [dimension] (delegates to the id-based overload
     * after canonicalizing its id).
     */
    fun setDimensionValue(
        dimension: LifeDimension,
        value: Double,
    ) {
        setDimensionValue(dimension.id, value)
    }

    // ===== Actions =====

    /**
     * Persists the current in-progress config and clears the "has changes" flag.
     *
     * Refused while a read is in flight or the last read failed. Both flags guard the same hazard: until a
     * read succeeds, [ScoringConfigUiState.config] holds defaults, and writing them would replace every
     * user-chosen weight with a value nobody picked. `isLoading` is not redundant — the first load starts
     * with `loadFailed == false`, and a retry runs with `loadFailed` still raised, so each flag covers a
     * window the other leaves open.
     */
    fun saveConfig() {
        val state = _uiState.value
        if (state.loadFailed || state.isLoading) {
            logger.w(
                "ScoringConfigViewModel.saveConfig",
                "Save refused: no successful read of the stored config",
                mapOf("loadFailed" to state.loadFailed, "isLoading" to state.isLoading),
            )
            return
        }
        scope.launch {
            logger.i("ScoringConfigViewModel.saveConfig", "Saving scoring config")
            _uiState.value = _uiState.value.copy(isSaving = true)

            scoringConfigRepository.saveConfig(_uiState.value.config)
            originalConfig = _uiState.value.config

            _uiState.value =
                _uiState.value.copy(
                    isSaving = false,
                    hasChanges = false,
                )
            logger.i("ScoringConfigViewModel.saveConfig", "Config saved successfully")
        }
    }

    /**
     * Reverts the config to the app defaults (persisting them) and clears changes.
     *
     * Refused while a read is in flight or the last read failed, for the same reason as [saveConfig]: a reset
     * writes, and before a successful read nobody — including this ViewModel — knows what is stored to be
     * overwritten. Showing defaults over a configuration that was never read must not destroy that
     * configuration either.
     */
    fun resetToDefaults() {
        val state = _uiState.value
        if (state.loadFailed || state.isLoading) {
            logger.w(
                "ScoringConfigViewModel.resetToDefaults",
                "Reset refused: no successful read of the stored config",
                mapOf("loadFailed" to state.loadFailed, "isLoading" to state.isLoading),
            )
            return
        }
        scope.launch {
            logger.i("ScoringConfigViewModel.resetToDefaults", "Resetting to defaults")
            scoringConfigRepository.resetToDefaults()
            val defaults = ScoringConfig.defaults()
            originalConfig = defaults
            _uiState.value =
                _uiState.value.copy(
                    config = defaults,
                    hasChanges = false,
                )
        }
    }

    /**
     * Discards unsaved edits, restoring the last-loaded config.
     */
    fun discardChanges() {
        logger.i("ScoringConfigViewModel.discardChanges", "Discarding config changes")
        _uiState.value =
            _uiState.value.copy(
                config = originalConfig,
                hasChanges = false,
            )
    }

    private fun updateConfig(update: (ScoringConfig) -> ScoringConfig) {
        val state = _uiState.value
        if (state.isLoading || state.loadFailed) {
            // A slider callback that outlived a failed read must not mutate the config: the values on screen
            // are defaults standing in for weights nobody could read, so an edit would make them look like
            // the user's own choices - and would arm a Save the guards would then have to refuse.
            logger.w(
                "ScoringConfigViewModel.updateConfig",
                "Edit ignored: no successful read of the stored config",
                mapOf("loadFailed" to state.loadFailed, "isLoading" to state.isLoading),
            )
            return
        }
        val newConfig = update(state.config)
        val hasChanges = newConfig != originalConfig
        logger.d("ScoringConfigViewModel.updateConfig", "Config updated", mapOf("hasChanges" to hasChanges))
        _uiState.value =
            state.copy(
                config = newConfig,
                hasChanges = hasChanges,
            )
    }

    /**
     * Cancels the work this ViewModel started. Android called this from `onCleared`; the platform calls it
     * instead - the app from the release action it registers when it retains the ViewModel, the desktop
     * from a `DisposableEffect`.
     */
    fun dispose() {
        scope.cancel()
    }
}

/**
 * The one-line entry point a platform's destination body calls to get the [ScoringConfigViewModel].
 *
 * Every collaborator comes from [LocalDataLayer], which the platform installed at startup with its own
 * implementation, so a call site on either platform contains no wiring and cannot supply a different data
 * source by accident.
 */
fun scoringConfigViewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): ScoringConfigViewModel =
    ScoringConfigViewModel(
        scoringConfigRepository = LocalDataLayer.current.scoringConfig,
        scope = scope,
    )
