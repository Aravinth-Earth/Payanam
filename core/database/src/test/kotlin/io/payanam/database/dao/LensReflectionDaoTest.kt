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
 * LensReflectionDaoTest.
 */
class LensReflectionDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var lensReflectionDao: LensReflectionDao

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
        lensReflectionDao = database.lensReflectionDao()
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
     * Insert reflection and observe.
     */
    fun insertReflection_and_observe() =
        runBlocking {
            /** Reflection. */
            val reflection = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            lensReflectionDao.insertReflection(reflection)

            /** Reflections. */
            val reflections = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(reflections).hasSize(1)
            /** Assert that. */
            assertThat(reflections[0].id).isEqualTo("ref-1")
            /** Assert that. */
            assertThat(reflections[0].reflectionType).isEqualTo("untracked_time")
        }

    @Test
    /**
     * Insert reflections inserts multiple.
     */
    fun insertReflections_insertsMultiple() =
        runBlocking {
            /** Reflections. */
            val reflections =
                /** List of. */
                listOf(
                    /** Create test reflection. */
                    createTestReflection("ref-1", "2026-02-08", "untracked_time"),
                    /** Create test reflection. */
                    createTestReflection("ref-2", "2026-02-08", "missed_task"),
                    /** Create test reflection. */
                    createTestReflection("ref-3", "2026-02-08", "focus_gap"),
                )
            lensReflectionDao.insertReflections(reflections)

            /** Retrieved. */
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(retrieved).hasSize(3)
        }

    @Test
    /**
     * Observe reflections for day filters by day.
     */
    fun observeReflectionsForDay_filtersByDay() =
        runBlocking {
            /** Reflection1. */
            val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            /** Reflection2. */
            val reflection2 = createTestReflection("ref-2", "2026-02-09", "missed_task")

            lensReflectionDao.insertReflection(reflection1)
            lensReflectionDao.insertReflection(reflection2)

            /** Day8reflections. */
            val day8Reflections = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(day8Reflections).hasSize(1)
            /** Assert that. */
            assertThat(day8Reflections[0].dayKey).isEqualTo("2026-02-08")
        }

    @Test
    /**
     * Mark reflection addressed updates is addressed and note.
     */
    fun markReflectionAddressed_updatesIsAddressedAndNote() =
        runBlocking {
            /** Reflection. */
            val reflection = createTestReflection("ref-1", "2026-02-08", "missed_task")
            lensReflectionDao.insertReflection(reflection)

            lensReflectionDao.markReflectionAddressed("ref-1", "Rescheduled for tomorrow")

            /** Updated. */
            val updated = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(updated[0].isAddressed).isEqualTo(1)
            /** Assert that. */
            assertThat(updated[0].userNote).isEqualTo("Rescheduled for tomorrow")
        }

    @Test
    /**
     * Mark reflection addressed with null note.
     */
    fun markReflectionAddressed_withNullNote() =
        runBlocking {
            /** Reflection. */
            val reflection = createTestReflection("ref-1", "2026-02-08", "focus_gap")
            lensReflectionDao.insertReflection(reflection)

            lensReflectionDao.markReflectionAddressed("ref-1", null)

            /** Updated. */
            val updated = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(updated[0].isAddressed).isEqualTo(1)
            /** Assert that. */
            assertThat(updated[0].userNote).isNull()
        }

    @Test
    /**
     * Delete reflections for day removes only specified day.
     */
    fun deleteReflectionsForDay_removesOnlySpecifiedDay() =
        runBlocking {
            /** Reflection1. */
            val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            /** Reflection2. */
            val reflection2 = createTestReflection("ref-2", "2026-02-08", "missed_task")
            /** Reflection3. */
            val reflection3 = createTestReflection("ref-3", "2026-02-09", "focus_gap")

            lensReflectionDao.insertReflection(reflection1)
            lensReflectionDao.insertReflection(reflection2)
            lensReflectionDao.insertReflection(reflection3)

            lensReflectionDao.deleteReflectionsForDay("2026-02-08")

            /** Day8reflections. */
            val day8Reflections = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Day9reflections. */
            val day9Reflections = lensReflectionDao.observeReflectionsForDay("2026-02-09").first()

            /** Assert that. */
            assertThat(day8Reflections).isEmpty()
            /** Assert that. */
            assertThat(day9Reflections).hasSize(1)
        }

    @Test
    /**
     * Delete old reflections removes old entries.
     */
    fun deleteOldReflections_removesOldEntries() =
        runBlocking {
            /** Old reflection. */
            val oldReflection = createTestReflection("ref-1", "2026-01-01", "untracked_time", "2026-01-01T10:00:00")
            /** Recent reflection. */
            val recentReflection = createTestReflection("ref-2", "2026-02-08", "missed_task", "2026-02-08T10:00:00")

            lensReflectionDao.insertReflection(oldReflection)
            lensReflectionDao.insertReflection(recentReflection)

            lensReflectionDao.deleteOldReflections("2026-02-01T00:00:00")

            /** All reflections. */
            val allReflections = lensReflectionDao.observeReflectionsForDay("2026-01-01").first()
            /** Recent reflections. */
            val recentReflections = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()

            /** Assert that. */
            assertThat(allReflections).isEmpty()
            /** Assert that. */
            assertThat(recentReflections).hasSize(1)
        }

    @Test
    /**
     * Reflection with dimension stores and retrieves.
     */
    fun reflectionWithDimension_storesAndRetrieves() =
        runBlocking {
            /** Reflection. */
            val reflection =
                /** Create test reflection. */
                createTestReflection(
                    "ref-1",
                    "2026-02-08",
                    "dimension_gap",
                    dimensionId = "dim-health",
                )
            lensReflectionDao.insertReflection(reflection)

            /** Retrieved. */
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(retrieved[0].dimensionId).isEqualTo("dim-health")
        }

    @Test
    /**
     * Reflection with gap minutes stores and retrieves.
     */
    fun reflectionWithGapMinutes_storesAndRetrieves() =
        runBlocking {
            /** Reflection. */
            val reflection =
                /** Create test reflection. */
                createTestReflection(
                    "ref-1",
                    "2026-02-08",
                    "focus_gap",
                    gapMinutes = 120,
                )
            lensReflectionDao.insertReflection(reflection)

            /** Retrieved. */
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(retrieved[0].gapMinutes).isEqualTo(120)
        }

    @Test
    /**
     * Reflection with related entity stores and retrieves.
     */
    fun reflectionWithRelatedEntity_storesAndRetrieves() =
        runBlocking {
            /** Reflection. */
            val reflection =
                /** Create test reflection. */
                createTestReflection(
                    "ref-1",
                    "2026-02-08",
                    "missed_task",
                    relatedEntityId = "task-123",
                )
            lensReflectionDao.insertReflection(reflection)

            /** Retrieved. */
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(retrieved[0].relatedEntityId).isEqualTo("task-123")
        }

    @Test
    /**
     * Observe reflections for day emits updates when new reflection added.
     */
    fun observeReflectionsForDay_emitsUpdatesWhenNewReflectionAdded() =
        runBlocking {
            /** Reflection1. */
            val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            lensReflectionDao.insertReflection(reflection1)

            /** Initial. */
            val initial = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(initial).hasSize(1)

            /** Reflection2. */
            val reflection2 = createTestReflection("ref-2", "2026-02-08", "missed_task")
            lensReflectionDao.insertReflection(reflection2)

            /** Updated. */
            val updated = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(updated).hasSize(2)
        }

    @Test
    /**
     * Entity copy creates new instance with modified fields.
     */
    fun entityCopy_createsNewInstanceWithModifiedFields() =
        runBlocking {
            /** Original. */
            val original = createTestReflection("ref-1", "2026-02-08", "untracked_time")
            lensReflectionDao.insertReflection(original)

            /** Copied. */
            val copied =
                original.copy(
                    isAddressed = 1,
                    userNote = "Fixed by user",
                )

            /** Assert that. */
            assertThat(copied.id).isEqualTo(original.id)
            /** Assert that. */
            assertThat(copied.dayKey).isEqualTo(original.dayKey)
            /** Assert that. */
            assertThat(copied.isAddressed).isEqualTo(1)
            /** Assert that. */
            assertThat(copied.userNote).isEqualTo("Fixed by user")
        }

    @Test
    /**
     * Entity equals returns true for same values.
     */
    fun entityEquals_returnsTrueForSameValues() {
        /** Timestamp. */
        val timestamp = "2026-02-08T10:00:00"
        /** Reflection1. */
        val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time", timestamp)
        /** Reflection2. */
        val reflection2 = createTestReflection("ref-1", "2026-02-08", "untracked_time", timestamp)

        /** Assert that. */
        assertThat(reflection1).isEqualTo(reflection2)
    }

    @Test
    /**
     * Entity hash code is same for equal entities.
     */
    fun entityHashCode_isSameForEqualEntities() {
        /** Timestamp. */
        val timestamp = "2026-02-08T10:00:00"
        /** Reflection1. */
        val reflection1 = createTestReflection("ref-1", "2026-02-08", "untracked_time", timestamp)
        /** Reflection2. */
        val reflection2 = createTestReflection("ref-1", "2026-02-08", "untracked_time", timestamp)

        /** Assert that. */
        assertThat(reflection1.hashCode()).isEqualTo(reflection2.hashCode())
    }

    @Test
    /**
     * Entity to string contains all fields.
     */
    fun entityToString_containsAllFields() {
        /** Reflection. */
        val reflection =
            /** Create test reflection. */
            createTestReflection(
                "ref-1",
                "2026-02-08",
                "missed_task",
                dimensionId = "dim-work",
                gapMinutes = 120,
            )

        /** String rep. */
        val stringRep = reflection.toString()
        /** Assert that. */
        assertThat(stringRep).contains("ref-1")
        /** Assert that. */
        assertThat(stringRep).contains("2026-02-08")
        /** Assert that. */
        assertThat(stringRep).contains("missed_task")
        /** Assert that. */
        assertThat(stringRep).contains("dim-work")
    }

    @Test
    /**
     * Entity component functions extract fields correctly.
     */
    fun entityComponentFunctions_extractFieldsCorrectly() {
        /** Reflection. */
        val reflection =
            /** Create test reflection. */
            createTestReflection(
                "ref-1",
                "2026-02-08",
                "focus_gap",
                dimensionId = "dim-health",
                gapMinutes = 90,
            )

        // Only destructure first 3 components to avoid Detekt warning
        /** Id. */
        val id = reflection.component1()
        /** Day key. */
        val dayKey = reflection.component2()
        /** Dimension id. */
        val dimensionId = reflection.component3()

        /** Assert that. */
        assertThat(id).isEqualTo("ref-1")
        /** Assert that. */
        assertThat(dayKey).isEqualTo("2026-02-08")
        /** Assert that. */
        assertThat(dimensionId).isEqualTo("dim-health")
        /** Assert that. */
        assertThat(reflection.reflectionType).isEqualTo("focus_gap")
        /** Assert that. */
        assertThat(reflection.title).contains("focus_gap")
    }

    @Test
    /**
     * Entity with all nullable fields handles nulls correctly.
     */
    fun entityWithAllNullableFields_handlesNullsCorrectly() =
        runBlocking {
            /** Reflection. */
            val reflection =
                /** Lens reflection entity. */
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

            /** Retrieved. */
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(retrieved[0].dimensionId).isNull()
            /** Assert that. */
            assertThat(retrieved[0].description).isNull()
            /** Assert that. */
            assertThat(retrieved[0].gapMinutes).isNull()
            /** Assert that. */
            assertThat(retrieved[0].relatedEntityId).isNull()
            /** Assert that. */
            assertThat(retrieved[0].userNote).isNull()
        }

    @Test
    /**
     * Entity with minimal required params uses defaults.
     */
    fun entityWithMinimalRequiredParams_usesDefaults() =
        runBlocking {
            // Create entity using only required params, letting defaults kick in
            /** Reflection. */
            val reflection =
                /** Lens reflection entity. */
                LensReflectionEntity(
                    id = "ref-minimal",
                    dayKey = "2026-02-08",
                    reflectionType = "untracked_time",
                    title = "Minimal Test",
                    createdAt = "2026-02-08T10:00:00",
                )

            lensReflectionDao.insertReflection(reflection)

            /** Retrieved. */
            val retrieved = lensReflectionDao.observeReflectionsForDay("2026-02-08").first()
            /** Assert that. */
            assertThat(retrieved).hasSize(1)
            /** Assert that. */
            assertThat(retrieved[0].id).isEqualTo("ref-minimal")
            /** Assert that. */
            assertThat(retrieved[0].dimensionId).isNull()
            /** Assert that. */
            assertThat(retrieved[0].description).isNull()
            /** Assert that. */
            assertThat(retrieved[0].gapMinutes).isNull()
            /** Assert that. */
            assertThat(retrieved[0].relatedEntityId).isNull()
            /** Assert that. */
            assertThat(retrieved[0].isAddressed).isEqualTo(0)
            /** Assert that. */
            assertThat(retrieved[0].userNote).isNull()
        }

    private fun createTestReflection(
        /** Id. */
        id: String,
        /** Day key. */
        dayKey: String,
        /** Reflection type. */
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
