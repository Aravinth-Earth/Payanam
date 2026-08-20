//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.tasks.DesktopHabitSortOption
import io.payanam.shared.tasks.DesktopTaskBoardContracts
import io.payanam.shared.tasks.DesktopTaskBoardSnapshot
import io.payanam.shared.tasks.DesktopTaskFilter
import io.payanam.shared.tasks.DesktopTaskSortOption
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import java.util.Properties

internal class DesktopTaskBoardStore(
    preferencesDirectory: Path = DesktopAppPaths.resolvePreferencesDirectory(),
    private val persistenceDatabase: DesktopPersistenceDatabase =
        DesktopPersistenceDatabase(
            databaseDirectory = preferencesDirectory,
            preferencesDirectory = preferencesDirectory,
        ),
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    /**
     * Load snapshot.
     */
    fun loadSnapshot(): DesktopTaskBoardSnapshot {
        val storedPayload = persistenceDatabase.readEntry(STATE_ENTRY_KEY)
        if (storedPayload.isNullOrBlank()) {
            val defaultSnapshot = DesktopTaskBoardContracts.snapshot()
            logEvent(
                "DesktopTaskBoardStore.loadSnapshot",
                "Using default desktop task board snapshot",
                emptyMap(),
            )
            return defaultSnapshot
        }

        val properties = Properties()
        StringReader(storedPayload).use(properties::load)
        val snapshot =
            DesktopTaskBoardContracts.snapshot(
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        schemaVersion =
                            properties.getProperty(KEY_SCHEMA_VERSION)?.toIntOrNull()
                                ?: DesktopTaskBoardContracts.SCHEMA_VERSION,
                        selectedTaskFilter = DesktopTaskFilter.fromStorageKey(properties.getProperty(KEY_TASK_FILTER)),
                        selectedTaskSort = DesktopTaskSortOption.fromStorageKey(properties.getProperty(KEY_TASK_SORT)),
                        selectedHabitSort = DesktopHabitSortOption.fromStorageKey(properties.getProperty(KEY_HABIT_SORT)),
                        showArchivedHabits =
                            properties.getProperty(KEY_SHOW_ARCHIVED_HABITS)?.toBooleanStrictOrNull() ?: false,
                        showCompletedHabits =
                            properties.getProperty(KEY_SHOW_COMPLETED_HABITS)?.toBooleanStrictOrNull() ?: true,
                    ),
            )
        logEvent(
            "DesktopTaskBoardStore.loadSnapshot",
            "Loaded desktop task board snapshot",
            mapOf(
                "taskFilter" to snapshot.preferences.selectedTaskFilter.storageKey,
                "taskSort" to snapshot.preferences.selectedTaskSort.storageKey,
                "habitSort" to snapshot.preferences.selectedHabitSort.storageKey,
            ),
        )
        return snapshot
    }

    /**
     * Save snapshot.
     */
    fun saveSnapshot(snapshot: DesktopTaskBoardSnapshot) {
        val properties =
            Properties().apply {
                setProperty(KEY_SCHEMA_VERSION, snapshot.preferences.schemaVersion.toString())
                setProperty(KEY_TASK_FILTER, snapshot.preferences.selectedTaskFilter.storageKey)
                setProperty(KEY_TASK_SORT, snapshot.preferences.selectedTaskSort.storageKey)
                setProperty(KEY_HABIT_SORT, snapshot.preferences.selectedHabitSort.storageKey)
                setProperty(KEY_SHOW_ARCHIVED_HABITS, snapshot.preferences.showArchivedHabits.toString())
                setProperty(KEY_SHOW_COMPLETED_HABITS, snapshot.preferences.showCompletedHabits.toString())
            }
        val payload =
            StringWriter().use { writer ->
                properties.store(writer, "Payanam Desktop Task Board")
                writer.toString()
            }
        persistenceDatabase.writeEntry(STATE_ENTRY_KEY, payload)
        logEvent(
            "DesktopTaskBoardStore.saveSnapshot",
            "Saved desktop task board snapshot",
            mapOf(
                "taskFilter" to snapshot.preferences.selectedTaskFilter.storageKey,
                "taskSort" to snapshot.preferences.selectedTaskSort.storageKey,
                "habitSort" to snapshot.preferences.selectedHabitSort.storageKey,
            ),
        )
    }

    /**
     * Get board file path.
     */
    fun getBoardFilePath(): Path = persistenceDatabase.getDatabaseFilePath()

    internal companion object {
        internal const val STATE_ENTRY_KEY = "desktop/task_board"
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_TASK_FILTER = "taskFilter"
        private const val KEY_TASK_SORT = "taskSort"
        private const val KEY_HABIT_SORT = "habitSort"
        private const val KEY_SHOW_ARCHIVED_HABITS = "showArchivedHabits"
        private const val KEY_SHOW_COMPLETED_HABITS = "showCompletedHabits"
    }
}
