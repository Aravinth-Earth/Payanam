//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.DayPlanAllocationEntity
import io.payanam.database.entity.DayPlanPolicyEntity
import io.payanam.database.entity.DayPlanTemplateAllocationEntity
import io.payanam.database.entity.DayPlanTemplateEntity
import io.payanam.database.entity.DayTypeTemplatePreferenceEntity
import io.payanam.domain.repository.DayPlanAllocationRecord
import io.payanam.domain.repository.DayPlanPolicyRecord
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.domain.repository.DayTypeTemplatePreferenceRecord
import io.payanam.domain.repository.TemplateAllocationRecord
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * JDBC implementation of [DayPlanRepository] over the encrypted database — a day's planned minutes per
 * dimension, the policy that decides where they came from, and the reusable templates behind them.
 *
 * Statements are copied from `DayPlanDao` and the behaviour mirrors `DayPlanRepositoryImpl`. The parts that
 * are easy to lose in a port, and are therefore test-pinned:
 *
 *  - **The mode decides what you see, not just what is stored.** `getEffectiveAllocationsForDay` returns the
 *    explicit rows only in CUSTOM mode; in TEMPLATE mode it derives from the named template and falls back
 *    to the explicit rows when the template yields nothing; in AUTO mode it resolves a template from the
 *    day type and falls back the same way. A port that simply returned the explicit rows would show an
 *    empty plan on every day whose template has not been applied yet.
 *  - **Template-derived allocations are VIRTUAL.** They are built on read with ids of the form
 *    `virtual_<templateId>_<dimensionId>` and are never written to `day_plan_allocations`. Writing them
 *    would turn every read into a write and pin the day to a snapshot of the template.
 *  - **Inactive templates resolve to nothing.** A soft-deleted template still has its rows and its
 *    allocations; both the resolver and the derivation check `is_active` and return empty/null.
 *  - **Past days are refused.** Every mutating call starts with `requireTodayOrFuture`, comparing day keys
 *    as strings (`YYYY-MM-DD` orders correctly). The template CRUD calls do not, because a template is not
 *    a day.
 *  - **`clearDayPlan` deletes and resets the mode in ONE transaction**, and `setDayMode` marks the day
 *    lens-dirty — so the transaction nests. [JdbcQueries.transaction] joins rather than opening a second
 *    one; see its KDoc for why that matters.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
@Suppress("TooManyFunctions")
class DayPlanRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : DayPlanRepository {
    private val logger = AppLogger.current

    /** `YYYY-MM-DD` from the system clock: ordering a day key as a string is the same as ordering the date. */
    private fun today(): String = LocalDate.now().toString()

    // ---- Day Allocations ----

    /** Emits the explicit allocations for [dayKey] — no template resolution. */
    override fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>> {
        logger.d(
            "DayPlanRepositoryJvm.observeAllocationsForDay",
            "Subscribing to allocations for day",
            mapOf("dayKey" to dayKey),
        )
        return JdbcFlows
            .observe(pollIntervalMs) { readAllocations(dayKey) }
            .map { entities -> entities.map { it.toRecord() } }
    }

    /** Returns the explicit allocations for [dayKey] as a one-shot list — no template resolution. */
    override suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> =
        JdbcFlows.onIo {
            logger.d(
                "DayPlanRepositoryJvm.getAllocationsForDay",
                "Fetching allocations for day",
                mapOf("dayKey" to dayKey),
            )
            readAllocations(dayKey).map { it.toRecord() }
        }

    /**
     * Resolves the allocations actually shown for [dayKey] given its mode.
     *
     * CUSTOM → the explicit rows (when there are any). TEMPLATE → the named template's derived rows, falling
     * back to the explicit rows. AUTO → the day-type template's derived rows, falling back the same way.
     * Empty when nothing resolves.
     */
    override suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> =
        JdbcFlows.onIo {
            logger.d(
                "DayPlanRepositoryJvm.getEffectiveAllocationsForDay",
                "Resolving effective allocations",
                mapOf("dayKey" to dayKey),
            )
            val policy = readPolicyRecord(dayKey)
            val explicit = readAllocations(dayKey)
            if (policy.mode == DayPlanRepository.MODE_CUSTOM && explicit.isNotEmpty()) {
                logger.d(
                    "DayPlanRepositoryJvm.getEffectiveAllocationsForDay",
                    "Using custom explicit allocations",
                    mapOf("dayKey" to dayKey, "count" to explicit.size),
                )
                return@onIo explicit.map { it.toRecord() }
            }
            if (policy.mode == DayPlanRepository.MODE_TEMPLATE) {
                val templateId = policy.templateId
                if (!templateId.isNullOrBlank()) {
                    val derived =
                        buildTemplateDerivedAllocations(
                            dayKey = dayKey,
                            templateId = templateId,
                            source = DayPlanRepository.SOURCE_TEMPLATE,
                        )
                    if (derived.isNotEmpty()) return@onIo derived
                }
                if (explicit.isNotEmpty()) return@onIo explicit.map { it.toRecord() }
            }
            if (policy.mode == DayPlanRepository.MODE_AUTO) {
                val resolved = resolveTemplateForDayInternal(dayKey = dayKey, policy = policy)
                if (resolved != null) {
                    val derived =
                        buildTemplateDerivedAllocations(
                            dayKey = dayKey,
                            templateId = resolved.id,
                            source = DayPlanRepository.SOURCE_TEMPLATE_AUTO,
                        )
                    if (derived.isNotEmpty()) return@onIo derived
                }
                if (explicit.isNotEmpty()) return@onIo explicit.map { it.toRecord() }
            }
            logger.d(
                "DayPlanRepositoryJvm.getEffectiveAllocationsForDay",
                "No effective allocations found",
                mapOf("dayKey" to dayKey, "mode" to policy.mode),
            )
            emptyList()
        }

    /** Sets one dimension's planned minutes for [dayKey], forcing CUSTOM mode. Rejects past days. */
    override suspend fun setAllocation(
        dayKey: String,
        dimensionId: String,
        plannedMinutes: Int,
        source: String,
        templateId: String?,
    ) {
        JdbcFlows.onIo {
            requireTodayOrFuture(dayKey)
            setDayModeCore(dayKey = dayKey, mode = DayPlanRepository.MODE_CUSTOM, templateId = null)
            val now = nowText()
            val existing = readAllocation(dayKey, dimensionId)
            val entity =
                DayPlanAllocationEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    dayKey = dayKey,
                    dimensionId = dimensionId,
                    // A negative plan is clamped rather than rejected: the UI can produce one while the
                    // user is still typing, and the stored value is what every later read reports.
                    plannedMinutes = plannedMinutes.coerceAtLeast(0),
                    source = source,
                    templateId = templateId,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            JdbcQueries.write(session, INSERT_ALLOCATION_SQL, entity.toBindings())
            markDirtyForDay(dayKey, "day_plan_set_single_allocation")
            logger.i(
                "DayPlanRepositoryJvm.setAllocation",
                "Set day allocation",
                mapOf(
                    "dayKey" to dayKey,
                    "dimensionId" to dimensionId,
                    "minutes" to plannedMinutes.toString(),
                ),
            )
        }
    }

    /** Replaces ALL of [dayKey]'s allocations with [allocations] in one transaction, forcing CUSTOM mode. */
    override suspend fun setAllocations(
        dayKey: String,
        allocations: Map<String, Int>,
        source: String,
        templateId: String?,
    ) {
        JdbcFlows.onIo {
            requireTodayOrFuture(dayKey)
            setDayModeCore(dayKey = dayKey, mode = DayPlanRepository.MODE_CUSTOM, templateId = null)
            // Delete-then-insert as one transaction: a partial state would show a day with half of the
            // user's plan, and these write paths run on every keystroke in the plan editor.
            JdbcQueries.transaction(session) {
                val now = nowText()
                JdbcQueries.write(session, DELETE_ALLOCATIONS_FOR_DAY_SQL, arrayOf(dayKey))
                allocations.forEach { (dimensionId, minutes) ->
                    val entity =
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
                    JdbcQueries.write(session, INSERT_ALLOCATION_SQL, entity.toBindings())
                }
            }
            logger.i(
                "DayPlanRepositoryJvm.setAllocations",
                "Set day allocations batch",
                mapOf("dayKey" to dayKey, "count" to allocations.size.toString(), "source" to source),
            )
            markDirtyForDay(dayKey, "day_plan_set_allocations_batch")
        }
    }

    /**
     * Applies [templateId]'s allocations to [dayKey] and sets TEMPLATE mode. Rejects past days.
     *
     * An empty template is NOT an error and NOT a rollback: the mode has already been set, and the Android
     * implementation returns without marking the day dirty a second time. Matching that matters because the
     * mode change already marked it.
     */
    override suspend fun applyTemplateToDay(
        dayKey: String,
        templateId: String,
    ) {
        JdbcFlows.onIo {
            requireTodayOrFuture(dayKey)
            setDayModeCore(dayKey = dayKey, mode = DayPlanRepository.MODE_TEMPLATE, templateId = templateId)
            val templateAllocations = readTemplateAllocations(templateId)
            if (templateAllocations.isEmpty()) {
                logger.w(
                    "DayPlanRepositoryJvm.applyTemplateToDay",
                    "Template has no allocations",
                    mapOf("templateId" to templateId),
                )
                return@onIo
            }
            val applied =
                JdbcQueries.transaction(session) {
                    val now = nowText()
                    JdbcQueries.write(session, DELETE_ALLOCATIONS_FOR_DAY_SQL, arrayOf(dayKey))
                    templateAllocations.forEach { templateAllocation ->
                        val entity =
                            DayPlanAllocationEntity(
                                id = UUID.randomUUID().toString(),
                                dayKey = dayKey,
                                dimensionId = templateAllocation.dimensionId,
                                plannedMinutes = templateAllocation.plannedMinutes,
                                source = DayPlanRepository.SOURCE_TEMPLATE,
                                templateId = templateId,
                                createdAt = now,
                                updatedAt = now,
                            )
                        JdbcQueries.write(session, INSERT_ALLOCATION_SQL, entity.toBindings())
                    }
                    templateAllocations.size
                }
            logger.i(
                "DayPlanRepositoryJvm.applyTemplateToDay",
                "Applied template to day",
                mapOf(
                    "dayKey" to dayKey,
                    "templateId" to templateId,
                    "allocations" to applied.toString(),
                ),
            )
            markDirtyForDay(dayKey, "day_plan_apply_template")
        }
    }

    /**
     * Clears [dayKey]'s allocations and resets it to AUTO, in one transaction. Rejects past days.
     *
     * The mode reset goes through the non-suspend core so it can run inside the transaction; the core still
     * marks the day lens-dirty, which is a nested transaction and therefore joins this one.
     */
    override suspend fun clearDayPlan(dayKey: String) {
        JdbcFlows.onIo {
            requireTodayOrFuture(dayKey)
            JdbcQueries.transaction(session) {
                JdbcQueries.write(session, DELETE_ALLOCATIONS_FOR_DAY_SQL, arrayOf(dayKey))
                setDayModeCore(dayKey = dayKey, mode = DayPlanRepository.MODE_AUTO, templateId = null)
            }
            markDirtyForDay(dayKey, "day_plan_cleared")
            logger.i(
                "DayPlanRepositoryJvm.clearDayPlan",
                "Cleared day plan and reset mode to auto",
                mapOf("dayKey" to dayKey),
            )
        }
    }

    /** Returns [dayKey]'s policy, defaulting to AUTO when no row is stored yet. */
    override suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord =
        JdbcFlows.onIo {
            logger.d("DayPlanRepositoryJvm.getDayPolicy", "Fetching day policy", mapOf("dayKey" to dayKey))
            readPolicyRecord(dayKey)
        }

    /** Sets [dayKey]'s plan mode and optional template id. Rejects past days and unknown modes. */
    override suspend fun setDayMode(
        dayKey: String,
        mode: String,
        templateId: String?,
    ) {
        JdbcFlows.onIo {
            requireTodayOrFuture(dayKey)
            setDayModeCore(dayKey = dayKey, mode = mode, templateId = templateId)
        }
    }

    /** Toggles the starred flag for [dayKey], keeping its current mode and template. */
    override suspend fun setDayStarred(
        dayKey: String,
        isStarred: Boolean,
    ) {
        JdbcFlows.onIo {
            requireTodayOrFuture(dayKey)
            val existing = readPolicy(dayKey)
            JdbcQueries.write(
                session,
                UPSERT_POLICY_SQL,
                arrayOf(
                    dayKey,
                    existing?.mode ?: DayPlanRepository.MODE_AUTO,
                    existing?.templateId,
                    if (isStarred) "1" else "0",
                    nowText(),
                ),
            )
            logger.i(
                "DayPlanRepositoryJvm.setDayStarred",
                "Updated starred status",
                mapOf("dayKey" to dayKey, "isStarred" to isStarred.toString()),
            )
            markDirtyForDay(dayKey, "day_plan_starred_changed")
        }
    }

    /** Returns the preferred template id for a day [dayType], or null when none is set. */
    override suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord =
        JdbcFlows.onIo {
            requireSupportedDayType(dayType)
            logger.d(
                "DayPlanRepositoryJvm.getDayTypeTemplatePreference",
                "Fetching day type template preference",
                mapOf("dayType" to dayType),
            )
            DayTypeTemplatePreferenceRecord(
                dayType = dayType,
                templateId = readDayTypePreference(dayType)?.templateId,
            )
        }

    /** Sets the preferred [templateId] for a day [dayType]. This is what AUTO mode resolves to. */
    override suspend fun setDayTypeTemplatePreference(
        dayType: String,
        templateId: String?,
    ) {
        JdbcFlows.onIo {
            requireSupportedDayType(dayType)
            JdbcQueries.write(
                session,
                UPSERT_DAY_TYPE_PREFERENCE_SQL,
                arrayOf(dayType, templateId?.ifBlank { null }, nowText()),
            )
            logger.i(
                "DayPlanRepositoryJvm.setDayTypeTemplatePreference",
                "Updated day type template preference",
                mapOf("dayType" to dayType, "templateId" to (templateId ?: "null")),
            )
        }
    }

    /** Resolves which active template applies to [dayKey] (starred wins → day type → null). */
    override suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord? =
        JdbcFlows.onIo {
            logger.d(
                "DayPlanRepositoryJvm.resolveTemplateForDay",
                "Resolving template for day",
                mapOf("dayKey" to dayKey),
            )
            resolveTemplateForDayInternal(dayKey = dayKey, policy = readPolicyRecord(dayKey))
        }

    // ---- Templates ----

    /** Emits active templates with their allocations, ordered by sort position. */
    override fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>> {
        logger.d("DayPlanRepositoryJvm.observeActiveTemplates", "Subscribing to active templates")
        return JdbcFlows
            .observe(pollIntervalMs) { readTemplates(ACTIVE_TEMPLATES_SQL) }
            .map { templates -> templates.toRecordsWithSharedAllocations() }
    }

    /** Emits every template, soft-deleted included, with their allocations. */
    override fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>> {
        logger.d("DayPlanRepositoryJvm.observeAllTemplates", "Subscribing to all templates")
        return JdbcFlows
            .observe(pollIntervalMs) { readTemplates(ALL_TEMPLATES_SQL) }
            .map { templates -> templates.toRecordsWithSharedAllocations() }
    }

    /** Returns the template [id] with its allocations, or null. */
    override suspend fun getTemplateById(id: String): DayPlanTemplateRecord? =
        JdbcFlows.onIo {
            logger.d("DayPlanRepositoryJvm.getTemplateById", "Fetching template by id", mapOf("id" to id))
            val entity = readTemplate(id)
            if (entity == null) {
                logger.d("DayPlanRepositoryJvm.getTemplateById", "Template not found", mapOf("id" to id))
                return@onIo null
            }
            entity.toRecord()
        }

    /**
     * Creates a template and returns its id.
     *
     * @throws IllegalStateException when the active template count is already at
     *   [DayPlanRepository.MAX_TEMPLATE_COUNT] — the limit is on ACTIVE templates, so soft-deleting one
     *   makes room without deleting its definition.
     */
    override suspend fun createTemplate(
        name: String,
        description: String?,
        allocations: Map<String, Int>,
    ): String =
        JdbcFlows.onIo {
            val activeCount = readActiveTemplateCount()
            check(activeCount < DayPlanRepository.MAX_TEMPLATE_COUNT) {
                "Maximum template count (${DayPlanRepository.MAX_TEMPLATE_COUNT}) reached"
            }
            val now = nowText()
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
            val created =
                JdbcQueries.transaction(session) {
                    JdbcQueries.write(session, INSERT_TEMPLATE_SQL, template.toBindings())
                    // A zero-minute allocation is dropped rather than stored: it is an empty row in the
                    // template editor and would otherwise be written back as a real allocation.
                    val positive = allocations.filter { it.value > 0 }
                    positive.forEach { (dimensionId, minutes) ->
                        val entity =
                            DayPlanTemplateAllocationEntity(
                                id = UUID.randomUUID().toString(),
                                templateId = templateId,
                                dimensionId = dimensionId,
                                plannedMinutes = minutes,
                                createdAt = now,
                                updatedAt = now,
                            )
                        JdbcQueries.write(session, INSERT_TEMPLATE_ALLOCATION_SQL, entity.toBindings())
                    }
                    positive.size
                }
            logger.i(
                "DayPlanRepositoryJvm.createTemplate",
                "Created template",
                mapOf("id" to templateId, "name" to name, "allocations" to created.toString()),
            )
            templateId
        }

    /** Updates template [id] and replaces all its allocation rows in one transaction. No-ops when absent. */
    override suspend fun updateTemplate(
        id: String,
        name: String,
        description: String?,
        allocations: Map<String, Int>,
    ) {
        JdbcFlows.onIo {
            val existing = readTemplate(id) ?: return@onIo
            val now = nowText()
            JdbcQueries.transaction(session) {
                JdbcQueries.write(
                    session,
                    INSERT_TEMPLATE_SQL,
                    existing
                        .copy(
                            name = name.trim(),
                            description = description?.trim()?.ifEmpty { null },
                            updatedAt = now,
                        ).toBindings(),
                )
                JdbcQueries.write(session, DELETE_TEMPLATE_ALLOCATIONS_SQL, arrayOf(id))
                allocations
                    .filter { it.value > 0 }
                    .forEach { (dimensionId, minutes) ->
                        val entity =
                            DayPlanTemplateAllocationEntity(
                                id = UUID.randomUUID().toString(),
                                templateId = id,
                                dimensionId = dimensionId,
                                plannedMinutes = minutes,
                                createdAt = now,
                                updatedAt = now,
                            )
                        JdbcQueries.write(session, INSERT_TEMPLATE_ALLOCATION_SQL, entity.toBindings())
                    }
            }
            logger.i(
                "DayPlanRepositoryJvm.updateTemplate",
                "Updated template",
                mapOf("id" to id, "name" to name),
            )
        }
    }

    /** Soft-deletes template [id] by marking it inactive. The rows and allocations stay. */
    override suspend fun deleteTemplate(id: String) {
        JdbcFlows.onIo {
            JdbcQueries.write(session, SOFT_DELETE_TEMPLATE_SQL, arrayOf(nowText(), id))
            logger.i("DayPlanRepositoryJvm.deleteTemplate", "Soft-deleted template", mapOf("id" to id))
        }
    }

    // ---- Private helpers ----

    /**
     * Sets [dayKey]'s mode, keeping the stored starred flag and clearing any template id unless the mode is
     * TEMPLATE. Non-suspend so [clearDayPlan] can call it inside a transaction.
     */
    private fun setDayModeCore(
        dayKey: String,
        mode: String,
        templateId: String?,
    ) {
        require(
            mode == DayPlanRepository.MODE_AUTO ||
                mode == DayPlanRepository.MODE_TEMPLATE ||
                mode == DayPlanRepository.MODE_CUSTOM,
        ) { "Unsupported day mode: $mode" }
        val existing = readPolicy(dayKey)
        JdbcQueries.write(
            session,
            UPSERT_POLICY_SQL,
            arrayOf(
                dayKey,
                mode,
                if (mode == DayPlanRepository.MODE_TEMPLATE) templateId?.ifBlank { null } else null,
                (existing?.isStarred ?: 0).toString(),
                nowText(),
            ),
        )
        logger.i(
            "DayPlanRepositoryJvm.setDayMode",
            "Updated day mode",
            mapOf("dayKey" to dayKey, "mode" to mode, "templateId" to (templateId ?: "null")),
        )
        markDirtyForDay(dayKey, "day_plan_mode_changed")
    }

    /**
     * Refuses a day before today. Day keys are `YYYY-MM-DD`, so the string comparison is the date
     * comparison — and it is the check that keeps the desktop from rewriting the past, which the lens
     * snapshots for those days have already been computed from.
     */
    private fun requireTodayOrFuture(dayKey: String) {
        val today = today()
        require(dayKey >= today) {
            "Cannot modify day plan for past date: $dayKey (today: $today)"
        }
    }

    private fun requireSupportedDayType(dayType: String) {
        require(
            dayType == DayPlanRepository.DAY_TYPE_WEEKDAY ||
                dayType == DayPlanRepository.DAY_TYPE_WEEKEND ||
                dayType == DayPlanRepository.DAY_TYPE_STARRED,
        ) { "Unsupported day type: $dayType" }
    }

    /** Builds the VIRTUAL allocations a template implies for [dayKey]; never persisted. */
    private fun buildTemplateDerivedAllocations(
        dayKey: String,
        templateId: String,
        source: String,
    ): List<DayPlanAllocationRecord> {
        val template = readTemplate(templateId)
        if (template == null || template.isActive != 1) return emptyList()
        return readTemplateAllocations(templateId).map { allocation ->
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

    private fun readPolicyRecord(dayKey: String): DayPlanPolicyRecord {
        val persisted = readPolicy(dayKey)
        return DayPlanPolicyRecord(
            dayKey = dayKey,
            mode = persisted?.mode ?: DayPlanRepository.MODE_AUTO,
            templateId = persisted?.templateId,
            isStarred = persisted?.isStarred == 1,
        )
    }

    /**
     * The template a day resolves to under AUTO/TEMPLATE.
     *
     * An inactive template resolves to null even when it is named explicitly: soft-deleting a template has
     * to stop it being applied, without erasing what a past day was planned from.
     */
    private fun resolveTemplateForDayInternal(
        dayKey: String,
        policy: DayPlanPolicyRecord,
    ): DayPlanTemplateRecord? {
        val resolvedTemplateId =
            when {
                policy.mode == DayPlanRepository.MODE_TEMPLATE && !policy.templateId.isNullOrBlank() -> {
                    policy.templateId
                }

                else -> {
                    resolveAutoTemplateIdForDay(dayKey = dayKey, isStarred = policy.isStarred)
                }
            } ?: run {
                logger.d(
                    "DayPlanRepositoryJvm.resolveTemplateForDay",
                    "No template resolved for day",
                    mapOf("dayKey" to dayKey, "mode" to policy.mode),
                )
                return null
            }
        val template = readTemplate(resolvedTemplateId) ?: return null
        if (template.isActive != 1) {
            logger.d(
                "DayPlanRepositoryJvm.resolveTemplateForDay",
                "Resolved template is inactive",
                mapOf("dayKey" to dayKey, "templateId" to resolvedTemplateId),
            )
            return null
        }
        logger.d(
            "DayPlanRepositoryJvm.resolveTemplateForDay",
            "Resolved template",
            mapOf(
                "dayKey" to dayKey,
                "templateId" to resolvedTemplateId,
                "templateName" to template.name,
            ),
        )
        return template.toRecord()
    }

    /** Starred days take the starred preference; otherwise the weekend/weekday one. */
    private fun resolveAutoTemplateIdForDay(
        dayKey: String,
        isStarred: Boolean,
    ): String? {
        if (isStarred) {
            readDayTypePreference(DayPlanRepository.DAY_TYPE_STARRED)?.templateId?.let { return it }
        }
        val date = LocalDate.parse(dayKey)
        val dayType =
            when (date.dayOfWeek.value) {
                6, 7 -> DayPlanRepository.DAY_TYPE_WEEKEND
                else -> DayPlanRepository.DAY_TYPE_WEEKDAY
            }
        return readDayTypePreference(dayType)?.templateId
    }

    private fun readAllocations(dayKey: String): List<DayPlanAllocationEntity> =
        readEntities(ALLOCATIONS_FOR_DAY_SQL, arrayOf(dayKey)) { resultSet -> resultSet.readAllocation() }

    private fun readAllocation(
        dayKey: String,
        dimensionId: String,
    ): DayPlanAllocationEntity? =
        JdbcQueries.read(session, ALLOCATION_FOR_DAY_DIMENSION_SQL, arrayOf(dayKey, dimensionId)) { resultSet ->
            if (resultSet.next()) resultSet.readAllocation() else null
        }

    private fun readPolicy(dayKey: String): DayPlanPolicyEntity? =
        JdbcQueries.read(session, POLICY_FOR_DAY_SQL, arrayOf(dayKey)) { resultSet ->
            if (resultSet.next()) resultSet.readPolicy() else null
        }

    private fun readDayTypePreference(dayType: String): DayTypeTemplatePreferenceEntity? =
        JdbcQueries.read(session, DAY_TYPE_PREFERENCE_SQL, arrayOf(dayType)) { resultSet ->
            if (resultSet.next()) resultSet.readDayTypePreference() else null
        }

    private fun readTemplates(sql: String): List<DayPlanTemplateEntity> =
        readEntities(sql, emptyArray<String?>()) { resultSet -> resultSet.readTemplate() }

    private fun readTemplate(id: String): DayPlanTemplateEntity? =
        JdbcQueries.read(session, TEMPLATE_BY_ID_SQL, arrayOf(id)) { resultSet ->
            if (resultSet.next()) resultSet.readTemplate() else null
        }

    private fun readActiveTemplateCount(): Int =
        JdbcQueries.readScalar(session, ACTIVE_TEMPLATE_COUNT_SQL, emptyArray<String?>())?.toIntOrNull() ?: 0

    private fun readTemplateAllocations(templateId: String): List<DayPlanTemplateAllocationEntity> =
        readEntities(TEMPLATE_ALLOCATIONS_SQL, arrayOf(templateId)) { resultSet ->
            resultSet.readTemplateAllocation()
        }

    private fun <T> readEntities(
        sql: String,
        parameters: Array<out String?>,
        mapRow: (ResultSet) -> T,
    ): List<T> =
        JdbcQueries.read(session, sql, parameters) { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(mapRow(resultSet))
                }
            }
        }

    private fun ResultSet.readAllocation(): DayPlanAllocationEntity =
        DayPlanAllocationEntity(
            id = getString("id"),
            dayKey = getString("day_key"),
            dimensionId = getString("dimension_id"),
            plannedMinutes = getInt("planned_minutes"),
            source = getString("source"),
            templateId = getString("template_id"),
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
        )

    private fun ResultSet.readPolicy(): DayPlanPolicyEntity =
        DayPlanPolicyEntity(
            dayKey = getString("day_key"),
            mode = getString("mode"),
            templateId = getString("template_id"),
            isStarred = getInt("is_starred"),
            updatedAt = getString("updated_at"),
        )

    private fun ResultSet.readDayTypePreference(): DayTypeTemplatePreferenceEntity =
        DayTypeTemplatePreferenceEntity(
            dayType = getString("day_type"),
            templateId = getString("template_id"),
            updatedAt = getString("updated_at"),
        )

    private fun ResultSet.readTemplate(): DayPlanTemplateEntity =
        DayPlanTemplateEntity(
            id = getString("id"),
            name = getString("name"),
            description = getString("description"),
            isActive = getInt("is_active"),
            sortOrder = getInt("sort_order"),
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
        )

    private fun ResultSet.readTemplateAllocation(): DayPlanTemplateAllocationEntity =
        DayPlanTemplateAllocationEntity(
            id = getString("id"),
            templateId = getString("template_id"),
            dimensionId = getString("dimension_id"),
            plannedMinutes = getInt("planned_minutes"),
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
        )

    private fun DayPlanAllocationEntity.toBindings(): Array<String?> =
        arrayOf(
            id,
            dayKey,
            dimensionId,
            plannedMinutes.toString(),
            source,
            templateId,
            createdAt,
            updatedAt,
        )

    private fun DayPlanTemplateEntity.toBindings(): Array<String?> =
        arrayOf(
            id,
            name,
            description,
            isActive.toString(),
            sortOrder.toString(),
            createdAt,
            updatedAt,
        )

    private fun DayPlanTemplateAllocationEntity.toBindings(): Array<String?> =
        arrayOf(
            id,
            templateId,
            dimensionId,
            plannedMinutes.toString(),
            createdAt,
            updatedAt,
        )

    private fun DayPlanAllocationEntity.toRecord(): DayPlanAllocationRecord =
        DayPlanAllocationRecord(
            id = id,
            dayKey = dayKey,
            dimensionId = dimensionId,
            plannedMinutes = plannedMinutes,
            source = source,
            templateId = templateId,
        )

    /** Loads a template's allocation rows for its record. One template, so no grouping needed. */
    private fun DayPlanTemplateEntity.toRecord(): DayPlanTemplateRecord {
        val allocations =
            readTemplateAllocations(id).map { templateAllocation ->
                TemplateAllocationRecord(
                    id = templateAllocation.id,
                    templateId = templateAllocation.templateId,
                    dimensionId = templateAllocation.dimensionId,
                    plannedMinutes = templateAllocation.plannedMinutes,
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

    /**
     * Loads every listed template's allocations in ONE query and groups them in memory.
     *
     * The per-template read is a query per row, which is what makes this worth a separate path: the
     * templates screen renders all of them at once.
     */
    private fun List<DayPlanTemplateEntity>.toRecordsWithSharedAllocations(): List<DayPlanTemplateRecord> {
        if (isEmpty()) return emptyList()
        // One placeholder per id: a positional bind cannot share a value across an IN list.
        val placeholders = joinToString(separator = ",") { "?" }
        val allocationsByTemplateId =
            JdbcQueries
                .read(
                    session,
                    TEMPLATE_ALLOCATIONS_FOR_IDS_SQL.replace(TEMPLATE_IDS_TOKEN, placeholders),
                    map { it.id }.toTypedArray(),
                ) { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.readTemplateAllocation())
                    }
                }.groupBy { it.templateId }
        return map { template ->
            DayPlanTemplateRecord(
                id = template.id,
                name = template.name,
                description = template.description,
                isActive = template.isActive == 1,
                sortOrder = template.sortOrder,
                allocations =
                    allocationsByTemplateId[template.id].orEmpty().map { templateAllocation ->
                        TemplateAllocationRecord(
                            id = templateAllocation.id,
                            templateId = templateAllocation.templateId,
                            dimensionId = templateAllocation.dimensionId,
                            plannedMinutes = templateAllocation.plannedMinutes,
                        )
                    },
            )
        }
    }

    private fun markDirtyForDay(
        dayKey: String,
        reason: String,
    ) {
        // The sync form: this runs inside the caller's onIo block, and inside a transaction that must not
        // be committed early by a second BEGIN.
        markLensDayDirtySync(
            session = session,
            dayKey = dayKey,
            changedModules = setOf("day_plan"),
            reason = reason,
            logger = logger,
        )
    }

    private fun nowText(): String = LocalDateTime.now().format(ISO_FORMATTER)

    private companion object {
        val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        const val TEMPLATE_IDS_TOKEN = "{templateIds}"

        /** Verbatim from `DayPlanDao.observeAllocationsForDay` / `getAllocationsForDay`. */
        const val ALLOCATIONS_FOR_DAY_SQL =
            "SELECT * FROM day_plan_allocations WHERE day_key = ? ORDER BY dimension_id"

        /** Verbatim from `DayPlanDao.getAllocationForDayAndDimension`. */
        const val ALLOCATION_FOR_DAY_DIMENSION_SQL =
            "SELECT * FROM day_plan_allocations WHERE day_key = ? AND dimension_id = ? LIMIT 1"

        /** Verbatim from `DayPlanDao.deleteAllocationsForDay`. */
        const val DELETE_ALLOCATIONS_FOR_DAY_SQL = "DELETE FROM day_plan_allocations WHERE day_key = ?"

        /** The 8 allocation columns, matching `DayPlanAllocationEntity`. */
        const val INSERT_ALLOCATION_SQL = """
            INSERT OR REPLACE INTO day_plan_allocations
            (id, day_key, dimension_id, planned_minutes, source, template_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """

        /** Verbatim from `DayPlanDao.getDayPolicy`. */
        const val POLICY_FOR_DAY_SQL = "SELECT * FROM day_plan_policies WHERE day_key = ? LIMIT 1"

        /** `DayPlanDao.upsertDayPolicy` is `@Insert(REPLACE)`; the policy's key IS the day key. */
        const val UPSERT_POLICY_SQL = """
            INSERT OR REPLACE INTO day_plan_policies (day_key, mode, template_id, is_starred, updated_at)
            VALUES (?, ?, ?, ?, ?)
        """

        /** Verbatim from `DayPlanDao.getDayTypeTemplatePreference`. */
        const val DAY_TYPE_PREFERENCE_SQL =
            "SELECT * FROM day_type_template_preferences WHERE day_type = ? LIMIT 1"

        /** `DayPlanDao.upsertDayTypeTemplatePreference` is `@Insert(REPLACE)`. */
        const val UPSERT_DAY_TYPE_PREFERENCE_SQL = """
            INSERT OR REPLACE INTO day_type_template_preferences (day_type, template_id, updated_at)
            VALUES (?, ?, ?)
        """

        /** Verbatim from `DayPlanDao.observeActiveTemplates`. */
        const val ACTIVE_TEMPLATES_SQL =
            "SELECT * FROM day_plan_templates WHERE is_active = 1 ORDER BY sort_order ASC"

        /** Verbatim from `DayPlanDao.observeAllTemplates`. */
        const val ALL_TEMPLATES_SQL = "SELECT * FROM day_plan_templates ORDER BY sort_order ASC"

        /** Verbatim from `DayPlanDao.getTemplateById`. */
        const val TEMPLATE_BY_ID_SQL = "SELECT * FROM day_plan_templates WHERE id = ?"

        /** Verbatim from `DayPlanDao.getActiveTemplateCount`. */
        const val ACTIVE_TEMPLATE_COUNT_SQL =
            "SELECT COUNT(*) FROM day_plan_templates WHERE is_active = 1"

        /** `DayPlanDao.insertTemplate` is `@Insert(REPLACE)`. */
        const val INSERT_TEMPLATE_SQL = """
            INSERT OR REPLACE INTO day_plan_templates
            (id, name, description, is_active, sort_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """

        /** Verbatim from `DayPlanDao.softDeleteTemplate` — inactive, not deleted. */
        const val SOFT_DELETE_TEMPLATE_SQL =
            "UPDATE day_plan_templates SET is_active = 0, updated_at = ? WHERE id = ?"

        /** Verbatim from `DayPlanDao.getTemplateAllocations`. */
        const val TEMPLATE_ALLOCATIONS_SQL =
            "SELECT * FROM day_plan_template_allocations WHERE template_id = ? ORDER BY dimension_id"

        /** Verbatim from `DayPlanDao.getTemplateAllocationsForTemplateIds`, IN list expanded by the caller. */
        const val TEMPLATE_ALLOCATIONS_FOR_IDS_SQL = """
            SELECT * FROM day_plan_template_allocations
            WHERE template_id IN ({templateIds})
            ORDER BY template_id, dimension_id
        """

        /** `DayPlanDao.insertTemplateAllocations` is `@Insert(REPLACE)`. */
        const val INSERT_TEMPLATE_ALLOCATION_SQL = """
            INSERT OR REPLACE INTO day_plan_template_allocations
            (id, template_id, dimension_id, planned_minutes, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """

        /** Verbatim from `DayPlanDao.deleteTemplateAllocations`. */
        const val DELETE_TEMPLATE_ALLOCATIONS_SQL =
            "DELETE FROM day_plan_template_allocations WHERE template_id = ?"
    }
}
