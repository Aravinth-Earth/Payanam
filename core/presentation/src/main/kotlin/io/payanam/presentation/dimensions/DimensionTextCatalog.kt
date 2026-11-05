//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.dimensions

import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.DimensionTaxonomyCatalog

/**
 * The app's own display name and description for each canonical dimension.
 *
 * SHARED, and that is the point: the name a dimension shows is part of the UI both apps draw, so a copy that
 * lived only in `app/` would force the desktop to keep its own list - which is exactly what it had, a
 * hardcoded English `DesktopNoteDimensionOption("dim_work_livelihood", "Work & Livelihood")` per screen.
 *
 * THE COPY IS NOT HERE. The strings live in `:core:strings`' own `src/main/res/values{,-ta}/strings.xml`,
 * which is the source the generated [UiStrings] table is built from, so EN/TA parity is enforced at build
 * time. This file only says which entry belongs to which canonical id - as `when` branches rather than a
 * resource id, so a renamed string is a COMPILE error instead of a blank label at runtime.
 *
 * An unknown id answers null, and a caller keeps its own fallback: the original Android map behaved the same
 * way, and a dimension the user invented has no app-owned name to render.
 *
 * WHY THIS MODULE (it used to live in `:core:ui`, above the thing it names):
 *
 * The mapping is not a drawing concern - it is the answer to "what is this dimension called", which the
 * first-run seeder, the preferences mapper and the Android widget all need, and none of them draw. Below in
 * `:core:ui` the only module that can see BOTH the copy (`:core:strings`) and the taxonomy (`:core:domain`)
 * is this one - `:core:strings` is a plain-JVM leaf with no dependency on `:core:domain`, so it cannot own
 * this file, and `:core:domain` does not depend on `:core:strings` either. Keeping it here is what lets
 * `DatabaseInitDimensionSetupModel` follow the same way down: once this catalog is not above that model,
 * the model is no longer blocked by a Compose-layer symbol.
 */
object DimensionTextCatalog {
    /**
     * The localized name for [canonicalId] in [strings], or null when the id has no app-owned name.
     */
    fun localizedLabel(
        canonicalId: String?,
        strings: UiStrings,
    ): String? =
        when (canonicalId) {
            DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id -> strings.locDimensionNamePhysicalHealth
            DimensionTaxonomyCatalog.MENTAL_HEALTH.id -> strings.locDimensionNameMentalHealth
            DimensionTaxonomyCatalog.FAMILY_RELATIONSHIPS.id -> strings.locDimensionNameFamilyRelationships
            DimensionTaxonomyCatalog.HOME_ENVIRONMENT.id -> strings.locDimensionNameHomeEnvironment
            DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id -> strings.locDimensionNameWorkLivelihood
            DimensionTaxonomyCatalog.MONEY_FINANCE.id -> strings.locDimensionNameMoneyFinance
            DimensionTaxonomyCatalog.LEARNING_GROWTH.id -> strings.locDimensionNameLearningGrowth
            DimensionTaxonomyCatalog.RECREATION_LEISURE.id -> strings.locDimensionNameRecreationLeisure
            DimensionTaxonomyCatalog.COMMUNITY_SERVICE.id -> strings.locDimensionNameCommunityService
            DimensionTaxonomyCatalog.UNASSIGNED.id -> strings.locDimensionFallbackUnassigned
            else -> null
        }

    /**
     * The localized description for [canonicalId] in [strings], or null when the id has no app-owned one.
     */
    fun localizedDescription(
        canonicalId: String?,
        strings: UiStrings,
    ): String? =
        when (canonicalId) {
            DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id -> strings.locDimensionDescPhysicalHealth
            DimensionTaxonomyCatalog.MENTAL_HEALTH.id -> strings.locDimensionDescMentalHealth
            DimensionTaxonomyCatalog.FAMILY_RELATIONSHIPS.id -> strings.locDimensionDescFamilyRelationships
            DimensionTaxonomyCatalog.HOME_ENVIRONMENT.id -> strings.locDimensionDescHomeEnvironment
            DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id -> strings.locDimensionDescWorkLivelihood
            DimensionTaxonomyCatalog.MONEY_FINANCE.id -> strings.locDimensionDescMoneyFinance
            DimensionTaxonomyCatalog.LEARNING_GROWTH.id -> strings.locDimensionDescLearningGrowth
            DimensionTaxonomyCatalog.RECREATION_LEISURE.id -> strings.locDimensionDescRecreationLeisure
            DimensionTaxonomyCatalog.COMMUNITY_SERVICE.id -> strings.locDimensionDescCommunityService
            DimensionTaxonomyCatalog.UNASSIGNED.id -> strings.locDimensionDescUnassigned
            else -> null
        }

    /**
     * The canonical ids that carry an app-owned name, in taxonomy order.
     *
     * Callers need this to enumerate the names a user did NOT type - a dimension label that matches one of
     * these is app-owned and can be normalized or replaced, where anything else is the user's own text.
     */
    val namedCanonicalIds: List<String> =
        listOf(
            DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id,
            DimensionTaxonomyCatalog.MENTAL_HEALTH.id,
            DimensionTaxonomyCatalog.FAMILY_RELATIONSHIPS.id,
            DimensionTaxonomyCatalog.HOME_ENVIRONMENT.id,
            DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
            DimensionTaxonomyCatalog.MONEY_FINANCE.id,
            DimensionTaxonomyCatalog.LEARNING_GROWTH.id,
            DimensionTaxonomyCatalog.RECREATION_LEISURE.id,
            DimensionTaxonomyCatalog.COMMUNITY_SERVICE.id,
            DimensionTaxonomyCatalog.UNASSIGNED.id,
        )
}
