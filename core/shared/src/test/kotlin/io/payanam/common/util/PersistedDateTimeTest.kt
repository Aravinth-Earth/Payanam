//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.common.util

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.AppLogger
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Plain JVM tests for [PersistedDateTime], which now lives in :core:shared.
 *
 * This was a Robolectric test while the class sat in :core:common and bootstrapped the Android
 * logger through a Context. The class reads `AppLogger.current` per call, and that seam defaults to
 * a no-op, so no logger setup is needed to exercise the parsing contract — which is what let this
 * become an ordinary JVM test. `resetForTests` around each case keeps an installed logger from
 * another test class from leaking in, since the JVM is shared across test classes.
 */
class PersistedDateTimeTest {
    @Before
    fun setUp() {
        AppLogger.resetForTests()
    }

    @After
    fun tearDown() {
        AppLogger.resetForTests()
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

    @Test
    fun parse_logsAndRethrowsOnUnparseableValue() {
        val thrown =
            runCatching { PersistedDateTime.parse("not-a-timestamp") }.exceptionOrNull()

        // The failure must surface, not be swallowed into a default — callers rely on the throw to
        // decide whether a row is readable at all.
        assertThat(thrown).isInstanceOf(java.time.format.DateTimeParseException::class.java)
    }

    @Test
    fun parseOrNull_returnsNullForNullBlankAndWhitespace() {
        assertThat(PersistedDateTime.parseOrNull(null)).isNull()
        assertThat(PersistedDateTime.parseOrNull("")).isNull()
        assertThat(PersistedDateTime.parseOrNull("   ")).isNull()
    }

    @Test
    fun parseOrNull_parsesARealValue() {
        assertThat(PersistedDateTime.parseOrNull(" 2026-05-06T07:08:09 "))
            .isEqualTo(LocalDateTime.of(2026, 5, 6, 7, 8, 9))
    }

    @Test
    fun parseOrDateStart_returnsNullForBlankInput() {
        assertThat(PersistedDateTime.parseOrDateStart(null)).isNull()
        assertThat(PersistedDateTime.parseOrDateStart("   ")).isNull()
    }

    @Test
    fun parseOrDateStart_throwsWhenNeitherFormParses() {
        val thrown =
            runCatching { PersistedDateTime.parseOrDateStart("garbage") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(java.time.format.DateTimeParseException::class.java)
    }
}
