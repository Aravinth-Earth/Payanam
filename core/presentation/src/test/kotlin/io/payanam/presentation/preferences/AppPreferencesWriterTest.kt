//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.preferences

import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.shared.settings.FocusModePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The rows the shared settings writer stores, asserted as ROWS - the storage contract the Android app
 * already wrote before this class existed.
 *
 * A stored row is a data contract: renaming a key or reformatting a value is a migration, not a
 * refactor, because the Android app and the desktop read the same `app_settings` table. So these assert
 * literal keys and literal values rather than comparing against the constants under test - a test that
 * compared `writer.setThemeMode(x)` against `AppPreferenceKeys.KEY_THEME_MODE` would pass after someone
 * changed both, which is exactly the drift a shared writer exists to catch.
 */
class AppPreferencesWriterTest {
    /** A canonical dimension id this repository actually owns, with its shipped label. */
    private val canonicalId = CANONICAL_DIMENSION_ID
    private val canonicalFallbackLabel =
        requireNotNull(DimensionTaxonomyCatalog.fromCanonicalId(CANONICAL_DIMENSION_ID)).fallbackLabel

    private companion object {
        /** A dimension id [DimensionTaxonomyCatalog] actually owns, so the canonical-fallback branches run. */
        const val CANONICAL_DIMENSION_ID = "dim_work_livelihood"

        /** A dimension id the app does not own, so there is no canonical label to fall back to. */
        const val UNKNOWN_DIMENSION_ID = "custom_side_quest"
    }

    private class RecordingSettings : AppSettingsRepository {
        val writes = mutableListOf<Pair<String, String?>>()
        var failOn: String? = null

        override suspend fun getSetting(key: String): String? = writes.lastOrNull { it.first == key }?.second

        override fun observeSetting(key: String): Flow<String?> = flowOf(null)

        override suspend fun setSetting(key: String, value: String?) {
            if (key == failOn) error("settings store unavailable")
            writes += key to value
        }

        override suspend fun deleteSetting(key: String) {
            writes.removeIf { it.first == key }
        }

        override fun getAllSettings(): Flow<Map<String, String?>> = flowOf(emptyMap())

        fun valueOf(key: String): String? = writes.lastOrNull { it.first == key }?.second

        fun wroteKey(key: String): Boolean = writes.any { it.first == key }
    }

    private class RecordingDimensions : LifeDimensionCatalogRepository {
        val labels = mutableListOf<Triple<String, String, Boolean>>()
        val weights = mutableListOf<Pair<String, Double>>()
        val colors = mutableListOf<Pair<String, String>>()
        val icons = mutableListOf<Pair<String, String>>()
        val visibility = mutableListOf<Pair<String, Boolean>>()
        var failLabels = false

        override suspend fun updateDimensionLabel(dimensionId: String, label: String) {
            if (failLabels) error("catalog unavailable")
            labels += Triple(dimensionId, label, false)
        }

        override suspend fun updateDimensionWeight(dimensionId: String, weight: Double) {
            weights += dimensionId to weight
        }

        override suspend fun updateDimensionColor(dimensionId: String, color: String) {
            colors += dimensionId to color
        }

        override suspend fun updateDimensionIcon(dimensionId: String, icon: String) {
            icons += dimensionId to icon
        }

        override suspend fun updateDimensionActiveState(dimensionId: String, isActive: Boolean) {
            visibility += dimensionId to isActive
        }

        override fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>> = flowOf(emptyList())

        override suspend fun replaceAllDimensions(dimensions: List<ConfiguredLifeDimension>): Int {
            error("not used by the settings write path")
        }
    }

    private class RecordingRollups : ScoreRollupTrigger {
        val recalcs = mutableListOf<LocalDate>()

        override suspend fun recalcDayOnly(changeDate: LocalDate) {
            recalcs += changeDate
        }

        override suspend fun recalcForStatusChange(taskId: String, date: LocalDate) {
            error("not used by the settings write path")
        }

        override suspend fun recalcForRuleChange(taskId: String) {
            error("not used by the settings write path")
        }
    }

    /** Reports [owned] as the app's own label in any language, so the branch under test is chosen by [owned]. */
    private class FixedNormalizer(private val owned: Boolean) : DimensionLabelNormalizer {
        override fun isAppOwnedLabel(
            canonicalId: String,
            candidateLabel: String,
            effectiveLanguageTag: String,
        ): Boolean = owned
    }

    private fun writer(
        settings: RecordingSettings = RecordingSettings(),
        dimensions: RecordingDimensions = RecordingDimensions(),
        rollups: RecordingRollups = RecordingRollups(),
        normalizer: DimensionLabelNormalizer = FixedNormalizer(owned = false),
    ): AppPreferencesWriter =
        AppPreferencesWriter(
            appSettings = settings,
            lifeDimensions = dimensions,
            scoreRollupTrigger = rollups,
            labelNormalizer = normalizer,
        )

    // ---------------------------------------------------------------- keys are data contracts

    @Test
    fun `appearance toggles store the shipped keys`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            writer.setThemeMode("dark")
            writer.setAppLanguage("ta")
            writer.setFontFamily("serif")
            writer.setTimeFormat("24h")

            assertEquals("dark", settings.valueOf("theme_mode"))
            assertEquals("ta", settings.valueOf("app_language"))
            assertEquals("serif", settings.valueOf("font_family"))
            assertEquals("24h", settings.valueOf("time_format"))
        }

    @Test
    fun `boolean toggles store the string the reader parses, not a boolean literal`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            writer.setAutoBackupEnabled(true)
            writer.setAutoTrackHabitTimeGlobal(false)

            // "false", not "": a reader testing `== "true"` reads "false" correctly and "" identically to
            // an absent row, so an off toggle must be the spelled-out value.
            assertEquals("true", settings.valueOf("auto_backup_enabled"))
            assertEquals("false", settings.valueOf("auto_track_habit_time_global"))
        }

    @Test
    fun `dimension auto-track preference stores one row per dimension under the prefix`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            writer.setAutoTrackDimensionPreference(CANONICAL_DIMENSION_ID, true)
            writer.setAutoTrackDimensionPreference("health", false)

            assertEquals("true", settings.valueOf("auto_track_dimension_$CANONICAL_DIMENSION_ID"))
            assertEquals("false", settings.valueOf("auto_track_dimension_health"))
        }

    // ---------------------------------------------------------------- clamps

    @Test
    fun `day boundary hour is clamped to the range the rollup accepts`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            writer.setDayBoundaryHour(9)
            assertEquals("5", settings.valueOf("day_boundary_hour"))

            // Out of range in both directions clamps rather than storing an hour no rollup is defined for.
            writer.setDayBoundaryHour(-4)
            assertEquals("0", settings.valueOf("day_boundary_hour"))

            writer.setDayBoundaryHour(3)
            assertEquals("3", settings.valueOf("day_boundary_hour"))
        }

    @Test
    fun `dimension weight is clamped to the range the aggregation is defined for`() =
        runTest {
            val dimensions = RecordingDimensions()
            val writer = writer(dimensions = dimensions)

            writer.setDimensionWeight(CANONICAL_DIMENSION_ID, 99.0)
            writer.setDimensionWeight(CANONICAL_DIMENSION_ID, 0.0)

            assertEquals(10.0, dimensions.weights[0].second, 0.0)
            assertEquals(0.1, dimensions.weights[1].second, 0.0)
        }

    @Test
    fun `backup rotation count is clamped to the range rotation can act on`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            writer.setBackupRotationCount(0)
            assertEquals("1", settings.valueOf("backup_rotation_count"))

            writer.setBackupRotationCount(5000)
            assertEquals("999", settings.valueOf("backup_rotation_count"))

            writer.setBackupRotationCount(7)
            assertEquals("7", settings.valueOf("backup_rotation_count"))
        }

    @Test
    fun `hour height is stored with two decimals and a locale-independent separator`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            writer.setTimeHourHeightDp(72f)

            // Locale-pinned on purpose: a locale rendering a decimal comma would store "72,00", which the
            // shared reader's toFloatOrNull cannot parse, and the scale preset would silently reset.
            assertEquals("72.00", settings.valueOf("time_hour_height_dp"))
        }

    // ---------------------------------------------------------------- dimension labels

    @Test
    fun `a label matching the app's own label is stored as the canonical default`() =
        runTest {
            val dimensions = RecordingDimensions()
            val writer = writer(dimensions = dimensions, normalizer = FixedNormalizer(owned = true))

            writer.setDimensionLabel(CANONICAL_DIMENSION_ID, "Work", effectiveLanguageTag = "en")

            // The user typed the word the row already displayed. Storing it verbatim would freeze that
            // word into a custom label that stops following translations.
            assertEquals(canonicalFallbackLabel, dimensions.labels.single().second)
        }

    @Test
    fun `a label that differs from the app's own label is stored as a custom label, trimmed`() =
        runTest {
            val dimensions = RecordingDimensions()
            val writer = writer(dimensions = dimensions, normalizer = FixedNormalizer(owned = false))

            writer.setDimensionLabel(CANONICAL_DIMENSION_ID, "  Deep work  ", effectiveLanguageTag = "en")

            assertEquals("Deep work", dimensions.labels.single().second)
        }

    @Test
    fun `a blank label resets to the canonical default rather than storing an empty name`() =
        runTest {
            val dimensions = RecordingDimensions()
            val writer = writer(dimensions = dimensions)

            writer.setDimensionLabel(CANONICAL_DIMENSION_ID, "   ", effectiveLanguageTag = "en")

            assertEquals(canonicalFallbackLabel, dimensions.labels.single().second)
        }

    @Test
    fun `a dimension the app does not own keeps its trimmed label`() =
        runTest {
            val dimensions = RecordingDimensions()
            val writer = writer(dimensions = dimensions, normalizer = FixedNormalizer(owned = true))

            // There is no canonical label for a custom dimension, so there is nothing to reset to and the
            // user's text is the only sensible stored value.
            writer.setDimensionLabel(UNKNOWN_DIMENSION_ID, "  Side quest  ", effectiveLanguageTag = "en")

            assertEquals("Side quest", dimensions.labels.single().second)
        }

    @Test
    fun `resetting a label writes the canonical default`() =
        runTest {
            val dimensions = RecordingDimensions()
            val writer = writer(dimensions = dimensions)

            writer.resetDimensionLabel(CANONICAL_DIMENSION_ID)

            assertEquals(CANONICAL_DIMENSION_ID, dimensions.labels.single().first)
            assertEquals(canonicalFallbackLabel, dimensions.labels.single().second)
        }

    @Test
    fun `a weight change recalculates the day layer so the new weight applies now`() =
        runTest {
            val rollups = RecordingRollups()
            val writer = writer(rollups = rollups)

            writer.setDimensionWeight(CANONICAL_DIMENSION_ID, 5.0)

            assertEquals(1, rollups.recalcs.size)
            assertEquals(LocalDate.now(), rollups.recalcs.single())
        }

    // ---------------------------------------------------------------- focus mode

    @Test
    fun `choosing a preset rewrites every tab, hiding the ones it omits`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            writer.setActivePreset(FocusModePreset.SIMPLE_TIME_HABITS)

            assertEquals("simple_time_habits", settings.valueOf("focus_mode_active_preset"))
            assertEquals("true", settings.valueOf("tab_visible_time"))
            assertEquals("true", settings.valueOf("tab_visible_habits"))
            // This preset does not include these routes, so their rows must be rewritten hidden: a writer
            // that only wrote the preset's own visible routes would leave a previously-shown tab on screen.
            assertEquals("false", settings.valueOf("tab_visible_journal"))
            assertEquals("false", settings.valueOf("tab_visible_notes"))
            assertEquals("false", settings.valueOf("tab_visible_tasks"))
            assertEquals("true", settings.valueOf("tab_visible_lenses"))
            // All seven, including the ones this preset omits.
            assertEquals(7, settings.writes.count { it.first.startsWith("tab_visible_") })
        }

    @Test
    fun `the settings tab stays visible under every preset`() =
        runTest {
            for (preset in FocusModePreset.entries) {
                val settings = RecordingSettings()
                writer(settings).setActivePreset(preset)

                // A focus mode that hides the way out of itself is a trap, so this row is unconditional.
                assertEquals("true", settings.valueOf("tab_visible_settings"))
            }
        }

    @Test
    fun `hiding the settings tab individually is refused and writes nothing`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            writer.setTabVisibility("settings", false)

            assertFalse(settings.wroteKey("tab_visible_settings"))
            assertTrue(settings.writes.isEmpty())
        }

    @Test
    fun `an ordinary tab visibility toggle is written`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setTabVisibility("notes", false)

            assertEquals("false", settings.valueOf("tab_visible_notes"))
        }

    // ---------------------------------------------------------------- launch destination

    @Test
    fun `launching into tasks stores the filter under both keys it is read from`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setLaunchDestinationTasks("overdue")

            assertEquals("tasks", settings.valueOf("launch_destination_route"))
            assertEquals("overdue", settings.valueOf("launch_destination_task_filter"))
            // Two rows, because the landing screen and the Tasks screen read separate rows; writing one
            // leaves them disagreeing on the next launch.
            assertEquals("overdue", settings.valueOf("task_filter_option"))
        }

    @Test
    fun `launching into tasks without a filter clears the filter row rather than storing an empty one`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setLaunchDestinationTasks(null)

            assertEquals("tasks", settings.valueOf("launch_destination_route"))
            // A cleared row and a row holding "" are different states: a reader distinguishing "never
            // chosen" from "chosen as none" reads them differently.
            assertEquals(null, settings.valueOf("launch_destination_task_filter"))
            assertTrue(settings.writes.any { it.first == "launch_destination_task_filter" && it.second == null })
            assertFalse(settings.wroteKey("task_filter_option"))
        }

    @Test
    fun `launching into time clears the tasks filter row`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setLaunchDestinationTime()

            assertEquals("time", settings.valueOf("launch_destination_route"))
            assertTrue(settings.writes.any { it.first == "launch_destination_task_filter" && it.second == null })
        }

    @Test
    fun `onboarding completion is a one-time flag`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).markFocusModeOnboardingCompleted()

            assertEquals("true", settings.valueOf("focus_mode_onboarding_completed"))
        }

    // ---------------------------------------------------------------- failures are contained

    @Test
    fun `a failing settings row does not propagate out of the write`() =
        runTest {
            val settings = RecordingSettings().apply { failOn = "theme_mode" }
            val writer = writer(settings)

            // The UI already reflected the user's gesture, so a write failure is a lost row rather than a
            // crash inside a toggle handler; the next read shows the previous value.
            writer.setThemeMode("dark")

            assertFalse(settings.wroteKey("theme_mode"))
        }

    @Test
    fun `a failing catalog write does not propagate out of the write`() =
        runTest {
            val dimensions = RecordingDimensions().apply { failLabels = true }
            val rollups = RecordingRollups()
            writer(dimensions = dimensions, rollups = rollups).setDimensionLabel(CANONICAL_DIMENSION_ID, "Deep work", "en")

            assertTrue(dimensions.labels.isEmpty())
            // A failed weight write must not leave a half-applied edit behind either.
            assertTrue(rollups.recalcs.isEmpty())
        }

    // ---------------------------------------------------------------- chart toggles

    @Test
    fun `each chart toggle writes its own key`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            writer.setChartTimeModuleEnabled(true)
            writer.setChartNoteModuleEnabled(false)
            writer.setChartDailyRhythmExclEmpty(true)

            assertEquals("true", settings.valueOf("chart_time_module_enabled"))
            assertEquals("false", settings.valueOf("chart_note_module_enabled"))
            assertEquals("true", settings.valueOf("chart_daily_rhythm_excl_empty"))
            // Each toggle owns its row: sharing one key across cards would make them read each other.
            assertEquals(3, settings.writes.size)
        }
}
