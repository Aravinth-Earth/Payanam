//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.ScoringConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for scoring configuration.
 */
interface ScoringConfigRepository {
    
    /**
     * Get the current scoring configuration.
     * Returns defaults if none is saved.
     */
    suspend fun getConfig(): ScoringConfig
    
    /**
     * Observe scoring configuration changes.
     * Emits defaults if none is saved.
     */
    fun observeConfig(): Flow<ScoringConfig>
    
    /**
     * Save the scoring configuration.
     */
    suspend fun saveConfig(config: ScoringConfig)
    
    /**
     * Reset configuration to defaults.
     */
    suspend fun resetToDefaults()
}
