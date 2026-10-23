//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.port.DatabaseSchemaReader
import io.payanam.domain.port.DatabaseSchemaResult
import io.payanam.domain.port.DatabaseTableSchema
import io.payanam.domain.port.ReadOnlyDatabaseQuery
import io.payanam.domain.port.ReadOnlyQueryOutcome
import io.payanam.domain.port.ReadOnlyQueryRules
import io.payanam.domain.port.ReadOnlyRefusal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's answer to the assistant's two read-only ports, over Room's own handle.
 *
 * ## WHERE THE READ-ONLY GUARANTEE COMES FROM HERE
 *
 * From [androidx.sqlite.db.SupportSQLiteDatabase], obtained through `openHelper.readableDatabase` and
 * nothing else. This class holds no writable handle, exposes no method that returns one, and every
 * statement it runs goes through `query(...)` — a call whose contract is to return a cursor, so a
 * statement that writes cannot be expressed through it.
 *
 * That is the strongest guarantee Android's SQLite can give in-process, and it is a real difference from
 * the desktop, which enforces read-only in the ENGINE through `PRAGMA query_only` and re-reads it back
 * ([io.payanam.database.jvm.ReadOnlyDatabaseQueryJvm]). The consequence is stated rather than papered
 * over: a caller's SQL cannot write here because the only handle Room exposes to this class is the
 * read-only one, while on the desktop the write is refused by SQLite itself. Both are structural; neither
 * depends on inspecting the SQL text, which is why `ReadOnlySqlGuard` remains a courtesy and not the gate.
 *
 * THE SESSION CLOSE IS NOT A REFUSAL TO HIDE: [DatabaseSessionManager.requireDatabase] throws
 * [IllegalStateException] when the database is locked, and that throw is deliberately allowed to escape
 * rather than being converted into a [ReadOnlyQueryOutcome.Refused]. The screen renders "data locked" for
 * it, and a model told `DATABASE_NOT_OPEN` would answer a question about a database nobody can open.
 */
@Singleton
class AndroidReadOnlyAssistantReads
@Inject
constructor(
    private val sessionManager: DatabaseSessionManager,
) : ReadOnlyDatabaseQuery,
    DatabaseSchemaReader {
    private val logger = UnifiedLogger.getInstance()

    // Broad on purpose: a read that fails for ANY reason - bad SQL, a table a model guessed, a bind
    // type mismatch - must reach the caller as a structured refusal. Letting one escape would take the
    // whole assistant turn down instead of answering "I could not read that". IllegalStateException, the
    // closed session, is rethrown just above and is deliberately NOT covered.
    @Suppress("TooGenericExceptionCaught")
    override fun query(
        sql: String,
        maxRows: Int,
    ): ReadOnlyQueryOutcome {
        if (maxRows <= 0) {
            throw IllegalArgumentException("maxRows must be positive, was $maxRows")
        }
        val database = sessionManager.requireDatabase()
        return try {
            database.openHelper.readableDatabase.query(sql).use { rows ->
                val columns = rows.columnNames.toList()
                val collected = mutableListOf<List<String?>>()
                var truncated = false
                while (rows.moveToNext()) {
                    if (collected.size >= maxRows) {
                        truncated = true
                        break
                    }
                    collected += columns.indices.map { index ->
                        if (rows.isNull(index)) null else rows.getString(index)
                    }
                }
                ReadOnlyQueryOutcome.Rows(columns, collected, truncated)
            }
        } catch (closed: IllegalStateException) {
            throw closed
        } catch (error: Exception) {
            logger.e("AssistantDbQuery.query", "Read-only query failed", error)
            ReadOnlyQueryOutcome.Refused(
                ReadOnlyRefusal.EXECUTION_FAILED,
                "${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    // Same reason as query() above: the schema read is the assistant's other window into the database,
    // and any failure in it has to become a refusal rather than an exception out of a chat turn.
    @Suppress("TooGenericExceptionCaught")
    override fun readSchema(): DatabaseSchemaResult {
        val database = sessionManager.requireDatabase()
        return try {
            database.openHelper.readableDatabase.query(ReadOnlyQueryRules.SCHEMA_QUERY).use { rows ->
                val tables = mutableListOf<DatabaseTableSchema>()
                while (rows.moveToNext()) {
                    val name = rows.getString(0) ?: continue
                    val ddl = rows.getString(1) ?: continue
                    tables += DatabaseTableSchema(name, ddl)
                }
                DatabaseSchemaResult.Tables(tables)
            }
        } catch (closed: IllegalStateException) {
            throw closed
        } catch (error: Exception) {
            logger.e("AssistantDbQuery.readSchema", "Schema read failed", error)
            DatabaseSchemaResult.Refused(
                ReadOnlyRefusal.EXECUTION_FAILED,
                "${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }
}
