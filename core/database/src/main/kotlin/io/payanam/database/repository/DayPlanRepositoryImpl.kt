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
 * DayPlanRepositoryImpl.
 */
class DayPlanRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : DayPlanRepository {
        private val logger = UnifiedLogger.getInstance()
        private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        // ---- Day Allocations ----

        override fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>> {
            logger.d("DayPlanRepositoryImpl.observeAllocationsForDay", "Subscribing to allocations for day", mapOf("dayKey" to dayKey))
            return sessionManager.requireDatabase().dayPlanDao().observeAllocationsForDay(dayKey).map { entities ->
                entities.map { it.toRecord() }
            }
        }

        override suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> {
            logger.d("DayPlanRepositoryImpl.getAllocationsForDay", "Fetching allocations for day", mapOf("dayKey" to dayKey))
            return sessionManager
                .requireDatabase()
                .dayPlanDao()
                .getAllocationsForDay(dayKey)
                .map { it.toRecord() }
        }

        override suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> {
            logger.d(
                "DayPlanRepositoryImpl.getEffectiveAllocationsForDay",
                "Resolving effective allocations",
                /** Map of. */
                mapOf("dayKey" to dayKey),
            )
            /** Dao. */
            val dao = sessionManager.requireDatabase().dayPlanDao()
            /** Policy. */
            val policy = getDayPolicyFromEntity(dayKey, dao.getDayPolicy(dayKey))
            /** Explicit. */
            val explicit = dao.getAllocationsForDay(dayKey)
            /** If. */
            if (policy.mode == MODE_CUSTOM && explicit.isNotEmpty()) {
                logger.d(
                    "DayPlanRepositoryImpl.getEffectiveAllocationsForDay",
                    "Using custom explicit allocations",
                    /** Map of. */
                    mapOf("dayKey" to dayKey, "count" to explicit.size),
                )
                return explicit.map { it.toRecord() }
            }
            /** If. */
            if (policy.mode == MODE_TEMPLATE) {
                /** Template id. */
                val templateId = policy.templateId
                /** If. */
                if (!templateId.isNullOrBlank()) {
                    /** Template derived. */
                    val templateDerived =
                        /** Build template derived allocations. */
                        buildTemplateDerivedAllocations(
                            dao = dao,
                            dayKey = dayKey,
                            templateId = templateId,
                            source = SOURCE_TEMPLATE,
                        )
                    /** If. */
                    if (templateDerived.isNotEmpty()) {
                        return templateDerived
                    }
                }
                /** If. */
                if (explicit.isNotEmpty()) {
                    return explicit.map { it.toRecord() }
                }
            }
            /** If. */
            if (policy.mode == MODE_AUTO) {
                /** Resolved template. */
                val resolvedTemplate = resolveTemplateForDayInternal(dayKey = dayKey, policy = policy, dao = dao)
                /** If. */
                if (resolvedTemplate != null) {
                    /** Template derived. */
                    val templateDerived =
                        /** Build template derived allocations. */
                        buildTemplateDerivedAllocations(
                            dao = dao,
                            dayKey = dayKey,
                            templateId = resolvedTemplate.id,
                            source = SOURCE_TEMPLATE_AUTO,
                        )
                    /** If. */
                    if (templateDerived.isNotEmpty()) {
                        return templateDerived
                    }
                }
                /** If. */
                if (explicit.isNotEmpty()) {
                    return explicit.map { it.toRecord() }
                }
            }
            logger.d(
                "DayPlanRepositoryImpl.getEffectiveAllocationsForDay",
                "No effective allocations found",
                /** Map of. */
                mapOf("dayKey" to dayKey, "mode" to policy.mode),
            )
            return emptyList()
        }

        override suspend fun setAllocation(
            /** Day key. */
            dayKey: String,
            /** Dimension id. */
            dimensionId: String,
            /** Planned minutes. */
            plannedMinutes: Int,
            /** Source. */
            source: String,
            templateId: String?,
        ) {
            /** Require today or future. */
            requireTodayOrFuture(dayKey)
            /** Set day mode. */
            setDayMode(dayKey = dayKey, mode = MODE_CUSTOM, templateId = null)
            /** Now. */
            val now = LocalDateTime.now().format(formatter)
            /** Existing. */
            val existing = sessionManager.requireDatabase().dayPlanDao().getAllocationForDayAndDimension(dayKey, dimensionId)
            /** Entity. */
            val entity =
                /** Day plan allocation entity. */
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
            /** Mark dirty for day. */
            markDirtyForDay(dayKey, "day_plan_set_single_allocation")
            logger.i(
                "DayPlanRepositoryImpl.setAllocation",
                "Set day allocation",
                /** Map of. */
                mapOf("dayKey" to dayKey, "dimensionId" to dimensionId, "minutes" to plannedMinutes.toString()),
            )
        }

        override suspend fun setAllocations(
            /** Day key. */
            dayKey: String,
            allocations: Map<String, Int>,
            /** Source. */
            source: String,
            templateId: String?,
        ) {
            /** Require today or future. */
            requireTodayOrFuture(dayKey)
            /** Set day mode. */
            setDayMode(dayKey = dayKey, mode = MODE_CUSTOM, templateId = null)
            sessionManager.requireDatabase().withTransaction {
                /** Now. */
                val now = LocalDateTime.now().format(formatter)
                // Replace as one atomic batch to avoid partial states during high-frequency updates.
                sessionManager.requireDatabase().dayPlanDao().deleteAllocationsForDay(dayKey)
                /** Entities. */
                val entities =
                    allocations.map { (dimensionId, minutes) ->
                        /** Day plan allocation entity. */
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
                /** Map of. */
                mapOf("dayKey" to dayKey, "count" to allocations.size.toString(), "source" to source),
            )
            /** Mark dirty for day. */
            markDirtyForDay(dayKey, "day_plan_set_allocations_batch")
        }

        override suspend fun applyTemplateToDay(
            /** Day key. */
            dayKey: String,
            /** Template id. */
            templateId: String,
        ) {
            /** Require today or future. */
            requireTodayOrFuture(dayKey)
            /** Set day mode. */
            setDayMode(dayKey = dayKey, mode = MODE_TEMPLATE, templateId = templateId)
            /** Template allocations. */
            val templateAllocations = sessionManager.requireDatabase().dayPlanDao().getTemplateAllocations(templateId)
            /** If. */
            if (templateAllocations.isEmpty()) {
                logger.w(
                    "DayPlanRepositoryImpl.applyTemplateToDay",
                    "Template has no allocations",
                    /** Map of. */
                    mapOf("templateId" to templateId),
                )
                /** Return. */
                return
            }
            /** Entities. */
            val entities =
                sessionManager.requireDatabase().withTransaction {
                    /** Now. */
                    val now = LocalDateTime.now().format(formatter)
                    sessionManager.requireDatabase().dayPlanDao().deleteAllocationsForDay(dayKey)
                    /** Replacement allocations. */
                    val replacementAllocations =
                        templateAllocations.map { ta ->
                            /** Day plan allocation entity. */
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
                    /** Replacement allocations. */
                    replacementAllocations
                }
            logger.i(
                "DayPlanRepositoryImpl.applyTemplateToDay",
                "Applied template to day",
                /** Map of. */
                mapOf("dayKey" to dayKey, "templateId" to templateId, "allocations" to entities.size.toString()),
            )
            /** Mark dirty for day. */
            markDirtyForDay(dayKey, "day_plan_apply_template")
        }

        override suspend fun clearDayPlan(dayKey: String) {
            /** Require today or future. */
            requireTodayOrFuture(dayKey)
            sessionManager.requireDatabase().withTransaction {
                sessionManager.requireDatabase().dayPlanDao().deleteAllocationsForDay(dayKey)
                /** Set day mode. */
                setDayMode(dayKey = dayKey, mode = MODE_AUTO, templateId = null)
            }
            /** Mark dirty for day. */
            markDirtyForDay(dayKey, "day_plan_cleared")
            logger.i("DayPlanRepositoryImpl.clearDayPlan", "Cleared day plan and reset mode to auto", mapOf("dayKey" to dayKey))
        }

        override suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord {
            logger.d("DayPlanRepositoryImpl.getDayPolicy", "Fetching day policy", mapOf("dayKey" to dayKey))
            /** Persisted. */
            val persisted = sessionManager.requireDatabase().dayPlanDao().getDayPolicy(dayKey)
            return getDayPolicyFromEntity(dayKey, persisted)
        }

        override suspend fun setDayMode(
            /** Day key. */
            dayKey: String,
            /** Mode. */
            mode: String,
            templateId: String?,
        ) {
            /** Require today or future. */
            requireTodayOrFuture(dayKey)
            /** Require. */
            require(mode == MODE_AUTO || mode == MODE_TEMPLATE || mode == MODE_CUSTOM) {
                "Unsupported day mode: $mode"
            }
            /** Existing. */
            val existing = sessionManager.requireDatabase().dayPlanDao().getDayPolicy(dayKey)
            sessionManager.requireDatabase().dayPlanDao().upsertDayPolicy(
                /** Day plan policy entity. */
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
                /** Map of. */
                mapOf("dayKey" to dayKey, "mode" to mode, "templateId" to (templateId ?: "null")),
            )
            /** Mark dirty for day. */
            markDirtyForDay(dayKey, "day_plan_mode_changed")
        }

        override suspend fun setDayStarred(
            /** Day key. */
            dayKey: String,
            /** Is starred. */
            isStarred: Boolean,
        ) {
            /** Require today or future. */
            requireTodayOrFuture(dayKey)
            /** Existing. */
            val existing = sessionManager.requireDatabase().dayPlanDao().getDayPolicy(dayKey)
            sessionManager.requireDatabase().dayPlanDao().upsertDayPolicy(
                /** Day plan policy entity. */
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
                /** Map of. */
                mapOf("dayKey" to dayKey, "isStarred" to isStarred.toString()),
            )
            /** Mark dirty for day. */
            markDirtyForDay(dayKey, "day_plan_starred_changed")
        }

        override suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord {
            /** Require. */
            require(dayType == DAY_TYPE_WEEKDAY || dayType == DAY_TYPE_WEEKEND || dayType == DAY_TYPE_STARRED) {
                "Unsupported day type: $dayType"
            }
            logger.d(
                "DayPlanRepositoryImpl.getDayTypeTemplatePreference",
                "Fetching day type template preference",
                /** Map of. */
                mapOf("dayType" to dayType),
            )
            /** Preference. */
            val preference = sessionManager.requireDatabase().dayPlanDao().getDayTypeTemplatePreference(dayType)
            return DayTypeTemplatePreferenceRecord(
                dayType = dayType,
                templateId = preference?.templateId,
            )
        }

        override suspend fun setDayTypeTemplatePreference(
            /** Day type. */
            dayType: String,
            templateId: String?,
        ) {
            /** Require. */
            require(dayType == DAY_TYPE_WEEKDAY || dayType == DAY_TYPE_WEEKEND || dayType == DAY_TYPE_STARRED) {
                "Unsupported day type: $dayType"
            }
            sessionManager.requireDatabase().dayPlanDao().upsertDayTypeTemplatePreference(
                /** Day type template preference entity. */
                DayTypeTemplatePreferenceEntity(
                    dayType = dayType,
                    templateId = templateId?.ifBlank { null },
                    updatedAt = LocalDateTime.now().format(formatter),
                ),
            )
            logger.i(
                "DayPlanRepositoryImpl.setDayTypeTemplatePreference",
                "Updated day type template preference",
                /** Map of. */
                mapOf("dayType" to dayType, "templateId" to (templateId ?: "null")),
            )
        }

        override suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord? {
            logger.d(
                "DayPlanRepositoryImpl.resolveTemplateForDay",
                "Resolving template for day",
                /** Map of. */
                mapOf("dayKey" to dayKey),
            )
            /** Dao. */
            val dao = sessionManager.requireDatabase().dayPlanDao()
            /** Policy. */
            val policy = getDayPolicyFromEntity(dayKey, dao.getDayPolicy(dayKey))
            return resolveTemplateForDayInternal(dayKey = dayKey, policy = policy, dao = dao)
        }

        // ---- Templates ----

        override fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>> {
            logger.d("DayPlanRepositoryImpl.observeActiveTemplates", "Subscribing to active templates")
            return sessionManager.requireDatabase().dayPlanDao().observeActiveTemplates().map { templates ->
                templates.toRecordsWithSharedAllocations()
            }
        }

        override fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>> {
            logger.d("DayPlanRepositoryImpl.observeAllTemplates", "Subscribing to all templates")
            return sessionManager.requireDatabase().dayPlanDao().observeAllTemplates().map { templates ->
                templates.toRecordsWithSharedAllocations()
            }
        }

        override suspend fun getTemplateById(id: String): DayPlanTemplateRecord? {
            logger.d("DayPlanRepositoryImpl.getTemplateById", "Fetching template by id", mapOf("id" to id))
            /** Entity. */
            val entity =
                sessionManager.requireDatabase().dayPlanDao().getTemplateById(id) ?: run {
                    logger.d("DayPlanRepositoryImpl.getTemplateById", "Template not found", mapOf("id" to id))
                    return null
                }
            return entity.toRecord()
        }

        override suspend fun createTemplate(
            /** Name. */
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ): String {
            /** Active count. */
            val activeCount = sessionManager.requireDatabase().dayPlanDao().getActiveTemplateCount()
            /** Check. */
            check(activeCount < DayPlanRepository.MAX_TEMPLATE_COUNT) {
                "Maximum template count (${DayPlanRepository.MAX_TEMPLATE_COUNT}) reached"
            }
            /** Now. */
            val now = LocalDateTime.now().format(formatter)
            /** Template id. */
            val templateId = UUID.randomUUID().toString()
            /** Template. */
            val template =
                /** Day plan template entity. */
                DayPlanTemplateEntity(
                    id = templateId,
                    name = name.trim(),
                    description = description?.trim()?.ifEmpty { null },
                    isActive = 1,
                    sortOrder = activeCount,
                    createdAt = now,
                    updatedAt = now,
                )
            /** Allocation entities. */
            val allocationEntities =
                sessionManager.requireDatabase().withTransaction {
                    sessionManager.requireDatabase().dayPlanDao().insertTemplate(template)
                    /** Created allocations. */
                    val createdAllocations =
                        /** Allocations. */
                        allocations
                            .filter { it.value > 0 }
                            .map { (dimensionId, minutes) ->
                                /** Day plan template allocation entity. */
                                DayPlanTemplateAllocationEntity(
                                    id = UUID.randomUUID().toString(),
                                    templateId = templateId,
                                    dimensionId = dimensionId,
                                    plannedMinutes = minutes,
                                    createdAt = now,
                                    updatedAt = now,
                                )
                            }
                    /** If. */
                    if (createdAllocations.isNotEmpty()) {
                        sessionManager.requireDatabase().dayPlanDao().insertTemplateAllocations(createdAllocations)
                    }
                    /** Created allocations. */
                    createdAllocations
                }

            logger.i(
                "DayPlanRepositoryImpl.createTemplate",
                "Created template",
                /** Map of. */
                mapOf("id" to templateId, "name" to name, "allocations" to allocationEntities.size.toString()),
            )
            return templateId
        }

        override suspend fun updateTemplate(
            /** Id. */
            id: String,
            /** Name. */
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ) {
            /** Existing. */
            val existing = sessionManager.requireDatabase().dayPlanDao().getTemplateById(id) ?: return
            /** Now. */
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
                /** Allocation entities. */
                val allocationEntities =
                    /** Allocations. */
                    allocations
                        .filter { it.value > 0 }
                        .map { (dimensionId, minutes) ->
                            /** Day plan template allocation entity. */
                            DayPlanTemplateAllocationEntity(
                                id = UUID.randomUUID().toString(),
                                templateId = id,
                                dimensionId = dimensionId,
                                plannedMinutes = minutes,
                                createdAt = now,
                                updatedAt = now,
                            )
                        }
                /** If. */
                if (allocationEntities.isNotEmpty()) {
                    sessionManager.requireDatabase().dayPlanDao().insertTemplateAllocations(allocationEntities)
                }
            }
            logger.i(
                "DayPlanRepositoryImpl.updateTemplate",
                "Updated template",
                /** Map of. */
                mapOf("id" to id, "name" to name),
            )
        }

        override suspend fun deleteTemplate(id: String) {
            /** Now. */
            val now = LocalDateTime.now().format(formatter)
            sessionManager.requireDatabase().dayPlanDao().softDeleteTemplate(id, now)
            logger.i("DayPlanRepositoryImpl.deleteTemplate", "Soft-deleted template", mapOf("id" to id))
        }

        // ---- Private helpers ----

        private fun requireTodayOrFuture(dayKey: String) {
            /** Today. */
            val today = LocalDate.now().toString()
            /** Require. */
            require(dayKey >= today) {
                "Cannot modify day plan for past date: $dayKey (today: $today)"
            }
        }

        private suspend fun buildTemplateDerivedAllocations(
            /** Dao. */
            dao: DayPlanDao,
            /** Day key. */
            dayKey: String,
            /** Template id. */
            templateId: String,
            /** Source. */
            source: String,
        ): List<DayPlanAllocationRecord> {
            /** Template. */
            val template = dao.getTemplateById(templateId)
            /** If. */
            if (template == null || template.isActive != 1) {
                return emptyList()
            }
            return dao.getTemplateAllocations(templateId).map { allocation ->
                /** Day plan allocation record. */
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
            /** Day key. */
            dayKey: String,
            persisted: DayPlanPolicyEntity?,
        ): DayPlanPolicyRecord =
            /** Day plan policy record. */
            DayPlanPolicyRecord(
                dayKey = dayKey,
                mode = persisted?.mode ?: MODE_AUTO,
                templateId = persisted?.templateId,
                isStarred = persisted?.isStarred == 1,
            )

        private suspend fun resolveTemplateForDayInternal(
            /** Day key. */
            dayKey: String,
            /** Policy. */
            policy: DayPlanPolicyRecord,
            /** Dao. */
            dao: DayPlanDao,
        ): DayPlanTemplateRecord? {
            /** Resolved template id. */
            val resolvedTemplateId =
                when {
                    policy.mode == MODE_TEMPLATE && !policy.templateId.isNullOrBlank() -> policy.templateId
                    else -> resolveAutoTemplateIdForDay(dayKey = dayKey, isStarred = policy.isStarred, dao = dao)
                } ?: run {
                    logger.d(
                        "DayPlanRepositoryImpl.resolveTemplateForDay",
                        "No template resolved for day",
                        /** Map of. */
                        mapOf("dayKey" to dayKey, "mode" to policy.mode),
                    )
                    return null
                }
            /** Template. */
            val template = dao.getTemplateById(resolvedTemplateId) ?: return null
            /** If. */
            if (template.isActive != 1) {
                logger.d(
                    "DayPlanRepositoryImpl.resolveTemplateForDay",
                    "Resolved template is inactive",
                    /** Map of. */
                    mapOf("dayKey" to dayKey, "templateId" to resolvedTemplateId),
                )
                return null
            }
            logger.d(
                "DayPlanRepositoryImpl.resolveTemplateForDay",
                "Resolved template",
                /** Map of. */
                mapOf(
                    "dayKey" to dayKey,
                    "templateId" to resolvedTemplateId,
                    "templateName" to template.name,
                ),
            )
            return template.toRecord()
        }

        private suspend fun resolveAutoTemplateIdForDay(
            /** Day key. */
            dayKey: String,
            /** Is starred. */
            isStarred: Boolean,
            /** Dao. */
            dao: DayPlanDao,
        ): String? {
            /** If. */
            if (isStarred) {
                dao.getDayTypeTemplatePreference(DAY_TYPE_STARRED)?.templateId?.let { return it }
            }
            /** Date. */
            val date = LocalDate.parse(dayKey)
            /** Day type. */
            val dayType =
                /** When. */
                when (date.dayOfWeek.value) {
                    6, 7 -> DAY_TYPE_WEEKEND
                    else -> DAY_TYPE_WEEKDAY
                }
            return dao.getDayTypeTemplatePreference(dayType)?.templateId
        }

        private fun DayPlanAllocationEntity.toRecord() =
            /** Day plan allocation record. */
            DayPlanAllocationRecord(
                id = id,
                dayKey = dayKey,
                dimensionId = dimensionId,
                plannedMinutes = plannedMinutes,
                source = source,
                templateId = templateId,
            )

        private suspend fun DayPlanTemplateEntity.toRecord(): DayPlanTemplateRecord {
            /** Allocations. */
            val allocations =
                sessionManager.requireDatabase().dayPlanDao().getTemplateAllocations(id).map { ta ->
                    /** Template allocation record. */
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
            /** Day key. */
            dayKey: String,
            /** Reason. */
            reason: String,
        ) {
            /** Mark lens day dirty. */
            markLensDayDirty(
                dailyInsightDao = sessionManager.requireDatabase().dailyInsightDao(),
                logger = logger,
                dayKey = dayKey,
                changedModules = setOf("day_plan"),
                reason = reason,
            )
        }

        private suspend fun List<DayPlanTemplateEntity>.toRecordsWithSharedAllocations(): List<DayPlanTemplateRecord> {
            /** If. */
            if (isEmpty()) return emptyList()
            /** Template ids. */
            val templateIds = map { it.id }
            /** Allocations by template id. */
            val allocationsByTemplateId =
                /** Session manager. */
                sessionManager
                    .requireDatabase()
                    .dayPlanDao()
                    .getTemplateAllocationsForTemplateIds(templateIds)
                    .groupBy { it.templateId }
            return map { template ->
                /** Day plan template record. */
                DayPlanTemplateRecord(
                    id = template.id,
                    name = template.name,
                    description = template.description,
                    isActive = template.isActive == 1,
                    sortOrder = template.sortOrder,
                    allocations =
                        allocationsByTemplateId[template.id].orEmpty().map { ta ->
                            /** Template allocation record. */
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
