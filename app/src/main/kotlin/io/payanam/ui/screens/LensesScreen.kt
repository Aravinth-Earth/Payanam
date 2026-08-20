//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.FeatureFlags
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.DimensionSplitWindow
import io.payanam.ui.viewmodel.DimensionTrendWindow
import io.payanam.ui.viewmodel.LensMoment
import io.payanam.ui.viewmodel.LensUiState
import io.payanam.ui.viewmodel.LensViewModel
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.colorForDimension
import io.payanam.ui.viewmodel.colorForDimensionId
import io.payanam.ui.viewmodel.isVisibleDimensionId
import io.payanam.ui.viewmodel.labelForDimension
import io.payanam.ui.viewmodel.labelForDimensionId

internal const val LENS_UNASSIGNED_DIMENSION_KEY = "unassigned"
internal const val LENS_DAY_MINUTES = 24 * 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * Lenses screen.
 */
fun LensesScreen(
    viewModel: LensViewModel = hiltViewModel(),
    onOpenTime: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenHabits: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
    onOpenNotes: () -> Unit = {},
    onOpenScoreDetail: (type: String, key: String) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsState()
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Screen scroll state. */
    val screenScrollState = rememberScrollState()
    var timeExpanded by rememberSaveable { mutableStateOf(true) }
    var tasksExpanded by rememberSaveable { mutableStateOf(false) }
    var habitsExpanded by rememberSaveable { mutableStateOf(false) }
    var journalExpanded by rememberSaveable { mutableStateOf(false) }
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    /**
     * Toggle exclusive.
     */
    fun toggleExclusive(section: String) {
        /** Should expand. */
        val shouldExpand = when (section) {
            "time" -> !timeExpanded
            "tasks" -> !tasksExpanded
            "habits" -> !habitsExpanded
            "journal" -> !journalExpanded
            "notes" -> !notesExpanded
            else -> false
        }
        timeExpanded = shouldExpand && section == "time"
        tasksExpanded = shouldExpand && section == "tasks"
        habitsExpanded = shouldExpand && section == "habits"
        journalExpanded = shouldExpand && section == "journal"
        notesExpanded = shouldExpand && section == "notes"
    }

    /** Launched effect. */
    LaunchedEffect(uiState.selectedDate, uiState.selectedMoment) {
        viewModel.loadLensData()
        logger.d(
            "LensesScreen",
            "Simplified lens updated",
            /** Map of. */
            mapOf(
                "date" to uiState.selectedDate.toString(),
                "moment" to uiState.selectedMoment.name,
            ),
        )
    }

    /** App prefs for trigger. */
    val appPrefsForTrigger = LocalAppPreferences.current
    /** Launched effect. */
    LaunchedEffect(
        appPrefsForTrigger.chartTimeModuleEnabled,
        appPrefsForTrigger.chartTimeOverallSnapshotEnabled,
        appPrefsForTrigger.chartTimeExecutionDetailsEnabled,
        appPrefsForTrigger.chartTaskModuleEnabled,
        appPrefsForTrigger.chartHabitModuleEnabled,
        appPrefsForTrigger.chartJournalModuleEnabled,
        appPrefsForTrigger.chartNoteModuleEnabled,
        appPrefsForTrigger.chartTimeScoreCardsEnabled,
        appPrefsForTrigger.chartTimeOverallScoreCardEnabled,
        appPrefsForTrigger.chartTimeDimensionScoreCardsEnabled,
        appPrefsForTrigger.chartTimeLineGraphsEnabled,
        appPrefsForTrigger.chartTimeDailyScoreTrendEnabled,
        appPrefsForTrigger.chartTimeProgressTrendEnabled,
        appPrefsForTrigger.chartTimeHistoricalRankingEnabled,
        appPrefsForTrigger.chartTimeMomentumStreakEnabled,
    ) {
        logger.d(
            "LensesScreen.chartVisibility",
            "Insight module visibility updated",
            /** Map of. */
            mapOf(
                "time" to appPrefsForTrigger.chartTimeModuleEnabled,
                "timeOverallSnapshot" to appPrefsForTrigger.chartTimeOverallSnapshotEnabled,
                "timeExecutionDetails" to appPrefsForTrigger.chartTimeExecutionDetailsEnabled,
                "tasks" to appPrefsForTrigger.chartTaskModuleEnabled,
                "habits" to appPrefsForTrigger.chartHabitModuleEnabled,
                "journal" to appPrefsForTrigger.chartJournalModuleEnabled,
                "notes" to appPrefsForTrigger.chartNoteModuleEnabled,
                "timeScoreCards" to appPrefsForTrigger.chartTimeScoreCardsEnabled,
                "timeOverallScoreCard" to appPrefsForTrigger.chartTimeOverallScoreCardEnabled,
                "timeDimensionScoreCards" to appPrefsForTrigger.chartTimeDimensionScoreCardsEnabled,
                "timeLineGraphs" to appPrefsForTrigger.chartTimeLineGraphsEnabled,
                "timeDailyScoreTrend" to appPrefsForTrigger.chartTimeDailyScoreTrendEnabled,
                "timeProgressTrend" to appPrefsForTrigger.chartTimeProgressTrendEnabled,
                "timeHistoricalRanking" to appPrefsForTrigger.chartTimeHistoricalRankingEnabled,
                "timeMomentumStreak" to appPrefsForTrigger.chartTimeMomentumStreakEnabled,
            ),
        )
    }
    /** Launched effect. */
    LaunchedEffect(
        uiState.isLoading,
        appPrefsForTrigger.chartTimeModuleEnabled,
        appPrefsForTrigger.chartTimeScoreCardsEnabled,
        appPrefsForTrigger.chartTimeOverallScoreCardEnabled,
        appPrefsForTrigger.chartTimeDimensionScoreCardsEnabled,
        appPrefsForTrigger.chartTimeLineGraphsEnabled,
        appPrefsForTrigger.chartTimeDailyScoreTrendEnabled,
        appPrefsForTrigger.chartTimeProgressTrendEnabled,
        appPrefsForTrigger.chartTimeHistoricalRankingEnabled,
        appPrefsForTrigger.chartTimeMomentumStreakEnabled,
        appPrefsForTrigger.chartDimSplitEnabled,
        appPrefsForTrigger.chartAverageDailyTimeEnabled,
        appPrefsForTrigger.chartDimTrendEnabled,
        appPrefsForTrigger.chartDailyTimelineEnabled,
        appPrefsForTrigger.chartWeeklyPatternEnabled,
        appPrefsForTrigger.chartWeeklyPatternExclEmpty,
        appPrefsForTrigger.chartDailyRhythmEnabled,
        appPrefsForTrigger.chartDailyRhythmExclEmpty,
    ) {
        /** If. */
        if (!uiState.isLoading) {
            viewModel.loadEnabledChartsSequentially(
                chartTimeModuleEnabled = appPrefsForTrigger.chartTimeModuleEnabled,
                chartTimeScoreCardsEnabled = appPrefsForTrigger.chartTimeScoreCardsEnabled,
                chartTimeOverallScoreCardEnabled = appPrefsForTrigger.chartTimeOverallScoreCardEnabled,
                chartTimeDimensionScoreCardsEnabled = appPrefsForTrigger.chartTimeDimensionScoreCardsEnabled,
                chartTimeLineGraphsEnabled = appPrefsForTrigger.chartTimeLineGraphsEnabled,
                chartTimeDailyScoreTrendEnabled = appPrefsForTrigger.chartTimeDailyScoreTrendEnabled,
                chartTimeProgressTrendEnabled = appPrefsForTrigger.chartTimeProgressTrendEnabled,
                chartTimeHistoricalRankingEnabled = appPrefsForTrigger.chartTimeHistoricalRankingEnabled,
                chartTimeMomentumStreakEnabled = appPrefsForTrigger.chartTimeMomentumStreakEnabled,
                chartDimSplitEnabled = appPrefsForTrigger.chartDimSplitEnabled,
                chartAverageDailyTimeEnabled = appPrefsForTrigger.chartAverageDailyTimeEnabled,
                chartDimTrendEnabled = appPrefsForTrigger.chartDimTrendEnabled,
                chartDailyTimelineEnabled = appPrefsForTrigger.chartDailyTimelineEnabled,
                chartWeeklyPatternEnabled = appPrefsForTrigger.chartWeeklyPatternEnabled,
                chartWeeklyPatternExclEmpty = appPrefsForTrigger.chartWeeklyPatternExclEmpty,
                chartDailyRhythmEnabled = appPrefsForTrigger.chartDailyRhythmEnabled,
                chartDailyRhythmExclEmpty = appPrefsForTrigger.chartDailyRhythmExclEmpty,
            )
        }
    }

    /** Scaffold. */
    Scaffold(
        topBar = {
            /** Top app bar. */
            TopAppBar(
                title = { Text(stringResource(id = R.string.loc_lenses)) },
                expandedHeight = 52.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        /** If. */
        if (uiState.isLoading) {
            /** Box. */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                /** Circular progress indicator. */
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        /** Column. */
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(screenScrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /** If. */
            if (!FeatureFlags.minimalModeEnabled && appPrefsForTrigger.chartTimeOverallSnapshotEnabled) {
                /** Overall card. */
                OverallCard(uiState)
            }
            /** Module sections. */
            ModuleSections(
                uiState = uiState,
                onRequestMoreHistory = viewModel::requestNextTimeHistoryStage,
                timeExpanded = timeExpanded,
                onToggleTime = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "time", "expanded" to !timeExpanded))
                    /** Toggle exclusive. */
                    toggleExclusive("time")
                },
                tasksExpanded = tasksExpanded,
                onToggleTasks = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "tasks", "expanded" to !tasksExpanded))
                    /** Toggle exclusive. */
                    toggleExclusive("tasks")
                },
                habitsExpanded = habitsExpanded,
                onToggleHabits = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "habits", "expanded" to !habitsExpanded))
                    /** Toggle exclusive. */
                    toggleExclusive("habits")
                },
                journalExpanded = journalExpanded,
                onToggleJournal = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "journal", "expanded" to !journalExpanded))
                    /** Toggle exclusive. */
                    toggleExclusive("journal")
                },
                notesExpanded = notesExpanded,
                onToggleNotes = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "notes", "expanded" to !notesExpanded))
                    /** Toggle exclusive. */
                    toggleExclusive("notes")
                },
                onOpenTime = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "time"))
                    /** On open time. */
                    onOpenTime()
                },
                onOpenTasks = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "tasks"))
                    /** On open tasks. */
                    onOpenTasks()
                },
                onOpenHabits = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "habits"))
                    /** On open habits. */
                    onOpenHabits()
                },
                onOpenJournal = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "journal"))
                    /** On open journal. */
                    onOpenJournal()
                },
                onOpenNotes = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "notes"))
                    /** On open notes. */
                    onOpenNotes()
                },
                onOpenScoreDetail = { type, key ->
                    logger.d("LensesScreen.scoreDetailOpened", "Score detail opened", mapOf("type" to type, "key" to key))
                    /** On open score detail. */
                    onOpenScoreDetail(type, key)
                },
                onDimensionSplitWindowSelect = { viewModel.selectDimensionSplitWindow(it) },
                onDimensionSplitShiftLeft = { viewModel.shiftDimensionSplitLeft() },
                onDimensionSplitShiftRight = { viewModel.shiftDimensionSplitRight() },
                onDimensionTrendWindowSelect = { viewModel.selectDimensionTrendWindow(it) },
            )
        }
    }
}

@Composable
private fun OverallCard(uiState: LensUiState) {
    /** Summary. */
    val summary = uiState.selectedRangeSummary
    /** Planned. */
    val planned = summary?.totalPlannedMinutes ?: 0
    /** Actual. */
    val actual = summary?.totalActualMinutes ?: 0
    /** Missed. */
    val missed = (planned - actual).coerceAtLeast(0)
    /** Adherence. */
    val adherence = (summary?.averageAdherence ?: uiState.adherenceScore).coerceIn(0f, 1f)
    /** Average completeness. */
    val averageCompleteness = ((summary?.averagePlanCompleteness ?: uiState.planCompletenessScore) * 100f).toInt()
    /** Average adherence. */
    val averageAdherence = (adherence * 100f).toInt()
    /** Card. */
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))) {
        /** Column. */
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            /** Text. */
            Text(stringResource(id = R.string.loc_lens_overall_snapshot), fontWeight = FontWeight.SemiBold)
            /** Text. */
            Text(stringResource(id = R.string.loc_plan_reality_totals_line, formatMinutes(planned), formatMinutes(actual)))
            /** Text. */
            Text(stringResource(id = R.string.loc_lens_missed_time_line, formatMinutes(missed)))
            /** Text. */
            Text(stringResource(id = R.string.loc_lens_focus_gap_line, formatMinutes(summary?.totalFocusGapMinutes ?: 0)))
            /** Text. */
            Text(stringResource(id = R.string.loc_plan_reality_avg_score_line, averageCompleteness, averageAdherence))
            /** Text. */
            Text(momentHint(uiState.selectedMoment))
            /** Linear progress indicator. */
            LinearProgressIndicator(progress = { adherence }, modifier = Modifier.fillMaxWidth().height(8.dp))
        }
    }
}

@Composable
private fun ModuleSections(
    /** Ui state. */
    uiState: LensUiState,
    onRequestMoreHistory: () -> Unit,
    /** Time expanded. */
    timeExpanded: Boolean,
    onToggleTime: () -> Unit,
    /** Tasks expanded. */
    tasksExpanded: Boolean,
    onToggleTasks: () -> Unit,
    /** Habits expanded. */
    habitsExpanded: Boolean,
    onToggleHabits: () -> Unit,
    /** Journal expanded. */
    journalExpanded: Boolean,
    onToggleJournal: () -> Unit,
    /** Notes expanded. */
    notesExpanded: Boolean,
    onToggleNotes: () -> Unit,
    onOpenTime: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenHabits: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenScoreDetail: (type: String, key: String) -> Unit = { _, _ -> },
    onDimensionSplitWindowSelect: (DimensionSplitWindow) -> Unit = {},
    onDimensionSplitShiftLeft: () -> Unit = {},
    onDimensionSplitShiftRight: () -> Unit = {},
    onDimensionTrendWindowSelect: (DimensionTrendWindow) -> Unit = {},
) {
    /** Summary. */
    val summary = uiState.selectedRangeSummary
    /** App prefs. */
    val appPrefs = LocalAppPreferences.current
    /** Time charts enabled. */
    val timeChartsEnabled = appPrefs.chartTimeModuleEnabled
    /** Execution details enabled. */
    val executionDetailsEnabled = appPrefs.chartTimeExecutionDetailsEnabled
    /** Task module enabled. */
    val taskModuleEnabled = appPrefs.chartTaskModuleEnabled
    /** Habit module enabled. */
    val habitModuleEnabled = appPrefs.chartHabitModuleEnabled
    /** Journal module enabled. */
    val journalModuleEnabled = appPrefs.chartJournalModuleEnabled
    /** Note module enabled. */
    val noteModuleEnabled = appPrefs.chartNoteModuleEnabled
    /** Include supplemental actual. */
    val includeSupplementalActual = true
    /** Dimension ids. */
    val dimensionIds = collectDimensionIds(uiState).filter(appPrefs::isVisibleDimensionId)

    /** If. */
    if (timeChartsEnabled) {
        /** If. */
        if (FeatureFlags.minimalModeEnabled) {
        /** Module card. */
        ModuleCard(
            title = stringResource(id = R.string.loc_time),
            expanded = timeExpanded,
            onToggle = onToggleTime,
            ctaText = stringResource(id = R.string.loc_go_to_time),
            onCta = onOpenTime,
        ) {
            /** If. */
            if (timeChartsEnabled) {
                /** Minimal tracked time bar chart. */
                MinimalTrackedTimeBarChart(items = uiState.dailyTrackedTimeStats)
                /** Minimal focus bar chart. */
                MinimalFocusBarChart(items = uiState.dailyFocusAverages)
                /** Minimal focused hours bar chart. */
                MinimalFocusedHoursBarChart(items = uiState.dailyFocusedHoursStats)
                /** If. */
                if (appPrefs.chartAverageDailyTimeEnabled) {
                    /** Average daily time table section. */
                    AverageDailyTimeTableSection(summary = uiState.averageDailyTimeTable)
                }
                /** If. */
                if (appPrefs.chartDimSplitEnabled) {
                    /** Dimension split section. */
                    DimensionSplitSection(
                        state = uiState.dimensionSplit,
                        onWindowSelect = onDimensionSplitWindowSelect,
                        onShiftLeft = onDimensionSplitShiftLeft,
                        onShiftRight = onDimensionSplitShiftRight,
                    )
                }
                /** If. */
                if (appPrefs.chartDimTrendEnabled) {
                    /** Dimension trend section. */
                    DimensionTrendSection(
                        state = uiState.dimensionTrend,
                        onWindowSelect = onDimensionTrendWindowSelect,
                        appPrefs = appPrefs,
                    )
                }
                /** If. */
                if (appPrefs.chartDailyTimelineEnabled) {
                    /** Dimension heatmap section. */
                    DimensionHeatmapSection(
                        state = uiState.heatmap,
                        appPrefs = appPrefs,
                    )
                }
                /** If. */
                if (appPrefs.chartWeeklyPatternEnabled) {
                    /** Week grid section. */
                    WeekGridSection(
                        state = uiState.weekGrid,
                        appPrefs = appPrefs,
                    )
                }
                /** If. */
                if (appPrefs.chartDailyRhythmEnabled) {
                    /** Minute pattern section. */
                    MinutePatternSection(
                        state = uiState.minutePattern,
                        appPrefs = appPrefs,
                    )
                }
            }
        }
        } else {
        /** Module card. */
        ModuleCard(
            title = stringResource(id = R.string.loc_time),
            expanded = timeExpanded,
            onToggle = onToggleTime,
            ctaText = stringResource(id = R.string.loc_go_to_time),
            onCta = onOpenTime,
        ) {
            /** Planned. */
            val planned = summary?.totalPlannedMinutes ?: 0
            /** Actual. */
            val actual = (summary?.totalActualMinutes ?: 0) - if (includeSupplementalActual) 0 else (summary?.supplementalActualMinutes ?: 0)
            /** Missed. */
            val missed = (planned - actual).coerceAtLeast(0)
            /** Drift. */
            val drift = actual - planned
            /** Planned task minutes. */
            val plannedTaskMinutes = summary?.plannedTaskMinutes ?: 0
            /** Planned habit minutes. */
            val plannedHabitMinutes = summary?.plannedHabitMinutes ?: 0
            /** Planned time only minutes. */
            val plannedTimeOnlyMinutes = (planned - plannedTaskMinutes - plannedHabitMinutes).coerceAtLeast(0)
            /** Unplanned minutes. */
            val unplannedMinutes = (LENS_DAY_MINUTES - planned).coerceAtLeast(0)
            /** Actual task minutes. */
            val actualTaskMinutes = summary?.actualTaskMinutes ?: 0
            /** Actual habit minutes. */
            val actualHabitMinutes = summary?.actualHabitMinutes ?: 0
            /** Actual time only minutes. */
            val actualTimeOnlyMinutes = (actual - actualTaskMinutes - actualHabitMinutes).coerceAtLeast(0)
            /** Untracked minutes. */
            val untrackedMinutes = (summary?.totalUntrackedMinutes ?: 0).coerceAtLeast(0)
            /** Plan coverage percent. */
            val planCoveragePercent = percentageValue(numerator = planned, denominator = LENS_DAY_MINUTES)
            /** Readiness percent. */
            val readinessPercent = percentageValue(
                numerator = ((summary?.averagePlanCompleteness ?: uiState.planCompletenessScore) * 100f).toInt(),
                denominator = 100,
            )
            /** Completion quality percent. */
            val completionQualityPercent = percentageValue(
                numerator = ((summary?.averageAdherence ?: uiState.adherenceScore) * 100f).toInt(),
                denominator = 100,
            )
            /** Addressed reflections. */
            val addressedReflections = uiState.reflections.count { it.isAddressed }
            /** Reflection outcome. */
            val reflectionOutcome = stringResource(
                id = R.string.loc_lens_kpi_addressed_ratio_value,
                /** Addressed reflections. */
                addressedReflections,
                uiState.reflections.size,
            )
            /** If. */
            if (executionDetailsEnabled) {
                /** Moment kpi rows. */
                MomentKpiRows(
                    moment = uiState.selectedMoment,
                    startRows = listOf(
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_kpi_planned_load) to formatMinutes(planned),
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_kpi_plan_coverage) to stringResource(id = R.string.loc_percent_value, planCoveragePercent),
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_kpi_readiness) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                    ),
                    liveRows = listOf(
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_kpi_execution_so_far) to formatMinutes(actual),
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_kpi_drift) to formatSignedMinutes(drift.toLong()),
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_kpi_missed_so_far) to formatMinutes(missed),
                    ),
                    closeRows = listOf(
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_kpi_completion_quality) to stringResource(id = R.string.loc_percent_value, completionQualityPercent),
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_kpi_reflection_outcomes) to reflectionOutcome,
                        /** String resource. */
                        stringResource(id = R.string.loc_lens_kpi_focus_gap) to formatMinutes(summary?.totalFocusGapMinutes ?: 0),
                    ),
                )
                /** Lens time split card. */
                LensTimeSplitCard(
                    title = stringResource(id = R.string.loc_lens_time_planned_breakdown),
                    items = listOf(
                        /** Lens time split item. */
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_planned_time_only), plannedTimeOnlyMinutes),
                        /** Lens time split item. */
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_planned_tasks), plannedTaskMinutes),
                        /** Lens time split item. */
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_planned_habits), plannedHabitMinutes),
                        /** Lens time split item. */
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_unplanned), unplannedMinutes),
                    ),
                )
                /** Lens time split card. */
                LensTimeSplitCard(
                    title = stringResource(id = R.string.loc_lens_time_spent_breakdown),
                    items = listOf(
                        /** Lens time split item. */
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_spent_time_only), actualTimeOnlyMinutes),
                        /** Lens time split item. */
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_spent_tasks), actualTaskMinutes),
                        /** Lens time split item. */
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_spent_habits), actualHabitMinutes),
                        /** Lens time split item. */
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_spent_untracked), untrackedMinutes),
                    ),
                )
            }
            /** Time module section content. */
            TimeModuleSectionContent(
                uiState = uiState,
                summary = summary,
                dimensionIds = dimensionIds,
                includeSupplementalActual = includeSupplementalActual,
                onRequestMoreHistory = onRequestMoreHistory,
            )
            /** If. */
            if (timeChartsEnabled && appPrefs.chartAverageDailyTimeEnabled) {
                /** Average daily time table section. */
                AverageDailyTimeTableSection(summary = uiState.averageDailyTimeTable)
            }
            /** If. */
            if (timeChartsEnabled) {
                /** Time advanced chart sections. */
                TimeAdvancedChartSections(
                    uiState = uiState,
                    appPrefs = appPrefs,
                    onDimensionSplitWindowSelect = onDimensionSplitWindowSelect,
                    onDimensionSplitShiftLeft = onDimensionSplitShiftLeft,
                    onDimensionSplitShiftRight = onDimensionSplitShiftRight,
                    onDimensionTrendWindowSelect = onDimensionTrendWindowSelect,
                )
            }
        }
        } // end minimal else
    }
    /** If. */
    if (taskModuleEnabled) {
    /** Module card. */
    ModuleCard(
        title = stringResource(id = R.string.settings_database_tasks),
        expanded = tasksExpanded,
        onToggle = onToggleTasks,
        ctaText = stringResource(id = R.string.loc_go_to_tasks),
        onCta = onOpenTasks,
    ) {
        /** Planned. */
        val planned = summary?.plannedTaskCount ?: 0
        /** Completed. */
        val completed = summary?.completedTaskCount ?: 0
        /** Missed. */
        val missed = summary?.missedTaskCount ?: 0
        /** In flight. */
        val inFlight = (planned - completed - missed).coerceAtLeast(0)
        /** If. */
        if (FeatureFlags.minimalModeEnabled) {
            /** Minimal task summary row. */
            MinimalTaskSummaryRow(
                overdue = missed,
                today = planned,
                future = inFlight,
            )
        } else {
            /** Total. */
            val total = planned
            /** Completion quality percent. */
            val completionQualityPercent = percentageValue(numerator = completed, denominator = planned)
            /** Readiness percent. */
            val readinessPercent = percentageValue(
                numerator = ((summary?.averagePlanCompleteness ?: uiState.planCompletenessScore) * 100f).toInt(),
                denominator = 100,
            )
            /** Addressed reflections. */
            val addressedReflections = uiState.reflections.count { it.isAddressed }
            /** Reflection outcome. */
            val reflectionOutcome = stringResource(
                id = R.string.loc_lens_kpi_addressed_ratio_value,
                /** Addressed reflections. */
                addressedReflections,
                uiState.reflections.size,
            )
            /** Moment kpi rows. */
            MomentKpiRows(
                moment = uiState.selectedMoment,
                startRows = listOf(
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_planned_load) to planned.toString(),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_plan_coverage) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_readiness) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                ),
                liveRows = listOf(
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_execution_so_far) to stringResource(id = R.string.loc_completed_tasks_ratio, completed, total),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_in_flight) to inFlight.toString(),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_missed_so_far) to missed.toString(),
                ),
                closeRows = listOf(
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_completion_quality) to stringResource(id = R.string.loc_percent_value, completionQualityPercent),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_reflection_outcomes) to reflectionOutcome,
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_missed_so_far) to missed.toString(),
                ),
            )
            /** Text. */
            Text(stringResource(id = R.string.loc_planned_tasks_count, planned))
            /** Text. */
            Text(stringResource(id = R.string.loc_completed_tasks_ratio, completed, total))
            /** Text. */
            Text(stringResource(id = R.string.loc_lens_missed_tasks_line, missed))
            /** Text. */
            Text(stringResource(id = R.string.loc_lens_group_by_dimension), fontWeight = FontWeight.Medium)
            /** If. */
            if (dimensionIds.isEmpty()) {
                /** Text. */
                Text(stringResource(id = R.string.loc_lens_no_dimension_distribution))
            } else {
                /** Planned map. */
                val plannedMap = summary?.plannedTasksByDimension ?: emptyMap()
                /** Completed map. */
                val completedMap = summary?.completedTasksByDimension ?: emptyMap()
                /** Missed map. */
                val missedMap = summary?.missedTasksByDimension ?: emptyMap()
                dimensionIds.forEach { id ->
                    /** Label. */
                    val label = appPrefs.labelForDimensionId(id)
                        ?: appPrefs.labelForDimension(id, null)
                        ?: stringResource(id = R.string.loc_dimension_fallback_unassigned)
                    /** Color. */
                    val color = appPrefs.colorForDimensionId(id)
                        ?: appPrefs.colorForDimension(id, null)
                        ?: MaterialTheme.colorScheme.primary
                    /** Planned by dimension. */
                    val plannedByDimension = plannedMap[id] ?: 0
                    /** Completed by dimension. */
                    val completedByDimension = completedMap[id] ?: 0
                    /** Missed by dimension. */
                    val missedByDimension = missedMap[id] ?: 0
                    /** Line. */
                    val line = stringResource(
                        id = R.string.loc_tagged_title,
                        /** Label. */
                        label,
                        /** String resource. */
                        stringResource(id = R.string.loc_completed_tasks_ratio, completedByDimension, plannedByDimension),
                    )
                    /** Text. */
                    Text(text = taggedDimensionLine(line = line, dimensionLabel = label, dimensionColor = color))
                    /** Text. */
                    Text(stringResource(id = R.string.loc_lens_missed_tasks_line, missedByDimension))
                }
            }
        } // end !minimalModeEnabled tasks detail
    }
    }
    /** If. */
    if (!FeatureFlags.minimalModeEnabled && habitModuleEnabled) {
        /** Module card. */
        ModuleCard(
            title = stringResource(id = R.string.loc_habits),
            expanded = habitsExpanded,
            onToggle = onToggleHabits,
            ctaText = stringResource(id = R.string.loc_go_to_habits),
            onCta = onOpenHabits,
        ) {
            /** Planned. */
            val planned = summary?.plannedHabitCount ?: 0
            /** Total. */
            val total = planned
            /** Completed. */
            val completed = summary?.completedHabitCount ?: 0
            /** Missed. */
            val missed = summary?.missedHabitCount ?: 0
            /** In flight. */
            val inFlight = (planned - completed - missed).coerceAtLeast(0)
            /** Completion quality percent. */
            val completionQualityPercent = percentageValue(numerator = completed, denominator = planned)
            /** Readiness percent. */
            val readinessPercent = percentageValue(
                numerator = ((summary?.averagePlanCompleteness ?: uiState.planCompletenessScore) * 100f).toInt(),
                denominator = 100,
            )
            /** Addressed reflections. */
            val addressedReflections = uiState.reflections.count { it.isAddressed }
            /** Reflection outcome. */
            val reflectionOutcome = stringResource(
                id = R.string.loc_lens_kpi_addressed_ratio_value,
                /** Addressed reflections. */
                addressedReflections,
                uiState.reflections.size,
            )
            /** Moment kpi rows. */
            MomentKpiRows(
                moment = uiState.selectedMoment,
                startRows = listOf(
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_planned_load) to planned.toString(),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_plan_coverage) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_readiness) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                ),
                liveRows = listOf(
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_execution_so_far) to stringResource(id = R.string.loc_completed_habits_ratio, completed, total),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_in_flight) to inFlight.toString(),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_missed_so_far) to missed.toString(),
                ),
                closeRows = listOf(
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_completion_quality) to stringResource(id = R.string.loc_percent_value, completionQualityPercent),
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_reflection_outcomes) to reflectionOutcome,
                    /** String resource. */
                    stringResource(id = R.string.loc_lens_kpi_missed_so_far) to missed.toString(),
                ),
            )
            /** Text. */
            Text(stringResource(id = R.string.loc_planned_habits_count, planned))
            /** Text. */
            Text(stringResource(id = R.string.loc_completed_habits_ratio, completed, total))
            /** Text. */
            Text(stringResource(id = R.string.loc_lens_missed_habits_line, missed))
            // Per-dimension text lines removed — the score matrix below now
            // renders per-dimension rows with colors/sparklines, making the
            // duplicate text block redundant.
            /** Spacer. */
            Spacer(modifier = Modifier.height(8.dp))
            /** Lens habit score matrix section. */
            LensHabitScoreMatrixSection(
                onRowSelected = { isDay, key ->
                    /** On open score detail. */
                    onOpenScoreDetail(if (isDay) "DAY" else "DIMENSION", key)
                },
            )
        }
    }
    /** If. */
    if (journalModuleEnabled) {
    /** Module card. */
    ModuleCard(
        title = stringResource(id = R.string.loc_journal_notes),
        expanded = journalExpanded,
        onToggle = onToggleJournal,
        ctaText = stringResource(id = R.string.loc_go_to_journal),
        onCta = onOpenJournal,
    ) {
        /** Addressed reflections. */
        val addressedReflections = uiState.reflections.count { it.isAddressed }
        /** Reflection outcome. */
        val reflectionOutcome = stringResource(
            id = R.string.loc_lens_kpi_addressed_ratio_value,
            /** Addressed reflections. */
            addressedReflections,
            uiState.reflections.size,
        )
        /** When. */
        when (uiState.selectedMoment) {
            LensMoment.START_DAY -> Text(stringResource(id = R.string.loc_lens_start_day_journal_hint))
            LensMoment.LIVE_DAY -> Text(stringResource(id = R.string.loc_lens_live_day_journal_hint))
            LensMoment.CLOSE_DAY -> Text(stringResource(id = R.string.loc_lens_close_day_journal_hint))
        }
        /** Text. */
        Text(
            /** String resource. */
            stringResource(
                id = R.string.loc_tagged_title,
                /** String resource. */
                stringResource(id = R.string.loc_lens_kpi_reflection_outcomes),
                /** Reflection outcome. */
                reflectionOutcome,
            ),
        )
    }
    }
    /** If. */
    if (noteModuleEnabled) {
    /** Module card. */
    ModuleCard(
        title = stringResource(id = R.string.settings_database_notes),
        expanded = notesExpanded,
        onToggle = onToggleNotes,
        ctaText = stringResource(id = R.string.loc_go_to_notes),
        onCta = onOpenNotes,
    ) {
        /** When. */
        when (uiState.selectedMoment) {
            LensMoment.START_DAY -> Text(stringResource(id = R.string.loc_lens_start_day_notes_hint))
            LensMoment.LIVE_DAY -> Text(stringResource(id = R.string.loc_lens_live_day_notes_hint))
            LensMoment.CLOSE_DAY -> Text(stringResource(id = R.string.loc_lens_close_day_notes_hint))
        }
        /** Text. */
        Text(stringResource(id = R.string.loc_lens_notes_added_placeholder))
    }
    }
}

@Composable
private fun TimeAdvancedChartSections(
    /** Ui state. */
    uiState: LensUiState,
    appPrefs: io.payanam.ui.viewmodel.AppPreferencesState,
    onDimensionSplitWindowSelect: (DimensionSplitWindow) -> Unit,
    onDimensionSplitShiftLeft: () -> Unit,
    onDimensionSplitShiftRight: () -> Unit,
    onDimensionTrendWindowSelect: (DimensionTrendWindow) -> Unit,
) {
    /** If. */
    if (appPrefs.chartDimSplitEnabled) {
        /** Dimension split section. */
        DimensionSplitSection(
            state = uiState.dimensionSplit,
            onWindowSelect = onDimensionSplitWindowSelect,
            onShiftLeft = onDimensionSplitShiftLeft,
            onShiftRight = onDimensionSplitShiftRight,
        )
    }
    /** If. */
    if (appPrefs.chartDimTrendEnabled) {
        /** Dimension trend section. */
        DimensionTrendSection(
            state = uiState.dimensionTrend,
            onWindowSelect = onDimensionTrendWindowSelect,
            appPrefs = appPrefs,
        )
    }
    /** If. */
    if (appPrefs.chartDailyTimelineEnabled) {
        /** Dimension heatmap section. */
        DimensionHeatmapSection(
            state = uiState.heatmap,
            appPrefs = appPrefs,
        )
    }
    /** If. */
    if (appPrefs.chartWeeklyPatternEnabled) {
        /** Week grid section. */
        WeekGridSection(
            state = uiState.weekGrid,
            appPrefs = appPrefs,
        )
    }
    /** If. */
    if (appPrefs.chartDailyRhythmEnabled) {
        /** Minute pattern section. */
        MinutePatternSection(
            state = uiState.minutePattern,
            appPrefs = appPrefs,
        )
    }
}

@Composable
private fun MomentKpiRows(
    /** Moment. */
    moment: LensMoment,
    startRows: List<Pair<String, String>>,
    liveRows: List<Pair<String, String>>,
    closeRows: List<Pair<String, String>>,
) {
    /** Rows. */
    val rows = when (moment) {
        LensMoment.START_DAY -> startRows
        LensMoment.LIVE_DAY -> liveRows
        LensMoment.CLOSE_DAY -> closeRows
    }
    rows.forEach { (label, value) ->
        /** Text. */
        Text(stringResource(id = R.string.loc_tagged_title, label, value))
    }
}

private fun percentageValue(numerator: Int, denominator: Int): Int {
    /** If. */
    if (denominator <= 0) {
        return 0
    }
    /** Return. */
    return ((numerator.toFloat() / denominator.toFloat()) * 100f).toInt().coerceIn(0, 100)
}

internal fun collectDimensionIds(uiState: LensUiState): List<String> {
    /** Summary. */
    val summary = uiState.selectedRangeSummary
    /** Summary keys. */
    val summaryKeys = buildList {
        /** Add all. */
        addAll(summary?.plannedByDimension?.keys ?: emptySet())
        /** Add all. */
        addAll(summary?.actualByDimension?.keys ?: emptySet())
        /** Add all. */
        addAll(summary?.plannedTasksByDimension?.keys ?: emptySet())
        /** Add all. */
        addAll(summary?.completedTasksByDimension?.keys ?: emptySet())
        /** Add all. */
        addAll(summary?.missedTasksByDimension?.keys ?: emptySet())
        /** Add all. */
        addAll(summary?.plannedHabitsByDimension?.keys ?: emptySet())
        /** Add all. */
        addAll(summary?.completedHabitsByDimension?.keys ?: emptySet())
        /** Add all. */
        addAll(summary?.missedHabitsByDimension?.keys ?: emptySet())
    }
    return summaryKeys.toSet().toList()
}

internal fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}
