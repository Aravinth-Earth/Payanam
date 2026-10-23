//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.preferences

import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The writer's remaining rows, asserted as ROWS, in the file beside
 * [AppPreferencesWriterTest] rather than inside it.
 *
 * A SEPARATE FILE because its sibling is already 473 lines and these cases would push it past the
 * module's length guard. The fakes are duplicated rather than extracted because pulling them into a
 * shared fixture would mean restructuring the sibling, and the same named chart keys are pinned in
 * both files anyway: each pins a different half of the same contract.
 *
 * WHAT IS WORTH A ROW HERE: every delegate that stores a literal key. A stored key is a value on a
 * user's device - renaming one is a data migration, not a refactor - so the literals are asserted as
 * literals rather than compared against [AppPreferenceKeys], which would pass after both changed
 * together and is exactly the drift a shared writer exists to catch.
 */
class AppPreferencesWriterDelegatesTest {
    private companion object {
        /** A dimension id [DimensionTaxonomyCatalog] actually owns, so its canonical label exists. */
        const val CANONICAL_DIMENSION_ID = "dim_work_livelihood"

        /** A dimension id the app does not own, so there is no canonical label to fall back to. */
        const val UNKNOWN_DIMENSION_ID = "custom_side_quest"

        /**
         * A key that is a real `app_settings` row but not a chart-visibility one, which is what the
         * key-held setter's guard is there to refuse.
         */
        const val NON_CHART_KEY = "theme_mode"
    }

    /**
     * One named chart delegate: its method name for a readable failure, the row it must store, and the
     * call itself.
     *
     * The call is a lambda rather than a `::setChart…` reference because a reference to a suspend member
     * is a `KSuspendFunction`, which a suspend function type will not accept - so the table below reads
     * the same as the writer's own delegate list, one line of production to one line of table.
     */
    private class ChartToggle(
        val delegate: String,
        val key: String,
        val invoke: suspend (AppPreferencesWriter, Boolean) -> Unit,
    )

    /**
     * Every named chart delegate, in the order [AppPreferencesWriter] declares them.
     *
     * Asserted against [AppPreferenceKeys.CHART_VISIBILITY_KEYS] below, so a key added without a
     * delegate - or a delegate pointed at its neighbour's key - fails rather than ships a toggle
     * nothing reads.
     */
    private val chartToggles: List<ChartToggle> =
        listOf(
            ChartToggle("setChartTimeModuleEnabled", "chart_time_module_enabled") { w, on ->
                w.setChartTimeModuleEnabled(on)
            },
            ChartToggle("setChartTimeOverallSnapshotEnabled", "chart_time_overall_snapshot_enabled") { w, on ->
                w.setChartTimeOverallSnapshotEnabled(on)
            },
            ChartToggle("setChartTimeExecutionDetailsEnabled", "chart_time_execution_details_enabled") { w, on ->
                w.setChartTimeExecutionDetailsEnabled(on)
            },
            ChartToggle("setChartTimeScoreCardsEnabled", "chart_time_score_cards_enabled") { w, on ->
                w.setChartTimeScoreCardsEnabled(on)
            },
            ChartToggle("setChartTimeOverallScoreCardEnabled", "chart_time_overall_score_card_enabled") { w, on ->
                w.setChartTimeOverallScoreCardEnabled(on)
            },
            ChartToggle("setChartTimeDimensionScoreCardsEnabled", "chart_time_dimension_score_cards_enabled") { w, on ->
                w.setChartTimeDimensionScoreCardsEnabled(on)
            },
            ChartToggle("setChartTimeLineGraphsEnabled", "chart_time_line_graphs_enabled") { w, on ->
                w.setChartTimeLineGraphsEnabled(on)
            },
            ChartToggle("setChartTimeDailyScoreTrendEnabled", "chart_time_daily_score_trend_enabled") { w, on ->
                w.setChartTimeDailyScoreTrendEnabled(on)
            },
            ChartToggle("setChartTimeProgressTrendEnabled", "chart_time_progress_trend_enabled") { w, on ->
                w.setChartTimeProgressTrendEnabled(on)
            },
            ChartToggle("setChartTimeHistoricalRankingEnabled", "chart_time_historical_ranking_enabled") { w, on ->
                w.setChartTimeHistoricalRankingEnabled(on)
            },
            ChartToggle("setChartTimeMomentumStreakEnabled", "chart_time_momentum_streak_enabled") { w, on ->
                w.setChartTimeMomentumStreakEnabled(on)
            },
            ChartToggle("setChartTaskModuleEnabled", "chart_task_module_enabled") { w, on ->
                w.setChartTaskModuleEnabled(on)
            },
            ChartToggle("setChartHabitModuleEnabled", "chart_habit_module_enabled") { w, on ->
                w.setChartHabitModuleEnabled(on)
            },
            ChartToggle("setChartJournalModuleEnabled", "chart_journal_module_enabled") { w, on ->
                w.setChartJournalModuleEnabled(on)
            },
            ChartToggle("setChartNoteModuleEnabled", "chart_note_module_enabled") { w, on ->
                w.setChartNoteModuleEnabled(on)
            },
            ChartToggle("setChartAverageDailyTimeEnabled", "chart_average_daily_time_enabled") { w, on ->
                w.setChartAverageDailyTimeEnabled(on)
            },
            ChartToggle("setChartDimSplitEnabled", "chart_dim_split_enabled") { w, on ->
                w.setChartDimSplitEnabled(on)
            },
            ChartToggle("setChartDimTrendEnabled", "chart_dim_trend_enabled") { w, on ->
                w.setChartDimTrendEnabled(on)
            },
            ChartToggle("setChartDailyTimelineEnabled", "chart_daily_timeline_enabled") { w, on ->
                w.setChartDailyTimelineEnabled(on)
            },
            ChartToggle("setChartWeeklyPatternEnabled", "chart_weekly_pattern_enabled") { w, on ->
                w.setChartWeeklyPatternEnabled(on)
            },
            ChartToggle("setChartDailyRhythmEnabled", "chart_daily_rhythm_enabled") { w, on ->
                w.setChartDailyRhythmEnabled(on)
            },
            ChartToggle("setChartWeeklyPatternExclEmpty", "chart_weekly_pattern_excl_empty") { w, on ->
                w.setChartWeeklyPatternExclEmpty(on)
            },
            ChartToggle("setChartDailyRhythmExclEmpty", "chart_daily_rhythm_excl_empty") { w, on ->
                w.setChartDailyRhythmExclEmpty(on)
            },
        )

    // ---------------------------------------------------------------- fakes

    private class RecordingSettings : AppSettingsRepository {
        val writes = mutableListOf<Pair<String, String?>>()
        var failOn: String? = null

        override suspend fun getSetting(key: String): String? = writes.lastOrNull { it.first == key }?.second

        override fun observeSetting(key: String): Flow<String?> = flowOf(null)

        override suspend fun setSetting(
            key: String,
            value: String?,
        ) {
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

    /**
     * The catalog fake, with a failure switch per writer that touches it.
     *
     * The sibling's fake can only fail a label write, which leaves the colour, icon and visibility
     * handlers' catch branches unreachable - and those handlers exist precisely to contain a throw.
     */
    private class RecordingDimensions : LifeDimensionCatalogRepository {
        val labels = mutableListOf<Triple<String, String, Boolean>>()
        val colors = mutableListOf<Pair<String, String>>()
        val icons = mutableListOf<Pair<String, String>>()
        val visibility = mutableListOf<Pair<String, Boolean>>()
        var failColors = false
        var failIcons = false
        var failVisibility = false

        override suspend fun updateDimensionLabel(
            dimensionId: String,
            label: String,
        ) {
            labels += Triple(dimensionId, label, false)
        }

        override suspend fun updateDimensionWeight(
            dimensionId: String,
            weight: Double,
        ) = Unit

        override suspend fun updateDimensionColor(
            dimensionId: String,
            color: String,
        ) {
            if (failColors) error("catalog unavailable")
            colors += dimensionId to color
        }

        override suspend fun updateDimensionIcon(
            dimensionId: String,
            icon: String,
        ) {
            if (failIcons) error("catalog unavailable")
            icons += dimensionId to icon
        }

        override suspend fun updateDimensionActiveState(
            dimensionId: String,
            isActive: Boolean,
        ) {
            if (failVisibility) error("catalog unavailable")
            visibility += dimensionId to isActive
        }

        override fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>> = flowOf(emptyList())

        override suspend fun replaceAllDimensions(dimensions: List<ConfiguredLifeDimension>): Int {
            error("not used by the settings write path")
        }
    }

    private class RecordingRollups : ScoreRollupTrigger {
        override suspend fun recalcDayOnly(changeDate: LocalDate) = Unit

        override suspend fun recalcForStatusChange(
            taskId: String,
            date: LocalDate,
        ) = Unit

        override suspend fun recalcForRuleChange(taskId: String) = Unit
    }

    /** Never claims a label is the app's own: the normalization branches are the sibling's subject. */
    private class FixedNormalizer : DimensionLabelNormalizer {
        override fun isAppOwnedLabel(
            canonicalId: String,
            candidateLabel: String,
            effectiveLanguageTag: String,
        ): Boolean = false
    }

    private fun writer(
        settings: RecordingSettings = RecordingSettings(),
        dimensions: RecordingDimensions = RecordingDimensions(),
    ): AppPreferencesWriter =
        AppPreferencesWriter(
            appSettings = settings,
            lifeDimensions = dimensions,
            scoreRollupTrigger = RecordingRollups(),
            labelNormalizer = FixedNormalizer(),
        )

    // ---------------------------------------------------------------- every named chart delegate

    /**
     * All 23 named chart delegates write their OWN row, and spell the value the reader parses.
     *
     * One loop over one table, not 23 test methods: the drift this guards against is a delegate pointed
     * at a NEIGHBOUR's key, and 23 separate methods each holding its own writer would still pass if two
     * of them shared a key. Seeing every pair side by side is what catches that, and asserting the write
     * COUNT is what catches a delegate that also wrote a row it does not own.
     */
    @Test
    fun `every named chart delegate writes its own row, true then false`() =
        runTest {
            for (toggle in chartToggles) {
                val settings = RecordingSettings()
                val writer = writer(settings)

                toggle.invoke(writer, true)
                assertEquals("${toggle.delegate} stored the wrong on-value", "true", settings.valueOf(toggle.key))

                toggle.invoke(writer, false)
                assertEquals("${toggle.delegate} stored the wrong off-value", "false", settings.valueOf(toggle.key))

                // "false", not "": a reader testing `== "true"` reads "false" correctly and "" identically
                // to an absent row, so an off toggle must be the spelled-out value. Two writes, and both
                // under its own key - a delegate that also wrote a neighbour's row would make the two
                // cards read each other.
                assertEquals(2, settings.writes.size)
                assertTrue(settings.writes.all { it.first == toggle.key })
            }
        }

    /**
     * The table above is the whole chart row set: no chart row is unreachable by name, and no named
     * delegate writes a row outside it.
     *
     * Without this, a new `CHART_VISIBILITY_KEYS` entry added for the shared insights section would sit
     * in the set with no delegate beside it, and a rename of one delegate's key would still pass the
     * loop above as long as both halves changed together.
     */
    @Test
    fun `the named chart delegates cover the chart-visibility key set exactly`() {
        assertEquals(
            AppPreferenceKeys.CHART_VISIBILITY_KEYS,
            chartToggles.map { it.key }.toSet(),
        )
        assertEquals(chartToggles.size, chartToggles.map { it.key }.toSet().size)
    }

    // ---------------------------------------------------------------- the key-held chart setter

    /** The key-held overload writes a chart row it was handed, for a caller that already holds the key. */
    @Test
    fun `the key-held chart setter accepts a chart key`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setChartVisibility("chart_dim_trend_enabled", false)

            assertEquals("false", settings.valueOf("chart_dim_trend_enabled"))
            assertEquals(1, settings.writes.size)
        }

    /**
     * A key that is not a chart row is refused, and nothing is written.
     *
     * Rejected loudly rather than stored: a mistyped key would otherwise create a row nothing ever
     * reads, and the toggle would appear to work forever. The throw is asserted, not swallowed, because
     * this setter has no repository call between the guard and the write to absorb it.
     */
    @Test
    fun `the key-held chart setter refuses a key that is not a chart row`() =
        runTest {
            val settings = RecordingSettings()
            val writer = writer(settings)

            // runCatching, not assertThrows: this is a suspend call, and assertThrows takes a
            // non-suspend lambda that cannot call one.
            val failure = runCatching { writer.setChartVisibility(NON_CHART_KEY, true) }.exceptionOrNull()

            assertTrue("guard must reject a non-chart key, got $failure", failure is IllegalArgumentException)
            assertTrue(failure?.message?.contains("Not a chart-visibility preference key") == true)
            assertTrue(settings.writes.isEmpty())
        }

    // ---------------------------------------------------------------- backup and diagnostics rows

    /** The auto-backup cadence is stored under the row the scheduler reads. */
    @Test
    fun `the auto-backup cadence is stored under its own key`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setAutoBackupInterval("daily")

            assertEquals("daily", settings.valueOf("auto_backup_interval"))
        }

    /** The last-run row stores the timestamp verbatim, so the worker compares what the clock wrote. */
    @Test
    fun `the last auto-backup timestamp is stored verbatim`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setAutoBackupLastRun("2026-02-15T03:00:00Z")

            assertEquals("2026-02-15T03:00:00Z", settings.valueOf("auto_backup_last_run"))
        }

    /** Backup rotation is its own spelled-out toggle, not a mirror of the auto-backup row. */
    @Test
    fun `backup rotation stores the spelled-out toggle under its own key`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setBackupRotationEnabled(true)

            assertEquals("true", settings.valueOf("backup_rotation_enabled"))
            assertEquals(1, settings.writes.size)
        }

    /** Debug logging is its own row, so turning rotation off cannot switch logging off with it. */
    @Test
    fun `debug logging stores the spelled-out toggle under its own key`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setDebugLoggingEnabled(false)

            assertEquals("false", settings.valueOf("debug_logging_enabled"))
            assertEquals(1, settings.writes.size)
        }

    // ---------------------------------------------------------------- launch destination and clearing

    /**
     * An arbitrary launch route is stored, and the tasks-filter row is cleared rather than left holding
     * the previous destination's filter.
     */
    @Test
    fun `an arbitrary launch route is stored and the tasks filter row is cleared`() =
        runTest {
            val settings = RecordingSettings()
            writer(settings).setLaunchDestination("habits")

            assertEquals("habits", settings.valueOf("launch_destination_route"))
            // Cleared, not overwritten: landing somewhere that is not Tasks must not leave behind a
            // filter row the next launch would apply to a screen that never asked for one.
            assertTrue(settings.writes.any { it.first == "launch_destination_task_filter" && it.second == null })
            assertEquals(2, settings.writes.size)
        }

    /**
     * A failing clear is contained, and the route row still lands.
     *
     * The stale filter row survives a failed clear, but the user's actual choice of launch screen is not
     * lost with it, and the failure does not reach a caller sitting in a settings gesture.
     */
    @Test
    fun `a failing clear is contained and the launch route still lands`() =
        runTest {
            val settings = RecordingSettings().apply { failOn = "launch_destination_task_filter" }
            writer(settings).setLaunchDestination("habits")

            assertEquals("habits", settings.valueOf("launch_destination_route"))
            assertFalse(settings.wroteKey("launch_destination_task_filter"))
        }

    // ---------------------------------------------------------------- dimension rows

    /** Visibility is written per dimension and in both directions, so a hidden row can come back. */
    @Test
    fun `dimension visibility is written per dimension in both directions`() =
        runTest {
            val dimensions = RecordingDimensions()
            val writer = writer(dimensions = dimensions)

            writer.setDimensionVisibility(CANONICAL_DIMENSION_ID, true)
            writer.setDimensionVisibility("health", false)

            assertEquals(listOf(CANONICAL_DIMENSION_ID to true, "health" to false), dimensions.visibility)
        }

    /** A dimension colour is stored as the hex string the row holds, not parsed and re-rendered. */
    @Test
    fun `a dimension colour is stored as the hex string it is given`() =
        runTest {
            val dimensions = RecordingDimensions()
            writer(dimensions = dimensions).setDimensionColor(CANONICAL_DIMENSION_ID, "#FFAABBCC")

            assertEquals("#FFAABBCC", dimensions.colors.single().second)
        }

    /** A dimension icon is stored under the icon key the renderer resolves. */
    @Test
    fun `a dimension icon is stored under the key the renderer resolves`() =
        runTest {
            val dimensions = RecordingDimensions()
            writer(dimensions = dimensions).setDimensionIcon(CANONICAL_DIMENSION_ID, "briefcase")

            assertEquals("briefcase", dimensions.icons.single().second)
        }

    /**
     * Resetting a label for a dimension the app does not OWN falls back to the dimension id itself.
     *
     * The canonical branch is the sibling's subject. This one is the other half of the same helper: a
     * custom dimension has no shipped label, so the id is the only value that cannot be blank - and a
     * reset that stored "" there would leave the row with a name the screen cannot render.
     */
    @Test
    fun `resetting a label the app does not own stores the dimension id as the label`() =
        runTest {
            val dimensions = RecordingDimensions()
            writer(dimensions = dimensions).resetDimensionLabel(UNKNOWN_DIMENSION_ID)

            assertEquals(UNKNOWN_DIMENSION_ID, dimensions.labels.single().first)
            assertEquals(UNKNOWN_DIMENSION_ID, dimensions.labels.single().second)
        }

    /**
     * A failing colour, icon or visibility write is contained rather than thrown at the caller.
     *
     * Three catch branches in one case: each handler is a standalone gesture, and a throw out of any of
     * them would crash a toggle handler after the UI had already flipped the switch.
     */
    @Test
    fun `a failing dimension colour, icon or visibility write is contained`() =
        runTest {
            val dimensions =
                RecordingDimensions().apply {
                    failColors = true
                    failIcons = true
                    failVisibility = true
                }
            val writer = writer(dimensions = dimensions)

            writer.setDimensionColor(CANONICAL_DIMENSION_ID, "#FFAABBCC")
            writer.setDimensionIcon(CANONICAL_DIMENSION_ID, "briefcase")
            writer.setDimensionVisibility(CANONICAL_DIMENSION_ID, true)

            assertTrue(dimensions.colors.isEmpty())
            assertTrue(dimensions.icons.isEmpty())
            assertTrue(dimensions.visibility.isEmpty())
        }
}
