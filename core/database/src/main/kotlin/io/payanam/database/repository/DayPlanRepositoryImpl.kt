//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length")

package io.payanam.database.repository

import androidx.room.withTransaction
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.dao.DayPlanDao
import io.payanam.database.entity.DayPlanAllocationEntity
import io.payanam.database.entity.DayPlanPolicyEntity
import io.payanam.database.entity.DayPlanTemplateAllocationEntity
import io.payanam.database.entity.DayPlanTemplateEntity
import io.payanam.database.entity.DayTypeTemplatePreferenceEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.DayPlanAllocationRecord
import io.payanam.domain.repository.DayPlanPolicyRecord
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanRepository.Companion.DAY_TYPE_STARRED
import io.payanam.domain.repository.DayPlanRepository.Companion.DAY_TYPE_WEEKDAY
import io.payanam.domain.repository.DayPlanRepository.Companion.DAY_TYPE_WEEKEND
import io.payanam.domain.repository.DayPlanRepository.Companion.MODE_AUTO
import io.payanam.domain.repository.DayPlanRepository.Companion.MODE_CUSTOM
import io.payanam.domain.repository.DayPlanRepository.Companion.MODE_TEMPLATE
import io.payanam.domain.repository.DayPlanRepository.Companion.SOURCE_TEMPLATE
import io.payanam.domain.repository.DayPlanRepository.Companion.SOURCE_TEMPLATE_AUTO
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.domain.repository.DayTypeTemplatePreferenceRecord
import io.payanam.domain.repository.TemplateAllocationRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("TooManyFunctions")
/**
 * Provides the day plan repository impl.
 */
class DayPlanRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : DayPlanRepository {
        private val logger = UnifiedLogger.getInstance()
        private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        // ---- Day Allocations ----

        /**
         * Registers the observe allocations for day.
         */
        override fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>> {
            logger.d("DayPlanRepositoryImpl.observeAllocationsForDay", "Subscribing to allocations for day", mapOf("dayKey" to dayKey))
            return sessionManager.requireDatabase().dayPlanDao().observeAllocationsForDay(dayKey).map { entities ->
                entities.map { it.toRecord() }
            }
        }

        /**
         * Returns the allocations for day.
         */
        override suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> {
            logger.d("DayPlanRepositoryImpl.getAllocationsForDay", "Fetching allocations for day", mapOf("dayKey" to dayKey))
            return sessionManager
                .requireDatabase()
                .dayPlanDao()
                .getAllocationsForDay(dayKey)
                .map { it.toRecord() }
        }

        /**
         * Returns the effective allocations for day.
         */
        override suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> {
            logger.d(
                "DayPlanRepositoryImpl.getEffectiveAllocationsForDay",
                "Resolving effective allocations",
                mapOf("dayKey" to dayKey),
            )
            val dao = sessionManager.requireDatabase().dayPlanDao()
            val policy = getDayPolicyFromEntity(dayKey, dao.getDayPolicy(dayKey))
            val explicit = dao.getAllocationsForDay(dayKey)
            if (policy.mode == MODE_CUSTOM && explicit.isNotEmpty()) {
                logger.d(
                    "DayPlanRepositoryImpl.getEffectiveAllocationsForDay",
                    "Using custom explicit allocations",
                    mapOf("dayKey" to dayKey, "count" to explicit.size),
                )
                return explicit.map { it.toRecord() }
            }
            if (policy.mode == MODE_TEMPLATE) {
                val templateId = policy.templateId
                if (!templateId.isNullOrBlank()) {
                    val templateDerived =
                        buildTemplateDerivedAllocations(
                            dao = dao,
                            dayKey = dayKey,
                            templateId = templateId,
                            source = SOURCE_TEMPLATE,
                        )
                    if (templateDerived.isNotEmpty()) {
                        return templateDerived
                    }
                }
                if (explicit.isNotEmpty()) {
                    return explicit.map { it.toRecord() }
                }
            }
            if (policy.mode == MODE_AUTO) {
                val resolvedTemplate = resolveTemplateForDayInternal(dayKey = dayKey, policy = policy, dao = dao)
                if (resolvedTemplate != null) {
                    val templateDerived =
                        buildTemplateDerivedAllocations(
                            dao = dao,
                            dayKey = dayKey,
                            templateId = resolvedTemplate.id,
                            source = SOURCE_TEMPLATE_AUTO,
                        )
                    if (templateDerived.isNotEmpty()) {
                        return templateDerived
                    }
                }
                if (explicit.isNotEmpty()) {
                    return explicit.map { it.toRecord() }
                }
            }
            logger.d(
                "DayPlanRepositoryImpl.getEffectiveAllocationsForDay",
                "No effective allocations found",
                mapOf("dayKey" to dayKey, "mode" to policy.mode),
            )
            return emptyList()
        }

        /**
         * Updates the set allocation.
         */
        override suspend fun setAllocation(
            dayKey: String,
            dimensionId: String,
            plannedMinutes: Int,
            source: String,
            templateId: String?,
        ) {
            requireTodayOrFuture(dayKey)
            setDayMode(dayKey = dayKey, mode = MODE_CUSTOM, templateId = null)
            val now = LocalDateTime.now().format(formatter)
            val existing = sessionManager.requireDatabase().dayPlanDao().getAllocationForDayAndDimension(dayKey, dimensionId)
            val entity =
                DayPlanAllocationEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    dayKey = dayKey,
                    dimensionId = dimensionId,
                    plannedMinutes = plannedMinutes.coerceAtLeast(0),
                    source = source,
                    templateId = templateId,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            sessionManager.requireDatabase().dayPlanDao().insertAllocation(entity)
            markDirtyForDay(dayKey, "day_plan_set_single_allocation")
            logger.i(
                "DayPlanRepositoryImpl.setAllocation",
                "Set day allocation",
                mapOf("dayKey" to dayKey, "dimensionId" to dimensionId, "minutes" to plannedMinutes.toString()),
            )
        }

        /**
         * Updates the set allocations.
         */
        override suspend fun setAllocations(
            dayKey: String,
            allocations: Map<String, Int>,
            source: String,
            templateId: String?,
        ) {
            requireTodayOrFuture(dayKey)
            setDayMode(dayKey = dayKey, mode = MODE_CUSTOM, templateId = null)
            sessionManager.requireDatabase().withTransaction {
                val now = LocalDateTime.now().format(formatter)
                // Replace as one atomic batch to avoid partial states during high-frequency updates.
                sessionManager.requireDatabase().dayPlanDao().deleteAllocationsForDay(dayKey)
                val entities =
                    allocations.map { (dimensionId, minutes) ->
                        DayPlanAllocationEntity(
                            id = UUID.randomUUID().toString(),
                            dayKey = dayKey,
                            dimensionId = dimensionId,
                            plannedMinutes = minutes.coerceAtLeast(0),
                            source = source,
                            templateId = templateId,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                sessionManager.requireDatabase().dayPlanDao().insertAllocations(entities)
            }
            logger.i(
                "DayPlanRepositoryImpl.setAllocations",
                "Set day allocations batch",
                mapOf("dayKey" to dayKey, "count" to allocations.size.toString(), "source" to source),
            )
            markDirtyForDay(dayKey, "day_plan_set_allocations_batch")
        }

        /**
         * Updates the apply template to day.
         */
        override suspend fun applyTemplateToDay(
            dayKey: String,
            templateId: String,
        ) {
            requireTodayOrFuture(dayKey)
            setDayMode(dayKey = dayKey, mode = MODE_TEMPLATE, templateId = templateId)
            val templateAllocations = sessionManager.requireDatabase().dayPlanDao().getTemplateAllocations(templateId)
            if (templateAllocations.isEmpty()) {
                logger.w(
                    "DayPlanRepositoryImpl.applyTemplateToDay",
                    "Template has no allocations",
                    mapOf("templateId" to templateId),
                )
                return
            }
            val entities =
                sessionManager.requireDatabase().withTransaction {
                    val now = LocalDateTime.now().format(formatter)
                    sessionManager.requireDatabase().dayPlanDao().deleteAllocationsForDay(dayKey)
                    val replacementAllocations =
                        templateAllocations.map { ta ->
                            DayPlanAllocationEntity(
                                id = UUID.randomUUID().toString(),
                                dayKey = dayKey,
                                dimensionId = ta.dimensionId,
                                plannedMinutes = ta.plannedMinutes,
                                source = SOURCE_TEMPLATE,
                                templateId = templateId,
                                createdAt = now,
                                updatedAt = now,
                            )
                        }
                    sessionManager.requireDatabase().dayPlanDao().insertAllocations(replacementAllocations)
                    replacementAllocations
                }
            logger.i(
                "DayPlanRepositoryImpl.applyTemplateToDay",
                "Applied template to day",
                mapOf("dayKey" to dayKey, "templateId" to templateId, "allocations" to entities.size.toString()),
            )
            markDirtyForDay(dayKey, "day_plan_apply_template")
        }

        /**
         * Removes the clear day plan.
         */
        override suspend fun clearDayPlan(dayKey: String) {
            requireTodayOrFuture(dayKey)
            sessionManager.requireDatabase().withTransaction {
                sessionManager.requireDatabase().dayPlanDao().deleteAllocationsForDay(dayKey)
                setDayMode(dayKey = dayKey, mode = MODE_AUTO, templateId = null)
            }
            markDirtyForDay(dayKey, "day_plan_cleared")
            logger.i("DayPlanRepositoryImpl.clearDayPlan", "Cleared day plan and reset mode to auto", mapOf("dayKey" to dayKey))
        }

        /**
         * Returns the day policy.
         */
        override suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord {
            logger.d("DayPlanRepositoryImpl.getDayPolicy", "Fetching day policy", mapOf("dayKey" to dayKey))
            val persisted = sessionManager.requireDatabase().dayPlanDao().getDayPolicy(dayKey)
            return getDayPolicyFromEntity(dayKey, persisted)
        }

        /**
         * Updates the set day mode.
         */
        override suspend fun setDayMode(
            dayKey: String,
            mode: String,
            templateId: String?,
        ) {
            requireTodayOrFuture(dayKey)
            require(mode == MODE_AUTO || mode == MODE_TEMPLATE || mode == MODE_CUSTOM) {
                "Unsupported day mode: $mode"
            }
            val existing = sessionManager.requireDatabase().dayPlanDao().getDayPolicy(dayKey)
            sessionManager.requireDatabase().dayPlanDao().upsertDayPolicy(
                DayPlanPolicyEntity(
                    dayKey = dayKey,
                    mode = mode,
                    templateId = if (mode == MODE_TEMPLATE) templateId?.ifBlank { null } else null,
                    isStarred = existing?.isStarred ?: 0,
                    updatedAt = LocalDateTime.now().format(formatter),
                ),
            )
            logger.i(
                "DayPlanRepositoryImpl.setDayMode",
                "Updated day mode",
                mapOf("dayKey" to dayKey, "mode" to mode, "templateId" to (templateId ?: "null")),
            )
            markDirtyForDay(dayKey, "day_plan_mode_changed")
        }

        /**
         * Updates the set day starred.
         */
        override suspend fun setDayStarred(
            dayKey: String,
            isStarred: Boolean,
        ) {
            requireTodayOrFuture(dayKey)
            val existing = sessionManager.requireDatabase().dayPlanDao().getDayPolicy(dayKey)
            sessionManager.requireDatabase().dayPlanDao().upsertDayPolicy(
                DayPlanPolicyEntity(
                    dayKey = dayKey,
                    mode = existing?.mode ?: MODE_AUTO,
                    templateId = existing?.templateId,
                    isStarred = if (isStarred) 1 else 0,
                    updatedAt = LocalDateTime.now().format(formatter),
                ),
            )
            logger.i(
                "DayPlanRepositoryImpl.setDayStarred",
                "Updated starred status",
                mapOf("dayKey" to dayKey, "isStarred" to isStarred.toString()),
            )
            markDirtyForDay(dayKey, "day_plan_starred_changed")
        }

        /**
         * Returns the day type template preference.
         */
        override suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord {
            require(dayType == DAY_TYPE_WEEKDAY || dayType == DAY_TYPE_WEEKEND || dayType == DAY_TYPE_STARRED) {
                "Unsupported day type: $dayType"
            }
            logger.d(
                "DayPlanRepositoryImpl.getDayTypeTemplatePreference",
                "Fetching day type template preference",
                mapOf("dayType" to dayType),
            )
            val preference = sessionManager.requireDatabase().dayPlanDao().getDayTypeTemplatePreference(dayType)
            return DayTypeTemplatePreferenceRecord(
                dayType = dayType,
                templateId = preference?.templateId,
            )
        }

        /**
         * Updates the set day type template preference.
         */
        override suspend fun setDayTypeTemplatePreference(
            dayType: String,
            templateId: String?,
        ) {
            require(dayType == DAY_TYPE_WEEKDAY || dayType == DAY_TYPE_WEEKEND || dayType == DAY_TYPE_STARRED) {
                "Unsupported day type: $dayType"
            }
            sessionManager.requireDatabase().dayPlanDao().upsertDayTypeTemplatePreference(
                DayTypeTemplatePreferenceEntity(
                    dayType = dayType,
                    templateId = templateId?.ifBlank { null },
                    updatedAt = LocalDateTime.now().format(formatter),
                ),
            )
            logger.i(
                "DayPlanRepositoryImpl.setDayTypeTemplatePreference",
                "Updated day type template preference",
                mapOf("dayType" to dayType, "templateId" to (templateId ?: "null")),
            )
        }

        /**
         * Returns the template for day.
         */
        override suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord? {
            logger.d(
                "DayPlanRepositoryImpl.resolveTemplateForDay",
                "Resolving template for day",
                mapOf("dayKey" to dayKey),
            )
            val dao = sessionManager.requireDatabase().dayPlanDao()
            val policy = getDayPolicyFromEntity(dayKey, dao.getDayPolicy(dayKey))
            return resolveTemplateForDayInternal(dayKey = dayKey, policy = policy, dao = dao)
        }

        // ---- Templates ----

        /**
         * Registers the observe active templates.
         */
        override fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>> {
            logger.d("DayPlanRepositoryImpl.observeActiveTemplates", "Subscribing to active templates")
            return sessionManager.requireDatabase().dayPlanDao().observeActiveTemplates().map { templates ->
                templates.toRecordsWithSharedAllocations()
            }
        }

        /**
         * Registers the observe all templates.
         */
        override fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>> {
            logger.d("DayPlanRepositoryImpl.observeAllTemplates", "Subscribing to all templates")
            return sessionManager.requireDatabase().dayPlanDao().observeAllTemplates().map { templates ->
                templates.toRecordsWithSharedAllocations()
            }
        }

        /**
         * Returns the template by id.
         */
        override suspend fun getTemplateById(id: String): DayPlanTemplateRecord? {
            logger.d("DayPlanRepositoryImpl.getTemplateById", "Fetching template by id", mapOf("id" to id))
            val entity =
                sessionManager.requireDatabase().dayPlanDao().getTemplateById(id) ?: run {
                    logger.d("DayPlanRepositoryImpl.getTemplateById", "Template not found", mapOf("id" to id))
                    return null
                }
            return entity.toRecord()
        }

        /**
         * Creates the create template.
         */
        override suspend fun createTemplate(
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ): String {
            val activeCount = sessionManager.requireDatabase().dayPlanDao().getActiveTemplateCount()
            check(activeCount < DayPlanRepository.MAX_TEMPLATE_COUNT) {
                "Maximum template count (${DayPlanRepository.MAX_TEMPLATE_COUNT}) reached"
            }
            val now = LocalDateTime.now().format(formatter)
            val templateId = UUID.randomUUID().toString()
            val template =
                DayPlanTemplateEntity(
                    id = templateId,
                    name = name.trim(),
                    description = description?.trim()?.ifEmpty { null },
                    isActive = 1,
                    sortOrder = activeCount,
                    createdAt = now,
                    updatedAt = now,
                )
            val allocationEntities =
                sessionManager.requireDatabase().withTransaction {
                    sessionManager.requireDatabase().dayPlanDao().insertTemplate(template)
                    val createdAllocations =
                        allocations
                            .filter { it.value > 0 }
                            .map { (dimensionId, minutes) ->
                                DayPlanTemplateAllocationEntity(
                                    id = UUID.randomUUID().toString(),
                                    templateId = templateId,
                                    dimensionId = dimensionId,
                                    plannedMinutes = minutes,
                                    createdAt = now,
                                    updatedAt = now,
                                )
                            }
                    if (createdAllocations.isNotEmpty()) {
                        sessionManager.requireDatabase().dayPlanDao().insertTemplateAllocations(createdAllocations)
                    }
                    createdAllocations
                }

            logger.i(
                "DayPlanRepositoryImpl.createTemplate",
                "Created template",
                mapOf("id" to templateId, "name" to name, "allocations" to allocationEntities.size.toString()),
            )
            return templateId
        }

        /**
         * Updates the update template.
         */
        override suspend fun updateTemplate(
            id: String,
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ) {
            val existing = sessionManager.requireDatabase().dayPlanDao().getTemplateById(id) ?: return
            val now = LocalDateTime.now().format(formatter)
            sessionManager.requireDatabase().withTransaction {
                sessionManager.requireDatabase().dayPlanDao().insertTemplate(
                    existing.copy(
                        name = name.trim(),
                        description = description?.trim()?.ifEmpty { null },
                        updatedAt = now,
                    ),
                )
                sessionManager.requireDatabase().dayPlanDao().deleteTemplateAllocations(id)
                val allocationEntities =
                    allocations
                        .filter { it.value > 0 }
                        .map { (dimensionId, minutes) ->
                            DayPlanTemplateAllocationEntity(
                                id = UUID.randomUUID().toString(),
                                templateId = id,
                                dimensionId = dimensionId,
                                plannedMinutes = minutes,
                                createdAt = now,
                                updatedAt = now,
                            )
                        }
                if (allocationEntities.isNotEmpty()) {
                    sessionManager.requireDatabase().dayPlanDao().insertTemplateAllocations(allocationEntities)
                }
            }
            logger.i(
                "DayPlanRepositoryImpl.updateTemplate",
                "Updated template",
                mapOf("id" to id, "name" to name),
            )
        }

        /**
         * Removes the delete template.
         */
        override suspend fun deleteTemplate(id: String) {
            val now = LocalDateTime.now().format(formatter)
            sessionManager.requireDatabase().dayPlanDao().softDeleteTemplate(id, now)
            logger.i("DayPlanRepositoryImpl.deleteTemplate", "Soft-deleted template", mapOf("id" to id))
        }

        // ---- Private helpers ----

        private fun requireTodayOrFuture(dayKey: String) {
            val today = LocalDate.now().toString()
            require(dayKey >= today) {
                "Cannot modify day plan for past date: $dayKey (today: $today)"
            }
        }

        private suspend fun buildTemplateDerivedAllocations(
            dao: DayPlanDao,
            dayKey: String,
            templateId: String,
            source: String,
        ): List<DayPlanAllocationRecord> {
            val template = dao.getTemplateById(templateId)
            if (template == null || template.isActive != 1) {
                return emptyList()
            }
            return dao.getTemplateAllocations(templateId).map { allocation ->
                DayPlanAllocationRecord(
                    id = "virtual_${templateId}_${allocation.dimensionId}",
                    dayKey = dayKey,
                    dimensionId = allocation.dimensionId,
                    plannedMinutes = allocation.plannedMinutes,
                    source = source,
                    templateId = templateId,
                )
            }
        }

        private fun getDayPolicyFromEntity(
            dayKey: String,
            persisted: DayPlanPolicyEntity?,
        ): DayPlanPolicyRecord =
            DayPlanPolicyRecord(
                dayKey = dayKey,
                mode = persisted?.mode ?: MODE_AUTO,
                templateId = persisted?.templateId,
                isStarred = persisted?.isStarred == 1,
            )

        private suspend fun resolveTemplateForDayInternal(
            dayKey: String,
            policy: DayPlanPolicyRecord,
            dao: DayPlanDao,
        ): DayPlanTemplateRecord? {
            val resolvedTemplateId =
                when {
                    policy.mode == MODE_TEMPLATE && !policy.templateId.isNullOrBlank() -> policy.templateId
                    else -> resolveAutoTemplateIdForDay(dayKey = dayKey, isStarred = policy.isStarred, dao = dao)
                } ?: run {
                    logger.d(
                        "DayPlanRepositoryImpl.resolveTemplateForDay",
                        "No template resolved for day",
                        mapOf("dayKey" to dayKey, "mode" to policy.mode),
                    )
                    return null
                }
            val template = dao.getTemplateById(resolvedTemplateId) ?: return null
            if (template.isActive != 1) {
                logger.d(
                    "DayPlanRepositoryImpl.resolveTemplateForDay",
                    "Resolved template is inactive",
                    mapOf("dayKey" to dayKey, "templateId" to resolvedTemplateId),
                )
                return null
            }
            logger.d(
                "DayPlanRepositoryImpl.resolveTemplateForDay",
                "Resolved template",
                mapOf(
                    "dayKey" to dayKey,
                    "templateId" to resolvedTemplateId,
                    "templateName" to template.name,
                ),
            )
            return template.toRecord()
        }

        private suspend fun resolveAutoTemplateIdForDay(
            dayKey: String,
            isStarred: Boolean,
            dao: DayPlanDao,
        ): String? {
            if (isStarred) {
                dao.getDayTypeTemplatePreference(DAY_TYPE_STARRED)?.templateId?.let { return it }
            }
            val date = LocalDate.parse(dayKey)
            val dayType =
                when (date.dayOfWeek.value) {
                    6, 7 -> DAY_TYPE_WEEKEND
                    else -> DAY_TYPE_WEEKDAY
                }
            return dao.getDayTypeTemplatePreference(dayType)?.templateId
        }

        private fun DayPlanAllocationEntity.toRecord() =
            DayPlanAllocationRecord(
                id = id,
                dayKey = dayKey,
                dimensionId = dimensionId,
                plannedMinutes = plannedMinutes,
                source = source,
                templateId = templateId,
            )

        private suspend fun DayPlanTemplateEntity.toRecord(): DayPlanTemplateRecord {
            val allocations =
                sessionManager.requireDatabase().dayPlanDao().getTemplateAllocations(id).map { ta ->
                    TemplateAllocationRecord(
                        id = ta.id,
                        templateId = ta.templateId,
                        dimensionId = ta.dimensionId,
                        plannedMinutes = ta.plannedMinutes,
                    )
                }
            return DayPlanTemplateRecord(
                id = id,
                name = name,
                description = description,
                isActive = isActive == 1,
                sortOrder = sortOrder,
                allocations = allocations,
            )
        }

        private suspend fun markDirtyForDay(
            dayKey: String,
            reason: String,
        ) {
            markLensDayDirty(
                dailyInsightDao = sessionManager.requireDatabase().dailyInsightDao(),
                logger = logger,
                dayKey = dayKey,
                changedModules = setOf("day_plan"),
                reason = reason,
            )
        }

        private suspend fun List<DayPlanTemplateEntity>.toRecordsWithSharedAllocations(): List<DayPlanTemplateRecord> {
            if (isEmpty()) return emptyList()
            val templateIds = map { it.id }
            val allocationsByTemplateId =
                sessionManager
                    .requireDatabase()
                    .dayPlanDao()
                    .getTemplateAllocationsForTemplateIds(templateIds)
                    .groupBy { it.templateId }
            return map { template ->
                DayPlanTemplateRecord(
                    id = template.id,
                    name = template.name,
                    description = template.description,
                    isActive = template.isActive == 1,
                    sortOrder = template.sortOrder,
                    allocations =
                        allocationsByTemplateId[template.id].orEmpty().map { ta ->
                            TemplateAllocationRecord(
                                id = ta.id,
                                templateId = ta.templateId,
                                dimensionId = ta.dimensionId,
                                plannedMinutes = ta.plannedMinutes,
                            )
                        },
                )
            }
        }
    }
