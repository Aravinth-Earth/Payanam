//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.mapper.ScoringConfigMapper
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.repository.ScoringConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room implementation of scoringConfigRepository.
 */
@Singleton
/**
 * Provides the scoring config repository impl.
 */
class ScoringConfigRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : ScoringConfigRepository {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Returns the get config.
         */
        override suspend fun getConfig(): ScoringConfig {
            logger.d("ScoringConfigRepository.getConfig", "Fetching scoring config")
            val entity = sessionManager.requireDatabase().scoringConfigDao().getConfig()
            return if (entity != null) {
                logger.i("ScoringConfigRepository.getConfig", "Loaded saved config")
                ScoringConfigMapper.toDomain(entity)
            } else {
                logger.i("ScoringConfigRepository.getConfig", "No saved config, using defaults")
                ScoringConfig.defaults()
            }
        }

        /**
         * Registers the observe config.
         */
        override fun observeConfig(): Flow<ScoringConfig> {
            logger.d("ScoringConfigRepository.observeConfig", "Subscribing to scoring config")
            return sessionManager.requireDatabase().scoringConfigDao().observeConfig().map { entity ->
                if (entity != null) {
                    ScoringConfigMapper.toDomain(entity)
                } else {
                    ScoringConfig.defaults()
                }
            }
        }

        /**
         * Writes the save config.
         */
        override suspend fun saveConfig(config: ScoringConfig) {
            logger.i(
                "ScoringConfigRepository.saveConfig",
                "Saving scoring config",
                mapOf(
                    "dimensionWeight" to config.dimensionWeight,
                    "impactWeight" to config.impactWeight,
                ),
            )
            val entity = ScoringConfigMapper.toEntity(config)
            sessionManager.requireDatabase().scoringConfigDao().upsertConfig(entity)
        }

        /**
         * Removes the reset to defaults.
         */
        override suspend fun resetToDefaults() {
            logger.i("ScoringConfigRepository.resetToDefaults", "Resetting to default config")
            sessionManager.requireDatabase().scoringConfigDao().deleteConfig()
        }
    }
