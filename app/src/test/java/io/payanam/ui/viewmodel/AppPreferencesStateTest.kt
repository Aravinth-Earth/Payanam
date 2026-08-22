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
class AppPreferencesStateTest {

    @Test
    fun `resolveEffectiveLanguageTag uses explicit language when selected`() {
        assertEquals("en", resolveEffectiveLanguageTag(AppLanguageOption.ENGLISH, "ta"))
        assertEquals("ta", resolveEffectiveLanguageTag(AppLanguageOption.TAMIL, "en"))
    }

    @Test
    fun `resolveEffectiveLanguageTag uses normalized system language for system mode`() {
        assertEquals("ta", resolveEffectiveLanguageTag(AppLanguageOption.SYSTEM, "ta-IN"))
        assertEquals("en", resolveEffectiveLanguageTag(AppLanguageOption.SYSTEM, "fr"))
        assertEquals("en", resolveEffectiveLanguageTag(AppLanguageOption.SYSTEM, null))
    }

    @Test
    fun `labelFor returns custom label when available`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Custom Work", Color.Red, true),
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, true),
            ),
        )
        assertEquals("Custom Work", state.labelFor(LifeDimension.CAREER_WORK.id))
        assertEquals("Fitness", state.labelFor(LifeDimension.HEALTH_WELLNESS.id))
    }

    @Test
    fun `labelFor returns input label when no preferences loaded`() {
        val state = AppPreferencesState()
        assertEquals("Career & Work", state.labelFor("Career & Work"))
    }

    @Test
    fun `colorFor returns custom color when available`() {
        val customColor = Color(0xFFFF0000)
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", customColor, true),
            ),
        )
        assertEquals(customColor, state.colorFor(LifeDimension.CAREER_WORK.id))
    }

    @Test
    fun `colorFor returns default color when no custom color`() {
        val state = AppPreferencesState()
        val defaultColor = state.colorFor(LifeDimension.CAREER_WORK.id)
        assertEquals(LifeDimensionColors.forDimension(LifeDimension.CAREER_WORK.id), defaultColor)
    }

    @Test
    fun `isVisible returns custom visibility when available`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", Color.Red, false),
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, true),
            ),
        )
        assertEquals(false, state.isVisible(LifeDimension.CAREER_WORK.id))
        assertEquals(true, state.isVisible(LifeDimension.HEALTH_WELLNESS.id))
    }

    @Test
    fun `isVisible returns true by default when no custom setting`() {
        val state = AppPreferencesState()
        assertEquals(true, state.isVisible(LifeDimension.CAREER_WORK.id))
    }

    @Test
    fun `visibleDimensions returns only visible dimensions`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", Color.Red, true),
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, false),
            ),
        )
        val visible = state.visibleDimensions()
        assertEquals(1, visible.size)
        assertEquals(LifeDimension.CAREER_WORK.id, visible[0].key)
    }

    @Test
    fun `optionsForSelection includes hidden if selected`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", Color.Red, true),
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, false),
            ),
        )
        val options = state.optionsForSelection(LifeDimension.HEALTH_WELLNESS.id)
        assertEquals(2, options.size) // Both visible and selected hidden
    }

    @Test
    fun `optionsForSelection excludes hidden if not selected`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(LifeDimension.CAREER_WORK.id, "Work", Color.Red, true),
                DimensionPreference(LifeDimension.HEALTH_WELLNESS.id, "Fitness", Color.Blue, false),
            ),
        )
        val options = state.optionsForSelection(selectedDimensionId = null)
        assertEquals(1, options.size) // Only visible
    }

    @Test
    fun `labelForDimension localizes legacy english category using dimension id backed label`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(LifeDimension.CAREER_WORK.id, "தொழில் & வேலை", Color.Red, true),
            ),
        )
        assertEquals("தொழில் & வேலை", state.labelForDimension(dimensionId = LifeDimension.CAREER_WORK.id, dimensionName = null))
    }

    @Test
    fun `matchesDimensionOption accepts legacy english category for localized option`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(LifeDimension.CAREER_WORK.id, "தொழில் & வேலை", Color.Red, true),
            ),
        )
        val option = DimensionOption(
            id = LifeDimension.CAREER_WORK.id,
            label = "தொழில் & வேலை",
            color = Color.Red,
            isVisible = true,
        )
        assertTrue(state.matchesDimensionOption(option = option, dimensionId = LifeDimension.CAREER_WORK.id, dimensionName = null))
    }

    @Test
    fun `visibleDimensionOptions preserves custom label override flag`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(
                    key = LifeDimension.CAREER_WORK.id,
                    label = "Earn to Live",
                    color = Color.Red,
                    isVisible = true,
                    hasCustomLabelOverride = true,
                ),
            ),
        )
        assertEquals(true, state.visibleDimensionOptions().single().hasCustomLabelOverride)
    }

    @Test
    fun `visibleDimensionOptions keeps DB-backed dimension id instead of legacy enum id`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
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
        assertEquals("dim_work_livelihood", state.visibleDimensionOptions().single().id)
    }

    @Test
    fun `labelForDimensionId resolves canonical id through legacy-backed preference`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
                DimensionPreference(
                    key = LifeDimension.CAREER_WORK.id,
                    label = "Work & Livelihood",
                    color = Color.Red,
                    isVisible = true,
                    canonicalId = "dim_work_livelihood",
                ),
            ),
        )
        assertEquals("Work & Livelihood", state.labelForDimensionId("dim_work_livelihood"))
    }

    @Test
    fun `labelForDimensionId resolves hidden unassigned dimension`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
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
        assertEquals("Unassigned", state.labelForDimensionId("dim_unassigned"))
    }

    @Test
    fun `colorForDimensionId resolves hidden unassigned dimension`() {
        val expectedColor = Color.Gray
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
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
        assertEquals(expectedColor, state.colorForDimensionId("dim_unassigned"))
    }

    @Test
    fun `iconOptionForDimensionId resolves hidden unassigned dimension`() {
        val state = AppPreferencesState(
            dimensionPreferences = listOf(
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
        assertEquals("help_outline", state.iconOptionForDimensionId("dim_unassigned")?.key)
        assertEquals("help_outline", DimensionIconCatalog.defaultIconKeyForDimensionId("dim_unassigned"))
    }

    @Test
    fun `effectiveLaunchTaskFilter uses saved launch destination filter when present`() {
        val state = AppPreferencesState(
            currentTaskFilter = TaskFilter.TODAY,
            launchDestination = LaunchDestination(route = "tasks", taskFilter = TaskFilter.FUTURE),
        )
        assertEquals(TaskFilter.FUTURE, state.effectiveLaunchTaskFilter())
    }

    @Test
    fun `effectiveLaunchTaskFilter falls back to current task filter when launch destination has none`() {
        val state = AppPreferencesState(
            currentTaskFilter = TaskFilter.OVERDUE,
            launchDestination = LaunchDestination(route = "tasks", taskFilter = null),
        )
        assertEquals(TaskFilter.OVERDUE, state.effectiveLaunchTaskFilter())
    }

    @Test
    fun `insights chart defaults keep time module and average daily time enabled only`() {
        val state = AppPreferencesState()
        assertEquals(true, state.chartTimeModuleEnabled)
        assertEquals(false, state.chartTimeOverallSnapshotEnabled)
        assertEquals(false, state.chartTimeExecutionDetailsEnabled)
        assertEquals(false, state.chartTimeScoreCardsEnabled)
        assertEquals(false, state.chartTimeOverallScoreCardEnabled)
        assertEquals(false, state.chartTimeDimensionScoreCardsEnabled)
        assertEquals(false, state.chartTimeLineGraphsEnabled)
        assertEquals(false, state.chartTimeDailyScoreTrendEnabled)
        assertEquals(false, state.chartTimeProgressTrendEnabled)
        assertEquals(false, state.chartTimeHistoricalRankingEnabled)
        assertEquals(false, state.chartTimeMomentumStreakEnabled)
        assertEquals(false, state.chartTaskModuleEnabled)
        assertEquals(false, state.chartHabitModuleEnabled)
        assertEquals(false, state.chartJournalModuleEnabled)
        assertEquals(false, state.chartNoteModuleEnabled)
        assertEquals(true, state.chartAverageDailyTimeEnabled)
        assertEquals(false, state.chartDimSplitEnabled)
        assertEquals(false, state.chartDimTrendEnabled)
        assertEquals(false, state.chartDailyTimelineEnabled)
        assertEquals(false, state.chartWeeklyPatternEnabled)
        assertEquals(false, state.chartDailyRhythmEnabled)
    }
}
