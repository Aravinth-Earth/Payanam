//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.DayPlanAllocationEntity
import io.payanam.database.entity.DayPlanTemplateAllocationEntity
import io.payanam.database.entity.DayPlanTemplateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * Provides the day plan dao test.
 */
class DayPlanDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var dayPlanDao: DayPlanDao

    @Before
    /**
     * Updates the setup.
     */
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        dayPlanDao = database.dayPlanDao()
        seedLifeDimensions()
    }

    private fun seedLifeDimensions() {
        val db = database.openHelper.writableDatabase
        val now = "2026-01-01T00:00:00"
        val dims = listOf("career_work", "health_wellness", "learning", "relationships", "personal_growth")
        dims.forEachIndexed { index, id ->
            db.execSQL(
                """INSERT OR IGNORE INTO life_dimensions (id, key, label, color, sortOrder, isActive, weight, createdAt, updatedAt)
                   VALUES ('$id', '$id', '$id', '#FF5722', $index, 1, 1.0, '$now', '$now')""",
            )
        }
    }

    @After
    /**
     * Performs the tear down.
     */
    fun tearDown() {
        database.close()
    }

    // ---- Day Plan Allocation Tests ----

    @Test
    /**
     * Performs the insert allocation and get allocations for day.
     */
    fun insertAllocation_and_getAllocationsForDay() =
        runBlocking {
            val entity = createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120)
            dayPlanDao.insertAllocation(entity)
            val allocations = dayPlanDao.getAllocationsForDay("2026-02-09")
            assertThat(allocations).hasSize(1)
            assertThat(allocations[0].dimensionId).isEqualTo("career_work")
            assertThat(allocations[0].plannedMinutes).isEqualTo(120)
        }

    @Test
    /**
     * Registers the observe allocations for day emits updates.
     */
    fun observeAllocationsForDay_emitsUpdates() =
        runBlocking {
            val entity = createAllocation(dayKey = "2026-02-09", dimensionId = "health_wellness", plannedMinutes = 60)
            dayPlanDao.insertAllocation(entity)
            val allocations = dayPlanDao.observeAllocationsForDay("2026-02-09").first()
            assertThat(allocations).hasSize(1)
            assertThat(allocations[0].plannedMinutes).isEqualTo(60)
        }

    @Test
    /**
     * Get allocation for day and dimension returns correct allocation.
     */
    fun getAllocationForDayAndDimension_returnsCorrectAllocation() =
        runBlocking {
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120))
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-09", dimensionId = "health_wellness", plannedMinutes = 60))
            val allocation = dayPlanDao.getAllocationForDayAndDimension("2026-02-09", "career_work")
            assertThat(allocation).isNotNull()
            assertThat(allocation?.plannedMinutes).isEqualTo(120)
            val missing = dayPlanDao.getAllocationForDayAndDimension("2026-02-09", "nonexistent")
            assertThat(missing).isNull()
        }

    @Test
    /**
     * Returns the get allocations for range returns range results.
     */
    fun getAllocationsForRange_returnsRangeResults() =
        runBlocking {
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-07", dimensionId = "career_work", plannedMinutes = 100))
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-08", dimensionId = "career_work", plannedMinutes = 110))
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120))
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-10", dimensionId = "career_work", plannedMinutes = 130))
            val range = dayPlanDao.getAllocationsForRange("2026-02-08", "2026-02-09")
            assertThat(range).hasSize(2)
        }

    @Test
    /**
     * Performs the insert allocations batch.
     */
    fun insertAllocations_batch() =
        runBlocking {
            val entities =
                listOf(
                    createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120),
                    createAllocation(dayKey = "2026-02-09", dimensionId = "health_wellness", plannedMinutes = 60),
                    createAllocation(dayKey = "2026-02-09", dimensionId = "learning", plannedMinutes = 90),
                )
            dayPlanDao.insertAllocations(entities)
            val allocations = dayPlanDao.getAllocationsForDay("2026-02-09")
            assertThat(allocations).hasSize(3)
        }

    @Test
    /**
     * Removes the delete allocations for day removes all.
     */
    fun deleteAllocationsForDay_removesAll() =
        runBlocking {
            dayPlanDao.insertAllocations(
                listOf(
                    createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120),
                    createAllocation(dayKey = "2026-02-09", dimensionId = "health_wellness", plannedMinutes = 60),
                ),
            )

            dayPlanDao.deleteAllocationsForDay("2026-02-09")
            val allocations = dayPlanDao.getAllocationsForDay("2026-02-09")
            assertThat(allocations).isEmpty()
        }

    @Test
    /**
     * Returns the get planned days returns distinct days.
     */
    fun getPlannedDays_returnsDistinctDays() =
        runBlocking {
            dayPlanDao.insertAllocations(
                listOf(
                    createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120),
                    createAllocation(dayKey = "2026-02-09", dimensionId = "health_wellness", plannedMinutes = 60),
                    createAllocation(dayKey = "2026-02-08", dimensionId = "career_work", plannedMinutes = 100),
                ),
            )
            val days = dayPlanDao.getPlannedDays(10)
            assertThat(days).hasSize(2)
            assertThat(days).containsExactly("2026-02-09", "2026-02-08")
            Unit
        }

    // ---- Template Tests ----

    @Test
    /**
     * Performs the insert template and get template by id.
     */
    fun insertTemplate_and_getTemplateById() =
        runBlocking {
            val template = createTemplate(id = "t1", name = "Work Day")
            dayPlanDao.insertTemplate(template)
            val retrieved = dayPlanDao.getTemplateById("t1")
            assertThat(retrieved).isNotNull()
            assertThat(retrieved?.name).isEqualTo("Work Day")
        }

    @Test
    /**
     * Registers the observe active templates filters inactive.
     */
    fun observeActiveTemplates_filtersInactive() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day", isActive = 1))
            dayPlanDao.insertTemplate(createTemplate(id = "t2", name = "Leave Day", isActive = 0))
            dayPlanDao.insertTemplate(createTemplate(id = "t3", name = "Travel Day", isActive = 1))
            val active = dayPlanDao.observeActiveTemplates().first()
            assertThat(active).hasSize(2)
            assertThat(active.map { it.name }).containsExactly("Work Day", "Travel Day")
            Unit
        }

    @Test
    /**
     * Registers the observe all templates returns all.
     */
    fun observeAllTemplates_returnsAll() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day", isActive = 1))
            dayPlanDao.insertTemplate(createTemplate(id = "t2", name = "Leave Day", isActive = 0))
            val all = dayPlanDao.observeAllTemplates().first()
            assertThat(all).hasSize(2)
        }

    @Test
    /**
     * Returns the get active template count counts active.
     */
    fun getActiveTemplateCount_countsActive() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day", isActive = 1))
            dayPlanDao.insertTemplate(createTemplate(id = "t2", name = "Leave Day", isActive = 0))
            dayPlanDao.insertTemplate(createTemplate(id = "t3", name = "Travel Day", isActive = 1))
            val count = dayPlanDao.getActiveTemplateCount()
            assertThat(count).isEqualTo(2)
        }

    @Test
    /**
     * Performs the soft delete template sets inactive.
     */
    fun softDeleteTemplate_setsInactive() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day", isActive = 1))

            dayPlanDao.softDeleteTemplate("t1", "2026-02-09T12:00:00")
            val template = dayPlanDao.getTemplateById("t1")
            assertThat(template?.isActive).isEqualTo(0)
        }

    @Test
    /**
     * Removes the delete template removes completely.
     */
    fun deleteTemplate_removesCompletely() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day"))

            dayPlanDao.deleteTemplate("t1")
            val template = dayPlanDao.getTemplateById("t1")
            assertThat(template).isNull()
        }

    // ---- Template Allocation Tests ----

    @Test
    /**
     * Performs the insert template allocations and get template allocations.
     */
    fun insertTemplateAllocations_and_getTemplateAllocations() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day"))
            val allocations =
                listOf(
                    createTemplateAllocation(templateId = "t1", dimensionId = "career_work", plannedMinutes = 240),
                    createTemplateAllocation(templateId = "t1", dimensionId = "health_wellness", plannedMinutes = 60),
                )
            dayPlanDao.insertTemplateAllocations(allocations)
            val retrieved = dayPlanDao.getTemplateAllocations("t1")
            assertThat(retrieved).hasSize(2)
        }

    @Test
    /**
     * Registers the observe template allocations emits allocations.
     */
    fun observeTemplateAllocations_emitsAllocations() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day"))
            dayPlanDao.insertTemplateAllocations(
                listOf(
                    createTemplateAllocation(templateId = "t1", dimensionId = "career_work", plannedMinutes = 240),
                ),
            )
            val allocations = dayPlanDao.observeTemplateAllocations("t1").first()
            assertThat(allocations).hasSize(1)
            assertThat(allocations[0].plannedMinutes).isEqualTo(240)
        }

    @Test
    /**
     * Removes the delete template allocations removes for template.
     */
    fun deleteTemplateAllocations_removesForTemplate() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day"))
            dayPlanDao.insertTemplate(createTemplate(id = "t2", name = "Leave Day"))
            dayPlanDao.insertTemplateAllocations(
                listOf(
                    createTemplateAllocation(templateId = "t1", dimensionId = "career_work", plannedMinutes = 240),
                    createTemplateAllocation(templateId = "t2", dimensionId = "health_wellness", plannedMinutes = 120),
                ),
            )

            dayPlanDao.deleteTemplateAllocations("t1")
            assertThat(dayPlanDao.getTemplateAllocations("t1")).isEmpty()
            assertThat(dayPlanDao.getTemplateAllocations("t2")).hasSize(1)
        }

    @Test
    /**
     * Performs the insert allocation replaces on conflict.
     */
    fun insertAllocation_replacesOnConflict() =
        runBlocking {
            val entity = createAllocation(id = "a1", dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120)
            dayPlanDao.insertAllocation(entity)

            dayPlanDao.insertAllocation(entity.copy(plannedMinutes = 200))
            val allocations = dayPlanDao.getAllocationsForDay("2026-02-09")
            assertThat(allocations).hasSize(1)
            assertThat(allocations[0].plannedMinutes).isEqualTo(200)
        }

    // ---- Helpers ----

    private var allocationCounter = 0

    private fun createAllocation(
        id: String = "alloc_${allocationCounter++}",
        dayKey: String = "2026-02-09",
        dimensionId: String = "career_work",
        plannedMinutes: Int = 120,
        source: String = "manual",
        templateId: String? = null,
    ) = DayPlanAllocationEntity(
        id = id,
        dayKey = dayKey,
        dimensionId = dimensionId,
        plannedMinutes = plannedMinutes,
        source = source,
        templateId = templateId,
        createdAt = "2026-02-09T09:00:00",
        updatedAt = "2026-02-09T09:00:00",
    )

    private var templateCounter = 0

    private fun createTemplate(
        id: String = "template_${templateCounter++}",
        name: String = "Template",
        description: String? = null,
        isActive: Int = 1,
        sortOrder: Int = 0,
    ) = DayPlanTemplateEntity(
        id = id,
        name = name,
        description = description,
        isActive = isActive,
        sortOrder = sortOrder,
        createdAt = "2026-02-09T09:00:00",
        updatedAt = "2026-02-09T09:00:00",
    )

    private var templateAllocCounter = 0

    private fun createTemplateAllocation(
        id: String = "talloc_${templateAllocCounter++}",
        templateId: String = "t1",
        dimensionId: String = "career_work",
        plannedMinutes: Int = 120,
    ) = DayPlanTemplateAllocationEntity(
        id = id,
        templateId = templateId,
        dimensionId = dimensionId,
        plannedMinutes = plannedMinutes,
        createdAt = "2026-02-09T09:00:00",
        updatedAt = "2026-02-09T09:00:00",
    )
}
