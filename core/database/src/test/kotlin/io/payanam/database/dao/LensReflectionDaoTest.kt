//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.LensReflectionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
/**
 * Provides the lens reflection dao test.
 */
class LensReflectionDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var lensReflectionDao: LensReflectionDao

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
        lensReflectionDao = database.lensReflectionDao()
    }

    @After
    /**
     * Performs the tear down.
     */
    fun tearDown() {
        database.close()
    }

    @Test
    /**
     * Performs the insert reflection and observe.
     */
    fun insertReflection_and_observe() =
        runBlocking {
            val reflection = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            lensReflectionDao.insertReflection(reflection)
            val reflections = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(reflections).hasSize(1)
            assertThat(reflections[0].id).isEqualTo("ref-1")
            assertThat(reflections[0].reflectionType).isEqualTo("untracked_time")
        }

    @Test
    /**
     * Performs the insert reflections inserts multiple.
     */
    fun insertReflections_insertsMultiple() =
        runBlocking {
            val reflections =
                listOf(
                    createTestReflection("ref-1", "2026-02-08", "untracked_time"),
                    createTestReflection("ref-2", "2026-02-08", "missed_task"),
                    createTestReflection("ref-3", "2026-02-08", "focus_gap"),
                )
            lensReflectionDao.insertReflections(reflections)
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(retrieved).hasSize(3)
        }

    @Test
    /**
     * Registers the observe reflections for day filters by day.
     */
    fun observeReflectionsForDay_filtersByDay() =
        runBlocking {
            val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            val reflection2 = createTestReflection("ref-2", "2026-02-09", "missed_task")

            lensReflectionDao.insertReflection(reflection1)
            lensReflectionDao.insertReflection(reflection2)
            val day8Reflections = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(day8Reflections).hasSize(1)
            assertThat(day8Reflections[0].dayKey).isEqualTo("2026-02-08")
        }

    @Test
    /**
     * Performs the mark reflection addressed updates is addressed and note.
     */
    fun markReflectionAddressed_updatesIsAddressedAndNote() =
        runBlocking {
            val reflection = createTestReflection("ref-1", "2026-02-08", "missed_task")
            lensReflectionDao.insertReflection(reflection)

            lensReflectionDao.markReflectionAddressed("ref-1", "Rescheduled for tomorrow")
            val updated = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(updated[0].isAddressed).isEqualTo(1)
            assertThat(updated[0].userNote).isEqualTo("Rescheduled for tomorrow")
        }

    @Test
    /**
     * Performs the mark reflection addressed with null note.
     */
    fun markReflectionAddressed_withNullNote() =
        runBlocking {
            val reflection = createTestReflection("ref-1", "2026-02-08", "focus_gap")
            lensReflectionDao.insertReflection(reflection)

            lensReflectionDao.markReflectionAddressed("ref-1", null)
            val updated = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(updated[0].isAddressed).isEqualTo(1)
            assertThat(updated[0].userNote).isNull()
        }

    @Test
    /**
     * Removes the delete reflections for day removes only specified day.
     */
    fun deleteReflectionsForDay_removesOnlySpecifiedDay() =
        runBlocking {
            val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            val reflection2 = createTestReflection("ref-2", "2026-02-08", "missed_task")
            val reflection3 = createTestReflection("ref-3", "2026-02-09", "focus_gap")

            lensReflectionDao.insertReflection(reflection1)
            lensReflectionDao.insertReflection(reflection2)
            lensReflectionDao.insertReflection(reflection3)

            lensReflectionDao.deleteReflectionsForDay("2026-02-08")
            val day8Reflections = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            val day9Reflections = lensReflectionDao.observeReflectionsForDay("2026-02-09").first()
            assertThat(day8Reflections).isEmpty()
            assertThat(day9Reflections).hasSize(1)
        }

    @Test
    /**
     * Removes the delete old reflections removes old entries.
     */
    fun deleteOldReflections_removesOldEntries() =
        runBlocking {
            val oldReflection = createTestReflection("ref-1", "2026-01-01", "untracked_time", "2026-01-01T10:00:00")
            val recentReflection = createTestReflection("ref-2", "2026-02-08", "missed_task", "2026-02-08T10:00:00")

            lensReflectionDao.insertReflection(oldReflection)
            lensReflectionDao.insertReflection(recentReflection)

            lensReflectionDao.deleteOldReflections("2026-02-01T00:00:00")
            val allReflections = lensReflectionDao.observeReflectionsForDay("2026-01-01").first()
            val recentReflections = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(allReflections).isEmpty()
            assertThat(recentReflections).hasSize(1)
        }

    @Test
    /**
     * Performs the reflection with dimension stores and retrieves.
     */
    fun reflectionWithDimension_storesAndRetrieves() =
        runBlocking {
            val reflection =
                createTestReflection(
                    "ref-1",
                    "2026-02-08",
                    "dimension_gap",
                    dimensionId = "dim-health",
                )
            lensReflectionDao.insertReflection(reflection)
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(retrieved[0].dimensionId).isEqualTo("dim-health")
        }

    @Test
    /**
     * Performs the reflection with gap minutes stores and retrieves.
     */
    fun reflectionWithGapMinutes_storesAndRetrieves() =
        runBlocking {
            val reflection =
                createTestReflection(
                    "ref-1",
                    "2026-02-08",
                    "focus_gap",
                    gapMinutes = 120,
                )
            lensReflectionDao.insertReflection(reflection)
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(retrieved[0].gapMinutes).isEqualTo(120)
        }

    @Test
    /**
     * Performs the reflection with related entity stores and retrieves.
     */
    fun reflectionWithRelatedEntity_storesAndRetrieves() =
        runBlocking {
            val reflection =
                createTestReflection(
                    "ref-1",
                    "2026-02-08",
                    "missed_task",
                    relatedEntityId = "task-123",
                )
            lensReflectionDao.insertReflection(reflection)
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(retrieved[0].relatedEntityId).isEqualTo("task-123")
        }

    @Test
    /**
     * Observe reflections for day emits updates when new reflection added.
     */
    fun observeReflectionsForDay_emitsUpdatesWhenNewReflectionAdded() =
        runBlocking {
            val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            lensReflectionDao.insertReflection(reflection1)
            val initial = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(initial).hasSize(1)
            val reflection2 = createTestReflection("ref-2", "2026-02-08", "missed_task")
            lensReflectionDao.insertReflection(reflection2)
            val updated = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(updated).hasSize(2)
        }

    @Test
    /**
     * Performs the entity copy creates new instance with modified fields.
     */
    fun entityCopy_createsNewInstanceWithModifiedFields() =
        runBlocking {
            val original = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            lensReflectionDao.insertReflection(original)
            val copied =
                original.copy(
                    isAddressed = 1,
                    userNote = "Fixed by user",
                )
            assertThat(copied.id).isEqualTo(original.id)
            assertThat(copied.dayKey).isEqualTo(original.dayKey)
            assertThat(copied.isAddressed).isEqualTo(1)
            assertThat(copied.userNote).isEqualTo("Fixed by user")
        }

    @Test
    /**
     * Performs the entity equals returns true for same values.
     */
    fun entityEquals_returnsTrueForSameValues() {
        val timestamp = "2026-02-08T10:00:00"
        val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time", timestamp)
        val reflection2 = createTestReflection("ref-1", "2026-02-08", "untracked_time", timestamp)
        assertThat(reflection1).isEqualTo(reflection2)
    }

    @Test
    /**
     * Performs the entity hash code is same for equal entities.
     */
    fun entityHashCode_isSameForEqualEntities() {
        val timestamp = "2026-02-08T10:00:00"
        val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time", timestamp)
        val reflection2 = createTestReflection("ref-1", "2026-02-08", "untracked_time", timestamp)
        assertThat(reflection1.hashCode()).isEqualTo(reflection2.hashCode())
    }

    @Test
    /**
     * Performs the entity to string contains all fields.
     */
    fun entityToString_containsAllFields() {
        val reflection =
            createTestReflection(
                "ref-1",
                "2026-02-08",
                "missed_task",
                dimensionId = "dim-work",
                gapMinutes = 120,
            )
        val stringRep = reflection.toString()
        assertThat(stringRep).contains("ref-1")
        assertThat(stringRep).contains("2026-02-08")
        assertThat(stringRep).contains("missed_task")
        assertThat(stringRep).contains("dim-work")
    }

    @Test
    /**
     * Performs the entity component functions extract fields correctly.
     */
    fun entityComponentFunctions_extractFieldsCorrectly() {
        val reflection =
            createTestReflection(
                "ref-1",
                "2026-02-08",
                "focus_gap",
                dimensionId = "dim-health",
                gapMinutes = 90,
            )

        // Only destructure first 3 components to avoid Detekt warning
        val id = reflection.component1()
        val dayKey = reflection.component2()
        val dimensionId = reflection.component3()
        assertThat(id).isEqualTo("ref-1")
        assertThat(dayKey).isEqualTo("2026-02-08")
        assertThat(dimensionId).isEqualTo("dim-health")
        assertThat(reflection.reflectionType).isEqualTo("focus_gap")
        assertThat(reflection.title).contains("focus_gap")
    }

    @Test
    /**
     * Performs the entity with all nullable fields handles nulls correctly.
     */
    fun entityWithAllNullableFields_handlesNullsCorrectly() =
        runBlocking {
            val reflection =
                LensReflectionEntity(
                    id = "ref-1",
                    dayKey = "2026-02-08",
                    dimensionId = null,
                    reflectionType = "untracked_time",
                    title = "Test",
                    description = null,
                    gapMinutes = null,
                    relatedEntityId = null,
                    isAddressed = 0,
                    userNote = null,
                    createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                )

            lensReflectionDao.insertReflection(reflection)
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(retrieved[0].dimensionId).isNull()
            assertThat(retrieved[0].description).isNull()
            assertThat(retrieved[0].gapMinutes).isNull()
            assertThat(retrieved[0].relatedEntityId).isNull()
            assertThat(retrieved[0].userNote).isNull()
        }

    @Test
    /**
     * Performs the entity with minimal required params uses defaults.
     */
    fun entityWithMinimalRequiredParams_usesDefaults() =
        runBlocking {
            // Create entity using only required params, letting defaults kick in
            val reflection =
                LensReflectionEntity(
                    id = "ref-minimal",
                    dayKey = "2026-02-08",
                    reflectionType = "untracked_time",
                    title = "Minimal Test",
                    createdAt = "2026-02-08T10:00:00",
                )

            lensReflectionDao.insertReflection(reflection)
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            assertThat(retrieved).hasSize(1)
            assertThat(retrieved[0].id).isEqualTo("ref-minimal")
            assertThat(retrieved[0].dimensionId).isNull()
            assertThat(retrieved[0].description).isNull()
            assertThat(retrieved[0].gapMinutes).isNull()
            assertThat(retrieved[0].relatedEntityId).isNull()
            assertThat(retrieved[0].isAddressed).isEqualTo(0)
            assertThat(retrieved[0].userNote).isNull()
        }

    private fun createTestReflection(
        id: String,
        dayKey: String,
        reflectionType: String,
        createdAt: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        dimensionId: String? = null,
        gapMinutes: Int? = null,
        relatedEntityId: String? = null,
    ) = LensReflectionEntity(
        id = id,
        dayKey = dayKey,
        dimensionId = dimensionId,
        reflectionType = reflectionType,
        title = "Test Reflection: $reflectionType",
        description = "Test description for $reflectionType",
        gapMinutes = gapMinutes,
        relatedEntityId = relatedEntityId,
        isAddressed = 0,
        userNote = null,
        createdAt = createdAt,
    )
}
