//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.compose.ui.graphics.Color
import io.payanam.domain.model.LifeDimension
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.theme.LifeDimensionColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppPreferencesStateTest.
 */
class AppPreferencesStateTest {

    @Test
    fun `resolveEffectiveLanguageTag uses explicit language when selected`() {
        /** Assert equals. */
        assertEquals("en", resolveEffectiveLanguageTag(AppLanguageOption.ENGLISH, "ta"))
        /** Assert equals. */
        assertEquals("ta", resolveEffectiveLanguageTag(AppLanguageOption.TAMIL, "en"))
    }

    @Test
    fun `resolveEffectiveLanguageTag uses normalized system language for system mode`() {
        /** Assert equals. */
        assertEquals("ta", resolveEffectiveLanguageTag(AppLanguageOption.SYSTEM, "ta-IN"))
        /** Assert equals. */
        assertEquals("en", resolveEffectiveLanguageTag(AppLanguageOption.SYSTEM, "fr"))
        /** Assert equals. */
        assertEquals("en", resolveEffectiveLanguageTag(AppLanguageOption.SYSTEM, null))
    }

    @Test
    fun `labelFor returns custom label when available`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Custom Work", Color.Red, true),
                /** Dimension preference. */
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, true),
            ),
        )

        /** Assert equals. */
        assertEquals("Custom Work", state.labelFor(LifeDimension.CAREER_WORK.id))
        /** Assert equals. */
        assertEquals("Fitness", state.labelFor(LifeDimension.HEALTH_WELLNESS.id))
    }

    @Test
    fun `labelFor returns input label when no preferences loaded`() {
        /** State. */
        val state = AppPreferencesState()

        /** Assert equals. */
        assertEquals("Career & Work", state.labelFor("Career & Work"))
    }

    @Test
    fun `colorFor returns custom color when available`() {
        /** Custom color. */
        val customColor = Color(0xFFFF0000)
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", customColor, true),
            ),
        )

        /** Assert equals. */
        assertEquals(customColor, state.colorFor(LifeDimension.CAREER_WORK.id))
    }

    @Test
    fun `colorFor returns default color when no custom color`() {
        /** State. */
        val state = AppPreferencesState()

        /** Default color. */
        val defaultColor = state.colorFor(LifeDimension.CAREER_WORK.id)
        /** Assert equals. */
        assertEquals(LifeDimensionColors.forDimension(LifeDimension.CAREER_WORK.id), defaultColor)
    }

    @Test
    fun `isVisible returns custom visibility when available`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", Color.Red, false),
                /** Dimension preference. */
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, true),
            ),
        )

        /** Assert equals. */
        assertEquals(false, state.isVisible(LifeDimension.CAREER_WORK.id))
        /** Assert equals. */
        assertEquals(true, state.isVisible(LifeDimension.HEALTH_WELLNESS.id))
    }

    @Test
    fun `isVisible returns true by default when no custom setting`() {
        /** State. */
        val state = AppPreferencesState()

        /** Assert equals. */
        assertEquals(true, state.isVisible(LifeDimension.CAREER_WORK.id))
    }

    @Test
    fun `visibleDimensions returns only visible dimensions`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", Color.Red, true),
                /** Dimension preference. */
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, false),
            ),
        )

        /** Visible. */
        val visible = state.visibleDimensions()
        /** Assert equals. */
        assertEquals(1, visible.size)
        /** Assert equals. */
        assertEquals(LifeDimension.CAREER_WORK.id, visible[0].key)
    }

    @Test
    fun `optionsForSelection includes hidden if selected`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", Color.Red, true),
                /** Dimension preference. */
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, false),
            ),
        )

        /** Options. */
        val options = state.optionsForSelection(LifeDimension.HEALTH_WELLNESS.id)
        /** Assert equals. */
        assertEquals(2, options.size) // Both visible and selected hidden
    }

    @Test
    fun `optionsForSelection excludes hidden if not selected`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", Color.Red, true),
                /** Dimension preference. */
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, false),
            ),
        )

        /** Options. */
        val options = state.optionsForSelection(selectedDimensionId = null)
        /** Assert equals. */
        assertEquals(1, options.size) // Only visible
    }

    @Test
    fun `labelForDimension localizes legacy english category using dimension id backed label`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(LifeDimension.CAREER_WORK.id, "தொழில் & வேலை", Color.Red, true),
            ),
        )

        /** Assert equals. */
        assertEquals("தொழில் & வேலை", state.labelForDimension(dimensionId = LifeDimension.CAREER_WORK.id, dimensionName = null))
    }

    @Test
    fun `matchesDimensionOption accepts legacy english category for localized option`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(LifeDimension.CAREER_WORK.id, "தொழில் & வேலை", Color.Red, true),
            ),
        )
        /** Option. */
        val option = DimensionOption(
            id = LifeDimension.CAREER_WORK.id,
            label = "தொழில் & வேலை",
            color = Color.Red,
            isVisible = true,
        )

        /** Assert true. */
        assertTrue(state.matchesDimensionOption(option = option, dimensionId = LifeDimension.CAREER_WORK.id, dimensionName = null))
    }

    @Test
    fun `visibleDimensionOptions preserves custom label override flag`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(
                    key = LifeDimension.CAREER_WORK.id,
                    label = "Earn to Live",
                    color = Color.Red,
                    isVisible = true,
                    hasCustomLabelOverride = true,
                ),
            ),
        )

        /** Assert equals. */
        assertEquals(true, state.visibleDimensionOptions().single().hasCustomLabelOverride)
    }

    @Test
    fun `visibleDimensionOptions keeps DB-backed dimension id instead of legacy enum id`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(
                    key = LifeDimension.CAREER_WORK.id,
                    id = "dim_work_livelihood",
                    label = "Work & Livelihood",
                    color = Color.Red,
                    isVisible = true,
                    canonicalId = "dim_work_livelihood",
                ),
            ),
        )

        /** Assert equals. */
        assertEquals("dim_work_livelihood", state.visibleDimensionOptions().single().id)
    }

    @Test
    fun `labelForDimensionId resolves canonical id through legacy-backed preference`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(
                    key = LifeDimension.CAREER_WORK.id,
                    label = "Work & Livelihood",
                    color = Color.Red,
                    isVisible = true,
                    canonicalId = "dim_work_livelihood",
                ),
            ),
        )

        /** Assert equals. */
        assertEquals("Work & Livelihood", state.labelForDimensionId("dim_work_livelihood"))
    }

    @Test
    fun `labelForDimensionId resolves hidden unassigned dimension`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(
                    key = "dim_unassigned",
                    id = "dim_unassigned",
                    label = "Unassigned",
                    color = Color.Gray,
                    isVisible = false,
                    canonicalId = "dim_unassigned",
                ),
            ),
        )

        /** Assert equals. */
        assertEquals("Unassigned", state.labelForDimensionId("dim_unassigned"))
    }

    @Test
    fun `colorForDimensionId resolves hidden unassigned dimension`() {
        /** Expected color. */
        val expectedColor = Color.Gray
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(
                    key = "dim_unassigned",
                    id = "dim_unassigned",
                    label = "Unassigned",
                    color = expectedColor,
                    isVisible = false,
                    canonicalId = "dim_unassigned",
                ),
            ),
        )

        /** Assert equals. */
        assertEquals(expectedColor, state.colorForDimensionId("dim_unassigned"))
    }

    @Test
    fun `iconOptionForDimensionId resolves hidden unassigned dimension`() {
        /** State. */
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                /** Dimension preference. */
                DimensionPreference(
                    key = "dim_unassigned",
                    id = "dim_unassigned",
                    label = "Unassigned",
                    color = Color.Gray,
                    isVisible = false,
                    canonicalId = "dim_unassigned",
                    iconKey = "help_outline",
                ),
            ),
        )

        /** Assert equals. */
        assertEquals("help_outline", state.iconOptionForDimensionId("dim_unassigned")?.key)
        /** Assert equals. */
        assertEquals("help_outline", DimensionIconCatalog.defaultIconKeyForDimensionId("dim_unassigned"))
    }

    @Test
    fun `effectiveLaunchTaskFilter uses saved launch destination filter when present`() {
        /** State. */
        val state = AppPreferencesState(
            currentTaskFilter = TaskFilter.TODAY,
            launchDestination = LaunchDestination(route = "tasks", taskFilter = TaskFilter.FUTURE),
        )

        /** Assert equals. */
        assertEquals(TaskFilter.FUTURE, state.effectiveLaunchTaskFilter())
    }

    @Test
    fun `effectiveLaunchTaskFilter falls back to current task filter when launch destination has none`() {
        /** State. */
        val state = AppPreferencesState(
            currentTaskFilter = TaskFilter.OVERDUE,
            launchDestination = LaunchDestination(route = "tasks", taskFilter = null),
        )

        /** Assert equals. */
        assertEquals(TaskFilter.OVERDUE, state.effectiveLaunchTaskFilter())
    }

    @Test
    fun `insights chart defaults keep time module and average daily time enabled only`() {
        /** State. */
        val state = AppPreferencesState()

        /** Assert equals. */
        assertEquals(true, state.chartTimeModuleEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeOverallSnapshotEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeExecutionDetailsEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeScoreCardsEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeOverallScoreCardEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeDimensionScoreCardsEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeLineGraphsEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeDailyScoreTrendEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeProgressTrendEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeHistoricalRankingEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTimeMomentumStreakEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartTaskModuleEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartHabitModuleEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartJournalModuleEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartNoteModuleEnabled)
        /** Assert equals. */
        assertEquals(true, state.chartAverageDailyTimeEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartDimSplitEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartDimTrendEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartDailyTimelineEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartWeeklyPatternEnabled)
        /** Assert equals. */
        assertEquals(false, state.chartDailyRhythmEnabled)
    }
}
