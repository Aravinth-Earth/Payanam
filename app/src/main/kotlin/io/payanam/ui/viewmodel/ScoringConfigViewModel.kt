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
 * UI state for Scoring Configuration screen.
 */
data class ScoringConfigUiState(
    /** Config. */
    val config: ScoringConfig = ScoringConfig.defaults(),
    /** Is loading. */
    val isLoading: Boolean = true,
    /** Is saving. */
    val isSaving: Boolean = false,
    /** Has changes. */
    val hasChanges: Boolean = false,
)

/**
 * ViewModel for the Scoring Configuration screen.
 */
@HiltViewModel
/**
 * ScoringConfigViewModel.
 */
class ScoringConfigViewModel @Inject constructor(
    private val scoringConfigRepository: ScoringConfigRepository,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()

    private val _uiState = MutableStateFlow(ScoringConfigUiState())
    /** Ui state. */
    val uiState: StateFlow<ScoringConfigUiState> = _uiState.asStateFlow()

    private var originalConfig: ScoringConfig = ScoringConfig.defaults()

    init {
        /** Load config. */
        loadConfig()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            logger.d("ScoringConfigViewModel.loadConfig", "Loading scoring config")
            /** Config. */
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
     * Set dimension weight.
     */
    fun setDimensionWeight(value: Double) {
        updateConfig { it.copy(dimensionWeight = value) }
    }

    /**
     * Set impact weight.
     */
    fun setImpactWeight(value: Double) {
        updateConfig { it.copy(impactWeight = value) }
    }

    /**
     * Set alignment weight.
     */
    fun setAlignmentWeight(value: Double) {
        updateConfig { it.copy(alignmentWeight = value) }
    }

    /**
     * Set energy weight.
     */
    fun setEnergyWeight(value: Double) {
        updateConfig { it.copy(energyWeight = value) }
    }

    /**
     * Set control weight.
     */
    fun setControlWeight(value: Double) {
        updateConfig { it.copy(controlWeight = value) }
    }

    /**
     * Set duration weight.
     */
    fun setDurationWeight(value: Double) {
        updateConfig { it.copy(durationWeight = value) }
    }

    // ===== Impact Level Updates =====

    /**
     * Set impact level value.
     */
    fun setImpactLevelValue(level: String, value: Double) {
        updateConfig {
            it.copy(impactLevelWeights = it.impactLevelWeights + (level to value))
        }
    }

    // ===== Alignment Level Updates =====

    /**
     * Set alignment value.
     */
    fun setAlignmentValue(level: String, value: Double) {
        updateConfig {
            it.copy(alignmentWeights = it.alignmentWeights + (level to value))
        }
    }

    // ===== Energy Level Updates =====

    /**
     * Set energy level value.
     */
    fun setEnergyLevelValue(level: String, value: Double) {
        updateConfig {
            it.copy(energyLevelWeights = it.energyLevelWeights + (level to value))
        }
    }

    // ===== Control Level Updates =====

    /**
     * Set control level value.
     */
    fun setControlLevelValue(level: String, value: Double) {
        updateConfig {
            it.copy(controlLevelWeights = it.controlLevelWeights + (level to value))
        }
    }

    // ===== Dimension Weight Updates =====

    /**
     * Set dimension value.
     */
    fun setDimensionValue(dimensionId: String, value: Double) {
        /** Canonical id. */
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id ?: dimensionId
        updateConfig {
            it.copy(
                dimensionWeightsById = it.dimensionWeightsById + (canonicalId to value),
            )
        }
    }

    /**
     * Set dimension value.
     */
    fun setDimensionValue(dimension: LifeDimension, value: Double) {
        /** Set dimension value. */
        setDimensionValue(dimension.id, value)
    }

    // ===== Actions =====

    /**
     * Save config.
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
     * Reset to defaults.
     */
    fun resetToDefaults() {
        viewModelScope.launch {
            logger.i("ScoringConfigViewModel.resetToDefaults", "Resetting to defaults")
            scoringConfigRepository.resetToDefaults()

            /** Defaults. */
            val defaults = ScoringConfig.defaults()
            originalConfig = defaults
            _uiState.value = _uiState.value.copy(
                config = defaults,
                hasChanges = false,
            )
        }
    }

    /**
     * Discard changes.
     */
    fun discardChanges() {
        logger.i("ScoringConfigViewModel.discardChanges", "Discarding config changes")
        _uiState.value = _uiState.value.copy(
            config = originalConfig,
            hasChanges = false,
        )
    }

    private fun updateConfig(update: (ScoringConfig) -> ScoringConfig) {
        /** New config. */
        val newConfig = update(_uiState.value.config)
        /** Has changes. */
        val hasChanges = newConfig != originalConfig
        logger.d("ScoringConfigViewModel.updateConfig", "Config updated", mapOf("hasChanges" to hasChanges))
        _uiState.value = _uiState.value.copy(
            config = newConfig,
            hasChanges = hasChanges,
        )
    }
}
