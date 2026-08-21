//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.AppSettingEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * Provides the app settings repository impl.
 */
class AppSettingsRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : AppSettingsRepository {
        private val logger = UnifiedLogger.getInstance()
        private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /**
         * Returns the get setting.
         */
        override suspend fun getSetting(key: String): String? {
            logger.d("AppSettingsRepositoryImpl.getSetting", "Getting setting", mapOf("key" to key))
            return sessionManager
                .requireDatabase()
                .appSettingsDao()
                .getSetting(key)
                ?.value
        }

        /**
         * Registers the observe setting.
         */
        override fun observeSetting(key: String): Flow<String?> {
            logger.d("AppSettingsRepositoryImpl.observeSetting", "Subscribing to setting", mapOf("key" to key))
            return sessionManager
                .requireDatabase()
                .appSettingsDao()
                .observeSetting(key)
                .map { it?.value }
        }

        /**
         * Updates the set setting.
         */
        override suspend fun setSetting(
            key: String,
            value: String?,
        ) {
            val now = LocalDateTime.now()
            val entity =
                AppSettingEntity(
                    key = key,
                    value = value,
                    updatedAt = now.format(dateTimeFormatter),
                )
            sessionManager.requireDatabase().appSettingsDao().insertSetting(entity)
            logger.d("AppSettingsRepositoryImpl.setSetting", "Setting saved", mapOf("key" to key))
        }

        /**
         * Removes the delete setting.
         */
        override suspend fun deleteSetting(key: String) {
            sessionManager.requireDatabase().appSettingsDao().deleteSetting(key)
            logger.d("AppSettingsRepositoryImpl.deleteSetting", "Setting deleted", mapOf("key" to key))
        }

        /**
         * Returns the get all settings.
         */
        override fun getAllSettings(): Flow<Map<String, String?>> {
            logger.d("AppSettingsRepositoryImpl.getAllSettings", "Subscribing to all settings")
            return sessionManager.requireDatabase().appSettingsDao().getAllSettings().map { entities ->
                entities.associate { it.key to it.value }
            }
        }
    }
