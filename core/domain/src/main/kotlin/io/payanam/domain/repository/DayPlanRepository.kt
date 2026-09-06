//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import kotlinx.coroutines.flow.Flow
/**
 * One dimension's planned time for a single day (from a custom/day-plan
 * allocation or a template instantiation).
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
 * A reusable day-plan template: a named bundle of per-dimension planned
 * minutes that can be applied to any day.
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
 * One dimension's planned minutes inside a [DayPlanTemplateRecord].
 */
data class TemplateAllocationRecord(
    val id: String,
    val templateId: String,
    val dimensionId: String,
    val plannedMinutes: Int
)
/**
 * Per-day policy: which mode (auto/template/custom) drives the allocations and
 * whether the day is starred.
 */
data class DayPlanPolicyRecord(
    val dayKey: String,
    val mode: String,
    val templateId: String?,
    val isStarred: Boolean
)
/**
 * Which template, if any, is the default for a given day type (weekday /
 * weekend / starred).
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
     * Emits the allocations for [dayKey] as a [Flow], for reactive day-plan UI.
     */
    fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>>
    /**
     * Returns the saved allocations for [dayKey] (no template resolution).
     */
    suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord>

    /**
     * Returns the effective day allocations resolved from per-day policy
     * (custom/template/auto template).
     */
    suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord>
    /**
     * Upserts the planned minutes for one dimension on [dayKey] (source tracks
     * manual vs template-derived).
     */
    suspend fun setAllocation(
        dayKey: String,
        dimensionId: String,
        plannedMinutes: Int,
        source: String = "manual",
        templateId: String? = null
    )
    /**
     * Bulk-upserts planned minutes for several dimensions on [dayKey] at once.
     */
    suspend fun setAllocations(
        dayKey: String,
        allocations: Map<String, Int>,
        source: String = "manual",
        templateId: String? = null
    )
    /**
     * Replaces [dayKey]'s allocations with the [templateId] template's
     * contents (source = template).
     */
    suspend fun applyTemplateToDay(dayKey: String, templateId: String)
    /**
     * Removes every allocation for [dayKey].
     */
    suspend fun clearDayPlan(dayKey: String)
    /**
     * Returns the per-day policy (mode + template + starred) for [dayKey].
     */
    suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord
    /**
     * Sets the planning mode for [dayKey] (auto/template/custom).
     */
    suspend fun setDayMode(dayKey: String, mode: String, templateId: String? = null)
    /**
     * Flags [dayKey] as starred (surfaces it above auto/weekend defaults).
     */
    suspend fun setDayStarred(dayKey: String, isStarred: Boolean)
    /**
     * Reads the preferred template for a given [dayType] (weekday, weekend, or
     * a starred preset), or null if none is set.
     */
    suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord
    /**
     * Persists the preferred template [templateId] for a [dayType], or clears
     * it (null) to fall back to the system default.
     */
    suspend fun setDayTypeTemplatePreference(dayType: String, templateId: String?)
    /**
     * Resolves the template that applies to [dayKey] (starred > weekend >
     * weekday > custom), or null.
     */
    suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord?

    // ---- Templates ----
    /**
     * Emits the active (non-archived) templates as a [Flow].
     */
    fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>>
    /**
     * Emits every template (active + archived) as a [Flow].
     */
    fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>>
    /**
     * Returns a single template by id, or null.
     */
    suspend fun getTemplateById(id: String): DayPlanTemplateRecord?
    /**
     * Creates a template with [name] and per-dimension [allocations]; returns
     * the new template id.
     */
    suspend fun createTemplate(
        name: String,
        description: String?,
        allocations: Map<String, Int>
    ): String
    /**
     * Updates the name/description/allocations of an existing template.
     */
    suspend fun updateTemplate(
        id: String,
        name: String,
        description: String?,
        allocations: Map<String, Int>
    )
    /**
     * Deletes a template by id.
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
