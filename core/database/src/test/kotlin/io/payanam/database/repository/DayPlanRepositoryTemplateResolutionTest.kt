//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.DayPlanRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
/**
 * DayPlanRepositoryTemplateResolutionTest.
 */
class DayPlanRepositoryTemplateResolutionTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: DayPlanRepository

    @Before
    /**
     * Setup.
     */
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        seedLifeDimensions()
        val encryptionManager = DatabaseEncryptionManager(context)
        val sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(database)
        repository = DayPlanRepositoryImpl(sessionManager)
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
     * Auto mode uses configured weekday template.
     */
    fun autoMode_usesConfiguredWeekdayTemplate() =
        runBlocking {
            val dayKey = nextWeekday()
            val templateId =
                repository.createTemplate(
                    name = "Weekday Plan",
                    description = null,
                    allocations = mapOf("career_work" to 180),
                )
            repository.setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKDAY, templateId)
            val allocations = repository.getEffectiveAllocationsForDay(dayKey)
            assertThat(allocations).hasSize(1)
            assertThat(allocations.first().plannedMinutes).isEqualTo(180)
            assertThat(allocations.first().source).isEqualTo(DayPlanRepository.SOURCE_TEMPLATE_AUTO)
        }

    @Test
    /**
     * Custom mode overrides auto template.
     */
    fun customMode_overridesAutoTemplate() =
        runBlocking {
            val dayKey = nextWeekday()
            val templateId =
                repository.createTemplate(
                    name = "Weekday Focus",
                    description = null,
                    allocations = mapOf("career_work" to 200),
                )
            repository.setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKDAY, templateId)
            repository.setAllocations(
                dayKey = dayKey,
                allocations = mapOf("career_work" to 90),
                source = DayPlanRepository.SOURCE_MANUAL,
            )
            val allocations = repository.getEffectiveAllocationsForDay(dayKey)
            assertThat(allocations).hasSize(1)
            assertThat(allocations.first().plannedMinutes).isEqualTo(90)
            assertThat(allocations.first().source).isEqualTo(DayPlanRepository.SOURCE_MANUAL)
        }

    @Test
    /**
     * Auto mode starred day prefers starred template.
     */
    fun autoMode_starredDay_prefersStarredTemplate() =
        runBlocking {
            val dayKey = nextWeekday()
            val weekdayTemplateId =
                repository.createTemplate(
                    name = "Weekday Base",
                    description = null,
                    allocations = mapOf("career_work" to 180),
                )
            val starredTemplateId =
                repository.createTemplate(
                    name = "Starred Focus",
                    description = null,
                    allocations = mapOf("career_work" to 60),
                )
            repository.setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKDAY, weekdayTemplateId)
            repository.setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_STARRED, starredTemplateId)
            repository.setDayStarred(dayKey, true)
            val allocations = repository.getEffectiveAllocationsForDay(dayKey)
            assertThat(allocations).hasSize(1)
            assertThat(allocations.first().templateId).isEqualTo(starredTemplateId)
            assertThat(allocations.first().plannedMinutes).isEqualTo(60)
            assertThat(allocations.first().source).isEqualTo(DayPlanRepository.SOURCE_TEMPLATE_AUTO)
        }

    @Test
    /**
     * Set day mode template persists policy.
     */
    fun setDayMode_template_persistsPolicy() =
        runBlocking {
            val dayKey = nextWeekday()
            val templateId =
                repository.createTemplate(
                    name = "Template Policy",
                    description = null,
                    allocations = mapOf("career_work" to 150),
                )

            repository.setDayMode(
                dayKey = dayKey,
                mode = DayPlanRepository.MODE_TEMPLATE,
                templateId = templateId,
            )
            val policy = repository.getDayPolicy(dayKey)
            assertThat(policy.mode).isEqualTo(DayPlanRepository.MODE_TEMPLATE)
            assertThat(policy.templateId).isEqualTo(templateId)
            assertThat(policy.isStarred).isFalse()
        }

    @Test
    /**
     * Get day policy without persisted policy returns auto defaults.
     */
    fun getDayPolicy_withoutPersistedPolicy_returnsAutoDefaults() =
        runBlocking {
            val policy = repository.getDayPolicy(nextWeekday())
            assertThat(policy.mode).isEqualTo(DayPlanRepository.MODE_AUTO)
            assertThat(policy.templateId).isNull()
            assertThat(policy.isStarred).isFalse()
        }

    @Test
    /**
     * Auto mode uses weekend template on weekend day.
     */
    fun autoMode_usesWeekendTemplate_onWeekendDay() =
        runBlocking {
            val weekendDayKey = nextWeekend()
            val templateId =
                repository.createTemplate(
                    name = "Weekend Plan",
                    description = null,
                    allocations = mapOf("learning" to 120),
                )
            repository.setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKEND, templateId)
            val allocations = repository.getEffectiveAllocationsForDay(weekendDayKey)
            assertThat(allocations).hasSize(1)
            assertThat(allocations.first().templateId).isEqualTo(templateId)
            assertThat(allocations.first().plannedMinutes).isEqualTo(120)
            assertThat(allocations.first().source).isEqualTo(DayPlanRepository.SOURCE_TEMPLATE_AUTO)
        }

    @Test
    /**
     * Template mode without template id falls back to explicit allocations.
     */
    fun templateMode_withoutTemplateId_fallsBackToExplicitAllocations() =
        runBlocking {
            val dayKey = nextWeekday()
            repository.setAllocations(
                dayKey = dayKey,
                allocations = mapOf("career_work" to 45),
                source = DayPlanRepository.SOURCE_MANUAL,
            )
            repository.setDayMode(
                dayKey = dayKey,
                mode = DayPlanRepository.MODE_TEMPLATE,
                templateId = null,
            )
            val allocations = repository.getEffectiveAllocationsForDay(dayKey)
            assertThat(allocations).hasSize(1)
            assertThat(allocations.first().plannedMinutes).isEqualTo(45)
            assertThat(allocations.first().source).isEqualTo(DayPlanRepository.SOURCE_MANUAL)
        }

    @Test
    /**
     * Day type preference accepts null template id.
     */
    fun dayTypePreference_acceptsNullTemplateId() =
        runBlocking {
            repository.setDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKDAY, null)
            val preference = repository.getDayTypeTemplatePreference(DayPlanRepository.DAY_TYPE_WEEKDAY)
            assertThat(preference.templateId).isNull()
        }

    @Test
    /**
     * Custom mode without explicit allocations returns empty effective allocations.
     */
    fun customMode_withoutExplicitAllocations_returnsEmptyEffectiveAllocations() =
        runBlocking {
            val dayKey = nextWeekday()
            repository.setDayMode(dayKey = dayKey, mode = DayPlanRepository.MODE_CUSTOM)
            val allocations = repository.getEffectiveAllocationsForDay(dayKey)
            assertThat(allocations).isEmpty()
        }

    @Test
    /**
     * Template mode with inactive template returns empty without explicit fallback.
     */
    fun templateMode_withInactiveTemplate_returnsEmptyWithoutExplicitFallback() =
        runBlocking {
            val dayKey = nextWeekday()
            val templateId =
                repository.createTemplate(
                    name = "Temporary Template",
                    description = null,
                    allocations = mapOf("career_work" to 30),
                )
            repository.deleteTemplate(templateId)
            repository.setDayMode(
                dayKey = dayKey,
                mode = DayPlanRepository.MODE_TEMPLATE,
                templateId = templateId,
            )
            val allocations = repository.getEffectiveAllocationsForDay(dayKey)
            assertThat(allocations).isEmpty()
        }

    private fun seedLifeDimensions() {
        val db = database.openHelper.writableDatabase
        val now = "2026-01-01T00:00:00"
        val dims = listOf("career_work", "health_wellness", "learning")
        dims.forEachIndexed { index, id ->
            db.execSQL(
                """INSERT OR IGNORE INTO life_dimensions (id, key, label, color, sortOrder, isActive, weight, createdAt, updatedAt)
                   VALUES ('$id', '$id', '$id', '#FF5722', $index, 1, 1.0, '$now', '$now')""",
            )
        }
    }

    private fun nextWeekday(): String {
        var date = LocalDate.now().plusDays(1)
        while (date.dayOfWeek.value >= 6) {
            date = date.plusDays(1)
        }
        return date.toString()
    }

    private fun nextWeekend(): String {
        var date = LocalDate.now().plusDays(1)
        while (date.dayOfWeek.value < 6) {
            date = date.plusDays(1)
        }
        return date.toString()
    }
}
