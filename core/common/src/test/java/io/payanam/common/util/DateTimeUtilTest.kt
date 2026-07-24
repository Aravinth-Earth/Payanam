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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class DateTimeUtilTest {
    private lateinit var logger: UnifiedLogger

    @Before
    fun setup() {
        logger = initLogger()
        logger.d("DateTimeUtilTest.setup", "Logger initialized for tests")
    }

    @Test
    fun startOfDay_returnsMidnight() {
        val date = LocalDate.of(2026, 1, 31)
        val result = DateTimeUtil.startOfDay(date)
        assertThat(result.toLocalTime()).isEqualTo(LocalTime.MIDNIGHT)
    }

    @Test
    fun endOfDay_returnsMaxTime() {
        val date = LocalDate.of(2026, 1, 31)
        val result = DateTimeUtil.endOfDay(date)
        assertThat(result.toLocalTime()).isEqualTo(LocalTime.MAX)
    }

    @Test
    fun defaultParameters_useTodayAnd12HourFormat() {
        val today = DateTimeUtil.today()
        val start = DateTimeUtil.startOfDay()
        val end = DateTimeUtil.endOfDay()

        assertThat(start.toLocalDate()).isEqualTo(today)
        assertThat(start.hour).isEqualTo(0)
        assertThat(start.minute).isEqualTo(0)
        assertThat(end.toLocalDate()).isEqualTo(today)
        assertThat(end.hour).isEqualTo(23)
        assertThat(end.minute).isEqualTo(59)
        assertThat(end.second).isEqualTo(59)

        val time = LocalTime.of(9, 7)
        assertThat(DateTimeUtil.formatTime(time)).isEqualTo(
            DateTimeUtil.formatTime(time, use24Hour = false),
        )
    }

    @Test
    fun isToday_matchesToday() {
        val now = DateTimeUtil.now()
        assertThat(DateTimeUtil.isToday(now)).isTrue()
    }

    @Test
    fun today_returnsCurrentDate() {
        val today = DateTimeUtil.today()
        assertThat(today).isEqualTo(LocalDate.now())
    }

    @Test
    fun isOverdue_trueForPast() {
        val past = LocalDateTime.now().minusMinutes(5)
        assertThat(DateTimeUtil.isOverdue(past)).isTrue()
    }

    @Test
    fun isOverdue_falseForFuture() {
        val future = LocalDateTime.now().plusMinutes(5)
        assertThat(DateTimeUtil.isOverdue(future)).isFalse()
    }

    @Test
    fun isOverdue_falseForNull() {
        assertThat(DateTimeUtil.isOverdue(null)).isFalse()
    }

    @Test
    fun formatTime_respects24HourPreference() {
        val time = LocalTime.of(14, 5)
        val formatted24 = DateTimeUtil.formatTime(time, use24Hour = true)
        val formatted12 = DateTimeUtil.formatTime(time, use24Hour = false)
        assertThat(formatted24).isEqualTo("14:05")
        assertThat(formatted12).contains("2:05")
    }

    @Test
    fun formatDuration_formatsReadable() {
        assertThat(DateTimeUtil.formatDuration(45)).isEqualTo("45m")
        assertThat(DateTimeUtil.formatDuration(120)).isEqualTo("2h")
        assertThat(DateTimeUtil.formatDuration(135)).isEqualTo("2h 15m")
    }

    @Test
    fun isoRoundTrip_parsesAndFormats() {
        val time = LocalDateTime.of(2026, 1, 31, 11, 45, 30)
        val iso = DateTimeUtil.toIsoString(time)
        val parsed = DateTimeUtil.parseIso(iso)
        assertThat(parsed).isEqualTo(time)
    }

    @Test
    fun formatDate_outputsReadable() {
        val date = LocalDate.of(2026, 1, 31)
        val formatted = DateTimeUtil.formatDate(date)
        assertThat(formatted).isEqualTo("Jan 31, 2026")
    }

    @Test
    fun minutesBetween_calculatesDifference() {
        val start = LocalDateTime.of(2026, 1, 31, 10, 0)
        val end = LocalDateTime.of(2026, 1, 31, 11, 30)
        assertThat(DateTimeUtil.minutesBetween(start, end)).isEqualTo(90)
    }

    @Test(expected = Exception::class)
    fun parseIso_invalidString_throwsException() {
        val invalidIso = "not-a-valid-iso-string"
        DateTimeUtil.parseIso(invalidIso)
    }

    private fun initLogger(): UnifiedLogger {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        return UnifiedLogger.getInstance()
    }
}
