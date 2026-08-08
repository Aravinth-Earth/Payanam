//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.backfill

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Inc 3 cascade + catch-up integration tests against an in-memory Room DB.
 * Covers: toggle → L1 today row + bridge score; L2/L3 refresh; missed-gap
 * catch-up fills 0.0 rows; no-gap launch skips; stale tail removal.
 */
@RunWith(RobolectricTestRunner::class)
class ScoreRollupCascadeServiceTest {

    private lateinit var db: PayanamDatabase
    private lateinit var sessionManager: DatabaseSessionManager
    private lateinit var service: ScoreRollupCascadeService
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
        db = Room.inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val encryptionManager = io.payanam.database.security.DatabaseEncryptionManager(context)
        sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(db)
        service = ScoreRollupCascadeService(sessionManager)
        seedLifeDimensions()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seedLifeDimensions() {
        val now = "2026-07-01T06:00:00"
        val stmt = "INSERT OR IGNORE INTO life_dimensions (id, key, label, description, color, icon, sortOrder, isActive, createdAt, updatedAt) VALUES (?, ?, ?, NULL, ?, NULL, ?, 1, ?, ?)"
        db.openHelper.writableDatabase.apply {
            execSQL(stmt, arrayOf<Any>("dim_health", "health", "Health", "#FF0000", 1, now, now))
            execSQL(stmt, arrayOf<Any>("dim_x", "x", "Dim X", "#00FF00", 2, now, now))
        }
    }

    private suspend fun insertTask(
        id: String,
        rule: String = "CONFIG:type=DAILY",
        dimensionId: String? = "dim_health",
    ) {
        db.taskDao().insert(
            TaskEntity(
                id = id,
                title = "Habit $id",
                status = "pending",
                createdAt = "2026-07-01T06:00:00",
                updatedAt = "2026-07-01T06:00:00",
                recurrenceEnabled = 1,
                recurrenceRule = rule,
                dimensionId = dimensionId,
            ),
        )
    }

    private suspend fun insertOccurrence(taskId: String, date: LocalDate, status: String = "completed") {
        db.taskOccurrenceDao().insert(
            TaskOccurrenceEntity(
                id = "$taskId-$date",
                taskId = taskId,
                dueDate = date.toString(),
                status = status,
                createdAt = LocalDateTime.now().toString(),
            ),
        )
    }

    @Test
    fun `toggle today creates L1 row and bridges currentScore`() = runTest {
        insertTask("h1")
        val today = LocalDate.now()
        insertOccurrence("h1", today)

        service.recalcForStatusChange("h1", today)

        val rows = db.habitMetricDao().observeForHabit("h1").first()
        assertTrue("L1 rows exist", rows.isNotEmpty())
        assertEquals(today.toString(), rows.last().dayKey)
        assertEquals(1.0, rows.last().score, 1e-9)
        val task = db.taskDao().getTaskById("h1")
        assertEquals(rows.last().runningAvg, task!!.currentScore, 1e-9) // bridge
    }

    @Test
    fun `missed day scores zero`() = runTest {
        insertTask("h2")
        val yesterday = LocalDate.now().minusDays(1)
        // Real toggle flow: a missed occurrence exists (toggleOccurrence with
        // status=missed). Missed status is NOT counted as 1.0 → 0.0 row.
        insertOccurrence("h2", LocalDate.now().minusDays(2), status = "completed")
        insertOccurrence("h2", yesterday, status = "missed")

        service.recalcForStatusChange("h2", yesterday)

        val rows = db.habitMetricDao().observeForHabit("h2").first()
        val yRow = rows.firstOrNull { it.dayKey == yesterday.toString() }
        assertEquals(0.0, yRow?.score ?: -1.0, 1e-9)
    }

    @Test
    fun `toggle refreshes dimension and day tails`() = runTest {
        insertTask("h3", dimensionId = "dim_x")
        val today = LocalDate.now()
        insertOccurrence("h3", today)

        service.recalcForStatusChange("h3", today)

        val dimRows = db.dimensionMetricDao().observeForDimension("dim_x").first()
        assertTrue("dimension rows exist", dimRows.isNotEmpty())
        assertEquals(today.toString(), dimRows.last().dayKey)
        val dayRows = db.dayMetricDao().observeAll().first()
        assertTrue("day rows exist", dayRows.isNotEmpty())
        assertEquals(today.toString(), dayRows.last().dayKey)
    }

    @Test
    fun `catchUp fills missed gap with zero rows`() = runTest {
        insertTask("h4")
        // Backfill-ish history: completed 5 days ago, then a gap until yesterday.
        val start = LocalDate.now().minusDays(5)
        insertOccurrence("h4", start)
        // Rebuild full L1 via cascade (creates history through yesterday)
        service.recalcForStatusChange("h4", start)

        val before = db.habitMetricDao().observeForHabit("h4").first()
        val beforeMax = before.maxOfOrNull { it.dayKey }!!
        // Simulate stale state: delete tail rows to mimic "no rows for recent days"
        db.habitMetricDao().deleteFrom("h4", LocalDate.now().minusDays(2).toString())

        service.catchUpTail()

        val after = db.habitMetricDao().observeForHabit("h4").first()
        val afterMax = after.maxOfOrNull { it.dayKey }!!
        assertEquals(LocalDate.now().minusDays(1).toString(), afterMax) // extended through yesterday
        // gap days (not completed) score 0.0
        val gap = after.firstOrNull { it.dayKey == LocalDate.now().minusDays(3).toString() }
        assertEquals(0.0, gap?.score ?: -1.0, 1e-9)
    }

    @Test
    fun `catchUp no-gap launch leaves rows untouched`() = runTest {
        insertTask("h5")
        val today = LocalDate.now()
        insertOccurrence("h5", today)
        service.recalcForStatusChange("h5", today)

        val before = db.habitMetricDao().observeForHabit("h5").first()
        service.catchUpTail()
        val after = db.habitMetricDao().observeForHabit("h5").first()
        assertEquals(before.size, after.size)
    }

    @Test
    fun `recalc deletes stale tail rows`() = runTest {
        insertTask("h6")
        val today = LocalDate.now()
        insertOccurrence("h6", today)
        // Seed a stale row on a future-ish day (should be removed by rebuild)
        db.habitMetricDao().upsert(
            HabitMetricEntity("h6", LocalDate.now().plusDays(1).toString(), 0.5, 0.5, 0.0, 0, 0, 0),
        )

        service.recalcForStatusChange("h6", today)

        val rows = db.habitMetricDao().observeForHabit("h6").first()
        assertTrue("stale future row removed", rows.none { it.dayKey > today.toString() })
    }

    @Test
    fun `rule change rebuild removes old grid rows`() = runTest {
        insertTask("h7", rule = "CONFIG:type=DAILY")
        val today = LocalDate.now()
        insertOccurrence("h7", today.minusDays(1))
        insertOccurrence("h7", today)
        // Build daily-grid rows first
        service.recalcForStatusChange("h7", today)

        // Rule changes: daily → specific weekdays (Mon-Fri only). Old daily rows
        // from weekend days are stale and must be removed by the full rebuild.
        db.taskDao().updateRecurrenceRule("h7", "CONFIG:type=WEEKDAYS_ONLY")
        service.recalcForRuleChange("h7")

        val rows = db.habitMetricDao().observeForHabit("h7").first()
        val weekend = rows.filter { it.dayKey < today.toString() && !isWeekday(it.dayKey) }
        assertTrue("weekend rows removed after rule change", weekend.isEmpty())
    }

    private fun isWeekday(dayKey: String): Boolean {
        val d = LocalDate.parse(dayKey)
        return d.dayOfWeek.value in 1..5
    }
}
