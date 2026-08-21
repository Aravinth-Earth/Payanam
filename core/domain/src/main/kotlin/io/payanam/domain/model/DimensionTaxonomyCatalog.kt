//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.domain.model


import io.payanam.common.logging.UnifiedLogger
/**
 * One canonical life-dimension definition: stable [id], [slug], localized
 * fallback label/description, display order, and default weight/color/icon.
 */
data class CanonicalDimensionDefinition(
    val id: String,
    val slug: String,
    val fallbackLabel: String,
    val fallbackDescription: String,
    val sortOrder: Int,
    val defaultWeight: Double,
    val defaultColorHex: String,
    val defaultIconKey: String
)
@Suppress("MagicNumber")
object DimensionTaxonomyCatalog {
    private fun loggerOrNull(): UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()
    private const val CATALOG_CLASS_NAME = "io.payanam.domain.model.DimensionTaxonomyCatalog"
    val PHYSICAL_HEALTH = CanonicalDimensionDefinition(
        id = "dim_physical_health",
        slug = "physical_health",
        fallbackLabel = "Physical Health",
        fallbackDescription = "Activities that support body health, exercise, rest, recovery, nutrition, and physical well-being.",
        sortOrder = 10,
        defaultWeight = 1.0,
        defaultColorHex = "#4CAF50",
        defaultIconKey = "monitor_heart"
    )
    val MENTAL_HEALTH = CanonicalDimensionDefinition(
        id = "dim_mental_health",
        slug = "mental_health",
        fallbackLabel = "Mental Health",
        fallbackDescription = "Activities that support emotional balance, mental wellness, reflection, calmness, and inner stability.",
        sortOrder = 20,
        defaultWeight = 0.75,
        defaultColorHex = "#9C27B0",
        defaultIconKey = "psychology"
    )
    val FAMILY_RELATIONSHIPS = CanonicalDimensionDefinition(
        id = "dim_family_relationships",
        slug = "family_relationships",
        fallbackLabel = "Family & Relationships",
        fallbackDescription =
            "Activities that nurture family bonds, close relationships, care, trust, " +
                "and meaningful connection with important people.",
        sortOrder = 30,
        defaultWeight = 0.9,
        defaultColorHex = "#E91E63",
        defaultIconKey = "groups"
    )
    val HOME_ENVIRONMENT = CanonicalDimensionDefinition(
        id = "dim_home_environment",
        slug = "home_environment",
        fallbackLabel = "Home & Environment",
        fallbackDescription =
            "Activities related to home care, organization, maintenance, comfort, " +
                "and improving one's living space and surroundings.",
        sortOrder = 40,
        defaultWeight = 0.85,
        defaultColorHex = "#009688",
        defaultIconKey = "home"
    )
    val WORK_LIVELIHOOD = CanonicalDimensionDefinition(
        id = "dim_work_livelihood",
        slug = "work_livelihood",
        fallbackLabel = "Work & Livelihood",
        fallbackDescription = "Work, profession, responsibilities, and effort that support daily living and long-term stability.",
        sortOrder = 50,
        defaultWeight = 1.0,
        defaultColorHex = "#3F51B5",
        defaultIconKey = "work"
    )
    val MONEY_FINANCE = CanonicalDimensionDefinition(
        id = "dim_money_finance",
        slug = "money_finance",
        fallbackLabel = "Money & Finance",
        fallbackDescription = "Activities involving budgeting, saving, spending, investing, bills, and financial decisions for present and future needs.",
        sortOrder = 60,
        defaultWeight = 0.8,
        defaultColorHex = "#FF9800",
        defaultIconKey = "account_balance_wallet"
    )
    val LEARNING_GROWTH = CanonicalDimensionDefinition(
        id = "dim_learning_growth",
        slug = "learning_growth",
        fallbackLabel = "Learning & Growth",
        fallbackDescription =
            "Activities focused on learning, skill-building, self-improvement, and " +
                "growth through study, practice, or experience.",
        sortOrder = 70,
        defaultWeight = 0.8,
        defaultColorHex = "#FFC107",
        defaultIconKey = "menu_book"
    )
    val RECREATION_LEISURE = CanonicalDimensionDefinition(
        id = "dim_recreation_leisure",
        slug = "recreation_leisure",
        fallbackLabel = "Recreation & Leisure",
        fallbackDescription = "Activities done for enjoyment, rest, hobbies, fun, exploration, and renewal outside essential responsibilities.",
        sortOrder = 80,
        defaultWeight = 0.7,
        defaultColorHex = "#00BCD4",
        defaultIconKey = "sports_esports"
    )
    val COMMUNITY_SERVICE = CanonicalDimensionDefinition(
        id = "dim_community_service",
        slug = "community_service",
        fallbackLabel = "Community & Service",
        fallbackDescription = "Activities that help others, support communities, encourage participation, and create value beyond personal or family needs.",
        sortOrder = 90,
        defaultWeight = 0.7,
        defaultColorHex = "#607D8B",
        defaultIconKey = "volunteer_activism"
    )
    val UNASSIGNED = CanonicalDimensionDefinition(
        id = "dim_unassigned",
        slug = "unassigned",
        fallbackLabel = "Unassigned",
        fallbackDescription = "System fallback for uncategorized or imported records.",
        sortOrder = 9999,
        defaultWeight = 0.5,
        defaultColorHex = "#9E9E9E",
        defaultIconKey = "help_outline"
    )
    val entries: List<CanonicalDimensionDefinition> = listOf(
        PHYSICAL_HEALTH,
        MENTAL_HEALTH,
        FAMILY_RELATIONSHIPS,
        HOME_ENVIRONMENT,
        WORK_LIVELIHOOD,
        MONEY_FINANCE,
        LEARNING_GROWTH,
        RECREATION_LEISURE,
        COMMUNITY_SERVICE,
        UNASSIGNED
    )

    private val entriesByCanonicalId = entries.associateBy { it.id }
    private val canonicalLabels = entries.map { it.fallbackLabel }.toSet()

    private fun fallbackCallerTrace(): Map<String, Any> {
        val caller = Throwable("Dimension taxonomy fallback trace").stackTrace.firstOrNull { element ->
            element.className != CATALOG_CLASS_NAME &&
                element.className != Throwable::class.java.name &&
                !element.className.startsWith("java.lang.Thread")
        }
        return mapOf(
            "callerClass" to (caller?.className ?: "unknown"),
            "callerMethod" to (caller?.methodName ?: "unknown"),
            "callerLine" to (caller?.lineNumber ?: -1)
        )
    }
    /**
     * Resolves a dimension by its canonical [id]; null when blank/unknown.
     */
    fun fromCanonicalId(id: String?): CanonicalDimensionDefinition? {
        return id?.let(entriesByCanonicalId::get)
    }
    /**
     * Resolves a dimension by canonical or legacy alias id, warning callers that
     * pass a non-canonical id (migration guard).
     */
    fun fromAnyId(id: String?): CanonicalDimensionDefinition? {
        if (id.isNullOrBlank()) {
            return null
        }
        return fromCanonicalId(id)?.also {
            if (id != it.id) {
                val callerData = fallbackCallerTrace()
                loggerOrNull()?.w(
                    "DimensionTaxonomyCatalog.fromAnyId",
                    "Rejected non-canonical dimension id",
                    mapOf(
                        "dimensionId" to id,
                        "canonicalId" to it.id,
                        "resolutionKind" to "canonical_id"
                    ) + callerData
                )
            }
        } ?: run {
            val callerData = fallbackCallerTrace()
            loggerOrNull()?.w(
                "DimensionTaxonomyCatalog.fromAnyId",
                "Rejected non-canonical dimension id",
                mapOf(
                    "dimensionId" to id,
                    "resolutionKind" to "non_canonical"
                ) + callerData
            )
            null
        }
    }
    /**
     * Rejects label-based lookup (labels are not stable identifiers) and warns;
     * always returns null — callers must use the canonical id.
     */
    fun fromAnyLabel(label: String?): CanonicalDimensionDefinition? {
        if (label.isNullOrBlank()) {
            return null
        }
        val trimmed = label.trim()
        val callerData = fallbackCallerTrace()
        loggerOrNull()?.w(
            "DimensionTaxonomyCatalog.fromAnyLabel",
            "Rejected dimension label; canonical id required",
            mapOf(
                "dimensionLabel" to trimmed,
                "resolutionKind" to "label_not_supported"
            ) + callerData
        )
        return null
    }
    /**
     * Returns true if [label] matches a known canonical dimension label.
     */
    fun isCanonicalLabel(label: String?): Boolean {
        return !label.isNullOrBlank() && canonicalLabels.contains(label.trim())
    }
    /**
     * Returns the default scoring weight for [id], or 0.5 when unknown.
     */
    fun defaultWeightForDimensionId(id: String?): Double {
        return fromCanonicalId(id)?.defaultWeight ?: 0.5
    }
}
