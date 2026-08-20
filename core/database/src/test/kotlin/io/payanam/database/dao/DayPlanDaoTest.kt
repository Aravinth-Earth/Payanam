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
 * DayPlanDaoTest.
 */
class DayPlanDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var dayPlanDao: DayPlanDao

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
        dayPlanDao = database.dayPlanDao()
        /** Seed life dimensions. */
        seedLifeDimensions()
    }

    private fun seedLifeDimensions() {
        /** Db. */
        val db = database.openHelper.writableDatabase
        /** Now. */
        val now = "2026-01-01T00:00:00"
        /** Dims. */
        val dims = listOf("career_work", "health_wellness", "learning", "relationships", "personal_growth")
        dims.forEachIndexed { index, id ->
            db.execSQL(
                """INSERT OR IGNORE INTO life_dimensions (id, key, label, color, sortOrder, isActive, weight, createdAt, updatedAt)
                   /** Values. */
                   VALUES ('$id', '$id', '$id', '#FF5722', $index, 1, 1.0, '$now', '$now')""",
            )
        }
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        database.close()
    }

    // ---- Day Plan Allocation Tests ----

    @Test
    /**
     * Insert allocation and get allocations for day.
     */
    fun insertAllocation_and_getAllocationsForDay() =
        runBlocking {
            /** Entity. */
            val entity = createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120)
            dayPlanDao.insertAllocation(entity)

            /** Allocations. */
            val allocations = dayPlanDao.getAllocationsForDay("2026-02-09")
            /** Assert that. */
            assertThat(allocations).hasSize(1)
            /** Assert that. */
            assertThat(allocations[0].dimensionId).isEqualTo("career_work")
            /** Assert that. */
            assertThat(allocations[0].plannedMinutes).isEqualTo(120)
        }

    @Test
    /**
     * Observe allocations for day emits updates.
     */
    fun observeAllocationsForDay_emitsUpdates() =
        runBlocking {
            /** Entity. */
            val entity = createAllocation(dayKey = "2026-02-09", dimensionId = "health_wellness", plannedMinutes = 60)
            dayPlanDao.insertAllocation(entity)

            /** Allocations. */
            val allocations = dayPlanDao.observeAllocationsForDay("2026-02-09").first()
            /** Assert that. */
            assertThat(allocations).hasSize(1)
            /** Assert that. */
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

            /** Allocation. */
            val allocation = dayPlanDao.getAllocationForDayAndDimension("2026-02-09", "career_work")
            /** Assert that. */
            assertThat(allocation).isNotNull()
            /** Assert that. */
            assertThat(allocation?.plannedMinutes).isEqualTo(120)

            /** Missing. */
            val missing = dayPlanDao.getAllocationForDayAndDimension("2026-02-09", "nonexistent")
            /** Assert that. */
            assertThat(missing).isNull()
        }

    @Test
    /**
     * Get allocations for range returns range results.
     */
    fun getAllocationsForRange_returnsRangeResults() =
        runBlocking {
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-07", dimensionId = "career_work", plannedMinutes = 100))
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-08", dimensionId = "career_work", plannedMinutes = 110))
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120))
            dayPlanDao.insertAllocation(createAllocation(dayKey = "2026-02-10", dimensionId = "career_work", plannedMinutes = 130))

            /** Range. */
            val range = dayPlanDao.getAllocationsForRange("2026-02-08", "2026-02-09")
            /** Assert that. */
            assertThat(range).hasSize(2)
        }

    @Test
    /**
     * Insert allocations batch.
     */
    fun insertAllocations_batch() =
        runBlocking {
            /** Entities. */
            val entities =
                /** List of. */
                listOf(
                    /** Create allocation. */
                    createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120),
                    /** Create allocation. */
                    createAllocation(dayKey = "2026-02-09", dimensionId = "health_wellness", plannedMinutes = 60),
                    /** Create allocation. */
                    createAllocation(dayKey = "2026-02-09", dimensionId = "learning", plannedMinutes = 90),
                )
            dayPlanDao.insertAllocations(entities)

            /** Allocations. */
            val allocations = dayPlanDao.getAllocationsForDay("2026-02-09")
            /** Assert that. */
            assertThat(allocations).hasSize(3)
        }

    @Test
    /**
     * Delete allocations for day removes all.
     */
    fun deleteAllocationsForDay_removesAll() =
        runBlocking {
            dayPlanDao.insertAllocations(
                /** List of. */
                listOf(
                    /** Create allocation. */
                    createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120),
                    /** Create allocation. */
                    createAllocation(dayKey = "2026-02-09", dimensionId = "health_wellness", plannedMinutes = 60),
                ),
            )

            dayPlanDao.deleteAllocationsForDay("2026-02-09")

            /** Allocations. */
            val allocations = dayPlanDao.getAllocationsForDay("2026-02-09")
            /** Assert that. */
            assertThat(allocations).isEmpty()
        }

    @Test
    /**
     * Get planned days returns distinct days.
     */
    fun getPlannedDays_returnsDistinctDays() =
        runBlocking {
            dayPlanDao.insertAllocations(
                /** List of. */
                listOf(
                    /** Create allocation. */
                    createAllocation(dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120),
                    /** Create allocation. */
                    createAllocation(dayKey = "2026-02-09", dimensionId = "health_wellness", plannedMinutes = 60),
                    /** Create allocation. */
                    createAllocation(dayKey = "2026-02-08", dimensionId = "career_work", plannedMinutes = 100),
                ),
            )

            /** Days. */
            val days = dayPlanDao.getPlannedDays(10)
            /** Assert that. */
            assertThat(days).hasSize(2)
            /** Assert that. */
            assertThat(days).containsExactly("2026-02-09", "2026-02-08")
            /** Unit. */
            Unit
        }

    // ---- Template Tests ----

    @Test
    /**
     * Insert template and get template by id.
     */
    fun insertTemplate_and_getTemplateById() =
        runBlocking {
            /** Template. */
            val template = createTemplate(id = "t1", name = "Work Day")
            dayPlanDao.insertTemplate(template)

            /** Retrieved. */
            val retrieved = dayPlanDao.getTemplateById("t1")
            /** Assert that. */
            assertThat(retrieved).isNotNull()
            /** Assert that. */
            assertThat(retrieved?.name).isEqualTo("Work Day")
        }

    @Test
    /**
     * Observe active templates filters inactive.
     */
    fun observeActiveTemplates_filtersInactive() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day", isActive = 1))
            dayPlanDao.insertTemplate(createTemplate(id = "t2", name = "Leave Day", isActive = 0))
            dayPlanDao.insertTemplate(createTemplate(id = "t3", name = "Travel Day", isActive = 1))

            /** Active. */
            val active = dayPlanDao.observeActiveTemplates().first()
            /** Assert that. */
            assertThat(active).hasSize(2)
            /** Assert that. */
            assertThat(active.map { it.name }).containsExactly("Work Day", "Travel Day")
            /** Unit. */
            Unit
        }

    @Test
    /**
     * Observe all templates returns all.
     */
    fun observeAllTemplates_returnsAll() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day", isActive = 1))
            dayPlanDao.insertTemplate(createTemplate(id = "t2", name = "Leave Day", isActive = 0))

            /** All. */
            val all = dayPlanDao.observeAllTemplates().first()
            /** Assert that. */
            assertThat(all).hasSize(2)
        }

    @Test
    /**
     * Get active template count counts active.
     */
    fun getActiveTemplateCount_countsActive() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day", isActive = 1))
            dayPlanDao.insertTemplate(createTemplate(id = "t2", name = "Leave Day", isActive = 0))
            dayPlanDao.insertTemplate(createTemplate(id = "t3", name = "Travel Day", isActive = 1))

            /** Count. */
            val count = dayPlanDao.getActiveTemplateCount()
            /** Assert that. */
            assertThat(count).isEqualTo(2)
        }

    @Test
    /**
     * Soft delete template sets inactive.
     */
    fun softDeleteTemplate_setsInactive() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day", isActive = 1))

            dayPlanDao.softDeleteTemplate("t1", "2026-02-09T12:00:00")

            /** Template. */
            val template = dayPlanDao.getTemplateById("t1")
            /** Assert that. */
            assertThat(template?.isActive).isEqualTo(0)
        }

    @Test
    /**
     * Delete template removes completely.
     */
    fun deleteTemplate_removesCompletely() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day"))

            dayPlanDao.deleteTemplate("t1")

            /** Template. */
            val template = dayPlanDao.getTemplateById("t1")
            /** Assert that. */
            assertThat(template).isNull()
        }

    // ---- Template Allocation Tests ----

    @Test
    /**
     * Insert template allocations and get template allocations.
     */
    fun insertTemplateAllocations_and_getTemplateAllocations() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day"))
            /** Allocations. */
            val allocations =
                /** List of. */
                listOf(
                    /** Create template allocation. */
                    createTemplateAllocation(templateId = "t1", dimensionId = "career_work", plannedMinutes = 240),
                    /** Create template allocation. */
                    createTemplateAllocation(templateId = "t1", dimensionId = "health_wellness", plannedMinutes = 60),
                )
            dayPlanDao.insertTemplateAllocations(allocations)

            /** Retrieved. */
            val retrieved = dayPlanDao.getTemplateAllocations("t1")
            /** Assert that. */
            assertThat(retrieved).hasSize(2)
        }

    @Test
    /**
     * Observe template allocations emits allocations.
     */
    fun observeTemplateAllocations_emitsAllocations() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day"))
            dayPlanDao.insertTemplateAllocations(
                /** List of. */
                listOf(
                    /** Create template allocation. */
                    createTemplateAllocation(templateId = "t1", dimensionId = "career_work", plannedMinutes = 240),
                ),
            )

            /** Allocations. */
            val allocations = dayPlanDao.observeTemplateAllocations("t1").first()
            /** Assert that. */
            assertThat(allocations).hasSize(1)
            /** Assert that. */
            assertThat(allocations[0].plannedMinutes).isEqualTo(240)
        }

    @Test
    /**
     * Delete template allocations removes for template.
     */
    fun deleteTemplateAllocations_removesForTemplate() =
        runBlocking {
            dayPlanDao.insertTemplate(createTemplate(id = "t1", name = "Work Day"))
            dayPlanDao.insertTemplate(createTemplate(id = "t2", name = "Leave Day"))
            dayPlanDao.insertTemplateAllocations(
                /** List of. */
                listOf(
                    /** Create template allocation. */
                    createTemplateAllocation(templateId = "t1", dimensionId = "career_work", plannedMinutes = 240),
                    /** Create template allocation. */
                    createTemplateAllocation(templateId = "t2", dimensionId = "health_wellness", plannedMinutes = 120),
                ),
            )

            dayPlanDao.deleteTemplateAllocations("t1")

            /** Assert that. */
            assertThat(dayPlanDao.getTemplateAllocations("t1")).isEmpty()
            /** Assert that. */
            assertThat(dayPlanDao.getTemplateAllocations("t2")).hasSize(1)
        }

    @Test
    /**
     * Insert allocation replaces on conflict.
     */
    fun insertAllocation_replacesOnConflict() =
        runBlocking {
            /** Entity. */
            val entity = createAllocation(id = "a1", dayKey = "2026-02-09", dimensionId = "career_work", plannedMinutes = 120)
            dayPlanDao.insertAllocation(entity)

            dayPlanDao.insertAllocation(entity.copy(plannedMinutes = 200))

            /** Allocations. */
            val allocations = dayPlanDao.getAllocationsForDay("2026-02-09")
            /** Assert that. */
            assertThat(allocations).hasSize(1)
            /** Assert that. */
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
