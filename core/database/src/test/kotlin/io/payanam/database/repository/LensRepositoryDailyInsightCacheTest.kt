//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.dao.DailyInsightDao
import io.payanam.database.entity.DailyInsightEntity
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.DayPlanAllocationRecord
import io.payanam.domain.repository.DayPlanPolicyRecord
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.domain.repository.DayTypeTemplatePreferenceRecord
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * LensRepositoryDailyInsightCacheTest.
 */
class LensRepositoryDailyInsightCacheTest {
    private lateinit var database: PayanamDatabase
    private lateinit var dailyInsightDao: DailyInsightDao
    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var timeEntryRepository: FakeTimeEntryRepository
    private lateinit var taskOccurrenceRepository: FakeTaskOccurrenceRepository
    private lateinit var dayPlanRepository: FakeDayPlanRepository
    private lateinit var repository: LensRepositoryImpl

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        database =
            /** Room. */
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        dailyInsightDao = database.dailyInsightDao()
        taskRepository = FakeTaskRepository()
        timeEntryRepository = FakeTimeEntryRepository()
        taskOccurrenceRepository = FakeTaskOccurrenceRepository()
        dayPlanRepository = FakeDayPlanRepository()
        /** Encryption manager. */
        val encryptionManager = DatabaseEncryptionManager(context)
        /** Session manager. */
        val sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(database)
        repository =
            /** Lens repository impl. */
            LensRepositoryImpl(
                sessionManager = sessionManager,
                taskRepository = taskRepository,
                timeEntryRepository = timeEntryRepository,
                taskOccurrenceRepository = taskOccurrenceRepository,
                dayPlanRepository = dayPlanRepository,
            )
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
     * Calculate unified snapshot reads from persistent cache after first load.
     */
    fun calculateUnifiedSnapshot_readsFromPersistentCacheAfterFirstLoad() =
        runBlocking {
            /** Day key. */
            val dayKey = "2026-02-19"
            /** Day date. */
            val dayDate = LocalDate.parse(dayKey)
            taskRepository.tasks =
                /** List of. */
                listOf(
                    /** Task. */
                    Task(
                        id = "task_1",
                        title = "Deep work",
                        status = "pending",
                        dueDate = dayDate.atTime(10, 0),
                        createdAt = dayDate.atStartOfDay(),
                        updatedAt = dayDate.atStartOfDay(),
                        durationMinutes = 90,
                        dimensionId = "career_work",
                    ),
                )
            dayPlanRepository.allocationsByDay[dayKey] =
                /** List of. */
                listOf(
                    /** Day plan allocation record. */
                    DayPlanAllocationRecord(
                        id = "alloc_1",
                        dayKey = dayKey,
                        dimensionId = "career_work",
                        plannedMinutes = 120,
                        source = "manual",
                        templateId = null,
                    ),
                )

            /** First. */
            val first = repository.calculateUnifiedSnapshot(dayKey)
            /** Cached entity. */
            val cachedEntity = dailyInsightDao.getSummaryForDay(dayKey, "lens_unified_snapshot")

            taskRepository.tasks = emptyList()
            /** Second. */
            val second = repository.calculateUnifiedSnapshot(dayKey)

            /** Assert that. */
            assertThat(first).isEqualTo(second)
            /** Assert that. */
            assertThat(taskRepository.getAllTasksCalls).isEqualTo(1)
            /** Assert that. */
            assertThat(cachedEntity).isNotNull()
            /** Assert that. */
            assertThat(cachedEntity?.summaryJson).isNotEmpty()
            /** Assert that. */
            assertThat(cachedEntity?.plannedMinutes).isEqualTo(first.planning.totalPlannedMinutes)
            /** Assert that. */
            assertThat(cachedEntity?.actualMinutes).isEqualTo(first.reality.totalActualMinutes)
        }

    @Test
    /**
     * Calculate unified snapshot recomputes when day marked dirty then clears dirty marker.
     */
    fun calculateUnifiedSnapshot_recomputesWhenDayMarkedDirty_thenClearsDirtyMarker() =
        runBlocking {
            /** Day key. */
            val dayKey = "2026-02-20"
            /** Day date. */
            val dayDate = LocalDate.parse(dayKey)
            taskRepository.tasks =
                /** List of. */
                listOf(
                    /** Task. */
                    Task(
                        id = "task_1",
                        title = "Focus block",
                        status = "pending",
                        dueDate = dayDate.atTime(9, 0),
                        createdAt = dayDate.atStartOfDay(),
                        updatedAt = dayDate.atStartOfDay(),
                        durationMinutes = 60,
                        dimensionId = "career_work",
                    ),
                )

            repository.calculateUnifiedSnapshot(dayKey)
            /** Assert that. */
            assertThat(taskRepository.getAllTasksCalls).isEqualTo(1)

            taskRepository.tasks =
                /** List of. */
                listOf(
                    /** Task. */
                    Task(
                        id = "task_2",
                        title = "Changed plan",
                        status = "pending",
                        dueDate = dayDate.atTime(10, 0),
                        createdAt = dayDate.atStartOfDay(),
                        updatedAt = dayDate.atStartOfDay(),
                        durationMinutes = 45,
                        dimensionId = "career_work",
                    ),
                )
            /** Mark lens day dirty. */
            markLensDayDirty(
                dailyInsightDao = dailyInsightDao,
                logger = UnifiedLogger.getInstance(),
                dayKey = dayKey,
                changedModules = setOf("task"),
                reason = "test_dirty",
            )

            /** Recomputed. */
            val recomputed = repository.calculateUnifiedSnapshot(dayKey)

            /** Assert that. */
            assertThat(taskRepository.getAllTasksCalls).isEqualTo(2)
            /** Assert that. */
            assertThat(
                recomputed.planning.plannedTasks
                    .single()
                    .taskId,
            ).isEqualTo("task_2")
            /** Assert that. */
            assertThat(loadLensDirtyDayMetadata(dailyInsightDao, dayKey)).isNull()
        }

    @Test
    /**
     * Calculate unified snapshot counts recurring plan once and includes occurrence duration when no time entry.
     */
    fun calculateUnifiedSnapshot_counts_recurring_plan_once_and_includes_occurrence_duration_when_no_time_entry() =
        runBlocking {
            /** Day key. */
            val dayKey = "2026-02-21"
            /** Day date. */
            val dayDate = LocalDate.parse(dayKey)
            taskRepository.tasks =
                /** List of. */
                listOf(
                    /** Task. */
                    Task(
                        id = "habit_1",
                        title = "Morning Walk",
                        status = "pending",
                        dueDate = dayDate.atTime(6, 0),
                        createdAt = dayDate.atStartOfDay(),
                        updatedAt = dayDate.atStartOfDay(),
                        durationMinutes = 30,
                        recurrenceEnabled = true,
                        dimensionId = "health_wellness",
                    ),
                )
            taskOccurrenceRepository.occurrencesByDate[dayDate] =
                /** List of. */
                listOf(
                    /** Task occurrence. */
                    TaskOccurrence(
                        id = "occ_1",
                        taskId = "habit_1",
                        occurrenceDate = dayKey,
                        status = "completed",
                        actualDurationMinutes = 25,
                    ),
                )

            /** Snapshot. */
            val snapshot = repository.calculateUnifiedSnapshot(dayKey)

            /** Assert that. */
            assertThat(snapshot.planning.plannedTasks).isEmpty()
            /** Assert that. */
            assertThat(snapshot.planning.plannedHabits).hasSize(1)
            /** Assert that. */
            assertThat(snapshot.planning.totalPlannedMinutes).isEqualTo(30)
            /** Assert that. */
            assertThat(snapshot.reality.totalActualMinutes).isEqualTo(25)
            /** Assert that. */
            assertThat(snapshot.reality.actualTimeByDimension["health_wellness"]).isEqualTo(25)
            /** Assert that. */
            assertThat(snapshot.reality.supplementalActualMinutes).isEqualTo(25)
            /** Assert that. */
            assertThat(snapshot.reality.supplementalActualByDimension["health_wellness"]).isEqualTo(25)
        }

    @Test
    /**
     * Calculate unified snapshot recomputes when cached snapshot missing split fields.
     */
    fun calculateUnifiedSnapshot_recomputesWhenCachedSnapshotMissingSplitFields() =
        runBlocking {
            /** Day key. */
            val dayKey = "2026-02-20"
            /** Day date. */
            val dayDate = LocalDate.parse(dayKey)
            taskRepository.tasks =
                /** List of. */
                listOf(
                    /** Task. */
                    Task(
                        id = "habit_legacy",
                        title = "Hydration",
                        status = "pending",
                        dueDate = dayDate.atTime(19, 0),
                        createdAt = dayDate.atStartOfDay(),
                        updatedAt = dayDate.atStartOfDay(),
                        durationMinutes = 5,
                        recurrenceEnabled = true,
                        dimensionId = "health_wellness",
                    ),
                )
            timeEntryRepository.entriesByDate[dayDate] =
                /** List of. */
                listOf(
                    /** Time entry. */
                    TimeEntry(
                        id = "entry_habit_legacy",
                        lifeIntentionCategory = "Health & Wellness",
                        taskId = "habit_legacy",
                        startedAt = dayDate.atTime(19, 27),
                        endedAt = dayDate.atTime(19, 29),
                        createdAt = dayDate.atTime(19, 27),
                        updatedAt = dayDate.atTime(19, 29),
                        dimensionId = "health_wellness",
                    ),
                )

            /** Fresh snapshot. */
            val freshSnapshot = repository.calculateUnifiedSnapshot(dayKey)
            /** Legacy encoded. */
            val legacyEncoded =
                /** Encode unified lens snapshot. */
                encodeUnifiedLensSnapshot(freshSnapshot)
                    .lineSequence()
                    .filterNot { line ->
                        line.startsWith("reality.timeOnly=") ||
                            line.startsWith("reality.taskOnly=") ||
                            line.startsWith("reality.habitOnly=")
                    }.joinToString("\n")
            dailyInsightDao.upsert(
                /** Daily insight entity. */
                DailyInsightEntity(
                    id = "lens_snapshot_$dayKey",
                    dayKey = dayKey,
                    module = DAILY_INSIGHT_MODULE_UNIFIED_SNAPSHOT,
                    dimensionId = null,
                    plannedMinutes = freshSnapshot.planning.totalPlannedMinutes,
                    actualMinutes = freshSnapshot.reality.totalActualMinutes,
                    focusedMinutes = null,
                    completedCount = freshSnapshot.reality.completedTasks.count { it.status == "completed" },
                    totalCount = freshSnapshot.planning.plannedTasks.size,
                    summaryJson = legacyEncoded,
                    generatedAt = dayDate.atStartOfDay().toString(),
                ),
            )
            taskRepository.getAllTasksCalls = 0

            /** Recomputed. */
            val recomputed = repository.calculateUnifiedSnapshot(dayKey)

            /** Assert that. */
            assertThat(taskRepository.getAllTasksCalls).isEqualTo(1)
            /** Assert that. */
            assertThat(recomputed.reality.totalActualMinutes).isEqualTo(2)
            /** Assert that. */
            assertThat(recomputed.reality.actualHabitMinutes).isEqualTo(2)
            /** Assert that. */
            assertThat(recomputed.reality.actualTaskMinutes).isEqualTo(0)
            /** Assert that. */
            assertThat(recomputed.reality.actualTimeOnlyMinutes).isEqualTo(0)
        }

    private class FakeTaskRepository : TaskRepository {
        /** Tasks. */
        var tasks: List<Task> = emptyList()
        /** Get all tasks calls. */
        var getAllTasksCalls: Int = 0

        override fun getAllTasks(): Flow<List<Task>> {
            getAllTasksCalls += 1
            return flowOf(tasks)
        }

        override fun getTasksByStatus(status: String): Flow<List<Task>> = unused("getTasksByStatus")

        override fun getTasksDueOn(date: LocalDate): Flow<List<Task>> = unused("getTasksDueOn")

        override suspend fun getTaskById(id: String): Task? = unused("getTaskById")

        override suspend fun createTask(input: TaskInput): Task = unused("createTask")

        override suspend fun updateTask(
            /** Id. */
            id: String,
            /** Input. */
            input: TaskInput,
        ): Task = unused("updateTask")

        override suspend fun updateTaskScore(
            /** Id. */
            id: String,
            /** Score. */
            score: Double,
        ) = unused("updateTaskScore")

        override suspend fun deleteTask(id: String) = unused("deleteTask")

        override suspend fun completeTask(
            /** Id. */
            id: String,
            note: String?,
        ): Task = unused("completeTask")

        override suspend fun skipTask(
            /** Id. */
            id: String,
            note: String?,
        ): Task = unused("skipTask")

        override suspend fun missTask(
            /** Id. */
            id: String,
            note: String?,
        ): Task = unused("missTask")

        override suspend fun archiveTask(id: String): Task = unused("archiveTask")

        override fun getOverdueTasks(): Flow<List<Task>> = unused("getOverdueTasks")

        override fun getTodaysTasks(): Flow<List<Task>> = unused("getTodaysTasks")

        override suspend fun getRecurringTasks(): List<Task> = unused("getRecurringTasks")

        override suspend fun updateRecurrenceState(
            /** Task id. */
            taskId: String,
            /** New due date. */
            newDueDate: LocalDateTime,
            /** Last occurrence date. */
            lastOccurrenceDate: LocalDateTime,
        ) = unused("updateRecurrenceState")
    }

    private class FakeTimeEntryRepository : TimeEntryRepository {
        /** Entries by date. */
        val entriesByDate = mutableMapOf<LocalDate, List<TimeEntry>>()

        override suspend fun getActiveTimeEntry(): TimeEntry? = unused("getActiveTimeEntry")

        override fun observeActiveTimeEntry(): Flow<TimeEntry?> = unused("observeActiveTimeEntry")

        override fun getTimeEntriesForRange(
            /** Start. */
            start: LocalDateTime,
            /** End. */
            end: LocalDateTime,
        ): Flow<List<TimeEntry>> = unused("getTimeEntriesForRange")

        override fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>> = flowOf(entriesByDate[date] ?: emptyList())

        override suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry = unused("startTimeEntry")

        override suspend fun stopActiveTimeEntry(): TimeEntry? = unused("stopActiveTimeEntry")

        override suspend fun stopActiveTimeEntryWithFocus(
            /** Focus rating. */
            focusRating: Double,
            focusNote: String?,
        ): TimeEntry? = unused("stopActiveTimeEntryWithFocus")

        override suspend fun updateTimeEntry(
            /** Id. */
            id: String,
            /** Input. */
            input: TimeEntryInput,
        ): TimeEntry = unused("updateTimeEntry")

        override suspend fun deleteTimeEntry(id: String) = unused("deleteTimeEntry")

        override suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry = unused("createTimeEntry")

        override fun getAllTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

        override fun getActiveTimeEntries(): Flow<List<TimeEntry>> = unused("getActiveTimeEntries")

        override suspend fun updateTimeEntry(entry: TimeEntry) = unused("updateTimeEntryDirect")
    }

    private class FakeTaskOccurrenceRepository : TaskOccurrenceRepository {
        /** Occurrences by date. */
        val occurrencesByDate = mutableMapOf<LocalDate, List<TaskOccurrence>>()

        override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> = unused("getOccurrencesByTaskId")

        override fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrence>> = unused("getOccurrencesForTask")

        override suspend fun getOccurrencesForLastNDays(
            /** Task id. */
            taskId: String,
            /** Days. */
            days: Int,
        ): List<TaskOccurrence> = unused("getOccurrencesForLastNDays")

        override suspend fun getOccurrencesForTasksInLastNDays(
            taskIds: List<String>,
            /** Days. */
            days: Int,
        ): Map<String, List<TaskOccurrence>> = unused("getOccurrencesForTasksInLastNDays")

        override suspend fun getOccurrenceForDate(
            /** Task id. */
            taskId: String,
            /** Date. */
            date: LocalDate,
        ): TaskOccurrence? = unused("getOccurrenceForDate")

        override suspend fun toggleOccurrence(
            /** Task id. */
            taskId: String,
            /** Date. */
            date: LocalDate,
            /** New status. */
            newStatus: String,
            note: String?,
            reason: String?,
            actualCompletedAt: LocalDateTime?,
            actualDurationMinutes: Int?,
        ): TaskOccurrence = unused("toggleOccurrence")

        override fun getOccurrencesForDate(date: LocalDate): Flow<List<TaskOccurrence>> = flowOf(occurrencesByDate[date] ?: emptyList())

        override suspend fun deleteOccurrence(
            /** Task id. */
            taskId: String,
            /** Date. */
            date: LocalDate,
        ) = unused("deleteOccurrenceByDate")

        override suspend fun recordOccurrence(occurrence: TaskOccurrence) = unused("recordOccurrenceModel")

        override suspend fun recordOccurrence(
            /** Task id. */
            taskId: String,
            /** Due date. */
            dueDate: LocalDateTime,
            /** Status. */
            status: String,
            note: String?,
            completionRate: Double?,
        ): TaskOccurrence = unused("recordOccurrenceLegacy")

        override suspend fun deleteOccurrence(id: String) = unused("deleteOccurrenceById")
    }

    private class FakeDayPlanRepository : DayPlanRepository {
        /** Allocations by day. */
        val allocationsByDay = mutableMapOf<String, List<DayPlanAllocationRecord>>()

        override fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>> = unused("observeAllocationsForDay")

        override suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> = allocationsByDay[dayKey] ?: emptyList()

        override suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> =
            allocationsByDay[dayKey] ?: emptyList()

        override suspend fun setAllocation(
            /** Day key. */
            dayKey: String,
            /** Dimension id. */
            dimensionId: String,
            /** Planned minutes. */
            plannedMinutes: Int,
            /** Source. */
            source: String,
            templateId: String?,
        ) = unused("setAllocation")

        override suspend fun setAllocations(
            /** Day key. */
            dayKey: String,
            allocations: Map<String, Int>,
            /** Source. */
            source: String,
            templateId: String?,
        ) = unused("setAllocations")

        override suspend fun applyTemplateToDay(
            /** Day key. */
            dayKey: String,
            /** Template id. */
            templateId: String,
        ) = unused("applyTemplateToDay")

        override suspend fun clearDayPlan(dayKey: String) = unused("clearDayPlan")

        override suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord = unused("getDayPolicy")

        override suspend fun setDayMode(
            /** Day key. */
            dayKey: String,
            /** Mode. */
            mode: String,
            templateId: String?,
        ) = unused("setDayMode")

        override suspend fun setDayStarred(
            /** Day key. */
            dayKey: String,
            /** Is starred. */
            isStarred: Boolean,
        ) = unused("setDayStarred")

        override suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord =
            /** Unused. */
            unused("getDayTypeTemplatePreference")

        override suspend fun setDayTypeTemplatePreference(
            /** Day type. */
            dayType: String,
            templateId: String?,
        ) = unused("setDayTypeTemplatePreference")

        override suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord? = unused("resolveTemplateForDay")

        override fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>> = emptyFlow()

        override fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>> = emptyFlow()

        override suspend fun getTemplateById(id: String): DayPlanTemplateRecord? = unused("getTemplateById")

        override suspend fun createTemplate(
            /** Name. */
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ): String = unused("createTemplate")

        override suspend fun updateTemplate(
            /** Id. */
            id: String,
            /** Name. */
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ) = unused("updateTemplate")

        override suspend fun deleteTemplate(id: String) = unused("deleteTemplate")
    }
}

private fun unused(methodName: String): Nothing {
    /** Error. */
    error("Not used in test: $methodName")
}
