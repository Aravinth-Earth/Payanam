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
interface DayPlanDao {
    // ---- Day Plan Allocations ----

    @Query("SELECT * FROM day_plan_allocations WHERE day_key = :dayKey ORDER BY dimension_id")
    fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationEntity>>

    @Query("SELECT * FROM day_plan_allocations WHERE day_key = :dayKey ORDER BY dimension_id")
    suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationEntity>

    @Query("SELECT * FROM day_plan_allocations WHERE day_key = :dayKey AND dimension_id = :dimensionId LIMIT 1")
    suspend fun getAllocationForDayAndDimension(
        dayKey: String,
        dimensionId: String,
    ): DayPlanAllocationEntity?

    @Query("SELECT * FROM day_plan_allocations WHERE day_key BETWEEN :startDayKey AND :endDayKey ORDER BY day_key, dimension_id")
    suspend fun getAllocationsForRange(
        startDayKey: String,
        endDayKey: String,
    ): List<DayPlanAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllocation(entity: DayPlanAllocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllocations(entities: List<DayPlanAllocationEntity>)

    @Query("DELETE FROM day_plan_allocations WHERE day_key = :dayKey")
    suspend fun deleteAllocationsForDay(dayKey: String)

    @Query("SELECT DISTINCT day_key FROM day_plan_allocations ORDER BY day_key DESC LIMIT :limit")
    suspend fun getPlannedDays(limit: Int = 30): List<String>

    // ---- Day Policy ----

    @Query("SELECT * FROM day_plan_policies WHERE day_key = :dayKey LIMIT 1")
    suspend fun getDayPolicy(dayKey: String): DayPlanPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDayPolicy(entity: DayPlanPolicyEntity)

    // ---- Day-Type Template Preferences ----

    @Query("SELECT * FROM day_type_template_preferences WHERE day_type = :dayType LIMIT 1")
    suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDayTypeTemplatePreference(entity: DayTypeTemplatePreferenceEntity)

    // ---- Templates ----

    @Query("SELECT * FROM day_plan_templates WHERE is_active = 1 ORDER BY sort_order ASC")
    fun observeActiveTemplates(): Flow<List<DayPlanTemplateEntity>>

    @Query("SELECT * FROM day_plan_templates ORDER BY sort_order ASC")
    fun observeAllTemplates(): Flow<List<DayPlanTemplateEntity>>

    @Query("SELECT * FROM day_plan_templates WHERE id = :id")
    suspend fun getTemplateById(id: String): DayPlanTemplateEntity?

    @Query("SELECT COUNT(*) FROM day_plan_templates WHERE is_active = 1")
    suspend fun getActiveTemplateCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(entity: DayPlanTemplateEntity)

    @Query("UPDATE day_plan_templates SET is_active = 0, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDeleteTemplate(
        id: String,
        updatedAt: String,
    )

    @Query("DELETE FROM day_plan_templates WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    // ---- Template Allocations ----

    @Query("SELECT * FROM day_plan_template_allocations WHERE template_id = :templateId ORDER BY dimension_id")
    fun observeTemplateAllocations(templateId: String): Flow<List<DayPlanTemplateAllocationEntity>>

    @Query("SELECT * FROM day_plan_template_allocations WHERE template_id = :templateId ORDER BY dimension_id")
    suspend fun getTemplateAllocations(templateId: String): List<DayPlanTemplateAllocationEntity>

    @Query("SELECT * FROM day_plan_template_allocations WHERE template_id IN (:templateIds) ORDER BY template_id, dimension_id")
    suspend fun getTemplateAllocationsForTemplateIds(templateIds: List<String>): List<DayPlanTemplateAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplateAllocations(entities: List<DayPlanTemplateAllocationEntity>)

    @Query("DELETE FROM day_plan_template_allocations WHERE template_id = :templateId")
    suspend fun deleteTemplateAllocations(templateId: String)
}
