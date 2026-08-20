//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.model

import android.content.Context
import android.content.res.Configuration
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import java.util.Locale

/**
 * DefaultDimensionLabels.
 */
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
     * Localized label.
     */
    fun localizedLabel(context: Context, dimensionId: String, languageTag: String? = null): String? {
        /** Res id. */
        val resId = canonicalLabelResIds[dimensionId]
            ?: DimensionTextCatalog.labelResIdForCanonicalId(DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id)
            ?: return null
        return if (languageTag.isNullOrBlank()) {
            context.getString(resId)
        } else {
            /** Localized string for locale. */
            localizedStringForLocale(context, resId, languageTag)
        }
    }

    /**
     * Canonical label.
     */
    fun canonicalLabel(dimensionId: String): String? = if (dimensionId == UNASSIGNED_DIMENSION_ID) {
        /** Canonical unassigned label. */
        CANONICAL_UNASSIGNED_LABEL
    } else {
        DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.fallbackLabel ?: dimensionId
    }

    /**
     * Resolve display label.
     */
    fun resolveDisplayLabel(
        /** Context. */
        context: Context,
        /** Dimension id. */
        dimensionId: String,
        storedLabel: String?,
        languageTag: String? = null,
    ): String {
        /** Trimmed. */
        val trimmed = storedLabel?.trim().orEmpty()
        /** Localized default. */
        val localizedDefault = localizedLabel(context, dimensionId, languageTag)
        /** If. */
        if (localizedDefault != null && (trimmed.isBlank() || isAppOwnedDefaultLabel(context, dimensionId, trimmed))) {
            return localizedDefault
        }
        return trimmed.ifBlank { localizedDefault ?: dimensionId }
    }

    /**
     * Canonicalize stored label.
     */
    fun canonicalizeStoredLabel(
        /** Context. */
        context: Context,
        /** Dimension id. */
        dimensionId: String,
        /** Candidate label. */
        candidateLabel: String,
        languageTag: String? = null,
    ): String {
        /** Trimmed. */
        val trimmed = candidateLabel.trim()
        /** If. */
        if (trimmed.isBlank()) {
            return localizedLabel(context, dimensionId, languageTag) ?: canonicalLabel(dimensionId) ?: candidateLabel
        }
        /** Canonical. */
        val canonical = canonicalLabel(dimensionId)
        /** If. */
        if (canonical != null && isAppOwnedDefaultLabel(context, dimensionId, trimmed)) {
            /** If. */
            if (trimmed != canonical) {
                logger.i(
                    "DefaultDimensionLabels.canonicalizeStoredLabel",
                    "Normalizing app-owned default dimension label to canonical storage form",
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId),
                )
            }
            return canonical
        }
        return trimmed
    }

    /**
     * Is app owned default label.
     */
    fun isAppOwnedDefaultLabel(context: Context, dimensionId: String, label: String?): Boolean {
        /** Trimmed. */
        val trimmed = label?.trim().orEmpty()
        /** If. */
        if (trimmed.isBlank()) {
            return false
        }
        return knownDefaultLabels(context, dimensionId).contains(trimmed)
    }

    private fun knownDefaultLabels(context: Context, dimensionId: String): Set<String> {
        /** Canonical id. */
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id
        /** Canonical. */
        val canonical = canonicalLabel(dimensionId)
        /** Canonical res id. */
        val canonicalResId = canonicalLabelResIds[canonicalId ?: dimensionId]
            ?: DimensionTextCatalog.labelResIdForCanonicalId(canonicalId)
        return buildSet {
            canonical?.let(::add)
            /** If. */
            if (canonicalResId != null) {
                /** Supported locale tags. */
                SUPPORTED_LOCALE_TAGS
                    .map { localeTag -> localizedStringForLocale(context, canonicalResId, localeTag) }
                    .forEach(::add)
            }
        }
    }

    private fun localizedStringForLocale(context: Context, resId: Int, localeTag: String): String {
        /** Config. */
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(localeTag))
        return context.createConfigurationContext(config).getString(resId)
    }

    private const val UNASSIGNED_DIMENSION_ID = "dim_unassigned"
    private const val CANONICAL_UNASSIGNED_LABEL = "Unassigned"
    private val SUPPORTED_LOCALE_TAGS = listOf("en", "ta")
}
