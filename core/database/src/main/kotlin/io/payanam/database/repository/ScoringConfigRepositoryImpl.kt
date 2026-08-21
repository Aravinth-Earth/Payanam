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
 * Room implementation of [ScoringConfigRepository]. The scoring config is a
 * singleton row (id = 1) holding tunable weights/thresholds for the scoring
 * engine.
 */
@Singleton
class ScoringConfigRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : ScoringConfigRepository {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Returns the scoring config, or `ScoringConfig.defaults()` when none has
         * been saved yet.
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
         * Emits the scoring config, or `ScoringConfig.defaults()` when unsaved, as a
         * [Flow].
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
         * Persists the scoring [config] (upserts the singleton row).
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
         * Deletes the scoring config row, so the next read returns
         * `ScoringConfig.defaults()`.
         */
        override suspend fun resetToDefaults() {
            logger.i("ScoringConfigRepository.resetToDefaults", "Resetting to default config")
            sessionManager.requireDatabase().scoringConfigDao().deleteConfig()
        }
    }
