//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.repository.ScoringConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for scoring configuration screen.
 */
data class ScoringConfigUiState(
    val config: ScoringConfig = ScoringConfig.defaults(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val hasChanges: Boolean = false,
)

/**
 * ViewModel for the scoring configuration screen.
 */
@HiltViewModel
class ScoringConfigViewModel @Inject constructor(
    private val scoringConfigRepository: ScoringConfigRepository,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()

    private val _uiState = MutableStateFlow(ScoringConfigUiState())
    val uiState: StateFlow<ScoringConfigUiState> = _uiState.asStateFlow()

    private var originalConfig: ScoringConfig = ScoringConfig.defaults()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            logger.d("ScoringConfigViewModel.loadConfig", "Loading scoring config")
            val config = scoringConfigRepository.getConfig()
            originalConfig = config
            _uiState.value = _uiState.value.copy(
                config = config,
                isLoading = false,
                hasChanges = false,
            )
            logger.i("ScoringConfigViewModel.loadConfig", "Config loaded")
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
    fun setImpactLevelValue(level: String, value: Double) {
        updateConfig {
            it.copy(impactLevelWeights = it.impactLevelWeights + (level to value))
        }
    }

    // ===== Alignment Level Updates =====
    /**
     * Sets the numeric weight mapped to alignment [level].
     */
    fun setAlignmentValue(level: String, value: Double) {
        updateConfig {
            it.copy(alignmentWeights = it.alignmentWeights + (level to value))
        }
    }

    // ===== Energy Level Updates =====
    /**
     * Sets the numeric weight mapped to energy [level].
     */
    fun setEnergyLevelValue(level: String, value: Double) {
        updateConfig {
            it.copy(energyLevelWeights = it.energyLevelWeights + (level to value))
        }
    }

    // ===== Control Level Updates =====
    /**
     * Sets the numeric weight mapped to control [level].
     */
    fun setControlLevelValue(level: String, value: Double) {
        updateConfig {
            it.copy(controlLevelWeights = it.controlLevelWeights + (level to value))
        }
    }

    // ===== Dimension Weight Updates =====
    /**
     * Sets the weight for a life dimension identified by [dimensionId] (canonicalized
     * via the taxonomy before storing).
     */
    fun setDimensionValue(dimensionId: String, value: Double) {
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
    fun setDimensionValue(dimension: LifeDimension, value: Double) {
        setDimensionValue(dimension.id, value)
    }

    // ===== Actions =====
    /**
     * Persists the current in-progress config and clears the "has changes" flag.
     */
    fun saveConfig() {
        viewModelScope.launch {
            logger.i("ScoringConfigViewModel.saveConfig", "Saving scoring config")
            _uiState.value = _uiState.value.copy(isSaving = true)

            scoringConfigRepository.saveConfig(_uiState.value.config)
            originalConfig = _uiState.value.config

            _uiState.value = _uiState.value.copy(
                isSaving = false,
                hasChanges = false,
            )
            logger.i("ScoringConfigViewModel.saveConfig", "Config saved successfully")
        }
    }
    /**
     * Reverts the config to the app defaults (persisting them) and clears changes.
     */
    fun resetToDefaults() {
        viewModelScope.launch {
            logger.i("ScoringConfigViewModel.resetToDefaults", "Resetting to defaults")
            scoringConfigRepository.resetToDefaults()
            val defaults = ScoringConfig.defaults()
            originalConfig = defaults
            _uiState.value = _uiState.value.copy(
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
        _uiState.value = _uiState.value.copy(
            config = originalConfig,
            hasChanges = false,
        )
    }

    private fun updateConfig(update: (ScoringConfig) -> ScoringConfig) {
        val newConfig = update(_uiState.value.config)
        val hasChanges = newConfig != originalConfig
        logger.d("ScoringConfigViewModel.updateConfig", "Config updated", mapOf("hasChanges" to hasChanges))
        _uiState.value = _uiState.value.copy(
            config = newConfig,
            hasChanges = hasChanges,
        )
    }
}
