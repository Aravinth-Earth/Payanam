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
 * Room-backed implementation of [AppSettingsRepository]. Thin key/value wrapper
 * around the `app_settings` table (nullable values, timestamped updates).
 */
class AppSettingsRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : AppSettingsRepository {
        private val logger = UnifiedLogger.getInstance()
        private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /**
         * Returns the value for [key], or null when unset.
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
         * Emits the value for [key] (null when unset), as a [Flow].
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
         * Saves [key] → [value] (upserts, stamps `updatedAt`). Passing null for
         * [value] deletes the key (Room's REPLACE on the same primary key replaces
         * the null value; but callers typically use [deleteSetting] to remove).
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
         * Deletes the setting for [key].
         */
        override suspend fun deleteSetting(key: String) {
            sessionManager.requireDatabase().appSettingsDao().deleteSetting(key)
            logger.d("AppSettingsRepositoryImpl.deleteSetting", "Setting deleted", mapOf("key" to key))
        }

        /**
         * Emits every setting as a key → value map, as a [Flow].
         */
        override fun getAllSettings(): Flow<Map<String, String?>> {
            logger.d("AppSettingsRepositoryImpl.getAllSettings", "Subscribing to all settings")
            return sessionManager.requireDatabase().appSettingsDao().getAllSettings().map { entities ->
                entities.associate { it.key to it.value }
            }
        }
    }
