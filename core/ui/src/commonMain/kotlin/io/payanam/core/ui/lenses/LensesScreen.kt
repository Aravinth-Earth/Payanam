//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.lenses

import io.payanam.core.ui.flags.LocalFeatureFlags
import io.payanam.core.ui.assistant.AssistantConsentDialog
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.shared.logging.AppLogger
import io.payanam.presentation.lenses.LensHabitScoreViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.lenses.taggedDimensionLine
import io.payanam.core.ui.lenses.momentHint
import io.payanam.core.ui.lenses.formatSignedMinutes
import io.payanam.core.ui.lenses.formatMinutes
import io.payanam.core.ui.lenses.collectDimensionIds
import io.payanam.core.ui.lenses.WeekGridSection
import io.payanam.core.ui.lenses.ModuleCard
import io.payanam.core.ui.lenses.MinutePatternSection
import io.payanam.core.ui.lenses.MinimalTaskSummaryRow
import io.payanam.core.ui.lenses.LensTimeSplitItem
import io.payanam.core.ui.lenses.LensTimeSplitCard
import io.payanam.core.ui.lenses.LENS_DAY_MINUTES
import io.payanam.core.ui.lenses.DimensionTrendSection
import io.payanam.core.ui.lenses.DimensionSplitSection
import io.payanam.core.ui.lenses.DimensionHeatmapSection
import io.payanam.core.ui.lenses.AverageDailyTimeTableSection
import io.payanam.presentation.lenses.LensUiState
import io.payanam.presentation.lenses.LensMoment
import io.payanam.presentation.lenses.DimensionTrendWindow
import io.payanam.presentation.lenses.DimensionSplitWindow
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.colorForDimension
import io.payanam.core.ui.preferences.colorForDimensionId
import io.payanam.core.ui.preferences.isVisibleDimensionId
import io.payanam.core.ui.preferences.labelForDimension
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.presentation.lenses.LensViewModel


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LensesScreen(
    viewModel: LensViewModel,
    scoreMatrixViewModel: LensHabitScoreViewModel,
    onOpenTime: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenHabits: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
    onOpenNotes: () -> Unit = {},
    onOpenScoreDetail: (type: String, key: String) -> Unit = { _, _ -> },
    onOpenAssistant: () -> Unit = {},
) {
    val strings = LocalUiStrings.current
    val uiState by viewModel.uiState.collectAsState()
    val logger = remember { AppLogger.current }
    val screenScrollState = rememberScrollState()
    // Ephemeral, deliberately NOT persisted: the gate is asked on the way IN, and the answer is
    // remembered where it actually matters - the presence of a key, not a local flag. A remembered
    // "don't ask again" would outlive the key it was given alongside.
    var assistantConsentVisible by remember { mutableStateOf(false) }
    var timeExpanded by rememberSaveable { mutableStateOf(true) }
    var tasksExpanded by rememberSaveable { mutableStateOf(false) }
    var habitsExpanded by rememberSaveable { mutableStateOf(false) }
    var journalExpanded by rememberSaveable { mutableStateOf(false) }
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    /**
     * Expands [section] and collapses the others (accordion behavior).
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
                title = { Text(strings.locLenses) },
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
            if (!LocalFeatureFlags.current.minimalModeEnabled && appPrefsForTrigger.chartTimeOverallSnapshotEnabled) {
                OverallCard(uiState)
            }
            ModuleSections(
                scoreMatrixViewModel = scoreMatrixViewModel,
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
            // Unconditional: the entry is always offered. Access itself is gated one level down, by the
            // opt-in consent the user has to accept before anything leaves the device.
            AssistantEntryCard(
                onOpen = {
                    logger.d("LensesScreen.assistantOpened", "AI Assistance entry tapped")
                    assistantConsentVisible = true
                },
            )
        }
    }
    if (assistantConsentVisible) {
        AssistantConsentDialog(
            onProceed = {
                assistantConsentVisible = false
                onOpenAssistant()
            },
            onCancel = {
                // Back to exactly where the user was: the same Lenses screen, same scroll position.
                // Nothing is stored and nothing is set up, so cancelling costs the user nothing and
                // leaves no trace to clean up.
                assistantConsentVisible = false
            },
        )
    }
}

@Composable
private fun AssistantEntryCard(onOpen: () -> Unit) {
    val strings = LocalUiStrings.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = strings.assistantTitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = strings.assistantChipExperimental,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = strings.assistantEntrySubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) {
                Text(strings.assistantEntryCta)
            }
        }
    }
}

@Composable
private fun OverallCard(uiState: LensUiState) {
    val strings = LocalUiStrings.current
    val summary = uiState.selectedRangeSummary
    val planned = summary?.totalPlannedMinutes ?: 0
    val actual = summary?.totalActualMinutes ?: 0
    val missed = (planned - actual).coerceAtLeast(0)
    val adherence = (summary?.averageAdherence ?: uiState.adherenceScore).coerceIn(0f, 1f)
    val averageCompleteness = ((summary?.averagePlanCompleteness ?: uiState.planCompletenessScore) * 100f).toInt()
    val averageAdherence = (adherence * 100f).toInt()
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.locLensOverallSnapshot, fontWeight = FontWeight.SemiBold)
            Text(strings.locPlanRealityTotalsLine.formatArgs(formatMinutes(planned), formatMinutes(actual)))
            Text(strings.locLensMissedTimeLine.formatArgs(formatMinutes(missed)))
            Text(strings.locLensFocusGapLine.formatArgs(formatMinutes(summary?.totalFocusGapMinutes ?: 0)))
            Text(strings.locPlanRealityAvgScoreLine.formatArgs(averageCompleteness, averageAdherence))
            Text(momentHint(uiState.selectedMoment))
            LinearProgressIndicator(progress = { adherence }, modifier = Modifier.fillMaxWidth().height(8.dp))
        }
    }
}

@Composable
private fun ModuleSections(
    scoreMatrixViewModel: LensHabitScoreViewModel,
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
    val strings = LocalUiStrings.current
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
        if (LocalFeatureFlags.current.minimalModeEnabled) {
        ModuleCard(
            title = strings.locTime,
            expanded = timeExpanded,
            onToggle = onToggleTime,
            ctaText = strings.locGoToTime,
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
            title = strings.locTime,
            expanded = timeExpanded,
            onToggle = onToggleTime,
            ctaText = strings.locGoToTime,
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
            val reflectionOutcome = strings.locLensKpiAddressedRatioValue.formatArgs(addressedReflections, uiState.reflections.size)
            if (executionDetailsEnabled) {
                MomentKpiRows(
                    moment = uiState.selectedMoment,
                    startRows = listOf(
                        strings.locLensKpiPlannedLoad to formatMinutes(planned),
                        strings.locLensKpiPlanCoverage to strings.locPercentValue.formatArgs(planCoveragePercent),
                        strings.locLensKpiReadiness to strings.locPercentValue.formatArgs(readinessPercent),
                    ),
                    liveRows = listOf(
                        strings.locLensKpiExecutionSoFar to formatMinutes(actual),
                        strings.locLensKpiDrift to formatSignedMinutes(drift.toLong()),
                        strings.locLensKpiMissedSoFar to formatMinutes(missed),
                    ),
                    closeRows = listOf(
                        strings.locLensKpiCompletionQuality to strings.locPercentValue.formatArgs(completionQualityPercent),
                        strings.locLensKpiReflectionOutcomes to reflectionOutcome,
                        strings.locLensKpiFocusGap to formatMinutes(summary?.totalFocusGapMinutes ?: 0),
                    ),
                )
                LensTimeSplitCard(
                    title = strings.locLensTimePlannedBreakdown,
                    items = listOf(
                        LensTimeSplitItem(strings.locLensTimePlannedTimeOnly, plannedTimeOnlyMinutes),
                        LensTimeSplitItem(strings.locLensTimePlannedTasks, plannedTaskMinutes),
                        LensTimeSplitItem(strings.locLensTimePlannedHabits, plannedHabitMinutes),
                        LensTimeSplitItem(strings.locLensTimeUnplanned, unplannedMinutes),
                    ),
                )
                LensTimeSplitCard(
                    title = strings.locLensTimeSpentBreakdown,
                    items = listOf(
                        LensTimeSplitItem(strings.locLensTimeSpentTimeOnly, actualTimeOnlyMinutes),
                        LensTimeSplitItem(strings.locLensTimeSpentTasks, actualTaskMinutes),
                        LensTimeSplitItem(strings.locLensTimeSpentHabits, actualHabitMinutes),
                        LensTimeSplitItem(strings.locLensTimeSpentUntracked, untrackedMinutes),
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
        title = strings.settingsDatabaseTasks,
        expanded = tasksExpanded,
        onToggle = onToggleTasks,
        ctaText = strings.locGoToTasks,
        onCta = onOpenTasks,
    ) {
        val planned = summary?.plannedTaskCount ?: 0
        val completed = summary?.completedTaskCount ?: 0
        val missed = summary?.missedTaskCount ?: 0
        val inFlight = (planned - completed - missed).coerceAtLeast(0)
        if (LocalFeatureFlags.current.minimalModeEnabled) {
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
            val reflectionOutcome = strings.locLensKpiAddressedRatioValue.formatArgs(addressedReflections, uiState.reflections.size)
            MomentKpiRows(
                moment = uiState.selectedMoment,
                startRows = listOf(
                    strings.locLensKpiPlannedLoad to planned.toString(),
                    strings.locLensKpiPlanCoverage to strings.locPercentValue.formatArgs(readinessPercent),
                    strings.locLensKpiReadiness to strings.locPercentValue.formatArgs(readinessPercent),
                ),
                liveRows = listOf(
                    strings.locLensKpiExecutionSoFar to strings.locCompletedTasksRatio.formatArgs(completed, total),
                    strings.locLensKpiInFlight to inFlight.toString(),
                    strings.locLensKpiMissedSoFar to missed.toString(),
                ),
                closeRows = listOf(
                    strings.locLensKpiCompletionQuality to strings.locPercentValue.formatArgs(completionQualityPercent),
                    strings.locLensKpiReflectionOutcomes to reflectionOutcome,
                    strings.locLensKpiMissedSoFar to missed.toString(),
                ),
            )
            Text(strings.locPlannedTasksCount.formatArgs(planned))
            Text(strings.locCompletedTasksRatio.formatArgs(completed, total))
            Text(strings.locLensMissedTasksLine.formatArgs(missed))
            Text(strings.locLensGroupByDimension, fontWeight = FontWeight.Medium)
            if (dimensionIds.isEmpty()) {
                Text(strings.locLensNoDimensionDistribution)
            } else {
                val plannedMap = summary?.plannedTasksByDimension ?: emptyMap()
                val completedMap = summary?.completedTasksByDimension ?: emptyMap()
                val missedMap = summary?.missedTasksByDimension ?: emptyMap()
                dimensionIds.forEach { id ->
                    val label = appPrefs.labelForDimensionId(id)
                        ?: appPrefs.labelForDimension(id, null)
                        ?: strings.locDimensionFallbackUnassigned
                    val color = appPrefs.colorForDimensionId(id)
                        ?: appPrefs.colorForDimension(id, null)
                        ?: MaterialTheme.colorScheme.primary
                    val plannedByDimension = plannedMap[id] ?: 0
                    val completedByDimension = completedMap[id] ?: 0
                    val missedByDimension = missedMap[id] ?: 0
                    val line = strings.locTaggedTitle.formatArgs(label, strings.locCompletedTasksRatio.formatArgs(completedByDimension, plannedByDimension))
                    Text(text = taggedDimensionLine(line = line, dimensionLabel = label, dimensionColor = color))
                    Text(strings.locLensMissedTasksLine.formatArgs(missedByDimension))
                }
            }
        } // end !minimalModeEnabled tasks detail
    }
    }
    if (!LocalFeatureFlags.current.minimalModeEnabled && habitModuleEnabled) {
        ModuleCard(
            title = strings.locHabits,
            expanded = habitsExpanded,
            onToggle = onToggleHabits,
            ctaText = strings.locGoToHabits,
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
            val reflectionOutcome = strings.locLensKpiAddressedRatioValue.formatArgs(addressedReflections, uiState.reflections.size)
            MomentKpiRows(
                moment = uiState.selectedMoment,
                startRows = listOf(
                    strings.locLensKpiPlannedLoad to planned.toString(),
                    strings.locLensKpiPlanCoverage to strings.locPercentValue.formatArgs(readinessPercent),
                    strings.locLensKpiReadiness to strings.locPercentValue.formatArgs(readinessPercent),
                ),
                liveRows = listOf(
                    strings.locLensKpiExecutionSoFar to strings.locCompletedHabitsRatio.formatArgs(completed, total),
                    strings.locLensKpiInFlight to inFlight.toString(),
                    strings.locLensKpiMissedSoFar to missed.toString(),
                ),
                closeRows = listOf(
                    strings.locLensKpiCompletionQuality to strings.locPercentValue.formatArgs(completionQualityPercent),
                    strings.locLensKpiReflectionOutcomes to reflectionOutcome,
                    strings.locLensKpiMissedSoFar to missed.toString(),
                ),
            )
            Text(strings.locPlannedHabitsCount.formatArgs(planned))
            Text(strings.locCompletedHabitsRatio.formatArgs(completed, total))
            Text(strings.locLensMissedHabitsLine.formatArgs(missed))
            // Per-dimension text lines removed — the score matrix below now
            // renders per-dimension rows with colors/sparklines, making the
            // duplicate text block redundant.
            Spacer(modifier = Modifier.height(8.dp))
            LensHabitScoreMatrixSection(
                viewModel = scoreMatrixViewModel,
                onRowSelected = { isDay, key ->
                    onOpenScoreDetail(if (isDay) "DAY" else "DIMENSION", key)
                },
            )
        }
    }
    if (journalModuleEnabled) {
    ModuleCard(
        title = strings.locJournalNotes,
        expanded = journalExpanded,
        onToggle = onToggleJournal,
        ctaText = strings.locGoToJournal,
        onCta = onOpenJournal,
    ) {
        val addressedReflections = uiState.reflections.count { it.isAddressed }
        val reflectionOutcome = strings.locLensKpiAddressedRatioValue.formatArgs(addressedReflections, uiState.reflections.size)
        when (uiState.selectedMoment) {
            LensMoment.START_DAY -> Text(strings.locLensStartDayJournalHint)
            LensMoment.LIVE_DAY -> Text(strings.locLensLiveDayJournalHint)
            LensMoment.CLOSE_DAY -> Text(strings.locLensCloseDayJournalHint)
        }
        Text(
            strings.locTaggedTitle.formatArgs(strings.locLensKpiReflectionOutcomes, reflectionOutcome),
        )
    }
    }
    if (noteModuleEnabled) {
    ModuleCard(
        title = strings.settingsDatabaseNotes,
        expanded = notesExpanded,
        onToggle = onToggleNotes,
        ctaText = strings.locGoToNotes,
        onCta = onOpenNotes,
    ) {
        when (uiState.selectedMoment) {
            LensMoment.START_DAY -> Text(strings.locLensStartDayNotesHint)
            LensMoment.LIVE_DAY -> Text(strings.locLensLiveDayNotesHint)
            LensMoment.CLOSE_DAY -> Text(strings.locLensCloseDayNotesHint)
        }
        Text(strings.locLensNotesAddedPlaceholder)
    }
    }
}

@Composable
private fun TimeAdvancedChartSections(
    uiState: LensUiState,
    appPrefs: io.payanam.core.ui.preferences.AppPreferencesState,
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
    val strings = LocalUiStrings.current
    val rows = when (moment) {
        LensMoment.START_DAY -> startRows
        LensMoment.LIVE_DAY -> liveRows
        LensMoment.CLOSE_DAY -> closeRows
    }
    rows.forEach { (label, value) ->
        Text(strings.locTaggedTitle.formatArgs(label, value))
    }
}

private fun percentageValue(numerator: Int, denominator: Int): Int {
    if (denominator <= 0) {
        return 0
    }
    return ((numerator.toFloat() / denominator.toFloat()) * 100f).toInt().coerceIn(0, 100)
}


