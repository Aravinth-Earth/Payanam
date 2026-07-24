//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import android.content.Context
import com.google.common.truth.Truth.assertThat
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DimensionTaxonomyCatalogTest {
    private lateinit var logger: UnifiedLogger

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()
        logger.d("DimensionTaxonomyCatalogTest.setup", "Logger initialized for taxonomy catalog tests")
    }

    @Test
    fun entries_follow_foundation_first_order() {
        assertThat(
            DimensionTaxonomyCatalog.entries
                .filterNot { it.id == DimensionTaxonomyCatalog.UNASSIGNED.id }
                .map { it.id }
        ).containsExactly(
            "dim_physical_health",
            "dim_mental_health",
            "dim_family_relationships",
            "dim_home_environment",
            "dim_work_livelihood",
            "dim_money_finance",
            "dim_learning_growth",
            "dim_recreation_leisure",
            "dim_community_service"
        ).inOrder()
    }

    @Test
    fun fromAnyId_rejects_legacy_id() {
        val result = DimensionTaxonomyCatalog.fromAnyId("dim_health_wellness")

        assertThat(result).isNull()
    }

    @Test
    fun fromAnyLabel_rejects_legacy_label() {
        val result = DimensionTaxonomyCatalog.fromAnyLabel("Community & Service")

        assertThat(result).isNull()
    }

    @Test
    fun isCanonicalLabel_accepts_canonical_label_only() {
        assertThat(DimensionTaxonomyCatalog.isCanonicalLabel("Home & Environment")).isTrue()
        assertThat(DimensionTaxonomyCatalog.isCanonicalLabel("Family & Relationship")).isFalse()
    }
}
