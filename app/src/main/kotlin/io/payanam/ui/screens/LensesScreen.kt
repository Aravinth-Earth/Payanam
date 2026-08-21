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
    val logger = remember { UnifiedLogger.getInstance() }
    val screenScrollState = rememberScrollState()
    var timeExpanded by rememberSaveable { mutableStateOf(true) }
    var tasksExpanded by rememberSaveable { mutableStateOf(false) }
    var habitsExpanded by rememberSaveable { mutableStateOf(false) }
    var journalExpanded by rememberSaveable { mutableStateOf(false) }
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    /**
     * Performs the toggle exclusive.
     */
    fun toggleExclusive(section: String) {
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
    LaunchedEffect(uiState.selectedDate, uiState.selectedMoment) {
        viewModel.loadLensData()
        logger.d(
            "LensesScreen",
            "Simplified lens updated",
            mapOf(
                "date" to uiState.selectedDate.toString(),
                "moment" to uiState.selectedMoment.name,
            ),
        )
    }
    val appPrefsForTrigger = LocalAppPreferences.current
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.loc_lenses)) },
                expandedHeight = 52.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(screenScrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!FeatureFlags.minimalModeEnabled && appPrefsForTrigger.chartTimeOverallSnapshotEnabled) {
                OverallCard(uiState)
            }
            ModuleSections(
                uiState = uiState,
                onRequestMoreHistory = viewModel::requestNextTimeHistoryStage,
                timeExpanded = timeExpanded,
                onToggleTime = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "time", "expanded" to !timeExpanded))
                    toggleExclusive("time")
                },
                tasksExpanded = tasksExpanded,
                onToggleTasks = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "tasks", "expanded" to !tasksExpanded))
                    toggleExclusive("tasks")
                },
                habitsExpanded = habitsExpanded,
                onToggleHabits = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "habits", "expanded" to !habitsExpanded))
                    toggleExclusive("habits")
                },
                journalExpanded = journalExpanded,
                onToggleJournal = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "journal", "expanded" to !journalExpanded))
                    toggleExclusive("journal")
                },
                notesExpanded = notesExpanded,
                onToggleNotes = {
                    logger.d("LensesScreen.sectionToggled", "Section toggled", mapOf("section" to "notes", "expanded" to !notesExpanded))
                    toggleExclusive("notes")
                },
                onOpenTime = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "time"))
                    onOpenTime()
                },
                onOpenTasks = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "tasks"))
                    onOpenTasks()
                },
                onOpenHabits = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "habits"))
                    onOpenHabits()
                },
                onOpenJournal = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "journal"))
                    onOpenJournal()
                },
                onOpenNotes = {
                    logger.d("LensesScreen.ctaTapped", "CTA button tapped", mapOf("section" to "notes"))
                    onOpenNotes()
                },
                onOpenScoreDetail = { type, key ->
                    logger.d("LensesScreen.scoreDetailOpened", "Score detail opened", mapOf("type" to type, "key" to key))
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
    val summary = uiState.selectedRangeSummary
    val planned = summary?.totalPlannedMinutes ?: 0
    val actual = summary?.totalActualMinutes ?: 0
    val missed = (planned - actual).coerceAtLeast(0)
    val adherence = (summary?.averageAdherence ?: uiState.adherenceScore).coerceIn(0f, 1f)
    val averageCompleteness = ((summary?.averagePlanCompleteness ?: uiState.planCompletenessScore) * 100f).toInt()
    val averageAdherence = (adherence * 100f).toInt()
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(id = R.string.loc_lens_overall_snapshot), fontWeight = FontWeight.SemiBold)
            Text(stringResource(id = R.string.loc_plan_reality_totals_line, formatMinutes(planned), formatMinutes(actual)))
            Text(stringResource(id = R.string.loc_lens_missed_time_line, formatMinutes(missed)))
            Text(stringResource(id = R.string.loc_lens_focus_gap_line, formatMinutes(summary?.totalFocusGapMinutes ?: 0)))
            Text(stringResource(id = R.string.loc_plan_reality_avg_score_line, averageCompleteness, averageAdherence))
            Text(momentHint(uiState.selectedMoment))
            LinearProgressIndicator(progress = { adherence }, modifier = Modifier.fillMaxWidth().height(8.dp))
        }
    }
}

@Composable
private fun ModuleSections(
    uiState: LensUiState,
    onRequestMoreHistory: () -> Unit,
    timeExpanded: Boolean,
    onToggleTime: () -> Unit,
    tasksExpanded: Boolean,
    onToggleTasks: () -> Unit,
    habitsExpanded: Boolean,
    onToggleHabits: () -> Unit,
    journalExpanded: Boolean,
    onToggleJournal: () -> Unit,
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
    val summary = uiState.selectedRangeSummary
    val appPrefs = LocalAppPreferences.current
    val timeChartsEnabled = appPrefs.chartTimeModuleEnabled
    val executionDetailsEnabled = appPrefs.chartTimeExecutionDetailsEnabled
    val taskModuleEnabled = appPrefs.chartTaskModuleEnabled
    val habitModuleEnabled = appPrefs.chartHabitModuleEnabled
    val journalModuleEnabled = appPrefs.chartJournalModuleEnabled
    val noteModuleEnabled = appPrefs.chartNoteModuleEnabled
    val includeSupplementalActual = true
    val dimensionIds = collectDimensionIds(uiState).filter(appPrefs::isVisibleDimensionId)
    if (timeChartsEnabled) {
        if (FeatureFlags.minimalModeEnabled) {
        ModuleCard(
            title = stringResource(id = R.string.loc_time),
            expanded = timeExpanded,
            onToggle = onToggleTime,
            ctaText = stringResource(id = R.string.loc_go_to_time),
            onCta = onOpenTime,
        ) {
            if (timeChartsEnabled) {
                MinimalTrackedTimeBarChart(items = uiState.dailyTrackedTimeStats)
                MinimalFocusBarChart(items = uiState.dailyFocusAverages)
                MinimalFocusedHoursBarChart(items = uiState.dailyFocusedHoursStats)
                if (appPrefs.chartAverageDailyTimeEnabled) {
                    AverageDailyTimeTableSection(summary = uiState.averageDailyTimeTable)
                }
                if (appPrefs.chartDimSplitEnabled) {
                    DimensionSplitSection(
                        state = uiState.dimensionSplit,
                        onWindowSelect = onDimensionSplitWindowSelect,
                        onShiftLeft = onDimensionSplitShiftLeft,
                        onShiftRight = onDimensionSplitShiftRight,
                    )
                }
                if (appPrefs.chartDimTrendEnabled) {
                    DimensionTrendSection(
                        state = uiState.dimensionTrend,
                        onWindowSelect = onDimensionTrendWindowSelect,
                        appPrefs = appPrefs,
                    )
                }
                if (appPrefs.chartDailyTimelineEnabled) {
                    DimensionHeatmapSection(
                        state = uiState.heatmap,
                        appPrefs = appPrefs,
                    )
                }
                if (appPrefs.chartWeeklyPatternEnabled) {
                    WeekGridSection(
                        state = uiState.weekGrid,
                        appPrefs = appPrefs,
                    )
                }
                if (appPrefs.chartDailyRhythmEnabled) {
                    MinutePatternSection(
                        state = uiState.minutePattern,
                        appPrefs = appPrefs,
                    )
                }
            }
        }
        } else {
        ModuleCard(
            title = stringResource(id = R.string.loc_time),
            expanded = timeExpanded,
            onToggle = onToggleTime,
            ctaText = stringResource(id = R.string.loc_go_to_time),
            onCta = onOpenTime,
        ) {
            val planned = summary?.totalPlannedMinutes ?: 0
            val actual = (summary?.totalActualMinutes ?: 0) - if (includeSupplementalActual) 0 else (summary?.supplementalActualMinutes ?: 0)
            val missed = (planned - actual).coerceAtLeast(0)
            val drift = actual - planned
            val plannedTaskMinutes = summary?.plannedTaskMinutes ?: 0
            val plannedHabitMinutes = summary?.plannedHabitMinutes ?: 0
            val plannedTimeOnlyMinutes = (planned - plannedTaskMinutes - plannedHabitMinutes).coerceAtLeast(0)
            val unplannedMinutes = (LENS_DAY_MINUTES - planned).coerceAtLeast(0)
            val actualTaskMinutes = summary?.actualTaskMinutes ?: 0
            val actualHabitMinutes = summary?.actualHabitMinutes ?: 0
            val actualTimeOnlyMinutes = (actual - actualTaskMinutes - actualHabitMinutes).coerceAtLeast(0)
            val untrackedMinutes = (summary?.totalUntrackedMinutes ?: 0).coerceAtLeast(0)
            val planCoveragePercent = percentageValue(numerator = planned, denominator = LENS_DAY_MINUTES)
            val readinessPercent = percentageValue(
                numerator = ((summary?.averagePlanCompleteness ?: uiState.planCompletenessScore) * 100f).toInt(),
                denominator = 100,
            )
            val completionQualityPercent = percentageValue(
                numerator = ((summary?.averageAdherence ?: uiState.adherenceScore) * 100f).toInt(),
                denominator = 100,
            )
            val addressedReflections = uiState.reflections.count { it.isAddressed }
            val reflectionOutcome = stringResource(
                id = R.string.loc_lens_kpi_addressed_ratio_value,
                addressedReflections,
                uiState.reflections.size,
            )
            if (executionDetailsEnabled) {
                MomentKpiRows(
                    moment = uiState.selectedMoment,
                    startRows = listOf(
                        stringResource(id = R.string.loc_lens_kpi_planned_load) to formatMinutes(planned),
                        stringResource(id = R.string.loc_lens_kpi_plan_coverage) to stringResource(id = R.string.loc_percent_value, planCoveragePercent),
                        stringResource(id = R.string.loc_lens_kpi_readiness) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                    ),
                    liveRows = listOf(
                        stringResource(id = R.string.loc_lens_kpi_execution_so_far) to formatMinutes(actual),
                        stringResource(id = R.string.loc_lens_kpi_drift) to formatSignedMinutes(drift.toLong()),
                        stringResource(id = R.string.loc_lens_kpi_missed_so_far) to formatMinutes(missed),
                    ),
                    closeRows = listOf(
                        stringResource(id = R.string.loc_lens_kpi_completion_quality) to stringResource(id = R.string.loc_percent_value, completionQualityPercent),
                        stringResource(id = R.string.loc_lens_kpi_reflection_outcomes) to reflectionOutcome,
                        stringResource(id = R.string.loc_lens_kpi_focus_gap) to formatMinutes(summary?.totalFocusGapMinutes ?: 0),
                    ),
                )
                LensTimeSplitCard(
                    title = stringResource(id = R.string.loc_lens_time_planned_breakdown),
                    items = listOf(
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_planned_time_only), plannedTimeOnlyMinutes),
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_planned_tasks), plannedTaskMinutes),
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_planned_habits), plannedHabitMinutes),
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_unplanned), unplannedMinutes),
                    ),
                )
                LensTimeSplitCard(
                    title = stringResource(id = R.string.loc_lens_time_spent_breakdown),
                    items = listOf(
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_spent_time_only), actualTimeOnlyMinutes),
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_spent_tasks), actualTaskMinutes),
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_spent_habits), actualHabitMinutes),
                        LensTimeSplitItem(stringResource(id = R.string.loc_lens_time_spent_untracked), untrackedMinutes),
                    ),
                )
            }
            TimeModuleSectionContent(
                uiState = uiState,
                summary = summary,
                dimensionIds = dimensionIds,
                includeSupplementalActual = includeSupplementalActual,
                onRequestMoreHistory = onRequestMoreHistory,
            )
            if (timeChartsEnabled && appPrefs.chartAverageDailyTimeEnabled) {
                AverageDailyTimeTableSection(summary = uiState.averageDailyTimeTable)
            }
            if (timeChartsEnabled) {
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
    if (taskModuleEnabled) {
    ModuleCard(
        title = stringResource(id = R.string.settings_database_tasks),
        expanded = tasksExpanded,
        onToggle = onToggleTasks,
        ctaText = stringResource(id = R.string.loc_go_to_tasks),
        onCta = onOpenTasks,
    ) {
        val planned = summary?.plannedTaskCount ?: 0
        val completed = summary?.completedTaskCount ?: 0
        val missed = summary?.missedTaskCount ?: 0
        val inFlight = (planned - completed - missed).coerceAtLeast(0)
        if (FeatureFlags.minimalModeEnabled) {
            MinimalTaskSummaryRow(
                overdue = missed,
                today = planned,
                future = inFlight,
            )
        } else {
            val total = planned
            val completionQualityPercent = percentageValue(numerator = completed, denominator = planned)
            val readinessPercent = percentageValue(
                numerator = ((summary?.averagePlanCompleteness ?: uiState.planCompletenessScore) * 100f).toInt(),
                denominator = 100,
            )
            val addressedReflections = uiState.reflections.count { it.isAddressed }
            val reflectionOutcome = stringResource(
                id = R.string.loc_lens_kpi_addressed_ratio_value,
                addressedReflections,
                uiState.reflections.size,
            )
            MomentKpiRows(
                moment = uiState.selectedMoment,
                startRows = listOf(
                    stringResource(id = R.string.loc_lens_kpi_planned_load) to planned.toString(),
                    stringResource(id = R.string.loc_lens_kpi_plan_coverage) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                    stringResource(id = R.string.loc_lens_kpi_readiness) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                ),
                liveRows = listOf(
                    stringResource(id = R.string.loc_lens_kpi_execution_so_far) to stringResource(id = R.string.loc_completed_tasks_ratio, completed, total),
                    stringResource(id = R.string.loc_lens_kpi_in_flight) to inFlight.toString(),
                    stringResource(id = R.string.loc_lens_kpi_missed_so_far) to missed.toString(),
                ),
                closeRows = listOf(
                    stringResource(id = R.string.loc_lens_kpi_completion_quality) to stringResource(id = R.string.loc_percent_value, completionQualityPercent),
                    stringResource(id = R.string.loc_lens_kpi_reflection_outcomes) to reflectionOutcome,
                    stringResource(id = R.string.loc_lens_kpi_missed_so_far) to missed.toString(),
                ),
            )
            Text(stringResource(id = R.string.loc_planned_tasks_count, planned))
            Text(stringResource(id = R.string.loc_completed_tasks_ratio, completed, total))
            Text(stringResource(id = R.string.loc_lens_missed_tasks_line, missed))
            Text(stringResource(id = R.string.loc_lens_group_by_dimension), fontWeight = FontWeight.Medium)
            if (dimensionIds.isEmpty()) {
                Text(stringResource(id = R.string.loc_lens_no_dimension_distribution))
            } else {
                val plannedMap = summary?.plannedTasksByDimension ?: emptyMap()
                val completedMap = summary?.completedTasksByDimension ?: emptyMap()
                val missedMap = summary?.missedTasksByDimension ?: emptyMap()
                dimensionIds.forEach { id ->
                    val label = appPrefs.labelForDimensionId(id)
                        ?: appPrefs.labelForDimension(id, null)
                        ?: stringResource(id = R.string.loc_dimension_fallback_unassigned)
                    val color = appPrefs.colorForDimensionId(id)
                        ?: appPrefs.colorForDimension(id, null)
                        ?: MaterialTheme.colorScheme.primary
                    val plannedByDimension = plannedMap[id] ?: 0
                    val completedByDimension = completedMap[id] ?: 0
                    val missedByDimension = missedMap[id] ?: 0
                    val line = stringResource(
                        id = R.string.loc_tagged_title,
                        label,
                        stringResource(id = R.string.loc_completed_tasks_ratio, completedByDimension, plannedByDimension),
                    )
                    Text(text = taggedDimensionLine(line = line, dimensionLabel = label, dimensionColor = color))
                    Text(stringResource(id = R.string.loc_lens_missed_tasks_line, missedByDimension))
                }
            }
        } // end !minimalModeEnabled tasks detail
    }
    }
    if (!FeatureFlags.minimalModeEnabled && habitModuleEnabled) {
        ModuleCard(
            title = stringResource(id = R.string.loc_habits),
            expanded = habitsExpanded,
            onToggle = onToggleHabits,
            ctaText = stringResource(id = R.string.loc_go_to_habits),
            onCta = onOpenHabits,
        ) {
            val planned = summary?.plannedHabitCount ?: 0
            val total = planned
            val completed = summary?.completedHabitCount ?: 0
            val missed = summary?.missedHabitCount ?: 0
            val inFlight = (planned - completed - missed).coerceAtLeast(0)
            val completionQualityPercent = percentageValue(numerator = completed, denominator = planned)
            val readinessPercent = percentageValue(
                numerator = ((summary?.averagePlanCompleteness ?: uiState.planCompletenessScore) * 100f).toInt(),
                denominator = 100,
            )
            val addressedReflections = uiState.reflections.count { it.isAddressed }
            val reflectionOutcome = stringResource(
                id = R.string.loc_lens_kpi_addressed_ratio_value,
                addressedReflections,
                uiState.reflections.size,
            )
            MomentKpiRows(
                moment = uiState.selectedMoment,
                startRows = listOf(
                    stringResource(id = R.string.loc_lens_kpi_planned_load) to planned.toString(),
                    stringResource(id = R.string.loc_lens_kpi_plan_coverage) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                    stringResource(id = R.string.loc_lens_kpi_readiness) to stringResource(id = R.string.loc_percent_value, readinessPercent),
                ),
                liveRows = listOf(
                    stringResource(id = R.string.loc_lens_kpi_execution_so_far) to stringResource(id = R.string.loc_completed_habits_ratio, completed, total),
                    stringResource(id = R.string.loc_lens_kpi_in_flight) to inFlight.toString(),
                    stringResource(id = R.string.loc_lens_kpi_missed_so_far) to missed.toString(),
                ),
                closeRows = listOf(
                    stringResource(id = R.string.loc_lens_kpi_completion_quality) to stringResource(id = R.string.loc_percent_value, completionQualityPercent),
                    stringResource(id = R.string.loc_lens_kpi_reflection_outcomes) to reflectionOutcome,
                    stringResource(id = R.string.loc_lens_kpi_missed_so_far) to missed.toString(),
                ),
            )
            Text(stringResource(id = R.string.loc_planned_habits_count, planned))
            Text(stringResource(id = R.string.loc_completed_habits_ratio, completed, total))
            Text(stringResource(id = R.string.loc_lens_missed_habits_line, missed))
            // Per-dimension text lines removed — the score matrix below now
            // renders per-dimension rows with colors/sparklines, making the
            // duplicate text block redundant.
            Spacer(modifier = Modifier.height(8.dp))
            LensHabitScoreMatrixSection(
                onRowSelected = { isDay, key ->
                    onOpenScoreDetail(if (isDay) "DAY" else "DIMENSION", key)
                },
            )
        }
    }
    if (journalModuleEnabled) {
    ModuleCard(
        title = stringResource(id = R.string.loc_journal_notes),
        expanded = journalExpanded,
        onToggle = onToggleJournal,
        ctaText = stringResource(id = R.string.loc_go_to_journal),
        onCta = onOpenJournal,
    ) {
        val addressedReflections = uiState.reflections.count { it.isAddressed }
        val reflectionOutcome = stringResource(
            id = R.string.loc_lens_kpi_addressed_ratio_value,
            addressedReflections,
            uiState.reflections.size,
        )
        when (uiState.selectedMoment) {
            LensMoment.START_DAY -> Text(stringResource(id = R.string.loc_lens_start_day_journal_hint))
            LensMoment.LIVE_DAY -> Text(stringResource(id = R.string.loc_lens_live_day_journal_hint))
            LensMoment.CLOSE_DAY -> Text(stringResource(id = R.string.loc_lens_close_day_journal_hint))
        }
        Text(
            stringResource(
                id = R.string.loc_tagged_title,
                stringResource(id = R.string.loc_lens_kpi_reflection_outcomes),
                reflectionOutcome,
            ),
        )
    }
    }
    if (noteModuleEnabled) {
    ModuleCard(
        title = stringResource(id = R.string.settings_database_notes),
        expanded = notesExpanded,
        onToggle = onToggleNotes,
        ctaText = stringResource(id = R.string.loc_go_to_notes),
        onCta = onOpenNotes,
    ) {
        when (uiState.selectedMoment) {
            LensMoment.START_DAY -> Text(stringResource(id = R.string.loc_lens_start_day_notes_hint))
            LensMoment.LIVE_DAY -> Text(stringResource(id = R.string.loc_lens_live_day_notes_hint))
            LensMoment.CLOSE_DAY -> Text(stringResource(id = R.string.loc_lens_close_day_notes_hint))
        }
        Text(stringResource(id = R.string.loc_lens_notes_added_placeholder))
    }
    }
}

@Composable
private fun TimeAdvancedChartSections(
    uiState: LensUiState,
    appPrefs: io.payanam.ui.viewmodel.AppPreferencesState,
    onDimensionSplitWindowSelect: (DimensionSplitWindow) -> Unit,
    onDimensionSplitShiftLeft: () -> Unit,
    onDimensionSplitShiftRight: () -> Unit,
    onDimensionTrendWindowSelect: (DimensionTrendWindow) -> Unit,
) {
    if (appPrefs.chartDimSplitEnabled) {
        DimensionSplitSection(
            state = uiState.dimensionSplit,
            onWindowSelect = onDimensionSplitWindowSelect,
            onShiftLeft = onDimensionSplitShiftLeft,
            onShiftRight = onDimensionSplitShiftRight,
        )
    }
    if (appPrefs.chartDimTrendEnabled) {
        DimensionTrendSection(
            state = uiState.dimensionTrend,
            onWindowSelect = onDimensionTrendWindowSelect,
            appPrefs = appPrefs,
        )
    }
    if (appPrefs.chartDailyTimelineEnabled) {
        DimensionHeatmapSection(
            state = uiState.heatmap,
            appPrefs = appPrefs,
        )
    }
    if (appPrefs.chartWeeklyPatternEnabled) {
        WeekGridSection(
            state = uiState.weekGrid,
            appPrefs = appPrefs,
        )
    }
    if (appPrefs.chartDailyRhythmEnabled) {
        MinutePatternSection(
            state = uiState.minutePattern,
            appPrefs = appPrefs,
        )
    }
}

@Composable
private fun MomentKpiRows(
    moment: LensMoment,
    startRows: List<Pair<String, String>>,
    liveRows: List<Pair<String, String>>,
    closeRows: List<Pair<String, String>>,
) {
    val rows = when (moment) {
        LensMoment.START_DAY -> startRows
        LensMoment.LIVE_DAY -> liveRows
        LensMoment.CLOSE_DAY -> closeRows
    }
    rows.forEach { (label, value) ->
        Text(stringResource(id = R.string.loc_tagged_title, label, value))
    }
}

private fun percentageValue(numerator: Int, denominator: Int): Int {
    if (denominator <= 0) {
        return 0
    }
    return ((numerator.toFloat() / denominator.toFloat()) * 100f).toInt().coerceIn(0, 100)
}

internal fun collectDimensionIds(uiState: LensUiState): List<String> {
    val summary = uiState.selectedRangeSummary
    val summaryKeys = buildList {
        addAll(summary?.plannedByDimension?.keys ?: emptySet())
        addAll(summary?.actualByDimension?.keys ?: emptySet())
        addAll(summary?.plannedTasksByDimension?.keys ?: emptySet())
        addAll(summary?.completedTasksByDimension?.keys ?: emptySet())
        addAll(summary?.missedTasksByDimension?.keys ?: emptySet())
        addAll(summary?.plannedHabitsByDimension?.keys ?: emptySet())
        addAll(summary?.completedHabitsByDimension?.keys ?: emptySet())
        addAll(summary?.missedHabitsByDimension?.keys ?: emptySet())
    }
    return summaryKeys.toSet().toList()
}

internal fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}
