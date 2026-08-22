//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.logging

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Synchronous breadcrumb recorder for cross-process debugging.
 *
 * Unlike normal logs (buffered/async), breadcrumbs use SharedPreferences.commit()
 * so the trail survives abrupt process death and immediate restarts.
 */
object CrashSafeBreadcrumbs {
    private const val PREFS_NAME = "payanam_crash_breadcrumbs"
    private const val KEY_TRAIL = "trail"
    private const val MAX_BREADCRUMB_LINES = 80
    private const val MAX_BREADCRUMB_VALUE_LENGTH = 180
    private const val LINE_SEPARATOR = "\n"

    private val logger = UnifiedLogger.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Appends a breadcrumb [stage] entry (with optional [data]) for [source] to
     * the crash-safe SharedPreferences trail, committing synchronously so it
     * survives abrupt process death.
     */
    fun record(
        context: Context,
        source: String,
        stage: String,
        data: Map<String, Any?>? = null,
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_TRAIL, "").orEmpty()
            val lines =
                existing
                    .split(LINE_SEPARATOR)
                    .filter { it.isNotBlank() }
                    .toMutableList()
            lines.add(formatEntry(source = source, stage = stage, data = data))
            val trimmed = lines.takeLast(MAX_BREADCRUMB_LINES).joinToString(LINE_SEPARATOR)
            prefs.edit().putString(KEY_TRAIL, trimmed).commit()
        }.onFailure { error ->
            logger.w(
                "CrashSafeBreadcrumbs.record",
                "Failed to persist breadcrumb",
                mapOf(
                    "source" to source,
                    "stage" to stage,
                    "error" to (error.message ?: "unknown"),
                ),
            )
        }
    }

    /**
     * Replays the stored breadcrumb trail into the main logger (as INFO
     * entries) and then clears the trail. Call early in startup recovery
     * so pre-crash steps are visible in the session log.
     */
    fun dumpToLoggerAndClear(
        context: Context,
        source: String = "CrashSafeBreadcrumbs.dumpToLoggerAndClear",
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = prefs.getString(KEY_TRAIL, "").orEmpty()
            val lines = payload.split(LINE_SEPARATOR).filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                logger.i(source, "Recovered crash-safe breadcrumb trail", mapOf("lineCount" to lines.size))
                lines.forEachIndexed { index, line ->
                    logger.i(source, "Breadcrumb", mapOf("index" to (index + 1), "entry" to line))
                }
                prefs.edit().remove(KEY_TRAIL).commit()
                logger.i(source, "Cleared recovered breadcrumb trail")
            }
        }.onFailure { error ->
            logger.w(
                source,
                "Failed to dump crash-safe breadcrumb trail",
                mapOf("error" to (error.message ?: "unknown")),
            )
        }
    }

    private fun formatEntry(
        source: String,
        stage: String,
        data: Map<String, Any?>?,
    ): String {
        val timestamp = dateFormat.format(Date())
        val details =
            data
                ?.entries
                ?.joinToString(",") { (key, value) -> "${sanitize(key)}=${sanitize(value)}" }
                ?: ""
        return if (details.isBlank()) {
            "$timestamp | $source | $stage"
        } else {
            "$timestamp | $source | $stage | $details"
        }
    }

    private fun sanitize(value: Any?): String =
        value
            ?.toString()
            ?.replace("\n", " ")
            ?.replace("\r", " ")
            ?.take(MAX_BREADCRUMB_VALUE_LENGTH)
            ?: "null"
}
