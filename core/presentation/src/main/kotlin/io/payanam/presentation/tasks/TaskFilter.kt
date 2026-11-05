//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

/**
 * Filters for the one-time task list; persisted by [key] in app settings and
 * restored with [fromKey] (defaulting to TODAY).
 *
 * SHARED because the preference that stores it is shared: the desktop's settings screen and Android's
 * write the same key into the same encrypted database, so the enum that names the key cannot live on
 * one platform.
 */
enum class TaskFilter(
    val key: String,
) {
    ALL("all"),
    ACTIVE("active"),
    TODAY("today"),
    OVERDUE("overdue"),
    FUTURE("future"),
    COMPLETED("completed"),
    ARCHIVED("archived"),
    NOT_ACTIVE("not_active"),
    ;

    companion object {
        /**
         * Resolves a stored filter key back to a [TaskFilter], falling back to
         * [TODAY] for unknown/null keys.
         */
        fun fromKey(key: String?): TaskFilter = entries.find { it.key == key } ?: TODAY
    }
}

/** The stored key, used where a filter has to be named in a label or a trace. */
val TaskFilter.displayName: String
    get() = key
