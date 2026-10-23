//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.AppSettingEntity
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * JDBC implementation of [AppSettingsRepository] over the encrypted database — a thin key/value wrapper
 * around `app_settings`, with nullable values.
 *
 * Statements are copied from `AppSettingsDao`. Two details are easy to get wrong:
 *
 *  - **`key` is a SQL keyword and the column is named `key`**, so every statement that names it quotes it
 *    with backticks. Without them the statement is a syntax error rather than a wrong result, but the
 *    backticks must be preserved verbatim from the DAO.
 *  - **`setSetting(key, null)` STORES A NULL, it does not delete.** The Android KDoc says otherwise, but the
 *    code inserts the row; the observable difference is in [getAllSettings], which then contains the key
 *    with a null value. Ported as the code behaves, not as the comment describes.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
class AppSettingsRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : AppSettingsRepository {
    private val logger = AppLogger.current

    /** Returns the value for [key], or null when unset. */
    override suspend fun getSetting(key: String): String? =
        JdbcFlows.onIo {
            logger.d("AppSettingsRepositoryJvm.getSetting", "Getting setting", mapOf("key" to key))
            readSetting(key)?.value
        }

    /** Emits the value for [key], or null. */
    override fun observeSetting(key: String): Flow<String?> {
        logger.d("AppSettingsRepositoryJvm.observeSetting", "Subscribing to setting", mapOf("key" to key))
        return JdbcFlows
            .observe(pollIntervalMs) { readSetting(key) }
            .map { entity -> entity?.value }
    }

    /** Saves [key] → [value] (upsert, stamping `updatedAt`). A null [value] is stored as SQL NULL. */
    override suspend fun setSetting(
        key: String,
        value: String?,
    ) {
        JdbcFlows.onIo {
            val entity =
                AppSettingEntity(
                    key = key,
                    value = value,
                    updatedAt = LocalDateTime.now().format(ISO_FORMATTER),
                )
            JdbcQueries.write(session, UPSERT_SETTING_SQL, arrayOf(entity.key, entity.value, entity.updatedAt))
            logger.d("AppSettingsRepositoryJvm.setSetting", "Setting saved", mapOf("key" to key))
        }
    }

    /** Deletes the setting for [key]. */
    override suspend fun deleteSetting(key: String) {
        JdbcFlows.onIo {
            JdbcQueries.write(session, DELETE_SETTING_SQL, arrayOf(key))
            logger.d("AppSettingsRepositoryJvm.deleteSetting", "Setting deleted", mapOf("key" to key))
        }
    }

    /** Emits every setting as a key → value map. */
    override fun getAllSettings(): Flow<Map<String, String?>> {
        logger.d("AppSettingsRepositoryJvm.getAllSettings", "Subscribing to all settings")
        return JdbcFlows
            .observe(pollIntervalMs) { readAllSettings() }
            .map { entities -> entities.associate { it.key to it.value } }
    }

    private fun readSetting(key: String): AppSettingEntity? =
        JdbcQueries.read(session, SETTING_BY_KEY_SQL, arrayOf(key)) { resultSet ->
            if (resultSet.next()) resultSet.readSetting() else null
        }

    private fun readAllSettings(): List<AppSettingEntity> =
        JdbcQueries.read(session, ALL_SETTINGS_SQL, emptyArray<String?>()) { resultSet ->
            buildList {
                while (resultSet.next()) add(resultSet.readSetting())
            }
        }

    private fun java.sql.ResultSet.readSetting(): AppSettingEntity =
        AppSettingEntity(
            key = getString("key"),
            value = getString("value"),
            updatedAt = getString("updatedAt"),
        )

    private companion object {
        val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /** Verbatim from `AppSettingsDao.getSetting` / `observeSetting`, backticks included. */
        const val SETTING_BY_KEY_SQL = "SELECT * FROM app_settings WHERE `key` = ?"

        /** Verbatim from `AppSettingsDao.getAllSettings`. */
        const val ALL_SETTINGS_SQL = "SELECT * FROM app_settings"

        /** `AppSettingsDao.insertSetting` is `@Insert(REPLACE)`; the primary key is the key. */
        const val UPSERT_SETTING_SQL =
            "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES (?, ?, ?)"

        /** Verbatim from `AppSettingsDao.deleteSetting`. */
        const val DELETE_SETTING_SQL = "DELETE FROM app_settings WHERE `key` = ?"
    }
}
