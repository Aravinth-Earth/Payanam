//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.dimensions

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.LifeDimension
import org.junit.Test

/**
 * The icon-key half of the icon catalog, pinned on its own.
 *
 * The key a dimension defaults to is a STORED value: a first-run seed row writes it, and a later launch
 * reads it back. So it cannot be "close enough" - the same dimension id must answer the same key on every
 * platform and every release, or an existing user's dimension silently changes icon the next time it is
 * read. This test is what makes the split of the icon catalog safe: the Compose `ImageVector` table in
 * `:core:ui` now delegates here instead of holding its own copy, and the two agreeing for every id is
 * exactly the property that must not be allowed to rot.
 */
class DimensionIconKeysTest {
    @Test
    fun `every canonical dimension resolves to the taxonomy's own default icon key`() {
        // The taxonomy owns these keys, so a mismatch means somebody edited one side of the mapping by
        // hand rather than deriving it.
        for (entry in DimensionTaxonomyCatalog.entries) {
            assertThat(DimensionIconKeys.defaultIconKeyForDimensionId(entry.id))
                .isEqualTo(entry.defaultIconKey)
            // Padded and untrimmed ids must land on the same key, or a seeded row and a stored row that
            // differ only in whitespace would resolve to different icons.
            assertThat(DimensionIconKeys.defaultIconKeyForDimensionId("  ${entry.id}  "))
                .isEqualTo(entry.defaultIconKey)
        }
    }

    @Test
    fun `every legacy LifeDimension id still maps to its own icon key`() {
        // These are the pre-taxonomy ids, still present in imported and migrated rows. They are a second
        // input rather than an alias, so each mapping is pinned literally: the string here IS the stored
        // value an existing user already has.
        val expected =
            mapOf(
                LifeDimension.CAREER_WORK.id to "work",
                LifeDimension.HEALTH_WELLNESS.id to "favorite",
                LifeDimension.RELATIONSHIPS.id to "groups",
                LifeDimension.PERSONAL_GROWTH.id to "trending_up",
                LifeDimension.FINANCIAL.id to "account_balance_wallet",
                LifeDimension.SPIRITUAL.id to "self_improvement",
                LifeDimension.RECREATION.id to "sports_esports",
                LifeDimension.LEARNING.id to "menu_book",
                LifeDimension.CONTRIBUTION.id to "volunteer_activism",
            )
        for ((id, key) in expected) {
            assertThat(DimensionIconKeys.defaultIconKeyForDimensionId(id)).isEqualTo(key)
        }
    }

    @Test
    fun `an unknown or absent id falls back to the generic category key`() {
        // The picker calls this with null to seed a new dimension, so null is a normal input, not an error.
        assertThat(DimensionIconKeys.defaultIconKeyForDimensionId(null)).isEqualTo("category")
        assertThat(DimensionIconKeys.defaultIconKeyForDimensionId("")).isEqualTo("category")
        assertThat(DimensionIconKeys.defaultIconKeyForDimensionId("   ")).isEqualTo("category")
        assertThat(DimensionIconKeys.defaultIconKeyForDimensionId("dim_a_dimension_the_user_invented"))
            .isEqualTo("category")
    }

    @Test
    fun `every resolved key is one the icon table can actually draw`() {
        // The seam splits the key from the vectors, so a key that is not a KEY anymore would resolve on
        // the presentation side and only fail later, in `DimensionIconCatalog.resolve`, as a warning log
        // and a fallback icon. This is the check that keeps the two halves from drifting apart.
        val drawable =
            setOf(
                "work",
                "favorite",
                "groups",
                "trending_up",
                "account_balance_wallet",
                "self_improvement",
                "sports_esports",
                "menu_book",
                "volunteer_activism",
                "category",
                "monitor_heart",
                "psychology",
                "home",
                "help_outline",
            )
        val resolved =
            DimensionTaxonomyCatalog.entries.map { DimensionIconKeys.defaultIconKeyForDimensionId(it.id) } +
                listOf(
                    "work",
                    "favorite",
                    "groups",
                    "trending_up",
                    "account_balance_wallet",
                    "self_improvement",
                    "sports_esports",
                    "menu_book",
                    "volunteer_activism",
                    "category",
                )
        for (key in resolved) {
            assertThat(key).isIn(drawable)
        }
    }
}
