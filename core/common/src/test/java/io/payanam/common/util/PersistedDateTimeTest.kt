//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class PersistedDateTimeTest {
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    fun parse_keepsLegacyZuluWallClockTime() {
        val parsed = PersistedDateTime.parse("2026-01-31T23:45:00Z")

        assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 1, 31, 23, 45))
    }

    @Test
    fun parseOrDateStart_supportsDateOnlyLegacyValues() {
        val parsed = PersistedDateTime.parseOrDateStart("2026-01-31")

        assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 1, 31, 0, 0))
    }

    @Test
    fun formatAndDayKey_useLocalTimestampContract() {
        val value = LocalDateTime.of(2026, 2, 3, 9, 15)

        assertThat(PersistedDateTime.format(value)).isEqualTo("2026-02-03T09:15:00")
        assertThat(PersistedDateTime.dayKey(value)).isEqualTo("2026-02-03")
    }
}
