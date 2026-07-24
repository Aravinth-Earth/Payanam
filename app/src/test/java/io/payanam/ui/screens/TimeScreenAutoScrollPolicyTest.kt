//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TimeScreenAutoScrollPolicyTest {
    private val logger: UnifiedLogger by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Before
    fun setUp() {
        logger.i("TimeScreenAutoScrollPolicyTest.setUp", "Preparing TimeScreen auto-scroll policy tests")
    }

    @Test
    fun shouldAutoScrollForHourHeightChange_true_when_today_and_height_changed() {
        val selectedDate = LocalDate.of(2026, 3, 5)
        val today = LocalDate.of(2026, 3, 5)

        val shouldAutoScroll = shouldAutoScrollForHourHeightChange(
            selectedDate = selectedDate,
            today = today,
            currentHourHeightDp = 180f,
            lastAutoScrollHourHeightDp = 60f,
        )

        assertTrue(shouldAutoScroll)
    }

    @Test
    fun shouldAutoScrollForHourHeightChange_false_when_not_today() {
        val shouldAutoScroll = shouldAutoScrollForHourHeightChange(
            selectedDate = LocalDate.of(2026, 3, 4),
            today = LocalDate.of(2026, 3, 5),
            currentHourHeightDp = 180f,
            lastAutoScrollHourHeightDp = 60f,
        )

        assertFalse(shouldAutoScroll)
    }

    @Test
    fun shouldAutoScrollForHourHeightChange_false_when_previous_height_missing() {
        val shouldAutoScroll = shouldAutoScrollForHourHeightChange(
            selectedDate = LocalDate.of(2026, 3, 5),
            today = LocalDate.of(2026, 3, 5),
            currentHourHeightDp = 180f,
            lastAutoScrollHourHeightDp = null,
        )

        assertFalse(shouldAutoScroll)
    }

    @Test
    fun shouldAutoScrollForHourHeightChange_false_when_height_change_within_epsilon() {
        val shouldAutoScroll = shouldAutoScrollForHourHeightChange(
            selectedDate = LocalDate.of(2026, 3, 5),
            today = LocalDate.of(2026, 3, 5),
            currentHourHeightDp = 60.05f,
            lastAutoScrollHourHeightDp = 60f,
        )

        assertFalse(shouldAutoScroll)
    }

    @Test
    fun shouldAutoScrollOnInitialDateSelection_true_when_today_and_preferences_loaded() {
        val shouldAutoScroll = shouldAutoScrollOnInitialDateSelection(
            selectedDate = LocalDate.of(2026, 3, 5),
            today = LocalDate.of(2026, 3, 5),
            preferencesLoading = false,
        )

        assertTrue(shouldAutoScroll)
    }

    @Test
    fun shouldAutoScrollOnInitialDateSelection_false_when_preferences_still_loading() {
        val shouldAutoScroll = shouldAutoScrollOnInitialDateSelection(
            selectedDate = LocalDate.of(2026, 3, 5),
            today = LocalDate.of(2026, 3, 5),
            preferencesLoading = true,
        )

        assertFalse(shouldAutoScroll)
    }

    @Test
    fun shouldShowTimelineLoadingPlaceholder_true_while_selected_date_content_is_loading() {
        val shouldShowPlaceholder = shouldShowTimelineLoadingPlaceholder(
            isLoading = true,
            isDateContentReady = false,
        )

        assertTrue(shouldShowPlaceholder)
    }

    @Test
    fun shouldShowTimelineLoadingPlaceholder_false_after_selected_date_content_is_ready() {
        val shouldShowPlaceholder = shouldShowTimelineLoadingPlaceholder(
            isLoading = true,
            isDateContentReady = true,
        )

        assertFalse(shouldShowPlaceholder)
    }
}
