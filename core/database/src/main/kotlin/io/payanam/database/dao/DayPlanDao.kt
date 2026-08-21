//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.payanam.database.entity.DayPlanAllocationEntity
import io.payanam.database.entity.DayPlanPolicyEntity
import io.payanam.database.entity.DayPlanTemplateAllocationEntity
import io.payanam.database.entity.DayPlanTemplateEntity
import io.payanam.database.entity.DayTypeTemplatePreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions")
/**
 * Defines the contract for day plan dao.
 */
interface DayPlanDao {
    // ---- Day Plan Allocations ----

    @Query("SELECT * FROM day_plan_allocations WHERE day_key = :dayKey ORDER BY dimension_id")
    /**
     * Registers the observe allocations for day.
     */
    fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationEntity>>

    @Query("SELECT * FROM day_plan_allocations WHERE day_key = :dayKey ORDER BY dimension_id")
    /**
     * Returns the allocations for day.
     */
    suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationEntity>

    @Query("SELECT * FROM day_plan_allocations WHERE day_key = :dayKey AND dimension_id = :dimensionId LIMIT 1")
    /**
     * Returns the allocation for day and dimension.
     */
    suspend fun getAllocationForDayAndDimension(
        dayKey: String,
        dimensionId: String,
    ): DayPlanAllocationEntity?

    @Query("SELECT * FROM day_plan_allocations WHERE day_key BETWEEN :startDayKey AND :endDayKey ORDER BY day_key, dimension_id")
    /**
     * Returns the allocations for range.
     */
    suspend fun getAllocationsForRange(
        startDayKey: String,
        endDayKey: String,
    ): List<DayPlanAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert allocation.
     */
    suspend fun insertAllocation(entity: DayPlanAllocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert allocations.
     */
    suspend fun insertAllocations(entities: List<DayPlanAllocationEntity>)

    @Query("DELETE FROM day_plan_allocations WHERE day_key = :dayKey")
    /**
     * Removes the delete allocations for day.
     */
    suspend fun deleteAllocationsForDay(dayKey: String)

    @Query("SELECT DISTINCT day_key FROM day_plan_allocations ORDER BY day_key DESC LIMIT :limit")
    /**
     * Returns the planned days.
     */
    suspend fun getPlannedDays(limit: Int = 30): List<String>

    // ---- Day Policy ----

    @Query("SELECT * FROM day_plan_policies WHERE day_key = :dayKey LIMIT 1")
    /**
     * Returns the day policy.
     */
    suspend fun getDayPolicy(dayKey: String): DayPlanPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the upsert day policy.
     */
    suspend fun upsertDayPolicy(entity: DayPlanPolicyEntity)

    // ---- Day-Type Template Preferences ----

    @Query("SELECT * FROM day_type_template_preferences WHERE day_type = :dayType LIMIT 1")
    /**
     * Returns the day type template preference.
     */
    suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the upsert day type template preference.
     */
    suspend fun upsertDayTypeTemplatePreference(entity: DayTypeTemplatePreferenceEntity)

    // ---- Templates ----

    @Query("SELECT * FROM day_plan_templates WHERE is_active = 1 ORDER BY sort_order ASC")
    /**
     * Registers the observe active templates.
     */
    fun observeActiveTemplates(): Flow<List<DayPlanTemplateEntity>>

    @Query("SELECT * FROM day_plan_templates ORDER BY sort_order ASC")
    /**
     * Registers the observe all templates.
     */
    fun observeAllTemplates(): Flow<List<DayPlanTemplateEntity>>

    @Query("SELECT * FROM day_plan_templates WHERE id = :id")
    /**
     * Returns the template by id.
     */
    suspend fun getTemplateById(id: String): DayPlanTemplateEntity?

    @Query("SELECT COUNT(*) FROM day_plan_templates WHERE is_active = 1")
    /**
     * Returns the active template count.
     */
    suspend fun getActiveTemplateCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert template.
     */
    suspend fun insertTemplate(entity: DayPlanTemplateEntity)

    @Query("UPDATE day_plan_templates SET is_active = 0, updated_at = :updatedAt WHERE id = :id")
    /**
     * Performs the soft delete template.
     */
    suspend fun softDeleteTemplate(
        id: String,
        updatedAt: String,
    )

    @Query("DELETE FROM day_plan_templates WHERE id = :id")
    /**
     * Removes the delete template.
     */
    suspend fun deleteTemplate(id: String)

    // ---- Template Allocations ----

    @Query("SELECT * FROM day_plan_template_allocations WHERE template_id = :templateId ORDER BY dimension_id")
    /**
     * Registers the observe template allocations.
     */
    fun observeTemplateAllocations(templateId: String): Flow<List<DayPlanTemplateAllocationEntity>>

    @Query("SELECT * FROM day_plan_template_allocations WHERE template_id = :templateId ORDER BY dimension_id")
    /**
     * Returns the template allocations.
     */
    suspend fun getTemplateAllocations(templateId: String): List<DayPlanTemplateAllocationEntity>

    @Query("SELECT * FROM day_plan_template_allocations WHERE template_id IN (:templateIds) ORDER BY template_id, dimension_id")
    /**
     * Returns the template allocations for template ids.
     */
    suspend fun getTemplateAllocationsForTemplateIds(templateIds: List<String>): List<DayPlanTemplateAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert template allocations.
     */
    suspend fun insertTemplateAllocations(entities: List<DayPlanTemplateAllocationEntity>)

    @Query("DELETE FROM day_plan_template_allocations WHERE template_id = :templateId")
    /**
     * Removes the delete template allocations.
     */
    suspend fun deleteTemplateAllocations(templateId: String)
}
