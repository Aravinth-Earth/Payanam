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
    val config: ScoringConfig = ScoringConfig.defaults(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val hasChanges: Boolean = false,
)

/**
 * ViewModel for the Scoring Configuration screen.
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

    fun setDimensionWeight(value: Double) {
        updateConfig { it.copy(dimensionWeight = value) }
    }

    fun setImpactWeight(value: Double) {
        updateConfig { it.copy(impactWeight = value) }
    }

    fun setAlignmentWeight(value: Double) {
        updateConfig { it.copy(alignmentWeight = value) }
    }

    fun setEnergyWeight(value: Double) {
        updateConfig { it.copy(energyWeight = value) }
    }

    fun setControlWeight(value: Double) {
        updateConfig { it.copy(controlWeight = value) }
    }

    fun setDurationWeight(value: Double) {
        updateConfig { it.copy(durationWeight = value) }
    }

    // ===== Impact Level Updates =====

    fun setImpactLevelValue(level: String, value: Double) {
        updateConfig {
            it.copy(impactLevelWeights = it.impactLevelWeights + (level to value))
        }
    }

    // ===== Alignment Level Updates =====

    fun setAlignmentValue(level: String, value: Double) {
        updateConfig {
            it.copy(alignmentWeights = it.alignmentWeights + (level to value))
        }
    }

    // ===== Energy Level Updates =====

    fun setEnergyLevelValue(level: String, value: Double) {
        updateConfig {
            it.copy(energyLevelWeights = it.energyLevelWeights + (level to value))
        }
    }

    // ===== Control Level Updates =====

    fun setControlLevelValue(level: String, value: Double) {
        updateConfig {
            it.copy(controlLevelWeights = it.controlLevelWeights + (level to value))
        }
    }

    // ===== Dimension Weight Updates =====

    fun setDimensionValue(dimensionId: String, value: Double) {
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id ?: dimensionId
        updateConfig {
            it.copy(
                dimensionWeightsById = it.dimensionWeightsById + (canonicalId to value),
            )
        }
    }

    fun setDimensionValue(dimension: LifeDimension, value: Double) {
        setDimensionValue(dimension.id, value)
    }

    // ===== Actions =====

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
