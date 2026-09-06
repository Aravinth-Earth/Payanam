//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.model

import android.content.Context
import android.content.res.Configuration
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import java.util.Locale
object DefaultDimensionLabels {
    private val logger = UnifiedLogger.getInstance()

    private val canonicalLabelResIds = mapOf(
        DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id to R.string.loc_dimension_name_work_livelihood,
        DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id to R.string.loc_dimension_name_physical_health,
        DimensionTaxonomyCatalog.FAMILY_RELATIONSHIPS.id to R.string.loc_dimension_name_family_relationships,
        DimensionTaxonomyCatalog.HOME_ENVIRONMENT.id to R.string.loc_dimension_name_home_environment,
        DimensionTaxonomyCatalog.MONEY_FINANCE.id to R.string.loc_dimension_name_money_finance,
        DimensionTaxonomyCatalog.MENTAL_HEALTH.id to R.string.loc_dimension_name_mental_health,
        DimensionTaxonomyCatalog.RECREATION_LEISURE.id to R.string.loc_dimension_name_recreation_leisure,
        DimensionTaxonomyCatalog.LEARNING_GROWTH.id to R.string.loc_dimension_name_learning_growth,
        DimensionTaxonomyCatalog.COMMUNITY_SERVICE.id to R.string.loc_dimension_name_community_service,
        UNASSIGNED_DIMENSION_ID to R.string.loc_dimension_fallback_unassigned,
    )
    /**
     * Locale-aware localized name for a canonical dimension (null if unknown).
     */
    fun localizedLabel(context: Context, dimensionId: String, languageTag: String? = null): String? {
        val resId = canonicalLabelResIds[dimensionId]
            ?: DimensionTextCatalog.labelResIdForCanonicalId(DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id)
            ?: return null
        return if (languageTag.isNullOrBlank()) {
            context.getString(resId)
        } else {
            localizedStringForLocale(context, resId, languageTag)
        }
    }
    /**
     * Language-neutral canonical name used for storage comparisons.
     */
    fun canonicalLabel(dimensionId: String): String? = if (dimensionId == UNASSIGNED_DIMENSION_ID) {
        CANONICAL_UNASSIGNED_LABEL
    } else {
        DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.fallbackLabel ?: dimensionId
    }
    /**
     * Best display name for a dimension: the localized default unless the user
     * stored a custom (non-app-owned) label.
     */
    fun resolveDisplayLabel(
        context: Context,
        dimensionId: String,
        storedLabel: String?,
        languageTag: String? = null,
    ): String {
        val trimmed = storedLabel?.trim().orEmpty()
        val localizedDefault = localizedLabel(context, dimensionId, languageTag)
        if (localizedDefault != null && (trimmed.isBlank() || isAppOwnedDefaultLabel(context, dimensionId, trimmed))) {
            return localizedDefault
        }
        return trimmed.ifBlank { localizedDefault ?: dimensionId }
    }
    /**
     * Normalizes a stored label to canonical form when it matches an
     * app-owned default translation; custom labels pass through untouched.
     */
    fun canonicalizeStoredLabel(
        context: Context,
        dimensionId: String,
        candidateLabel: String,
        languageTag: String? = null,
    ): String {
        val trimmed = candidateLabel.trim()
        if (trimmed.isBlank()) {
            return localizedLabel(context, dimensionId, languageTag) ?: canonicalLabel(dimensionId) ?: candidateLabel
        }
        val canonical = canonicalLabel(dimensionId)
        if (canonical != null && isAppOwnedDefaultLabel(context, dimensionId, trimmed)) {
            if (trimmed != canonical) {
                logger.i(
                    "DefaultDimensionLabels.canonicalizeStoredLabel",
                    "Normalizing app-owned default dimension label to canonical storage form",
                    mapOf("dimensionId" to dimensionId),
                )
            }
            return canonical
        }
        return trimmed
    }
    /**
     * True when [label] equals any built-in default name (any supported
     * locale) for this dimension — i.e. not user-customized.
     */
    fun isAppOwnedDefaultLabel(context: Context, dimensionId: String, label: String?): Boolean {
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return false
        }
        return knownDefaultLabels(context, dimensionId).contains(trimmed)
    }

    private fun knownDefaultLabels(context: Context, dimensionId: String): Set<String> {
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id
        val canonical = canonicalLabel(dimensionId)
        val canonicalResId = canonicalLabelResIds[canonicalId ?: dimensionId]
            ?: DimensionTextCatalog.labelResIdForCanonicalId(canonicalId)
        return buildSet {
            canonical?.let(::add)
            if (canonicalResId != null) {
                SUPPORTED_LOCALE_TAGS
                    .map { localeTag -> localizedStringForLocale(context, canonicalResId, localeTag) }
                    .forEach(::add)
            }
        }
    }

    private fun localizedStringForLocale(context: Context, resId: Int, localeTag: String): String {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(localeTag))
        return context.createConfigurationContext(config).getString(resId)
    }

    private const val UNASSIGNED_DIMENSION_ID = "dim_unassigned"
    private const val CANONICAL_UNASSIGNED_LABEL = "Unassigned"
    private val SUPPORTED_LOCALE_TAGS = listOf("en", "ta")
}
