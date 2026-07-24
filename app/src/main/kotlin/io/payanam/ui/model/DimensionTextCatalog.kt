//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.model

import android.content.Context
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog

object DimensionTextCatalog {
    private fun loggerOrNull(): UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

    private val labelResIds = mapOf(
        DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id to R.string.loc_dimension_name_physical_health,
        DimensionTaxonomyCatalog.MENTAL_HEALTH.id to R.string.loc_dimension_name_mental_health,
        DimensionTaxonomyCatalog.FAMILY_RELATIONSHIPS.id to R.string.loc_dimension_name_family_relationships,
        DimensionTaxonomyCatalog.HOME_ENVIRONMENT.id to R.string.loc_dimension_name_home_environment,
        DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id to R.string.loc_dimension_name_work_livelihood,
        DimensionTaxonomyCatalog.MONEY_FINANCE.id to R.string.loc_dimension_name_money_finance,
        DimensionTaxonomyCatalog.LEARNING_GROWTH.id to R.string.loc_dimension_name_learning_growth,
        DimensionTaxonomyCatalog.RECREATION_LEISURE.id to R.string.loc_dimension_name_recreation_leisure,
        DimensionTaxonomyCatalog.COMMUNITY_SERVICE.id to R.string.loc_dimension_name_community_service,
        DimensionTaxonomyCatalog.UNASSIGNED.id to R.string.loc_dimension_fallback_unassigned,
    )

    private val descriptionResIds = mapOf(
        DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id to R.string.loc_dimension_desc_physical_health,
        DimensionTaxonomyCatalog.MENTAL_HEALTH.id to R.string.loc_dimension_desc_mental_health,
        DimensionTaxonomyCatalog.FAMILY_RELATIONSHIPS.id to R.string.loc_dimension_desc_family_relationships,
        DimensionTaxonomyCatalog.HOME_ENVIRONMENT.id to R.string.loc_dimension_desc_home_environment,
        DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id to R.string.loc_dimension_desc_work_livelihood,
        DimensionTaxonomyCatalog.MONEY_FINANCE.id to R.string.loc_dimension_desc_money_finance,
        DimensionTaxonomyCatalog.LEARNING_GROWTH.id to R.string.loc_dimension_desc_learning_growth,
        DimensionTaxonomyCatalog.RECREATION_LEISURE.id to R.string.loc_dimension_desc_recreation_leisure,
        DimensionTaxonomyCatalog.COMMUNITY_SERVICE.id to R.string.loc_dimension_desc_community_service,
        DimensionTaxonomyCatalog.UNASSIGNED.id to R.string.loc_dimension_desc_unassigned,
    )

    fun labelResIdForCanonicalId(canonicalId: String?): Int? = canonicalId?.let(labelResIds::get)

    fun descriptionResIdForCanonicalId(canonicalId: String?): Int? = canonicalId?.let(descriptionResIds::get)

    fun localizedLabel(context: Context, canonicalId: String?): String? {
        val resId = labelResIdForCanonicalId(canonicalId) ?: return null
        return resolveLocalizedString(context, resId, null).also {
            loggerOrNull()?.d(
                "DimensionTextCatalog.localizedLabel",
                "Resolved localized canonical dimension label",
                mapOf("canonicalId" to (canonicalId ?: "none")),
            )
        }
    }

    fun localizedDescription(context: Context, canonicalId: String?): String? {
        val resId = descriptionResIdForCanonicalId(canonicalId) ?: return null
        return resolveLocalizedString(context, resId, null).also {
            loggerOrNull()?.d(
                "DimensionTextCatalog.localizedDescription",
                "Resolved localized canonical dimension description",
                mapOf("canonicalId" to (canonicalId ?: "none")),
            )
        }
    }

    private fun resolveLocalizedString(context: Context, resId: Int, languageTag: String?): String? = runCatching {
        if (languageTag.isNullOrBlank()) {
            context.getString(resId)
        } else {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(languageTag))
            context.createConfigurationContext(config).getString(resId)
        }
    }.getOrNull() ?: runCatching {
        context.getString(resId)
    }.getOrNull()
}
