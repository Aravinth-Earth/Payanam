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
class LifeDimensionTest {

    private lateinit var logger: UnifiedLogger

    @Before
    fun setup() {
        logger = initLogger()
        logger.d("LifeDimensionTest.setup", "Logger initialized for tests")
    }

    @Test
    fun fromDisplayName_returnsMatchingEnum() {
        val result = LifeDimension.fromDisplayName("Career & Work")
        assertThat(result).isEqualTo(LifeDimension.CAREER_WORK)
    }

    @Test
    fun fromDisplayName_returnsNullForUnknown() {
        val result = LifeDimension.fromDisplayName("Unknown")
        assertThat(result).isNull()
    }

    @Test
    fun fromId_returnsMatchingEnum() {
        val result = LifeDimension.fromId("dim_career_work")
        assertThat(result).isEqualTo(LifeDimension.CAREER_WORK)
    }

    @Test
    fun fromId_returnsNullForUnknown() {
        val result = LifeDimension.fromId("dim_unknown")
        assertThat(result).isNull()
    }

    @Test
    fun allDisplayNames_containsAllEntries() {
        val names = LifeDimension.allDisplayNames()
        assertThat(names).contains("Health & Wellness")
        assertThat(names.size).isEqualTo(LifeDimension.entries.size)
    }

    @Test
    fun weights_arePositive() {
        LifeDimension.entries.forEach { dimension ->
            assertThat(dimension.weight).isGreaterThan(0.0)
        }
    }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
