//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import kotlinx.coroutines.flow.Flow

data class DayPlanAllocationRecord(
    val id: String,
    val dayKey: String,
    val dimensionId: String,
    val plannedMinutes: Int,
    val source: String,
    val templateId: String?
)

data class DayPlanTemplateRecord(
    val id: String,
    val name: String,
    val description: String?,
    val isActive: Boolean,
    val sortOrder: Int,
    val allocations: List<TemplateAllocationRecord>
)

data class TemplateAllocationRecord(
    val id: String,
    val templateId: String,
    val dimensionId: String,
    val plannedMinutes: Int
)

data class DayPlanPolicyRecord(
    val dayKey: String,
    val mode: String,
    val templateId: String?,
    val isStarred: Boolean
)

data class DayTypeTemplatePreferenceRecord(
    val dayType: String,
    val templateId: String?
)

/**
 * Repository for per-day time plan allocations and reusable day plan templates.
 */
interface DayPlanRepository {

    // ---- Day Allocations ----

    fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>>

    suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord>

    /**
     * Returns the effective day allocations resolved from per-day policy
     * (custom/template/auto template).
     */
    suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord>

    suspend fun setAllocation(
        dayKey: String,
        dimensionId: String,
        plannedMinutes: Int,
        source: String = "manual",
        templateId: String? = null
    )

    suspend fun setAllocations(
        dayKey: String,
        allocations: Map<String, Int>,
        source: String = "manual",
        templateId: String? = null
    )

    suspend fun applyTemplateToDay(dayKey: String, templateId: String)

    suspend fun clearDayPlan(dayKey: String)

    suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord

    suspend fun setDayMode(dayKey: String, mode: String, templateId: String? = null)

    suspend fun setDayStarred(dayKey: String, isStarred: Boolean)

    suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord

    suspend fun setDayTypeTemplatePreference(dayType: String, templateId: String?)

    suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord?

    // ---- Templates ----

    fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>>

    fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>>

    suspend fun getTemplateById(id: String): DayPlanTemplateRecord?

    suspend fun createTemplate(
        name: String,
        description: String?,
        allocations: Map<String, Int>
    ): String

    suspend fun updateTemplate(
        id: String,
        name: String,
        description: String?,
        allocations: Map<String, Int>
    )

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
