//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * LifeDimensionTest.
 */
class LifeDimensionTest {

    private lateinit var logger: UnifiedLogger

    @Before
    /**
     * Setup.
     */
    fun setup() {
        logger = initLogger()
        logger.d("LifeDimensionTest.setup", "Logger initialized for tests")
    }

    @Test
    /**
     * From display name returns matching enum.
     */
    fun fromDisplayName_returnsMatchingEnum() {
        /** Result. */
        val result = LifeDimension.fromDisplayName("Career & Work")
        /** Assert that. */
        assertThat(result).isEqualTo(LifeDimension.CAREER_WORK)
    }

    @Test
    /**
     * From display name returns null for unknown.
     */
    fun fromDisplayName_returnsNullForUnknown() {
        /** Result. */
        val result = LifeDimension.fromDisplayName("Unknown")
        /** Assert that. */
        assertThat(result).isNull()
    }

    @Test
    /**
     * From id returns matching enum.
     */
    fun fromId_returnsMatchingEnum() {
        /** Result. */
        val result = LifeDimension.fromId("dim_career_work")
        /** Assert that. */
        assertThat(result).isEqualTo(LifeDimension.CAREER_WORK)
    }

    @Test
    /**
     * From id returns null for unknown.
     */
    fun fromId_returnsNullForUnknown() {
        /** Result. */
        val result = LifeDimension.fromId("dim_unknown")
        /** Assert that. */
        assertThat(result).isNull()
    }

    @Test
    /**
     * All display names contains all entries.
     */
    fun allDisplayNames_containsAllEntries() {
        /** Names. */
        val names = LifeDimension.allDisplayNames()
        /** Assert that. */
        assertThat(names).contains("Health & Wellness")
        /** Assert that. */
        assertThat(names.size).isEqualTo(LifeDimension.entries.size)
    }

    @Test
    /**
     * Weights are positive.
     */
    fun weights_arePositive() {
        LifeDimension.entries.forEach { dimension ->
            /** Assert that. */
            assertThat(dimension.weight).isGreaterThan(0.0)
        }
    }

    private fun initLogger(): UnifiedLogger {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
