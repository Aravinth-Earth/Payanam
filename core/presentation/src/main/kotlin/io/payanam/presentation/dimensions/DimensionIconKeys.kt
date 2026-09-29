//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.dimensions

import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.LifeDimension

/**
 * The DEFAULT ICON KEY for a dimension - the half of the icon catalog that is not a drawing.
 *
 * WHY THE SEAM:
 *
 * `DimensionIconCatalog` in `:core:ui` is a table of 350+ Compose `ImageVector`s, so the whole file cannot
 * leave the composable layer. But only its TABLE is a drawing concern. Asking "which icon key does this
 * dimension default to" is data: the answer is a stored string on a seeded row, produced by
 * `DatabaseInitDimensionSetupModel` before any composable exists, and read by the preferences mapper, the
 * lenses sections and the Android widget. Leaving that half in `:core:ui` means every one of those callers
 * depends on the Compose layer to learn a string.
 *
 * So the lookup lives here and the vector table consumes it. Nothing is duplicated: this object is the only
 * definition of the default, and `:core:ui`'s `resolve` asks this for its fallback. The returned key is a
 * STORED value, which is why a change here is a data migration and not a refactor - the same reasoning
 * [io.payanam.presentation.preferences.AppPreferenceKeys] exists to enforce for `app_settings` keys.
 *
 * WHY NOT `:core:domain`, where the taxonomy is: it sits BESIDE the taxonomy rather than inside it, because
 * this is a presentation default, not a property of the dimension. `LifeDimension` is the legacy id table and
 * stays a second input until that legacy data is gone.
 */
object DimensionIconKeys {
    /**
     * Default icon key for a dimension: taxonomy default, legacy id mapping,
     * then the generic category icon.
     */
    fun defaultIconKeyForDimensionId(dimensionId: String?): String {
        val normalizedId = dimensionId?.trim().orEmpty()
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(normalizedId)?.id
        DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.let { return it.defaultIconKey }
        return when (dimensionId) {
            LifeDimension.CAREER_WORK.id -> "work"
            LifeDimension.HEALTH_WELLNESS.id -> "favorite"
            LifeDimension.RELATIONSHIPS.id -> "groups"
            LifeDimension.PERSONAL_GROWTH.id -> "trending_up"
            LifeDimension.FINANCIAL.id -> "account_balance_wallet"
            LifeDimension.SPIRITUAL.id -> "self_improvement"
            LifeDimension.RECREATION.id -> "sports_esports"
            LifeDimension.LEARNING.id -> "menu_book"
            LifeDimension.CONTRIBUTION.id -> "volunteer_activism"
            else -> "category"
        }
    }
}
