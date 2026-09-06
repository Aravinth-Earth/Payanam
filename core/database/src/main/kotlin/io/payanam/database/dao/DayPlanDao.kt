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
 * Room DAO for the day-planning tables: per-day [DayPlanAllocationEntity]
 * entries, [DayPlanPolicyEntity] overrides, [DayTypeTemplatePreferenceEntity]
 * mappings, and reusable [DayPlanTemplateEntity] plans with their allocations.
 *
 * `observe*` methods return [Flow] for reactive UI; the rest are single-shot
 * writes and lookups.
 */
interface DayPlanDao {
    // ---- Day Plan Allocations ----

    @Query("SELECT * FROM day_plan_allocations WHERE day_key = :dayKey ORDER BY dimension_id")
    /**
     * Emits the allocation rows for [dayKey], ordered by dimension, as a [Flow]
     * that updates whenever allocations change.
     */
    fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationEntity>>

    @Query("SELECT * FROM day_plan_allocations WHERE day_key = :dayKey ORDER BY dimension_id")
    /**
     * Returns the allocation rows for [dayKey], ordered by dimension.
     */
    suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationEntity>

    @Query("SELECT * FROM day_plan_allocations WHERE day_key = :dayKey AND dimension_id = :dimensionId LIMIT 1")
    /**
     * Returns the single allocation for [dayKey] + [dimensionId], or null.
     */
    suspend fun getAllocationForDayAndDimension(
        dayKey: String,
        dimensionId: String,
    ): DayPlanAllocationEntity?

    @Query("SELECT * FROM day_plan_allocations WHERE day_key BETWEEN :startDayKey AND :endDayKey ORDER BY day_key, dimension_id")
    /**
     * Returns allocations whose `day_key` falls within the inclusive range
     * [startDayKey]..[endDayKey], ordered by day then dimension.
     */
    suspend fun getAllocationsForRange(
        startDayKey: String,
        endDayKey: String,
    ): List<DayPlanAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a single day-plan allocation.
     */
    suspend fun insertAllocation(entity: DayPlanAllocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a batch of day-plan allocations.
     */
    suspend fun insertAllocations(entities: List<DayPlanAllocationEntity>)

    @Query("DELETE FROM day_plan_allocations WHERE day_key = :dayKey")
    /**
     * Deletes every allocation for [dayKey]. Used before rewriting a day's
     * plan from scratch.
     */
    suspend fun deleteAllocationsForDay(dayKey: String)

    @Query("SELECT DISTINCT day_key FROM day_plan_allocations ORDER BY day_key DESC LIMIT :limit")
    /**
     * Returns up to [limit] distinct day keys that have at least one allocation,
     * newest first. Defaults to 30.
     */
    suspend fun getPlannedDays(limit: Int = 30): List<String>

    // ---- Day Policy ----

    @Query("SELECT * FROM day_plan_policies WHERE day_key = :dayKey LIMIT 1")
    /**
     * Returns the per-day override policy for [dayKey], or null when the day
     * falls back to global/template defaults.
     */
    suspend fun getDayPolicy(dayKey: String): DayPlanPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces the per-day policy.
     */
    suspend fun upsertDayPolicy(entity: DayPlanPolicyEntity)

    // ---- Day-Type Template Preferences ----

    @Query("SELECT * FROM day_type_template_preferences WHERE day_type = :dayType LIMIT 1")
    /**
     * Returns the preferred template for a calendar day type ([dayType]), or
     * null when no preference is set.
     */
    suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces the day-type-to-template preference.
     */
    suspend fun upsertDayTypeTemplatePreference(entity: DayTypeTemplatePreferenceEntity)

    // ---- Templates ----

    @Query("SELECT * FROM day_plan_templates WHERE is_active = 1 ORDER BY sort_order ASC")
    /**
     * Emits all active templates ordered by [DayPlanTemplateEntity.sortOrder],
     * as a [Flow].
     */
    fun observeActiveTemplates(): Flow<List<DayPlanTemplateEntity>>

    @Query("SELECT * FROM day_plan_templates ORDER BY sort_order ASC")
    /**
     * Emits every template (active or soft-deleted) ordered by sort order, as a
     * [Flow].
     */
    fun observeAllTemplates(): Flow<List<DayPlanTemplateEntity>>

    @Query("SELECT * FROM day_plan_templates WHERE id = :id")
    /**
     * Returns the template with [id], or null.
     */
    suspend fun getTemplateById(id: String): DayPlanTemplateEntity?

    @Query("SELECT COUNT(*) FROM day_plan_templates WHERE is_active = 1")
    /**
     * Counts active (non-soft-deleted) templates.
     */
    suspend fun getActiveTemplateCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a template definition.
     */
    suspend fun insertTemplate(entity: DayPlanTemplateEntity)

    @Query("UPDATE day_plan_templates SET is_active = 0, updated_at = :updatedAt WHERE id = :id")
    /**
     * Soft-deletes a template by flipping `is_active` to 0 (keeps the row for
     * history/audit) and recording [updatedAt].
     */
    suspend fun softDeleteTemplate(
        id: String,
        updatedAt: String,
    )

    @Query("DELETE FROM day_plan_templates WHERE id = :id")
    /**
     * Hard-deletes a template row entirely.
     */
    suspend fun deleteTemplate(id: String)

    // ---- Template Allocations ----

    @Query("SELECT * FROM day_plan_template_allocations WHERE template_id = :templateId ORDER BY dimension_id")
    /**
     * Emits the allocation rows belonging to [templateId], ordered by
     * dimension, as a [Flow].
     */
    fun observeTemplateAllocations(templateId: String): Flow<List<DayPlanTemplateAllocationEntity>>

    @Query("SELECT * FROM day_plan_template_allocations WHERE template_id = :templateId ORDER BY dimension_id")
    /**
     * Returns the allocation rows for [templateId], ordered by dimension.
     */
    suspend fun getTemplateAllocations(templateId: String): List<DayPlanTemplateAllocationEntity>

    @Query("SELECT * FROM day_plan_template_allocations WHERE template_id IN (:templateIds) ORDER BY template_id, dimension_id")
    /**
     * Returns allocations for all templates in [templateIds], ordered by
     * template then dimension. Used to apply several templates at once.
     */
    suspend fun getTemplateAllocationsForTemplateIds(templateIds: List<String>): List<DayPlanTemplateAllocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a batch of template allocation rows.
     */
    suspend fun insertTemplateAllocations(entities: List<DayPlanTemplateAllocationEntity>)

    @Query("DELETE FROM day_plan_template_allocations WHERE template_id = :templateId")
    /**
     * Deletes every allocation row for [templateId]. Called before rewriting a
     * template's allocations.
     */
    suspend fun deleteTemplateAllocations(templateId: String)
}
