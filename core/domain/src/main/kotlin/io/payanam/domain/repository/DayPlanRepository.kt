//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * DayPlanAllocationRecord.
 */
data class DayPlanAllocationRecord(
    /** Id. */
    val id: String,
    /** Day key. */
    val dayKey: String,
    /** Dimension id. */
    val dimensionId: String,
    /** Planned minutes. */
    val plannedMinutes: Int,
    /** Source. */
    val source: String,
    /** Template id. */
    val templateId: String?
)

/**
 * DayPlanTemplateRecord.
 */
data class DayPlanTemplateRecord(
    /** Id. */
    val id: String,
    /** Name. */
    val name: String,
    /** Description. */
    val description: String?,
    /** Is active. */
    val isActive: Boolean,
    /** Sort order. */
    val sortOrder: Int,
    /** Allocations. */
    val allocations: List<TemplateAllocationRecord>
)

/**
 * TemplateAllocationRecord.
 */
data class TemplateAllocationRecord(
    /** Id. */
    val id: String,
    /** Template id. */
    val templateId: String,
    /** Dimension id. */
    val dimensionId: String,
    /** Planned minutes. */
    val plannedMinutes: Int
)

/**
 * DayPlanPolicyRecord.
 */
data class DayPlanPolicyRecord(
    /** Day key. */
    val dayKey: String,
    /** Mode. */
    val mode: String,
    /** Template id. */
    val templateId: String?,
    /** Is starred. */
    val isStarred: Boolean
)

/**
 * DayTypeTemplatePreferenceRecord.
 */
data class DayTypeTemplatePreferenceRecord(
    /** Day type. */
    val dayType: String,
    /** Template id. */
    val templateId: String?
)

/**
 * Repository for per-day time plan allocations and reusable day plan templates.
 */
interface DayPlanRepository {

    // ---- Day Allocations ----

    /**
     * Observe allocations for day.
     */
    fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>>

    /**
     * Get allocations for day.
     */
    suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord>

    /**
     * Returns the effective day allocations resolved from per-day policy
     * (custom/template/auto template).
     */
    suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord>

    /**
     * Set allocation.
     */
    suspend fun setAllocation(
        /** Day key. */
        dayKey: String,
        /** Dimension id. */
        dimensionId: String,
        /** Planned minutes. */
        plannedMinutes: Int,
        source: String = "manual",
        templateId: String? = null
    )

    /**
     * Set allocations.
     */
    suspend fun setAllocations(
        /** Day key. */
        dayKey: String,
        allocations: Map<String, Int>,
        source: String = "manual",
        templateId: String? = null
    )

    /**
     * Apply template to day.
     */
    suspend fun applyTemplateToDay(dayKey: String, templateId: String)

    /**
     * Clear day plan.
     */
    suspend fun clearDayPlan(dayKey: String)

    /**
     * Get day policy.
     */
    suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord

    /**
     * Set day mode.
     */
    suspend fun setDayMode(dayKey: String, mode: String, templateId: String? = null)

    /**
     * Set day starred.
     */
    suspend fun setDayStarred(dayKey: String, isStarred: Boolean)

    /**
     * Get day type template preference.
     */
    suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord

    /**
     * Set day type template preference.
     */
    suspend fun setDayTypeTemplatePreference(dayType: String, templateId: String?)

    /**
     * Resolve template for day.
     */
    suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord?

    // ---- Templates ----

    /**
     * Observe active templates.
     */
    fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>>

    /**
     * Observe all templates.
     */
    fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>>

    /**
     * Get template by id.
     */
    suspend fun getTemplateById(id: String): DayPlanTemplateRecord?

    /**
     * Create template.
     */
    suspend fun createTemplate(
        /** Name. */
        name: String,
        description: String?,
        allocations: Map<String, Int>
    ): String

    /**
     * Update template.
     */
    suspend fun updateTemplate(
        /** Id. */
        id: String,
        /** Name. */
        name: String,
        description: String?,
        allocations: Map<String, Int>
    )

    /**
     * Delete template.
     */
    suspend fun deleteTemplate(id: String)

    companion object {
        /** Max template count. */
        const val MAX_TEMPLATE_COUNT = 10
        /** Source manual. */
        const val SOURCE_MANUAL = "manual"
        /** Source template. */
        const val SOURCE_TEMPLATE = "template"
        /** Source template auto. */
        const val SOURCE_TEMPLATE_AUTO = "template_auto"

        /** Mode auto. */
        const val MODE_AUTO = "auto"
        /** Mode template. */
        const val MODE_TEMPLATE = "template"
        /** Mode custom. */
        const val MODE_CUSTOM = "custom"

        /** Day type weekday. */
        const val DAY_TYPE_WEEKDAY = "weekday"
        /** Day type weekend. */
        const val DAY_TYPE_WEEKEND = "weekend"
        /** Day type starred. */
        const val DAY_TYPE_STARRED = "starred"
    }
}
