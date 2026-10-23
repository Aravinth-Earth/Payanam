//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TimeEntryInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Properties
import io.payanam.domain.lens.encodeUnifiedLensSnapshot as encodeUnifiedLensSnapshotForTest

/**
 * Tests for [LensRepositoryJvm].
 *
 * The behaviours worth this many tests are the ones the port can get wrong while still compiling and still
 * looking plausible: the cache/dirty cycle (a clean day must not recompute, a dirty one must), the minute
 * split between time-only / task / habit, habit minutes counted once and not twice, and reflection cards
 * being regenerated rather than appended to.
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
@Suppress("LargeClass")
class LensRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "lens-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    private val today: LocalDate = LocalDate.now()
    private val todayKey: String = today.toString()

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("lens.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE tasks (
                    id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, description TEXT, status TEXT NOT NULL DEFAULT 'pending',
                    dueDate TEXT, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, completedAt TEXT, archivedAt TEXT,
                    recurrenceEnabled INTEGER NOT NULL DEFAULT 0, recurrenceRule TEXT, durationMinutes INTEGER NOT NULL DEFAULT 60,
                    impactLevel TEXT NOT NULL DEFAULT 'Moderate Impact', goalAlignment TEXT NOT NULL DEFAULT 'Moderate Alignment',
                    energyLevel TEXT NOT NULL DEFAULT 'Moderate', controlLevel TEXT NOT NULL DEFAULT 'Office/Colleagues Dependent',
                    lifeIntentionCategory TEXT NOT NULL DEFAULT 'Career & Work', dimension_id TEXT, day_key TEXT,
                    explicitUrgency REAL, focusRequired REAL, recurrenceStrategy TEXT, blockedReason TEXT, completionRate REAL,
                    externalDependency TEXT, notificationMode TEXT DEFAULT 'auto', customNotificationMinutes INTEGER,
                    taskScore REAL, lastOccurrenceDate TEXT, dayBoundaryHour INTEGER NOT NULL DEFAULT 0,
                    import_source TEXT, import_id TEXT, imported_at TEXT, import_batch_id TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE time_entries (
                    id TEXT PRIMARY KEY NOT NULL, lifeIntentionCategory TEXT NOT NULL, dimension_id TEXT, day_key TEXT,
                    taskId TEXT, startedAt TEXT NOT NULL, endedAt TEXT, focusRating REAL, focusNote TEXT, focusRatedAt TEXT,
                    import_source TEXT, import_id TEXT, imported_at TEXT, import_batch_id TEXT,
                    createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE task_occurrences (
                    id TEXT PRIMARY KEY NOT NULL, taskId TEXT NOT NULL, dueDate TEXT NOT NULL, completedAt TEXT,
                    actualCompletedAt TEXT, actualDurationMinutes INTEGER, status TEXT NOT NULL, statusReason TEXT,
                    createdAt TEXT NOT NULL, completionRate REAL, note TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_plan_allocations (
                    id TEXT PRIMARY KEY NOT NULL, day_key TEXT NOT NULL, dimension_id TEXT NOT NULL,
                    planned_minutes INTEGER NOT NULL, source TEXT NOT NULL DEFAULT 'manual', template_id TEXT,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_plan_policies (
                    day_key TEXT PRIMARY KEY NOT NULL, mode TEXT NOT NULL DEFAULT 'auto', template_id TEXT,
                    is_starred INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_plan_templates (
                    id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, description TEXT, is_active INTEGER NOT NULL DEFAULT 1,
                    sort_order INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_plan_template_allocations (
                    id TEXT PRIMARY KEY NOT NULL, template_id TEXT NOT NULL, dimension_id TEXT NOT NULL,
                    planned_minutes INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_type_template_preferences (
                    day_type TEXT PRIMARY KEY NOT NULL, template_id TEXT, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE daily_insights (
                    id TEXT PRIMARY KEY NOT NULL, day_key TEXT NOT NULL, module TEXT NOT NULL, dimension_id TEXT,
                    planned_minutes INTEGER, actual_minutes INTEGER, focused_minutes INTEGER, completed_count INTEGER,
                    total_count INTEGER, summary_json TEXT, generated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE lens_reflections (
                    id TEXT PRIMARY KEY NOT NULL, day_key TEXT NOT NULL, dimension_id TEXT, reflection_type TEXT NOT NULL,
                    title TEXT NOT NULL, description TEXT, gap_minutes INTEGER, related_entity_id TEXT,
                    is_addressed INTEGER NOT NULL DEFAULT 0, user_note TEXT, created_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun taskRepository() = TaskRepositoryJvm(session, pollIntervalMs = 10L)

    private fun timeRepository() = TimeEntryRepositoryJvm(session, pollIntervalMs = 10L)

    private fun occurrenceRepository() = TaskOccurrenceRepositoryJvm(session, pollIntervalMs = 10L)

    private fun dayPlanRepository() = DayPlanRepositoryJvm(session, pollIntervalMs = 10L)

    private fun lens() =
        LensRepositoryJvm(
            session = session,
            taskRepository = taskRepository(),
            timeEntryRepository = timeRepository(),
            taskOccurrenceRepository = occurrenceRepository(),
            dayPlanRepository = dayPlanRepository(),
            pollIntervalMs = 10L,
        )

    private suspend fun createTask(
        title: String,
        dueDate: LocalDateTime? = today.atTime(9, 0),
        durationMinutes: Int = 60,
        dimensionId: String? = "dim_work_livelihood",
        recurrenceEnabled: Boolean = false,
        focusRequired: Double? = null,
    ): Task =
        taskRepository().createTask(
            TaskInput(
                title = title,
                dueDate = dueDate,
                durationMinutes = durationMinutes,
                dimensionId = dimensionId,
                recurrenceEnabled = recurrenceEnabled,
                focusRequired = focusRequired,
            ),
        )

    /** Inserts a task row directly, bypassing the repository and therefore any dirty marking. */
    private fun insertTaskRowDirectly(
        id: String,
        title: String,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO tasks (id, title, status, dueDate, createdAt, updatedAt, recurrenceEnabled, durationMinutes) " +
                    "VALUES (?, ?, 'pending', ?, 'T0', 'T0', 0, 60)",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, title)
                statement.setString(3, today.atTime(9, 0).toString())
                statement.executeUpdate()
            }
    }

    private fun setTaskStatus(
        id: String,
        status: String,
    ) {
        connection.prepareStatement("UPDATE tasks SET status = ? WHERE id = ?").use { statement ->
            statement.setString(1, status)
            statement.setString(2, id)
            statement.executeUpdate()
        }
    }

    private fun cacheRowCount(): Int =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT COUNT(*) FROM daily_insights WHERE module = 'lens_unified_snapshot'",
                ).use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
        }

    private fun summaryJson(): String? =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT summary_json FROM daily_insights WHERE module = 'lens_unified_snapshot'",
                ).use { resultSet ->
                    if (resultSet.next()) resultSet.getString(1) else null
                }
        }

    private fun reflectionRows(): List<Pair<String, Int>> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT reflection_type, is_addressed FROM lens_reflections").use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getString(1) to resultSet.getInt(2))
                }
            }
        }

    // ---- analytics passthrough ----

    @Test
    fun `the first tracked date is null with no entries and the earliest start otherwise`(): Unit =
        runBlocking {
            assertThat(lens().getFirstTrackedDate()).isNull()

            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.minusDays(4).atTime(9, 0),
                    endedAt = today.minusDays(4).atTime(10, 0),
                ),
            )
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.minusDays(1).atTime(9, 0),
                    endedAt = today.minusDays(1).atTime(10, 0),
                ),
            )

            assertThat(lens().getFirstTrackedDate()).isEqualTo(today.minusDays(4))
        }

    @Test
    fun `the daily analytics series are produced from the tracked entries`(): Unit =
        runBlocking {
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.atTime(9, 0),
                    endedAt = today.atTime(11, 0),
                    focusRating = 0.9,
                    dimensionId = "dim_work_livelihood",
                ),
            )

            val repository = lens()

            assertThat(repository.getDailyFocusAverages()).hasSize(1)
            assertThat(repository.getDailyTrackedTimeStats()).hasSize(1)
            assertThat(repository.getDailyFocusedHoursStats()).hasSize(1)
            assertThat(repository.getAverageDailyTimeTableData()).isNotNull()
            assertThat(repository.getHeatmapDays()).hasSize(1)
            assertThat(repository.getWeekGridData().days).isNotEmpty()
            assertThat(repository.getMinutePatternData().days).isNotEmpty()
            assertThat(repository.getDimensionTrendBlocks(windowDays = 7)).isNotEmpty()
            assertThat(repository.getDimensionSplitForRange(today, today))
                .containsKey("dim_work_livelihood")
        }

    // ---- snapshot: planning ----

    @Test
    fun `the budget dominates planned minutes and completeness records that a budget exists`(): Unit =
        runBlocking {
            dayPlanRepository().setAllocation(todayKey, "dim_work_livelihood", 300)
            createTask("A task of an hour", durationMinutes = 60)

            val planning = lens().calculatePlanningData(todayKey)

            // 300 from the budget, not 300 + 60: a budget replaces the task-derived total.
            assertThat(planning.totalPlannedMinutes).isEqualTo(300)
            assertThat(planning.budgetAllocationsByDimension["dim_work_livelihood"]).isEqualTo(300)
            assertThat(planning.plannedTasks).hasSize(1)
            assertThat(planning.planCompletenessScore).isGreaterThan(0f)
        }

    @Test
    fun `recurring tasks are planned as habits and one-off tasks as tasks`(): Unit =
        runBlocking {
            createTask("One-off", recurrenceEnabled = false)
            createTask("Habit", recurrenceEnabled = true)

            val planning = lens().calculatePlanningData(todayKey)

            assertThat(planning.plannedTasks.map { it.title }).containsExactly("One-off")
            assertThat(planning.plannedHabits.map { it.title }).containsExactly("Habit")
        }

    @Test
    fun `an archived task is excluded from the plan and a task due another day plays no part`(): Unit =
        runBlocking {
            val archived = createTask("Archived")
            taskRepository().archiveTask(archived.id)
            createTask("Tomorrow", dueDate = today.plusDays(1).atTime(9, 0))

            val planning = lens().calculatePlanningData(todayKey)

            assertThat(planning.plannedTasks).isEmpty()
            assertThat(planning.totalPlannedMinutes).isEqualTo(0)
        }

    // ---- snapshot: reality ----

    @Test
    fun `untracked minutes are the remainder of the day and the split counts each kind once`(): Unit =
        runBlocking {
            val task = createTask("Tracked task", durationMinutes = 60)
            val habit = createTask("Tracked habit", recurrenceEnabled = true)
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.atTime(9, 0),
                    endedAt = today.atTime(10, 0),
                    taskId = task.id,
                ),
            )
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.atTime(11, 0),
                    endedAt = today.atTime(12, 0),
                    taskId = habit.id,
                ),
            )
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Untracked",
                    startedAt = today.atTime(13, 0),
                    endedAt = today.atTime(13, 30),
                ),
            )

            val reality = lens().calculateRealityData(todayKey)

            assertThat(reality.actualTaskMinutes).isEqualTo(60)
            assertThat(reality.actualHabitMinutes).isEqualTo(60)
            assertThat(reality.actualTimeOnlyMinutes).isEqualTo(30)
            assertThat(reality.totalActualMinutes).isEqualTo(150)
            assertThat(reality.untrackedMinutes).isEqualTo(1440 - 150)
        }

    @Test
    fun `a habit without an entry is counted from its occurrence`(): Unit =
        runBlocking {
            val habit = createTask("Habit", recurrenceEnabled = true)
            occurrenceRepository().toggleOccurrence(
                taskId = habit.id,
                date = today,
                newStatus = "completed",
                note = null,
                reason = null,
                actualCompletedAt = null,
                actualDurationMinutes = 25,
            )

            val reality = lens().calculateRealityData(todayKey)

            assertThat(reality.supplementalActualMinutes).isEqualTo(25)
            assertThat(reality.supplementalActualByDimension["dim_work_livelihood"]).isEqualTo(25)
            assertThat(reality.totalActualMinutes).isEqualTo(25)
        }

    @Test
    fun `a habit with BOTH an entry and an occurrence is not counted twice`(): Unit =
        runBlocking {
            val habit = createTask("Habit", recurrenceEnabled = true)
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.atTime(9, 0),
                    endedAt = today.atTime(10, 0),
                    taskId = habit.id,
                ),
            )
            occurrenceRepository().toggleOccurrence(
                taskId = habit.id,
                date = today,
                newStatus = "completed",
                note = null,
                reason = null,
                actualCompletedAt = null,
                actualDurationMinutes = 60,
            )

            val reality = lens().calculateRealityData(todayKey)

            // The entry already accounts for those minutes; the occurrence is the same work.
            assertThat(reality.supplementalActualMinutes).isEqualTo(0)
            assertThat(reality.totalActualMinutes).isEqualTo(60)
        }

    @Test
    fun `an entry spanning midnight contributes only the minutes inside the day`(): Unit =
        runBlocking {
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.minusDays(1).atTime(23, 0),
                    endedAt = today.atTime(1, 0),
                ),
            )

            val reality = lens().calculateRealityData(todayKey)

            assertThat(reality.totalActualMinutes).isEqualTo(60)
        }

    @Test
    fun `a task's reality item carries its status, minutes and adherence gap`(): Unit =
        runBlocking {
            val task = createTask("Measured", durationMinutes = 90)
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.atTime(9, 0),
                    endedAt = today.atTime(10, 0),
                    taskId = task.id,
                ),
            )

            val item = lens().calculateRealityData(todayKey).completedTasks.single()

            assertThat(item.taskId).isEqualTo(task.id)
            assertThat(item.actualMinutes).isEqualTo(60)
            assertThat(item.adherenceGap).isEqualTo(30)
            assertThat(item.status).isEqualTo("pending")
        }

    // ---- snapshot: scores and the cache ----

    @Test
    fun `adherence is zero with no plan and credits an empty side rather than punishing it`(): Unit =
        runBlocking {
            assertThat(lens().calculateAdherence(todayKey)).isEqualTo(0f)

            // A budget with no tasks or habits: time component 0, task 0.3, habit 0.2.
            dayPlanRepository().setAllocation(todayKey, "dim_work_livelihood", 100)

            assertThat(lens().calculateAdherence(todayKey)).isEqualTo(0.5f)
        }

    @Test
    fun `a clean day reads its cached snapshot instead of recomputing`(): Unit =
        runBlocking {
            createTask("Planned")
            val first = lens().calculateUnifiedSnapshot(todayKey)
            assertThat(cacheRowCount()).isEqualTo(1)

            // The write goes straight to SQL, so nothing marks the day dirty. A repository write would mark it,
            // and the point here is what a CLEAN day does with a stale cache.
            insertTaskRowDirectly("behind-the-repos-back", "Added while nobody was looking")

            val second = lens().calculateUnifiedSnapshot(todayKey)

            assertThat(second.planning.plannedTasks).hasSize(1)
            assertThat(second.planning.plannedTasks).isEqualTo(first.planning.plannedTasks)
            assertThat(summaryJson()).isEqualTo(encodeUnifiedLensSnapshotForTest(first))
        }

    @Test
    fun `a dirty day recomputes and clears its marker`(): Unit =
        runBlocking {
            createTask("Planned")
            lens().calculateUnifiedSnapshot(todayKey)

            // A task write marks the day dirty through the task repository.
            createTask("Added and marked dirty")

            val recomputed = lens().calculateUnifiedSnapshot(todayKey)

            assertThat(recomputed.planning.plannedTasks).hasSize(2)
            assertThat(lens().isDayDirty(todayKey)).isFalse()
        }

    @Test
    fun `a day with no cache entry computes even when it is not dirty`(): Unit =
        runBlocking {
            createTask("Never snapshotted")
            // Creating a task marks its day dirty, so clear the marker: this is about a CLEAN day whose cache row
            // was never written, which must still compute rather than reporting an empty day forever.
            clearLensDayDirty(session, todayKey, io.payanam.shared.logging.AppLogger.current)
            assertThat(lens().isDayDirty(todayKey)).isFalse()

            assertThat(lens().calculateUnifiedSnapshot(todayKey).planning.plannedTasks).hasSize(1)
        }

    @Test
    fun `the dirty day key set is filtered to the marked days`(): Unit =
        runBlocking {
            createTask("Planned")
            assertThat(lens().getDirtyDayKeys(setOf(todayKey, today.minusDays(1).toString()))).contains(todayKey)
        }

    @Test
    fun `the plan completeness and adherence helpers delegate to the snapshot`(): Unit =
        runBlocking {
            dayPlanRepository().setAllocation(todayKey, "dim_work_livelihood", 1440)

            val completeness = lens().calculatePlanCompleteness(todayKey)

            assertThat(completeness).isGreaterThan(0f)
            assertThat(completeness).isAtMost(1f)
        }

    // ---- reflections ----

    @Test
    fun `a day with almost no tracked time gets an untracked-time card`(): Unit =
        runBlocking {
            createTask("Planned")

            lens().generateReflectionCards(todayKey)

            assertThat(reflectionRows().map { it.first }).contains("untracked_time")
        }

    @Test
    fun `a missed task and a dimension gap each produce a card`(): Unit =
        runBlocking {
            val task = createTask("Planned task", durationMinutes = 60)
            // The card keys off the TASK's status, not the occurrence's: a task nobody marked missed is not
            // reported as missed, however its occurrence was recorded.
            setTaskStatus(task.id, "missed")
            dayPlanRepository().setAllocation(todayKey, "dim_work_livelihood", 240)
            // Both writes above happened behind the repositories' backs, so mark the day dirty explicitly.
            markLensDayDirty(session, todayKey, setOf("task"), "test")

            lens().generateReflectionCards(todayKey)

            val types = reflectionRows().map { it.first }
            assertThat(types).contains("missed_task")
            assertThat(types).contains("dimension_gap")
        }

    @Test
    fun `regenerating replaces the cards rather than appending, and resets addressed state`(): Unit =
        runBlocking {
            createTask("Planned")
            lens().generateReflectionCards(todayKey)
            val firstCount = reflectionRows().size
            val addressedId =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM lens_reflections LIMIT 1").use { resultSet ->
                        resultSet.next()
                        resultSet.getString(1)
                    }
                }
            lens().markReflectionAddressed(addressedId, "accepted the gap")
            assertThat(reflectionRows().any { it.second == 1 }).isTrue()

            lens().generateReflectionCards(todayKey)

            assertThat(reflectionRows()).hasSize(firstCount)
            // Every regenerated card is unaddressed: the user addressed the card that was replaced.
            assertThat(reflectionRows().none { it.second == 1 }).isTrue()
        }

    @Test
    fun `marking a reflection addressed stores the note`(): Unit =
        runBlocking {
            createTask("Planned")
            lens().generateReflectionCards(todayKey)
            val id =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM lens_reflections LIMIT 1").use { resultSet ->
                        resultSet.next()
                        resultSet.getString(1)
                    }
                }

            lens().markReflectionAddressed(id, "I was at a wedding")

            val note =
                connection.prepareStatement("SELECT user_note FROM lens_reflections WHERE id = ?").use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getString(1)
                    }
                }
            assertThat(note).isEqualTo("I was at a wedding")
        }

    @Test
    fun `a day with nothing wrong produces no cards`(): Unit =
        runBlocking {
            // 1440 planned minutes and 1440 tracked, no missed tasks, no focus gap.
            dayPlanRepository().setAllocation(todayKey, "dim_work_livelihood", 1440)
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.atStartOfDay(),
                    endedAt = today.atStartOfDay().plusMinutes(1440),
                    dimensionId = "dim_work_livelihood",
                ),
            )

            lens().generateReflectionCards(todayKey)

            assertThat(reflectionRows()).isEmpty()
        }

    @Test
    fun `the reflection flow emits the stored cards`(): Unit =
        runBlocking {
            createTask("Planned")
            lens().generateReflectionCards(todayKey)

            val records = lens().observeReflections(todayKey).first()

            assertThat(records).isNotEmpty()
            assertThat(records.first().isAddressed).isFalse()
            assertThat(records.first().dayKey).isEqualTo(todayKey)
        }

    @Test
    fun `the reactive planning and reality flows emit`(): Unit =
        runBlocking {
            createTask("Planned")
            timeRepository().createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = "Work",
                    startedAt = today.atTime(9, 0),
                    endedAt = today.atTime(10, 0),
                ),
            )

            assertThat(lens().observePlanningData(todayKey).first().plannedTasks).hasSize(1)
            assertThat(lens().observeRealityData(todayKey).first().totalActualMinutes).isEqualTo(60)
        }
}
