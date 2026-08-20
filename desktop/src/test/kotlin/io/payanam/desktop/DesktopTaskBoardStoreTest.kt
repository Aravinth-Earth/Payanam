//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.tasks.DesktopHabitSortOption
import io.payanam.shared.tasks.DesktopTaskBoardContracts
import io.payanam.shared.tasks.DesktopTaskFilter
import io.payanam.shared.tasks.DesktopTaskSortOption
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * DesktopTaskBoardStoreTest.
 */
class DesktopTaskBoardStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `load snapshot falls back to desktop task board defaults when file is missing`() {
        val store = DesktopTaskBoardStore(preferencesDirectory = temporaryFolder.newFolder("task-board-default").toPath())

        val snapshot = store.loadSnapshot()

        assertThat(snapshot).isEqualTo(DesktopTaskBoardContracts.snapshot())
    }

    @Test
    fun `save snapshot persists desktop task board preferences for reload`() {
        val preferencesDirectory = temporaryFolder.newFolder("task-board-persisted").toPath()
        val persistenceDatabase =
            DesktopPersistenceDatabase(
                databaseDirectory = preferencesDirectory,
                preferencesDirectory = preferencesDirectory,
            )
        val store = DesktopTaskBoardStore(persistenceDatabase = persistenceDatabase)
        val savedSnapshot =
            DesktopTaskBoardContracts.snapshot(
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedTaskFilter = DesktopTaskFilter.OVERDUE,
                        selectedTaskSort = DesktopTaskSortOption.TITLE_ASC,
                        selectedHabitSort = DesktopHabitSortOption.BY_DUE_TIME,
                        showArchivedHabits = true,
                        showCompletedHabits = false,
                    ),
            )

        store.saveSnapshot(savedSnapshot)

        val reloadedSnapshot = store.loadSnapshot()
        val entryPayload = persistenceDatabase.readEntry(DesktopTaskBoardStore.STATE_ENTRY_KEY).orEmpty()
        assertThat(reloadedSnapshot).isEqualTo(savedSnapshot)
        assertThat(entryPayload).contains("taskFilter=overdue")
        assertThat(entryPayload).contains("taskSort=title_asc")
        assertThat(entryPayload).contains("habitSort=by_due_time")
        assertThat(entryPayload).contains("showArchivedHabits=true")
    }
}
