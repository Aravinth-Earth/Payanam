//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.LifeDimension
import io.payanam.ui.theme.LifeDimensionColors
import io.payanam.ui.viewmodel.DimensionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
/**
 * TimeScreenGapRangeRegressionTest.
 */
class TimeScreenGapRangeRegressionTest {
    private val logger: UnifiedLogger by lazy {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Test
    fun `midnight gap bridges to previous day end when available`() {
        /** Selected date. */
        val selectedDate = LocalDate.of(2026, 2, 11)
        /** Last entry end. */
        val lastEntryEnd = LocalDateTime.of(2026, 2, 10, 23, 30)

        /** Val. */
        val (start, end) = resolveGapConvertDateTimeRange(
            selectedDate = selectedDate,
            gapStartTime = LocalTime.MIDNIGHT,
            gapEndTime = LocalTime.of(8, 40),
            lastEntryEndDateTime = lastEntryEnd,
        )

        logger.i(
            "TimeScreenGapRangeRegressionTest",
            "Validated bridged midnight range",
            /** Map of. */
            mapOf(
                "start" to start.toString(),
                "end" to end.toString(),
            ),
        )
        /** Assert equals. */
        assertEquals(lastEntryEnd, start)
        /** Assert equals. */
        assertEquals(LocalDateTime.of(2026, 2, 11, 8, 40), end)
    }

    @Test
    fun `non midnight gap stays within selected date`() {
        /** Selected date. */
        val selectedDate = LocalDate.of(2026, 2, 11)

        /** Val. */
        val (start, end) = resolveGapConvertDateTimeRange(
            selectedDate = selectedDate,
            gapStartTime = LocalTime.of(14, 10),
            gapEndTime = LocalTime.of(15, 0),
            lastEntryEndDateTime = LocalDateTime.of(2026, 2, 10, 23, 30),
        )

        /** Assert equals. */
        assertEquals(LocalDateTime.of(2026, 2, 11, 14, 10), start)
        /** Assert equals. */
        assertEquals(LocalDateTime.of(2026, 2, 11, 15, 0), end)
    }

    @Test
    fun `end before start rolls to next day`() {
        /** Selected date. */
        val selectedDate = LocalDate.of(2026, 2, 11)

        /** Val. */
        val (start, end) = resolveGapConvertDateTimeRange(
            selectedDate = selectedDate,
            gapStartTime = LocalTime.of(23, 30),
            gapEndTime = LocalTime.of(1, 10),
            lastEntryEndDateTime = null,
        )

        /** Assert equals. */
        assertEquals(LocalDateTime.of(2026, 2, 11, 23, 30), start)
        /** Assert equals. */
        assertEquals(LocalDateTime.of(2026, 2, 12, 1, 10), end)
        /** Assert true. */
        assertTrue(end.isAfter(start))
    }

    @Test
    fun `gap continue action forwards selected start context`() {
        /** Start date. */
        val startDate = LocalDate.of(2026, 2, 11)
        /** Start time. */
        val startTime = LocalTime.of(10, 5)
        /** Captured dimension. */
        var capturedDimension: DimensionOption? = null
        /** Captured task id. */
        var capturedTaskId: String? = null
        /** Captured start date. */
        var capturedStartDate: LocalDate? = null
        /** Captured start time. */
        var capturedStartTime: LocalTime? = null

        /** Continue action. */
        val continueAction = resolveContinueAction(
            isGapCreate = true,
            isActiveEntry = false,
            onSetAndContinue = { dimension, taskId, date, time ->
                capturedDimension = dimension
                capturedTaskId = taskId
                capturedStartDate = date
                capturedStartTime = time
            },
            onContinueEntry = null,
            selectedDimension = DimensionOption(
                id = LifeDimension.HEALTH_WELLNESS.id,
                label = LifeDimension.HEALTH_WELLNESS.displayName,
                color = LifeDimensionColors.forDimension(LifeDimension.HEALTH_WELLNESS.displayName),
                isVisible = true,
            ),
            selectedTaskId = "task-1",
            startDate = startDate,
            startTime = startTime,
        )

        /** Assert not null. */
        assertNotNull(continueAction)
        continueAction?.invoke()
        /** Assert equals. */
        assertEquals(LifeDimension.HEALTH_WELLNESS.id, capturedDimension?.id)
        /** Assert equals. */
        assertEquals("task-1", capturedTaskId)
        /** Assert equals. */
        assertEquals(startDate, capturedStartDate)
        /** Assert equals. */
        assertEquals(startTime, capturedStartTime)
    }

    @Test
    fun `continue action hidden for active entry without gap mode`() {
        /** Continue action. */
        val continueAction = resolveContinueAction(
            isGapCreate = false,
            isActiveEntry = true,
            onSetAndContinue = null,
            onContinueEntry = { error("Should not be invoked") },
            selectedDimension = DimensionOption(
                id = LifeDimension.CAREER_WORK.id,
                label = LifeDimension.CAREER_WORK.displayName,
                color = LifeDimensionColors.forDimension(LifeDimension.CAREER_WORK.displayName),
                isVisible = true,
            ),
            selectedTaskId = null,
            startDate = LocalDate.of(2026, 2, 11),
            startTime = LocalTime.NOON,
        )

        /** Assert null. */
        assertNull(continueAction)
    }
}
