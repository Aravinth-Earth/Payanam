//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import kotlinx.coroutines.flow.Flow
/**
 * Holds the day plan allocation record.
 */
data class DayPlanAllocationRecord(
    val id: String,
    val dayKey: String,
    val dimensionId: String,
    val plannedMinutes: Int,
    val source: String,
    val templateId: String?
)
/**
 * Holds the day plan template record.
 */
data class DayPlanTemplateRecord(
    val id: String,
    val name: String,
    val description: String?,
    val isActive: Boolean,
    val sortOrder: Int,
    val allocations: List<TemplateAllocationRecord>
)
/**
 * Holds the template allocation record.
 */
data class TemplateAllocationRecord(
    val id: String,
    val templateId: String,
    val dimensionId: String,
    val plannedMinutes: Int
)
/**
 * Holds the day plan policy record.
 */
data class DayPlanPolicyRecord(
    val dayKey: String,
    val mode: String,
    val templateId: String?,
    val isStarred: Boolean
)
/**
 * Holds the day type template preference record.
 */
data class DayTypeTemplatePreferenceRecord(
    val dayType: String,
    val templateId: String?
)

/**
 * Repository for per-day time plan allocations and reusable day plan templates.
 */
interface DayPlanRepository {

    // ---- Day Allocations ----
    /**
     * Registers the observe allocations for day.
     */
    fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>>
    /**
     * Returns the get allocations for day.
     */
    suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord>

    /**
     * Returns the effective day allocations resolved from per-day policy
     * (custom/template/auto template).
     */
    suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord>
    /**
     * Updates the set allocation.
     */
    suspend fun setAllocation(
        dayKey: String,
        dimensionId: String,
        plannedMinutes: Int,
        source: String = "manual",
        templateId: String? = null
    )
    /**
     * Updates the set allocations.
     */
    suspend fun setAllocations(
        dayKey: String,
        allocations: Map<String, Int>,
        source: String = "manual",
        templateId: String? = null
    )
    /**
     * Updates the apply template to day.
     */
    suspend fun applyTemplateToDay(dayKey: String, templateId: String)
    /**
     * Removes the clear day plan.
     */
    suspend fun clearDayPlan(dayKey: String)
    /**
     * Returns the get day policy.
     */
    suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord
    /**
     * Updates the set day mode.
     */
    suspend fun setDayMode(dayKey: String, mode: String, templateId: String? = null)
    /**
     * Updates the set day starred.
     */
    suspend fun setDayStarred(dayKey: String, isStarred: Boolean)
    /**
     * Returns the get day type template preference.
     */
    suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord
    /**
     * Updates the set day type template preference.
     */
    suspend fun setDayTypeTemplatePreference(dayType: String, templateId: String?)
    /**
     * Returns the resolve template for day.
     */
    suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord?

    // ---- Templates ----
    /**
     * Registers the observe active templates.
     */
    fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>>
    /**
     * Registers the observe all templates.
     */
    fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>>
    /**
     * Returns the get template by id.
     */
    suspend fun getTemplateById(id: String): DayPlanTemplateRecord?
    /**
     * Creates the create template.
     */
    suspend fun createTemplate(
        name: String,
        description: String?,
        allocations: Map<String, Int>
    ): String
    /**
     * Updates the update template.
     */
    suspend fun updateTemplate(
        id: String,
        name: String,
        description: String?,
        allocations: Map<String, Int>
    )
    /**
     * Removes the delete template.
     */
    suspend fun deleteTemplate(id: String)

    companion object {
        const val MAX_TEMPLATE_COUNT = 10
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_TEMPLATE = "template"
        const val SOURCE_TEMPLATE_AUTO = "template_auto"
        const val MODE_AUTO = "auto"
        const val MODE_TEMPLATE = "template"
        const val MODE_CUSTOM = "custom"
        const val DAY_TYPE_WEEKDAY = "weekday"
        const val DAY_TYPE_WEEKEND = "weekend"
        const val DAY_TYPE_STARRED = "starred"
    }
}
