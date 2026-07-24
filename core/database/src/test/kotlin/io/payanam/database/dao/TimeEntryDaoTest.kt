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
class TimeEntryDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var timeEntryDao: TimeEntryDao
    private lateinit var taskDao: TaskDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        timeEntryDao = database.timeEntryDao()
        taskDao = database.taskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_and_getById() =
        runBlocking {
            val entry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            timeEntryDao.insert(entry)

            val retrieved = timeEntryDao.getById("entry-1")
            assertThat(retrieved).isNotNull()
            assertThat(retrieved?.id).isEqualTo("entry-1")
            assertThat(retrieved?.lifeIntentionCategory).isEqualTo("health")
        }

    @Test
    fun getActiveTimeEntry_returnsNullWhenNoActive() =
        runBlocking {
            val completedEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z", "2026-02-02T11:00:00Z")
            timeEntryDao.insert(completedEntry)

            val active = timeEntryDao.getActiveTimeEntry()
            assertThat(active).isNull()
        }

    @Test
    fun getActiveTimeEntry_returnsActiveEntry() =
        runBlocking {
            val activeEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            val completedEntry = createTestTimeEntry("entry-2", "2026-02-02T09:00:00Z", "2026-02-02T10:00:00Z")

            timeEntryDao.insert(activeEntry)
            timeEntryDao.insert(completedEntry)

            val active = timeEntryDao.getActiveTimeEntry()
            assertThat(active).isNotNull()
            assertThat(active?.id).isEqualTo("entry-1")
            assertThat(active?.endedAt).isNull()
        }

    @Test
    fun observeActiveTimeEntry_emitsActiveEntry() =
        runBlocking {
            val activeEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            timeEntryDao.insert(activeEntry)

            val observed = timeEntryDao.observeActiveTimeEntry().first()
            assertThat(observed).isNotNull()
            assertThat(observed?.id).isEqualTo("entry-1")
        }

    @Test
    fun getTimeEntriesForDate_filtersByDate() =
        runBlocking {
            val todayEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z", "2026-02-02T11:00:00Z")
            val yesterdayEntry = createTestTimeEntry("entry-2", "2026-02-01T10:00:00Z", "2026-02-01T11:00:00Z")

            timeEntryDao.insert(todayEntry)
            timeEntryDao.insert(yesterdayEntry)

            val todaysEntries =
                timeEntryDao
                    .getTimeEntriesForDate(
                        dayStart = "2026-02-02T00:00:00",
                        dayEnd = "2026-02-03T00:00:00",
                        currentTime = "2026-02-02T23:59:59",
                    ).first()
            assertThat(todaysEntries).hasSize(1)
            assertThat(todaysEntries[0].id).isEqualTo("entry-1")
        }

    @Test
    fun getTimeEntriesForDate_includesEntryThatSpansMidnight() =
        runBlocking {
            val spanningEntry =
                createTestTimeEntry(
                    id = "entry-overnight",
                    startedAt = "2026-02-01T23:50:00",
                    endedAt = "2026-02-02T00:20:00",
                )
            timeEntryDao.insert(spanningEntry)

            val nextDayEntries =
                timeEntryDao
                    .getTimeEntriesForDate(
                        dayStart = "2026-02-02T00:00:00",
                        dayEnd = "2026-02-03T00:00:00",
                        currentTime = "2026-02-02T12:00:00",
                    ).first()

            assertThat(nextDayEntries).hasSize(1)
            assertThat(nextDayEntries[0].id).isEqualTo("entry-overnight")
        }

    @Test
    fun getTimeEntriesForDate_includesLegacyZuluEntryForMatchingLocalDay() =
        runBlocking {
            val zuluEntry =
                createTestTimeEntry(
                    id = "entry-zulu-local-day",
                    startedAt = "2026-02-02T10:00:00Z",
                    endedAt = "2026-02-02T11:00:00Z",
                )
            timeEntryDao.insert(zuluEntry)

            val todaysEntries =
                timeEntryDao
                    .getTimeEntriesForDate(
                        dayStart = "2026-02-02T00:00:00",
                        dayEnd = "2026-02-03T00:00:00",
                        currentTime = "2026-02-02T23:59:59",
                    ).first()

            assertThat(todaysEntries).hasSize(1)
            assertThat(todaysEntries[0].id).isEqualTo("entry-zulu-local-day")
        }

    @Test
    fun stopEntry_updatesEndedAt() =
        runBlocking {
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

            val updated = timeEntryDao.getById("entry-1")
            assertThat(updated?.endedAt).isEqualTo("2026-02-02T11:00:00Z")
            assertThat(updated?.focusRating).isEqualTo(0.25)
            assertThat(updated?.focusNote).isEqualTo("context switch")
            assertThat(updated?.focusRatedAt).isEqualTo("2026-02-02T11:00:00Z")
        }

    @Test
    fun delete_removesEntry() =
        runBlocking {
            val entry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            timeEntryDao.insert(entry)

            timeEntryDao.delete(entry)

            val retrieved = timeEntryDao.getById("entry-1")
            assertThat(retrieved).isNull()
        }

    @Test
    fun getAllActiveTimeEntries_returnsOnlyActive() =
        runBlocking {
            val activeEntry = createTestTimeEntry("entry-1", "2026-02-02T10:00:00Z")
            val completedEntry = createTestTimeEntry("entry-2", "2026-02-02T09:00:00Z", "2026-02-02T10:00:00Z")

            timeEntryDao.insert(activeEntry)
            timeEntryDao.insert(completedEntry)

            val activeEntries = timeEntryDao.getAllActiveTimeEntries().first()
            assertThat(activeEntries).hasSize(1)
            assertThat(activeEntries[0].id).isEqualTo("entry-1")
        }

    @Test
    fun deletingTask_nullsLinkedTimeEntryTaskId() =
        runBlocking {
            taskDao.insert(
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
                    currentScore = 0.5,
                    lastOccurrenceDate = null,
                ),
            )
            timeEntryDao.insert(createTestTimeEntry("entry-linked", "2026-02-02T10:00:00Z", taskId = "task-1"))

            val beforeDelete = timeEntryDao.getById("entry-linked")
            assertThat(beforeDelete?.taskId).isEqualTo("task-1")

            taskDao.deleteById("task-1")

            val afterDelete = timeEntryDao.getById("entry-linked")
            assertThat(afterDelete?.taskId).isNull()
        }

    private fun createTestTimeEntry(
        id: String,
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
