//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.AverageDailyTimeRowType
import io.payanam.domain.repository.AverageDailyTimeWindow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * AverageDailyTimeCalculatorTest.
 */
class AverageDailyTimeCalculatorTest {
    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    /**
     * Calculate average daily time table returns null when no entries.
     */
    fun calculateAverageDailyTimeTable_returnsNull_whenNoEntries() {
        /** Result. */
        val result = DailyStatsCalculator.calculateAverageDailyTimeTable(emptyList())

        /** Assert that. */
        assertThat(result).isNull()
    }

    @Test
    /**
     * Calculate average daily time table shows today yesterday and all and includes unassigned and untracked.
     */
    fun calculateAverageDailyTimeTable_shows_today_yesterday_and_all_and_includes_unassigned_and_untracked() {
        /** Now. */
        val now = LocalDateTime.of(2026, 2, 8, 1, 0)
        /** Entries. */
        val entries =
            /** List of. */
            listOf(
                /** Create time entry. */
                createTimeEntry(
                    id = "yesterday-work",
                    startedAt = LocalDateTime.of(2026, 2, 7, 9, 0),
                    endedAt = LocalDateTime.of(2026, 2, 7, 10, 0),
                    dimensionId = "career_work",
                ),
                /** Create time entry. */
                createTimeEntry(
                    id = "today-work-part-1",
                    startedAt = LocalDateTime.of(2026, 2, 8, 0, 0),
                    endedAt = LocalDateTime.of(2026, 2, 8, 0, 15),
                    dimensionId = "career_work",
                ),
                /** Create time entry. */
                createTimeEntry(
                    id = "today-unassigned",
                    startedAt = LocalDateTime.of(2026, 2, 8, 0, 15),
                    endedAt = LocalDateTime.of(2026, 2, 8, 0, 25),
                    dimensionId = null,
                ),
                /** Create time entry. */
                createTimeEntry(
                    id = "today-work-active",
                    startedAt = LocalDateTime.of(2026, 2, 8, 0, 45),
                    endedAt = null,
                    dimensionId = "career_work",
                ),
            )

        /** Result. */
        val result = DailyStatsCalculator.calculateAverageDailyTimeTable(entries, now)

        /** Assert that. */
        assertThat(result).isNotNull()
        result!!
        /** Assert that. */
        assertThat(result.totalCalendarDays).isEqualTo(2)
        /** Assert that. */
        assertThat(result.visibleWindows).containsExactly(
            AverageDailyTimeWindow.TODAY_SO_FAR,
            AverageDailyTimeWindow.YESTERDAY,
            AverageDailyTimeWindow.ALL_DAYS,
        ).inOrder()
        /** Assert that. */
        assertThat(result.visibleWindows).doesNotContain(AverageDailyTimeWindow.LAST_7_DAYS)

        /** Career row. */
        val careerRow = result.rows.single {
            it.rowType == AverageDailyTimeRowType.DIMENSION && it.dimensionId == "career_work"
        }
        /** Assert that. */
        assertThat(careerRow.averageMinutesByWindow[AverageDailyTimeWindow.TODAY_SO_FAR]).isEqualTo(30.0)
        /** Assert that. */
        assertThat(careerRow.averageMinutesByWindow[AverageDailyTimeWindow.YESTERDAY]).isEqualTo(60.0)
        /** Assert that. */
        assertThat(careerRow.averageMinutesByWindow[AverageDailyTimeWindow.ALL_DAYS]).isEqualTo(45.0)

        /** Unassigned row. */
        val unassignedRow = result.rows.single { it.rowType == AverageDailyTimeRowType.UNASSIGNED }
        /** Assert that. */
        assertThat(unassignedRow.averageMinutesByWindow[AverageDailyTimeWindow.TODAY_SO_FAR]).isEqualTo(10.0)
        /** Assert that. */
        assertThat(unassignedRow.averageMinutesByWindow[AverageDailyTimeWindow.ALL_DAYS]).isEqualTo(5.0)

        /** Untracked row. */
        val untrackedRow = result.rows.single { it.rowType == AverageDailyTimeRowType.UNTRACKED }
        /** Assert that. */
        assertThat(untrackedRow.averageMinutesByWindow[AverageDailyTimeWindow.TODAY_SO_FAR]).isEqualTo(20.0)
        /** Assert that. */
        assertThat(untrackedRow.averageMinutesByWindow[AverageDailyTimeWindow.ALL_DAYS]).isEqualTo(700.0)
    }
}

private fun createTimeEntry(
    /** Id. */
    id: String,
    /** Started at. */
    startedAt: LocalDateTime,
    endedAt: LocalDateTime?,
    dimensionId: String?,
): TimeEntry =
    /** Time entry. */
    TimeEntry(
        id = id,
        lifeIntentionCategory = "category",
        taskId = null,
        startedAt = startedAt,
        endedAt = endedAt,
        focusRating = null,
        focusNote = null,
        focusRatedAt = null,
        createdAt = startedAt,
        updatedAt = startedAt,
        dimensionId = dimensionId,
    )
