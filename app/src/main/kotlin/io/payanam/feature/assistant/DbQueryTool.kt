//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooGenericExceptionCaught", "SwallowedException", "MagicNumber")

package io.payanam.feature.assistant

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Result of one guarded query execution. */
data class DbQueryOutcome(
    val sql: String,
    val columns: List<String>,
    val rows: List<List<String?>>,
    val truncated: Boolean,
    val durationMs: Long,
    val error: String? = null,
) {
    /** Number of rows returned by the engine (bounded by the configured cap). */
    val rowCount: Int get() = rows.size

    /** Renders one row pipe-separated with each cell clipped — shared by model output and log preview. */
    internal fun renderRow(row: List<String?>, cellClip: Int): String =
        row.joinToString(" | ") { cell -> clip(cell ?: "NULL", cellClip) }

    /**
     * Renders the result for the model exactly like the POC (`rows_pretty`): a header of
     * column names, pipe-separated cells, and a truncation NOTE when the row cap was hit.
     * The rendered text is additionally bounded to [AssistantDefaults.MAX_TOOL_RESULT_CHARS]
     * so one round of wide results cannot balloon the outgoing request (the app-side row cap
     * and cell clip bound each round; a multi-round turn may still carry several rounds' text).
     */
    fun renderForModel(): String {
        error?.let { return "SQL_ERROR: $it" }
        if (columns.isEmpty()) return "SQL_ERROR: statement returned no columns"
        val lines = mutableListOf<String>()
        lines += columns.joinToString(" | ")
        rows.forEach { row -> lines += renderRow(row, AssistantDefaults.CELL_CLIP) }
        var text = lines.joinToString("\n")
        if (truncated) {
            text += "\n[NOTE: result truncated at ${rows.size} rows - aggregate in SQL " +
                "(GROUP BY / SUM / COUNT) to cover the full range]"
        }
        if (text.length > AssistantDefaults.MAX_TOOL_RESULT_CHARS) {
            text = text.take(AssistantDefaults.MAX_TOOL_RESULT_CHARS) +
                "\n[NOTE: result clipped at ${AssistantDefaults.MAX_TOOL_RESULT_CHARS} chars - " +
                "aggregate in SQL (GROUP BY / SUM / COUNT) to cover the full range]"
        }
        return text
    }

    companion object {
        /** Clips [value] to [max] characters with an ellipsis marker. */
        internal fun clip(value: String, max: Int): String =
            if (value.length <= max) value else value.take(max) + "…"
    }
}

/**
 * Executes guarded, SELECT-only SQL against the app's live (unlocked) database and logs
 * every executed statement with its receipt: SQL length, columns, row count, truncation,
 * and duration — the trace asked for.
 *
 * Raw SQL text and row contents are deliberately omitted from info-level logs to prevent
 * personal data leakage. The full SQL and rows are still available to the model via
 * [DbQueryOutcome.renderForModel].
 *
 * Read path is the existing Room session ([DatabaseSessionManager.requireDatabase]) —
 * no second handle, no file copy, no adb.
 */
@Singleton
class DbQueryTool
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Runs [sql] (already guard-sanitized) and returns at most [maxRows] rows.
         * Throws [IllegalStateException] when the DB session is closed so the caller can
         * surface the "data locked" state instead of sending a SQL error to the model.
         */
        suspend fun execute(sql: String, maxRows: Int): DbQueryOutcome =
            withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                val database = sessionManager.requireDatabase()
                try {
                    val cursor = database.openHelper.writableDatabase.query(sql)
                    cursor.use { rows ->
                        val columns = rows.columnNames.toList()
                        val collected = mutableListOf<List<String?>>()
                        var truncated = false
                        while (rows.moveToNext()) {
                            if (collected.size >= maxRows) {
                                truncated = true
                                break
                            }
                            collected += columns.indices.map { index ->
                                if (rows.isNull(index)) {
                                    null
                                } else {
                                    rows.getString(index)?.let { cell -> DbQueryOutcome.clip(cell, AssistantDefaults.CELL_CLIP) }
                                }
                            }
                        }
                        val outcome = DbQueryOutcome(
                            sql = sql,
                            columns = columns,
                            rows = collected,
                            truncated = truncated,
                            durationMs = System.currentTimeMillis() - started,
                        )
                        logOutcome(outcome)
                        return@withContext outcome
                    }
                } catch (closed: IllegalStateException) {
                    throw closed
                } catch (error: Exception) {
                    logger.e("AssistantDbQuery.execute", "Query execution failed", error)
                    val outcome = DbQueryOutcome(
                        sql = sql,
                        columns = emptyList(),
                        rows = emptyList(),
                        truncated = false,
                        durationMs = System.currentTimeMillis() - started,
                        error = "${error.javaClass.simpleName}: ${error.message}",
                    )
                    logOutcome(outcome)
                    return@withContext outcome
                }
            }

        private fun logOutcome(outcome: DbQueryOutcome) {
            logger.i(
                "AssistantDbQuery.logOutcome",
                if (outcome.error == null) "Query executed" else "Query failed",
                mapOf(
                    "sql_chars" to outcome.sql.length,
                    "columns" to outcome.columns.joinToString(","),
                    "row_count" to outcome.rowCount,
                    "truncated" to outcome.truncated,
                    "duration_ms" to outcome.durationMs,
                    "error" to (outcome.error ?: "-"),
                ),
            )
        }
    }
