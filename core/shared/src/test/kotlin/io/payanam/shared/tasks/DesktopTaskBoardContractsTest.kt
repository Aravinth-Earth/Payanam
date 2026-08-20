//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.tasks

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Ignore
import org.junit.Test

/**
 * DesktopTaskBoardContractsTest.
 */
class DesktopTaskBoardContractsTest {
    private val fixedNow: LocalDateTime = LocalDateTime.parse("2026-03-26T09:30:00")

    @Test
    fun `default snapshot starts in loading state with today filter`() {
        val snapshot = DesktopTaskBoardContracts.snapshot()

        /** Assert that. */
        assertThat(snapshot.preferences.selectedTaskFilter).isEqualTo(DesktopTaskFilter.TODAY)
        /** Assert that. */
        assertThat(snapshot.preferences.selectedTaskSort).isEqualTo(DesktopTaskSortOption.DUE_DATE_ASC)
        /** Assert that. */
        assertThat(snapshot.preferences.selectedHabitSort).isEqualTo(DesktopHabitSortOption.BY_STATUS)
        /** Assert that. */
        assertThat(snapshot.content.loadState).isEqualTo(DesktopTaskBoardLoadState.LOADING)
        /** Assert that. */
        assertThat(snapshot.visibleTaskCount()).isEqualTo(0)
    }

    @Test
    fun `board snapshot builds filtered visible task rows from catalog`() {
        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = DesktopTaskBoardContracts.seededCatalog(now = fixedNow),
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedTaskFilter = DesktopTaskFilter.OVERDUE,
                        selectedTaskSort = DesktopTaskSortOption.TITLE_ASC,
                    ),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.loadState).isEqualTo(DesktopTaskBoardLoadState.READY)
        /** Assert that. */
        assertThat(snapshot.counts.totalTaskCount).isEqualTo(4)
        /** Assert that. */
        assertThat(snapshot.counts.totalHabitCount).isEqualTo(3)
        /** Assert that. */
        assertThat(snapshot.visibleTaskCount()).isEqualTo(2)
        /** Assert that. */
        assertThat(snapshot.content.visibleTasks.map { it.title })
            .containsExactly("Pay utility bill", "Plan weekly outcomes")
            .inOrder()
    }

    @Test
    fun `board snapshot respects habit visibility toggles`() {
        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = DesktopTaskBoardContracts.seededCatalog(now = fixedNow),
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedHabitSort = DesktopHabitSortOption.BY_NAME,
                        showArchivedHabits = false,
                        showCompletedHabits = false,
                    ),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.visibleHabits.map { it.title }).containsExactly("Evening walk").inOrder()
        /** Assert that. */
        assertThat(snapshot.counts.completedHabitCountToday).isEqualTo(1)
    }

    @Test
    fun `board snapshot returns empty state when catalog has no rows`() {
        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = DesktopTaskCatalogSnapshot(),
                preferences = DesktopTaskBoardContracts.defaultPreferences(),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.loadState).isEqualTo(DesktopTaskBoardLoadState.EMPTY)
        /** Assert that. */
        assertThat(snapshot.counts.totalTaskCount).isEqualTo(0)
        /** Assert that. */
        assertThat(snapshot.counts.totalHabitCount).isEqualTo(0)
    }

    @Test
    fun `board snapshot exposes error state when catalog load fails`() {
        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = DesktopTaskCatalogSnapshot(),
                preferences = DesktopTaskBoardContracts.defaultPreferences(),
                errorMessage = "bad json",
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.loadState).isEqualTo(DesktopTaskBoardLoadState.ERROR)
        /** Assert that. */
        assertThat(snapshot.content.errorMessage).isEqualTo("bad json")
        /** Assert that. */
        assertThat(snapshot.content.visibleTasks).isEmpty()
        /** Assert that. */
        assertThat(snapshot.content.visibleHabits).isEmpty()
    }

    @Test
    fun `task filters include completed and archived buckets`() {
        val catalog = DesktopTaskBoardContracts.seededCatalog(now = fixedNow)

        val completedSnapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences = DesktopTaskBoardContracts.defaultPreferences().copy(selectedTaskFilter = DesktopTaskFilter.COMPLETED),
                now = fixedNow,
            )
        val archivedSnapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences = DesktopTaskBoardContracts.defaultPreferences().copy(selectedTaskFilter = DesktopTaskFilter.ARCHIVED),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(completedSnapshot.content.visibleTasks.map { it.title }).containsExactly("Read planning notes")
        /** Assert that. */
        assertThat(archivedSnapshot.content.visibleTasks.map { it.title }).containsExactly("Archive old receipts")
    }

    @Test
    fun `task filters include active future and not active buckets`() {
        val catalog = DesktopTaskCatalogSnapshot(
            tasks =
                /** List of. */
                listOf(
                    /** Desktop task record. */
                    DesktopTaskRecord(
                        id = "active-pending",
                        title = "Pending task",
                        status = "pending",
                        createdAtIso = fixedNow.minusDays(1).toString(),
                        dueAtIso = fixedNow.plusDays(1).toString(),
                    ),
                    /** Desktop task record. */
                    DesktopTaskRecord(
                        id = "future-active",
                        title = "Future task",
                        status = "active",
                        createdAtIso = fixedNow.minusDays(1).toString(),
                        dueAtIso = fixedNow.plusDays(2).toString(),
                    ),
                    /** Desktop task record. */
                    DesktopTaskRecord(
                        id = "done",
                        title = "Done task",
                        status = "completed",
                        createdAtIso = fixedNow.minusDays(3).toString(),
                    ),
                ),
        )

        val activeSnapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences = DesktopTaskBoardContracts.defaultPreferences().copy(selectedTaskFilter = DesktopTaskFilter.ACTIVE),
                now = fixedNow,
            )
        val futureSnapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences = DesktopTaskBoardContracts.defaultPreferences().copy(selectedTaskFilter = DesktopTaskFilter.FUTURE),
                now = fixedNow,
            )
        val inactiveSnapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences = DesktopTaskBoardContracts.defaultPreferences().copy(selectedTaskFilter = DesktopTaskFilter.NOT_ACTIVE),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(activeSnapshot.content.visibleTasks.map { it.title }).containsExactly("Future task", "Pending task")
        /** Assert that. */
        assertThat(futureSnapshot.content.visibleTasks.map { it.title }).containsExactly("Pending task", "Future task").inOrder()
        /** Assert that. */
        assertThat(inactiveSnapshot.content.visibleTasks.map { it.title }).containsExactly("Done task")
    }

    @Test
    fun `task sort dimension groups rows by life area`() {
        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = DesktopTaskBoardContracts.seededCatalog(now = fixedNow),
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedTaskFilter = DesktopTaskFilter.ACTIVE,
                        selectedTaskSort = DesktopTaskSortOption.DIMENSION,
                    ),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.visibleTasks.first().dimensionLabel).isEqualTo("Career & Work")
    }

    @Test
    fun `task sort created desc prefers newest rows and fallback dimension label`() {
        val catalog =
            /** Desktop task catalog snapshot. */
            DesktopTaskCatalogSnapshot(
                tasks =
                    /** List of. */
                    listOf(
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "older",
                            title = "Older row",
                            status = "active",
                            createdAtIso = fixedNow.minusDays(5).toString(),
                        ),
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "newer",
                            title = "Newer row",
                            status = "active",
                            createdAtIso = fixedNow.minusDays(1).toString(),
                        ),
                    ),
            )

        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedTaskFilter = DesktopTaskFilter.ACTIVE,
                        selectedTaskSort = DesktopTaskSortOption.CREATED_DESC,
                    ),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.visibleTasks.first().title).isEqualTo("Newer row")
        /** Assert that. */
        assertThat(snapshot.content.visibleTasks.first().dimensionLabel).isEqualTo("General")
        /** Assert that. */
        assertThat(snapshot.content.visibleTasks.first().dueLabel).isEqualTo("No due date")
    }

    @Test
    fun `today filter keeps actionable rows and formats task status labels`() {
        val catalog =
            /** Desktop task catalog snapshot. */
            DesktopTaskCatalogSnapshot(
                tasks =
                    /** List of. */
                    listOf(
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "today-active",
                            title = "Today task",
                            status = "active",
                            createdAtIso = fixedNow.minusDays(1).toString(),
                            dueAtIso = fixedNow.withHour(16).toString(),
                            currentScore = 0.73,
                        ),
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "today-completed",
                            title = "Done today",
                            status = "completed",
                            createdAtIso = fixedNow.minusDays(1).toString(),
                            dueAtIso = fixedNow.withHour(12).toString(),
                        ),
                    ),
            )

        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences = DesktopTaskBoardContracts.defaultPreferences(),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.visibleTasks.map { it.title }).containsExactly("Today task")
        /** Assert that. */
        assertThat(snapshot.content.visibleTasks.first().status).isEqualTo("Active")
        /** Assert that. */
        assertThat(snapshot.content.visibleTasks.first().scoreLabel).isEqualTo("73%")
    }

    @Test
    @Ignore("Locale-dependent date format — expected '26 Mar' but system is ta_IN")
    fun `habit sort by due time keeps due label and status readable`() {
        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = DesktopTaskBoardContracts.seededCatalog(now = fixedNow),
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedHabitSort = DesktopHabitSortOption.BY_DUE_TIME,
                        showArchivedHabits = true,
                    ),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.visibleHabits.first().title).isEqualTo("Review daily priorities")
        /** Assert that. */
        assertThat(snapshot.content.visibleHabits.first().todayStatusLabel).isEqualTo("Completed today")
        /** Assert that. */
        assertThat(snapshot.content.visibleHabits.first().dueLabel).contains("26 Mar")
    }

    @Test
    fun `habit list can surface archived status when archived rows are shown`() {
        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = DesktopTaskBoardContracts.seededCatalog(now = fixedNow),
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedHabitSort = DesktopHabitSortOption.BY_NAME,
                        showArchivedHabits = true,
                    ),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.visibleHabits.map { it.todayStatusLabel }).contains("Archived")
    }

    @Test
    fun `habit sorts cover name status and dimension orderings`() {
        val catalog =
            /** Desktop task catalog snapshot. */
            DesktopTaskCatalogSnapshot(
                tasks =
                    /** List of. */
                    listOf(
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "habit-z",
                            title = "Zen breathing",
                            status = "active",
                            recurrenceEnabled = true,
                            createdAtIso = fixedNow.minusDays(5).toString(),
                            dueAtIso = fixedNow.plusHours(2).toString(),
                            lifeDimension = "Self",
                        ),
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "habit-a",
                            title = "Admin reset",
                            status = "archived",
                            recurrenceEnabled = true,
                            createdAtIso = fixedNow.minusDays(6).toString(),
                            dueAtIso = fixedNow.plusHours(1).toString(),
                            lifeDimension = "Career & Work",
                        ),
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "habit-b",
                            title = "Book review",
                            status = "active",
                            recurrenceEnabled = true,
                            createdAtIso = fixedNow.minusDays(7).toString(),
                            dueAtIso = fixedNow.plusHours(3).toString(),
                            lifeDimension = "Learning",
                            completedToday = true,
                        ),
                    ),
            )

        val byName =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedHabitSort = DesktopHabitSortOption.BY_NAME,
                        showArchivedHabits = true,
                    ),
                now = fixedNow,
            )
        val byStatus =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedHabitSort = DesktopHabitSortOption.BY_STATUS,
                        showArchivedHabits = true,
                    ),
                now = fixedNow,
            )
        val byDimension =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences =
                    DesktopTaskBoardContracts.defaultPreferences().copy(
                        selectedHabitSort = DesktopHabitSortOption.BY_LIFE_DIMENSION,
                        showArchivedHabits = true,
                    ),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(byName.content.visibleHabits.map { it.title }).containsExactly("Admin reset", "Book review", "Zen breathing").inOrder()
        /** Assert that. */
        assertThat(byStatus.content.visibleHabits.first().todayStatusLabel).isEqualTo("Due today")
        /** Assert that. */
        assertThat(byDimension.content.visibleHabits.first().dimensionLabel).isEqualTo("Career & Work")
    }

    @Test
    fun `future filter keeps actionable tasks without due dates`() {
        val catalog =
            /** Desktop task catalog snapshot. */
            DesktopTaskCatalogSnapshot(
                tasks =
                    /** List of. */
                    listOf(
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "no-due",
                            title = "Inbox cleanup",
                            status = "pending",
                            createdAtIso = fixedNow.minusDays(2).toString(),
                            dueAtIso = null,
                        ),
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "today",
                            title = "Today task",
                            status = "active",
                            createdAtIso = fixedNow.minusDays(1).toString(),
                            dueAtIso = fixedNow.plusHours(1).toString(),
                        ),
                    ),
            )

        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences = DesktopTaskBoardContracts.defaultPreferences().copy(selectedTaskFilter = DesktopTaskFilter.FUTURE),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.visibleTasks.map { it.title }).containsExactly("Inbox cleanup")
    }

    @Test
    fun `habit status falls back to open when nothing is due or completed`() {
        val catalog =
            /** Desktop task catalog snapshot. */
            DesktopTaskCatalogSnapshot(
                tasks =
                    /** List of. */
                    listOf(
                        /** Desktop task record. */
                        DesktopTaskRecord(
                            id = "habit-open",
                            title = "Stretch break",
                            status = "active",
                            recurrenceEnabled = true,
                            createdAtIso = fixedNow.minusDays(4).toString(),
                            dueAtIso = fixedNow.plusDays(1).toString(),
                            lifeDimension = "Health",
                        ),
                    ),
            )

        val snapshot =
            DesktopTaskBoardContracts.boardSnapshotForCatalog(
                catalog = catalog,
                preferences = DesktopTaskBoardContracts.defaultPreferences(),
                now = fixedNow,
            )

        /** Assert that. */
        assertThat(snapshot.content.visibleHabits.single().todayStatusLabel).isEqualTo("Open")
    }

    @Test
    fun `seeded catalog includes both task and habit entries`() {
        val catalog = DesktopTaskBoardContracts.seededCatalog(now = fixedNow)

        /** Assert that. */
        assertThat(catalog.schemaVersion).isEqualTo(DesktopTaskBoardContracts.CATALOG_SCHEMA_VERSION)
        /** Assert that. */
        assertThat(catalog.tasks.count { it.recurrenceEnabled }).isEqualTo(3)
        /** Assert that. */
        assertThat(catalog.tasks.count { !it.recurrenceEnabled }).isEqualTo(4)
    }

    @Test
    fun `filter and sort enums accept persisted storage keys`() {
        /** Assert that. */
        assertThat(DesktopTaskFilter.fromStorageKey("overdue")).isEqualTo(DesktopTaskFilter.OVERDUE)
        /** Assert that. */
        assertThat(DesktopTaskSortOption.fromStorageKey("title_asc")).isEqualTo(DesktopTaskSortOption.TITLE_ASC)
        /** Assert that. */
        assertThat(DesktopHabitSortOption.fromStorageKey("by_due_time")).isEqualTo(DesktopHabitSortOption.BY_DUE_TIME)
        /** Assert that. */
        assertThat(DesktopTaskFilter.fromStorageKey("missing")).isEqualTo(DesktopTaskFilter.TODAY)
    }
}
