//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TimeEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * TimeEntryDaoTest.
 */
class TimeEntryDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var timeEntryDao: TimeEntryDao
    private lateinit var taskDao: TaskDao

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            /** Room. */
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        timeEntryDao = database.timeEntryDao()
        taskDao = database.taskDao()
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        database.close()
    }

    @Test
    /**
     * Insert and get by id.
     */
    fun insert_and_getById() =
        runBlocking {
            /** Entry. */
            val entry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            timeEntryDao.insert(entry)

            /** Retrieved. */
            val retrieved = timeEntryDao.getById("entry-1")
            /** Assert that. */
            assertThat(retrieved).isNotNull()
            /** Assert that. */
            assertThat(retrieved?.id).isEqualTo("entry-1")
            /** Assert that. */
            assertThat(retrieved?.lifeIntentionCategory).isEqualTo("health")
        }

    @Test
    /**
     * Get active time entry returns null when no active.
     */
    fun getActiveTimeEntry_returnsNullWhenNoActive() =
        runBlocking {
            /** Completed entry. */
            val completedEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z", "2026-02-02T11:00:00Z")
            timeEntryDao.insert(completedEntry)

            /** Active. */
            val active = timeEntryDao.getActiveTimeEntry()
            /** Assert that. */
            assertThat(active).isNull()
        }

    @Test
    /**
     * Get active time entry returns active entry.
     */
    fun getActiveTimeEntry_returnsActiveEntry() =
        runBlocking {
            /** Active entry. */
            val activeEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            /** Completed entry. */
            val completedEntry = createTestTimeEntry("entry-2", "2026-02-02T09:00:00Z", "2026-02-02T10:00:00Z")

            timeEntryDao.insert(activeEntry)
            timeEntryDao.insert(completedEntry)

            /** Active. */
            val active = timeEntryDao.getActiveTimeEntry()
            /** Assert that. */
            assertThat(active).isNotNull()
            /** Assert that. */
            assertThat(active?.id).isEqualTo("entry-1")
            /** Assert that. */
            assertThat(active?.endedAt).isNull()
        }

    @Test
    /**
     * Observe active time entry emits active entry.
     */
    fun observeActiveTimeEntry_emitsActiveEntry() =
        runBlocking {
            /** Active entry. */
            val activeEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            timeEntryDao.insert(activeEntry)

            /** Observed. */
            val observed = timeEntryDao.observeActiveTimeEntry().first()
            /** Assert that. */
            assertThat(observed).isNotNull()
            /** Assert that. */
            assertThat(observed?.id).isEqualTo("entry-1")
        }

    @Test
    /**
     * Get time entries for date filters by date.
     */
    fun getTimeEntriesForDate_filtersByDate() =
        runBlocking {
            /** Today entry. */
            val todayEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z", "2026-02-02T11:00:00Z")
            /** Yesterday entry. */
            val yesterdayEntry = createTestTimeEntry("entry-2", "2026-02-01T10:00:00Z", "2026-02-01T11:00:00Z")

            timeEntryDao.insert(todayEntry)
            timeEntryDao.insert(yesterdayEntry)

            /** Todays entries. */
            val todaysEntries =
                /** Time entry dao. */
                timeEntryDao
                    .getTimeEntriesForDate(
                        dayStart = "2026-02-02T00:00:00",
                        dayEnd = "2026-02-03T00:00:00",
                        currentTime = "2026-02-02T23:59:59",
                    ).first()
            /** Assert that. */
            assertThat(todaysEntries).hasSize(1)
            /** Assert that. */
            assertThat(todaysEntries[0].id).isEqualTo("entry-1")
        }

    @Test
    /**
     * Get time entries for date includes entry that spans midnight.
     */
    fun getTimeEntriesForDate_includesEntryThatSpansMidnight() =
        runBlocking {
            /** Spanning entry. */
            val spanningEntry =
                /** Create test time entry. */
                createTestTimeEntry(
                    id = "entry-overnight",
                    startedAt = "2026-02-01T23:50:00",
                    endedAt = "2026-02-02T00:20:00",
                )
            timeEntryDao.insert(spanningEntry)

            /** Next day entries. */
            val nextDayEntries =
                /** Time entry dao. */
                timeEntryDao
                    .getTimeEntriesForDate(
                        dayStart = "2026-02-02T00:00:00",
                        dayEnd = "2026-02-03T00:00:00",
                        currentTime = "2026-02-02T12:00:00",
                    ).first()

            /** Assert that. */
            assertThat(nextDayEntries).hasSize(1)
            /** Assert that. */
            assertThat(nextDayEntries[0].id).isEqualTo("entry-overnight")
        }

    @Test
    /**
     * Get time entries for date includes legacy zulu entry for matching local day.
     */
    fun getTimeEntriesForDate_includesLegacyZuluEntryForMatchingLocalDay() =
        runBlocking {
            /** Zulu entry. */
            val zuluEntry =
                /** Create test time entry. */
                createTestTimeEntry(
                    id = "entry-zulu-local-day",
                    startedAt = "2026-02-02T10:00:00Z",
                    endedAt = "2026-02-02T11:00:00Z",
                )
            timeEntryDao.insert(zuluEntry)

            /** Todays entries. */
            val todaysEntries =
                /** Time entry dao. */
                timeEntryDao
                    .getTimeEntriesForDate(
                        dayStart = "2026-02-02T00:00:00",
                        dayEnd = "2026-02-03T00:00:00",
                        currentTime = "2026-02-02T23:59:59",
                    ).first()

            /** Assert that. */
            assertThat(todaysEntries).hasSize(1)
            /** Assert that. */
            assertThat(todaysEntries[0].id).isEqualTo("entry-zulu-local-day")
        }

    @Test
    /**
     * Stop entry updates ended at.
     */
    fun stopEntry_updatesEndedAt() =
        runBlocking {
            /** Entry. */
            val entry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            timeEntryDao.insert(entry)

            timeEntryDao.stopEntry(
                id = "entry-1",
                endedAt = "2026-02-02T11:00:00Z",
                focusRating = 0.25,
                focusNote = "context switch",
                focusRatedAt = "2026-02-02T11:00:00Z",
                updatedAt = "2026-02-02T11:00:00Z",
            )

            /** Updated. */
            val updated = timeEntryDao.getById("entry-1")
            /** Assert that. */
            assertThat(updated?.endedAt).isEqualTo("2026-02-02T11:00:00Z")
            /** Assert that. */
            assertThat(updated?.focusRating).isEqualTo(0.25)
            /** Assert that. */
            assertThat(updated?.focusNote).isEqualTo("context switch")
            /** Assert that. */
            assertThat(updated?.focusRatedAt).isEqualTo("2026-02-02T11:00:00Z")
        }

    @Test
    /**
     * Delete removes entry.
     */
    fun delete_removesEntry() =
        runBlocking {
            /** Entry. */
            val entry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            timeEntryDao.insert(entry)

            timeEntryDao.delete(entry)

            /** Retrieved. */
            val retrieved = timeEntryDao.getById("entry-1")
            /** Assert that. */
            assertThat(retrieved).isNull()
        }

    @Test
    /**
     * Get all active time entries returns only active.
     */
    fun getAllActiveTimeEntries_returnsOnlyActive() =
        runBlocking {
            /** Active entry. */
            val activeEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            /** Completed entry. */
            val completedEntry = createTestTimeEntry("entry-2", "2026-02-02T09:00:00Z", "2026-02-02T10:00:00Z")

            timeEntryDao.insert(activeEntry)
            timeEntryDao.insert(completedEntry)

            /** Active entries. */
            val activeEntries = timeEntryDao.getAllActiveTimeEntries().first()
            /** Assert that. */
            assertThat(activeEntries).hasSize(1)
            /** Assert that. */
            assertThat(activeEntries[0].id).isEqualTo("entry-1")
        }

    @Test
    /**
     * Deleting task nulls linked time entry task id.
     */
    fun deletingTask_nullsLinkedTimeEntryTaskId() =
        runBlocking {
            taskDao.insert(
                /** Task entity. */
                TaskEntity(
                    id = "task-1",
                    title = "Linked task",
                    description = "desc",
                    status = "pending",
                    dueDate = null,
                    createdAt = "2026-02-01T09:00:00Z",
                    updatedAt = "2026-02-01T09:00:00Z",
                    recurrenceEnabled = 0,
                    recurrenceRule = null,
                    durationMinutes = 30,
                    impactLevel = "medium",
                    goalAlignment = "personal",
                    energyLevel = "medium",
                    controlLevel = "high",
                    lifeIntentionCategory = "health",
                    explicitUrgency = null,
                    focusRequired = null,
                    blockedReason = null,
                    completionRate = null,
                    externalDependency = null,
                    notificationMode = "default",
                    customNotificationMinutes = null,
                    taskScore = 0.5,
                    lastOccurrenceDate = null,
                ),
            )
            timeEntryDao.insert(createTestTimeEntry("entry-linked", "2026-02-02T10:00:00Z", taskId = "task-1"))

            /** Before delete. */
            val beforeDelete = timeEntryDao.getById("entry-linked")
            /** Assert that. */
            assertThat(beforeDelete?.taskId).isEqualTo("task-1")

            taskDao.deleteById("task-1")

            /** After delete. */
            val afterDelete = timeEntryDao.getById("entry-linked")
            /** Assert that. */
            assertThat(afterDelete?.taskId).isNull()
        }

    private fun createTestTimeEntry(
        /** Id. */
        id: String,
        /** Started at. */
        startedAt: String,
        endedAt: String? = null,
        taskId: String? = null,
    ) = TimeEntryEntity(
        id = id,
        lifeIntentionCategory = "health",
        taskId = taskId,
        startedAt = startedAt,
        endedAt = endedAt,
        createdAt = "2026-02-01T09:00:00Z",
        updatedAt = "2026-02-01T09:00:00Z",
    )
}
