//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * LensHistoryBackfillCoordinatorTest.
 */
class LensHistoryBackfillCoordinatorTest {

    private lateinit var coordinator: LensHistoryBackfillCoordinator

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "test", 0)
        }
        coordinator = LensHistoryBackfillCoordinator(UnifiedLogger.getInstance())
    }

    @Test
    /**
     * Next limit after returns expected progressive sequence.
     */
    fun next_limit_after_returns_expected_progressive_sequence() {
        assertEquals(14, coordinator.nextLimitAfter(7))
        assertEquals(30, coordinator.nextLimitAfter(14))
        assertEquals(60, coordinator.nextLimitAfter(30))
        assertEquals(90, coordinator.nextLimitAfter(60))
        assertEquals(180, coordinator.nextLimitAfter(90))
        assertEquals(365, coordinator.nextLimitAfter(180))
        assertEquals(Int.MAX_VALUE, coordinator.nextLimitAfter(365))
        assertEquals(null, coordinator.nextLimitAfter(Int.MAX_VALUE))
    }
}
