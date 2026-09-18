//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.assistant

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the SQLite schema text injected into the assistant's system prompt.
 *
 * The schema is read from the live (unlocked) database via `sqlite_master` on every call —
 * the query costs a few milliseconds, and building fresh means the prompt can never serve a
 * schema stale after an import/media swap or any table change within the DB session.
 */
@Singleton
class DbSchemaProvider
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) {
        private val logger = UnifiedLogger.getInstance()

        /** Returns the current schema text (read fresh from the live database). */
        suspend fun schemaText(): String = withContext(Dispatchers.IO) { build() }

        private fun build(): String {
            val database = sessionManager.requireDatabase()
            val cursor = database.openHelper.writableDatabase.query(SCHEMA_QUERY)
            val blocks = mutableListOf<String>()
            cursor.use { rows ->
                while (rows.moveToNext()) {
                    val name = rows.getString(0) ?: continue
                    val ddl = rows.getString(1) ?: continue
                    blocks += "-- $name\n$ddl"
                }
            }
            val text = blocks.joinToString("\n\n")
            logger.i(
                "DbSchemaProvider.build",
                "Schema text built for assistant prompt",
                mapOf("tables" to blocks.size, "chars" to text.length),
            )
            return text
        }

        private companion object {
            private const val SCHEMA_QUERY =
                "SELECT name, sql FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
                    "AND name != 'app_settings' ORDER BY name"
        }
    }
