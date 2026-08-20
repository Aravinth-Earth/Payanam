//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.payanam.FeatureFlags
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog

import io.payanam.ui.viewmodel.AppPreferencesViewModel
import io.payanam.ui.viewmodel.DayPlanViewModel
import io.payanam.ui.viewmodel.DimensionOption
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.TimeTagEditorViewModel
import io.payanam.ui.viewmodel.TimeViewModel
import io.payanam.ui.viewmodel.TimeVisualsViewModel
import io.payanam.ui.viewmodel.labelForDimension
import io.payanam.ui.viewmodel.labelForDimensionId
import io.payanam.ui.viewmodel.optionsForSelection
import io.payanam.ui.viewmodel.visibleDimensionOptions
import io.payanam.ui.viewmodel.visibleDimensions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
/**
 * Time screen.
 */
fun TimeScreen(
    viewModel: TimeViewModel = hiltViewModel(),
    openStartTrackingDialogRequestId: Long? = null,
    onOpenStartTrackingDialogHandled: (Long) -> Unit = {},
    openStopTrackingDialogRequestId: Long? = null,
    onOpenStopTrackingDialogHandled: (Long) -> Unit = {},
    onNavigateToTask: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    /** Time tag editor view model. */
    val timeTagEditorViewModel: TimeTagEditorViewModel = hiltViewModel()
    val timeTagEditorState by timeTagEditorViewModel.uiState.collectAsState()
    /** Time visuals view model. */
    val timeVisualsViewModel: TimeVisualsViewModel = hiltViewModel()
    val timeVisualsState by timeVisualsViewModel.uiState.collectAsState()
    /** Day plan view model. */
    val dayPlanViewModel: DayPlanViewModel = hiltViewModel()
    val dayPlanState by dayPlanViewModel.uiState.collectAsState()
    /** Prefs view model. */
    val prefsViewModel: AppPreferencesViewModel = hiltViewModel()
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Prefs. */
    val prefs = LocalAppPreferences.current
    var showStartTrackingDialog by remember { mutableStateOf(false) }
    var activeModalTarget by remember { mutableStateOf<TimeBlockModalTarget?>(null) }
    var showDayPlanDialog by remember { mutableStateOf(false) }
    var showDayPlanTemplateScreen by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var timeHourHeightDp by remember { mutableStateOf(prefs.timeHourHeightDp) }
    var lastAutoScrollHourHeightDp by remember { mutableStateOf<Float?>(null) }
    var currentTime by remember { mutableStateOf(LocalDateTime.now()) }
    var hasObservedTimelineRefresh by remember { mutableStateOf(false) }
    /** Scroll state. */
    val scrollState = rememberScrollState()
    /** Context. */
    val context = LocalContext.current
    /** Density. */
    val density = LocalDensity.current
    /** Lifecycle owner. */
    val lifecycleOwner = LocalLifecycleOwner.current
    /** Coroutine scope. */
    val coroutineScope = rememberCoroutineScope()
    /** Snackbar host state. */
    val snackbarHostState = remember { SnackbarHostState() }
    /** Action failed reason placeholder. */
    val actionFailedReasonPlaceholder = "__TIME_ERROR_REASON__"
    /** Action failed message template. */
    val actionFailedMessageTemplate = stringResource(
        id = R.string.loc_action_failed_with_reason,
        /** Action failed reason placeholder. */
        actionFailedReasonPlaceholder,
    )
    /** Tagged title label placeholder. */
    val taggedTitleLabelPlaceholder = "__TAG_LABEL__"
    /** Tagged title hint placeholder. */
    val taggedTitleHintPlaceholder = "__TAG_HINT__"
    /** Tagged title template. */
    val taggedTitleTemplate = stringResource(
        id = R.string.loc_tagged_title,
        /** Tagged title label placeholder. */
        taggedTitleLabelPlaceholder,
        /** Tagged title hint placeholder. */
        taggedTitleHintPlaceholder,
    )
    /** Launched effect. */
    LaunchedEffect(prefs.timeHourHeightDp) {
        timeHourHeightDp = prefs.timeHourHeightDp
    }
    /** Launched effect. */
    LaunchedEffect(openStartTrackingDialogRequestId) {
        /** Request id. */
        val requestId = openStartTrackingDialogRequestId ?: return@LaunchedEffect
        showStartTrackingDialog = true
        logger.i(
            "TimeScreen.externalQuickStart",
            "Opened start tracking dialog from external command",
            /** Map of. */
            mapOf(
                "requestId" to requestId,
            ),
        )
        /** On open start tracking dialog handled. */
        onOpenStartTrackingDialogHandled(requestId)
    }
    /** Launched effect. */
    LaunchedEffect(openStopTrackingDialogRequestId, uiState.activeEntry?.id) {
        /** Request id. */
        val requestId = openStopTrackingDialogRequestId ?: return@LaunchedEffect
        /** Active entry. */
        val activeEntry = uiState.activeEntry
        /** If. */
        if (activeEntry != null) {
            activeModalTarget = TimeBlockModalTarget.ExistingEntry(activeEntry)
            logger.i(
                "TimeScreen.externalStop",
                "Opened stop tracking dialog from external command",
                /** Map of. */
                mapOf(
                    "requestId" to requestId,
                ),
            )
        }
        /** On open stop tracking dialog handled. */
        onOpenStopTrackingDialogHandled(requestId)
    }
    /** Launched effect. */
    LaunchedEffect(activeModalTarget) {
        /** When. */
        when (val target = activeModalTarget) {
            is TimeBlockModalTarget.ExistingEntry -> {
                timeTagEditorViewModel.loadEntryTags(target.entry.id)
                timeTagEditorViewModel.loadTaskTags(null)
            }

            is TimeBlockModalTarget.TaskBlock -> {
                timeTagEditorViewModel.loadEntryTags(null)
                timeTagEditorViewModel.loadTaskTags(target.task.id)
            }

            else -> {
                timeTagEditorViewModel.loadEntryTags(null)
                timeTagEditorViewModel.loadTaskTags(null)
            }
        }
    }
    /** Launched effect. */
    LaunchedEffect(uiState.selectedDate, uiState.activeEntry?.id, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            /** While. */
            while (true) {
                currentTime = LocalDateTime.now()
                /** Delay millis. */
                val delayMillis = if (uiState.activeEntry != null) 1_000L else 60_000L
                /** Delay. */
                delay(delayMillis)
            }
        }
    }
    /** Launched effect. */
    LaunchedEffect(uiState.error) {
        /** Error. */
        val error = uiState.error ?: return@LaunchedEffect
        /** Message. */
        val message = actionFailedMessageTemplate.replace(actionFailedReasonPlaceholder, error)
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        viewModel.clearError()
    }
    /** Date formatter. */
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
    /** Is today. */
    val isToday = uiState.selectedDate == currentTime.toLocalDate()
    /** Day plan action label. */
    val dayPlanActionLabel = resolveDayPlanActionLabel(
        dayMode = dayPlanState.dayMode,
        resolvedTemplateName = dayPlanState.resolvedTemplateForDay?.name,
        planLabel = stringResource(id = R.string.loc_day_plan_short),
        customModeLabel = stringResource(id = R.string.loc_day_plan_mode_custom),
        formatLabelWithHint = { label, hint ->
            /** Tagged title template. */
            taggedTitleTemplate
                .replace(taggedTitleLabelPlaceholder, label)
                .replace(taggedTitleHintPlaceholder, hint)
        },
    )
    /** Selected scale preset. */
    val selectedScalePreset = remember(timeHourHeightDp) {
        /** Nearest time scale preset. */
        nearestTimeScalePreset(timeHourHeightDp)
    }
    /**
     * Auto scroll to current time.
     */
    suspend fun autoScrollToCurrentTime(reason: String) {
        /** If. */
        if (uiState.selectedDate != LocalDate.now()) {
            /** Return. */
            return
        }
        /** Now. */
        val now = LocalDateTime.now()
        currentTime = now
        /** Current minutes. */
        val currentMinutes = now.hour * 60 + now.minute
        /** Hour height. */
        val hourHeight = timeHourHeightDp.dp
        /** Minute height px. */
        val minuteHeightPx = with(density) { (hourHeight / 60f).toPx() }
        /** Target px. */
        val targetPx = (currentMinutes * minuteHeightPx - with(density) { (hourHeight * 2).toPx() })
        scrollState.animateScrollTo(targetPx.coerceAtLeast(0f).toInt())
        lastAutoScrollHourHeightDp = timeHourHeightDp
        logger.d(
            "TimeScreen.autoScroll",
            "Auto-scrolled to current time",
            /** Map of. */
            mapOf(
                "date" to uiState.selectedDate.toString(),
                "hourHeightDp" to String.format(Locale.US, "%.1f", timeHourHeightDp),
                "reason" to reason,
            ),
        )
    }
    /** Launched effect. */
    LaunchedEffect(uiState.selectedDate, prefs.isLoading) {
        /** If. */
        if (prefs.isLoading) return@LaunchedEffect
        /** If. */
        if (
            /** Should auto scroll on initial date selection. */
            shouldAutoScrollOnInitialDateSelection(
                selectedDate = uiState.selectedDate,
                today = LocalDate.now(),
                preferencesLoading = prefs.isLoading,
            )
        ) {
            /** Auto scroll to current time. */
            autoScrollToCurrentTime("date_change")
        }
        /** If. */
        if (!FeatureFlags.minimalModeEnabled) {
            timeVisualsViewModel.loadForDate(uiState.selectedDate)
        }
        /** If. */
        if (FeatureFlags.plansCtaEnabled) {
            dayPlanViewModel.loadDayPlan(uiState.selectedDate.toString())
        }
    }
    /** Launched effect. */
    LaunchedEffect(timeHourHeightDp) {
        /** If. */
        if (
            /** Should auto scroll for hour height change. */
            shouldAutoScrollForHourHeightChange(
                selectedDate = uiState.selectedDate,
                today = LocalDate.now(),
                currentHourHeightDp = timeHourHeightDp,
                lastAutoScrollHourHeightDp = lastAutoScrollHourHeightDp,
            )
        ) {
            /** Auto scroll to current time. */
            autoScrollToCurrentTime("hour_height_change")
        }
    }
    /** Launched effect. */
    LaunchedEffect(uiState.timeEntries, uiState.pastOccurrences) {
        /** If. */
        if (!hasObservedTimelineRefresh) {
            hasObservedTimelineRefresh = true
            return@LaunchedEffect
        }
        logger.d(
            "TimeScreen.visualRefresh",
            "Refreshing top time visuals from latest timeline data",
            /** Map of. */
            mapOf(
                "date" to uiState.selectedDate.toString(),
                "entryCount" to uiState.timeEntries.size.toString(),
                "occurrenceCount" to uiState.pastOccurrences.size.toString(),
            ),
        )
        /** If. */
        if (!FeatureFlags.minimalModeEnabled) {
            timeVisualsViewModel.loadForDate(uiState.selectedDate)
        }
    }
    /** If. */
    if (showDayPlanTemplateScreen) {
        /** Day plan template screen. */
        DayPlanTemplateScreen(viewModel = dayPlanViewModel, onNavigateBack = {
            showDayPlanTemplateScreen = false
            dayPlanViewModel.loadDayPlan(uiState.selectedDate.toString())
        })
        /** Return. */
        return
    }
    /** Disposable effect. */
    DisposableEffect(lifecycleOwner, uiState.selectedDate) {
        /** Observer. */
        val observer = LifecycleEventObserver { _, event ->
            /** If. */
            if (
                event == Lifecycle.Event.ON_RESUME &&
                uiState.selectedDate == LocalDate.now()
            ) {
                coroutineScope.launch {
                    /** Auto scroll to current time. */
                    autoScrollToCurrentTime("resume")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    /** Scaffold. */
    Scaffold(
        snackbarHost = {
            /** Snackbar host. */
            SnackbarHost(snackbarHostState) { data ->
                /** Snackbar. */
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        },
        topBar = {
            /** Top app bar. */
            TopAppBar(
                title = {
                    /** Row. */
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        /** Icon button. */
                        IconButton(
                            onClick = {
                                /** Target date. */
                                val targetDate = uiState.selectedDate.minusDays(1)
                                logger.d(
                                    "TimeScreen.dayNav",
                                    "Navigating to previous day",
                                    /** Map of. */
                                    mapOf("fromDate" to uiState.selectedDate.toString(), "toDate" to targetDate.toString()),
                                )
                                viewModel.navigateToPreviousDay()
                            },
                        ) {
                            /** Icon. */
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                /** String resource. */
                                stringResource(id = R.string.loc_previous_day),
                            )
                        }
                        /** Text button. */
                        TextButton(onClick = {
                            logger.d("TimeScreen.dateTapped", "Date header tapped to open picker")
                            showDatePicker = true
                        }) {
                            /** Text. */
                            Text(
                                text = if (isToday) {
                                    /** String resource. */
                                    stringResource(id = R.string.loc_today)
                                } else {
                                    uiState.selectedDate.format(dateFormatter)
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        /** Icon button. */
                        IconButton(
                            onClick = {
                                /** Target date. */
                                val targetDate = uiState.selectedDate.plusDays(1)
                                logger.d(
                                    "TimeScreen.dayNav",
                                    "Navigating to next day",
                                    /** Map of. */
                                    mapOf("fromDate" to uiState.selectedDate.toString(), "toDate" to targetDate.toString()),
                                )
                                viewModel.navigateToNextDay()
                            },
                        ) {
                            /** Icon. */
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                /** String resource. */
                                stringResource(id = R.string.loc_next_day),
                            )
                        }
                    }
                },
                actions = {
                    /** If. */
                    if (!isToday) {
                        /** Text button. */
                        TextButton(
                            onClick = {
                                logger.d(
                                    "TimeScreen.dayNav",
                                    "Navigating to today",
                                    /** Map of. */
                                    mapOf("fromDate" to uiState.selectedDate.toString(), "toDate" to LocalDate.now().toString()),
                                )
                                viewModel.navigateToToday()
                            },
                        ) {
                            /** Text. */
                            Text(stringResource(id = R.string.loc_today))
                        }
                    }
                    /** Time scale preset selector. */
                    TimeScalePresetSelector(
                        selectedPreset = selectedScalePreset,
                        onApplyPreset = { preset ->
                            /** Preset hour height dp. */
                            val presetHourHeightDp = hourHeightDpForSlotMinutes(preset.slotMinutes)
                            timeHourHeightDp = presetHourHeightDp
                            prefsViewModel.setTimeHourHeightDp(presetHourHeightDp)
                            logger.d(
                                "TimeScreen.scalePreset",
                                "Applied explicit time scale preset",
                                /** Map of. */
                                mapOf(
                                    "slotMinutes" to preset.slotMinutes.toString(),
                                    "hourHeightDp" to String.format(Locale.US, "%.2f", presetHourHeightDp),
                                ),
                            )
                        },
                    )
                    uiState.activeEntry?.let { active ->
                        /** Duration. */
                        val duration = Duration.between(active.startedAt, currentTime)
                        /** Minutes. */
                        val minutes = duration.toMinutes()
                        /** Hours. */
                        val hours = minutes / 60
                        /** Mins. */
                        val mins = minutes % 60
                        /** Filled icon button. */
                        FilledIconButton(
                            onClick = {
                                logger.d("TimeScreen.stopTrackingTapped", "Stop tracking button tapped")
                                activeModalTarget = TimeBlockModalTarget.ExistingEntry(active)
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            /** Icon. */
                            Icon(Icons.Default.Stop, stringResource(id = R.string.loc_stop_tracking))
                        }
                        /** Text. */
                        Text(
                            text = stringResource(
                                id = R.string.loc_duration_hours_minutes_compact,
                                /** Hours. */
                                hours,
                                /** Mins. */
                                mins,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    /** If. */
                    if (FeatureFlags.plansCtaEnabled) {
                        /** Icon button. */
                        IconButton(onClick = {
                            logger.d("TimeScreen.dayPlanTapped", "Day plan button tapped")
                            showDayPlanDialog = true
                        }) {
                            /** Text. */
                            Text(
                                text = dayPlanActionLabel,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            /** Column. */
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                /** Floating action button. */
                FloatingActionButton(
                    onClick = {
                        logger.d("TimeScreen.addEntryFabTapped", "Add time entry FAB tapped")
                        activeModalTarget = TimeBlockModalTarget.ManualCreate(uiState.selectedDate)
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    /** Icon. */
                    Icon(Icons.Default.Add, stringResource(id = R.string.loc_add_time_entry))
                }
                /** If. */
                if (uiState.activeEntry == null) {
                    /** Floating action button. */
                    FloatingActionButton(
                        onClick = {
                            logger.d("TimeScreen.startTrackingFabTapped", "Start tracking FAB tapped")
                            showStartTrackingDialog = true
                        },
                    ) {
                        /** Icon. */
                        Icon(Icons.Default.PlayArrow, stringResource(id = R.string.loc_start_tracking))
                    }
                }
            }
        },
    ) { padding ->
        /** Column. */
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            /** If. */
            if (!FeatureFlags.minimalModeEnabled) {
                /** Column. */
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    /** Time dimension compact overview panel. */
                    TimeDimensionCompactOverviewPanel(
                        rows = timeVisualsState.perDimension,
                        visibleDimensions = prefs.visibleDimensions(),
                        selectedDimensionId = timeVisualsState.selectedDimensionFilterId,
                        onSelectDimension = timeVisualsViewModel::toggleDimensionFilter,
                    )
                }
            }
            /** Box. */
            Box(modifier = Modifier.weight(1f)) {
                /** If. */
                if (shouldShowTimelineLoadingPlaceholder(uiState.isLoading, uiState.isDateContentReady)) {
                    /** Column. */
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        /** Circular progress indicator. */
                        CircularProgressIndicator()
                        /** Text. */
                        Text(
                            text = stringResource(id = R.string.loc_time_screen_loading_timeline),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                } else {
                    /** Time calendar view. */
                    TimeCalendarView(
                        selectedDate = uiState.selectedDate,
                        entries = uiState.timeEntries,
                        activeEntry = uiState.activeEntry,
                        plannedTasks = uiState.plannedTasks,
                        pastOccurrences = uiState.pastOccurrences,
                        taskLookup = uiState.tasks.associateBy { it.id },
                        hourHeightDp = timeHourHeightDp,
                        currentTime = currentTime,
                        scrollState = scrollState,
                        use24Hour = prefs.timeFormat.use24Hour,
                        highlightedDimensionId = timeVisualsState.selectedDimensionFilterId,
                        preferences = prefs,
                        onEntryClick = { entry ->
                            logger.d("TimeScreen.entryTap", "Tapped existing time entry", mapOf("entryId" to entry.id))
                            activeModalTarget = TimeBlockModalTarget.ExistingEntry(entry)
                        },
                        onPlannedTaskClick = { task ->
                            logger.d(
                                "TimeScreen.plannedTaskTap",
                                "Tapped planned task block",
                                /** Map of. */
                                mapOf("taskId" to task.id, "date" to uiState.selectedDate.toString()),
                            )
                            activeModalTarget = TimeBlockModalTarget.TaskBlock(task = task, occurrence = null)
                        },
                        onPastOccurrenceClick = { occurrence, task ->
                            /** If. */
                            if (task != null) {
                                logger.d(
                                    "TimeScreen.pastOccurrenceTap",
                                    "Tapped past occurrence block",
                                    /** Map of. */
                                    mapOf(
                                        "taskId" to task.id,
                                        "occurrenceId" to occurrence.id,
                                        "date" to uiState.selectedDate.toString(),
                                    ),
                                )
                                activeModalTarget = TimeBlockModalTarget.TaskBlock(task = task, occurrence = occurrence)
                            }
                        },
                        onGapClick = { startTime, endTime ->
                            /** Val. */
                            val (gapStart, gapEnd) = resolveGapConvertDateTimeRange(
                                selectedDate = uiState.selectedDate,
                                gapStartTime = startTime,
                                gapEndTime = endTime,
                                lastEntryEndDateTime = uiState.lastEntry?.endedAt,
                            )
                            logger.d(
                                "TimeScreen.gapTap",
                                "Tapped empty time gap",
                                /** Map of. */
                                mapOf(
                                    "date" to uiState.selectedDate.toString(),
                                    "gapStart" to gapStart.toString(),
                                    "gapEnd" to gapEnd.toString(),
                                ),
                            )
                            activeModalTarget = TimeBlockModalTarget.GapCreate(gapStart = gapStart, gapEnd = gapEnd)
                        },
                    )
                }
            }
        }
    }
    /** Time screen planning dialogs. */
    TimeScreenPlanningDialogs(
        showDatePicker = showDatePicker,
        showStartTrackingDialog = showStartTrackingDialog,
        showDayPlanDialog = showDayPlanDialog,
        selectedDate = uiState.selectedDate,
        taskPickerTasks = uiState.taskPickerTasks,
        visibleDimensions = prefs.visibleDimensions(),
        startTrackingDimensions = prefs.visibleDimensionOptions(),
        templates = dayPlanState.templates,
        dayAllocations = dayPlanState.dayAllocations,
        dayMode = dayPlanState.dayMode,
        selectedDayTemplateId = dayPlanState.selectedDayTemplateId,
        isStarredDay = dayPlanState.isStarredDay,
        dayTypeTemplateByType = dayPlanState.dayTypeTemplateByType,
        resolvedTemplateName = dayPlanState.resolvedTemplateForDay?.name,
        onDateSelected = { date ->
            logger.d(
                "TimeScreen.datePicker",
                "Date selected from picker",
                /** Map of. */
                mapOf("fromDate" to uiState.selectedDate.toString(), "toDate" to date.toString()),
            )
            viewModel.loadEntriesForDate(date)
        },
        onStartTracking = { dimension, taskId ->
            viewModel.startTracking(dimension.id, dimension.label, taskId)
        },
        onSaveDayPlan = { mode, allocations, templateId, isStarredDay, dayTypeTemplateByType ->
            dayPlanViewModel.saveDayPlan(
                dayKey = uiState.selectedDate.toString(),
                mode = mode,
                allocations = allocations,
                templateId = templateId,
                isStarred = isStarredDay,
                dayTypeTemplateByType = dayTypeTemplateByType,
            )
            timeVisualsViewModel.loadForDate(uiState.selectedDate)
        },
        onClearDayPlan = {
            dayPlanViewModel.clearDayPlan(uiState.selectedDate.toString())
            timeVisualsViewModel.loadForDate(uiState.selectedDate)
        },
        onManageTemplates = {
            showDayPlanDialog = false
            showDayPlanTemplateScreen = true
        },
        onDismissDatePicker = { showDatePicker = false },
        onDismissStartTracking = { showStartTrackingDialog = false },
        onDismissDayPlan = { showDayPlanDialog = false },
    )
    activeModalTarget?.let { target ->
        /** Fallback dimension definition. */
        val fallbackDimensionDefinition = DimensionTaxonomyCatalog.WORK_LIVELIHOOD
        /** Fallback dimension. */
        val fallbackDimension = prefs.visibleDimensionOptions().firstOrNull() ?: DimensionOption(
            id = fallbackDimensionDefinition.id,
            canonicalId = fallbackDimensionDefinition.id,
            label = prefs.labelForDimensionId(fallbackDimensionDefinition.id) ?: fallbackDimensionDefinition.fallbackLabel,
            color = MaterialTheme.colorScheme.primary,
            isVisible = true,
            iconKey = fallbackDimensionDefinition.defaultIconKey,
        )
        /** Initial context. */
        val initialContext = buildTimeBlockModalInitialContext(
            target = target,
            selectedDate = uiState.selectedDate,
            appPreferences = prefs,
            fallbackDimensionId = fallbackDimension.id,
            fallbackDimensionLabel = fallbackDimension.label,
        )
        /** Dimension options. */
        val dimensionOptions = if (target is TimeBlockModalTarget.ExistingEntry) {
            prefs.optionsForSelection(initialContext.initialDimensionId)
        } else {
            prefs.visibleDimensionOptions()
        }
        /** Initial dimension option. */
        val initialDimensionOption = dimensionOptions.firstOrNull { it.id == initialContext.initialDimensionId }
            ?: DimensionOption(
                id = initialContext.initialDimensionId,
                label = initialContext.initialDimensionLabel,
                color = MaterialTheme.colorScheme.primary,
                isVisible = true,
            )
        /** Initial tags. */
        val initialTags = when (target) {
            is TimeBlockModalTarget.ExistingEntry -> timeTagEditorState.editingEntryTags
            is TimeBlockModalTarget.TaskBlock -> timeTagEditorState.editingTaskTags
            else -> emptyList()
        }
        /** Task action state. */
        val taskActionState = when (target) {
            is TimeBlockModalTarget.TaskBlock -> {
                /** Task block action state. */
                TaskBlockActionState(
                    task = target.task,
                    isCompletedBlock = target.occurrence != null || target.task.status == "completed",
                )
            }

            else -> null
        }
        /** Time block modal dialog. */
        TimeBlockModalDialog(
            title = stringResource(id = initialContext.titleResId),
            tasks = uiState.taskPickerTasks,
            dimensionOptions = dimensionOptions,
            initialDimension = initialDimensionOption,
            initialTaskId = initialContext.initialTaskId,
            initialStartDate = initialContext.initialStart.toLocalDate(),
            initialStartTime = initialContext.initialStart.toLocalTime(),
            initialEndDate = initialContext.initialEnd?.toLocalDate(),
            initialEndTime = initialContext.initialEnd?.toLocalTime(),
            initialFocusRating = initialContext.initialFocusRating,
            initialFocusNote = initialContext.initialFocusNote,
            initialTags = initialTags,
            tagSuggestions = timeTagEditorState.tagSuggestions,
            use24Hour = prefs.timeFormat.use24Hour,
            isExistingEntry = target is TimeBlockModalTarget.ExistingEntry,
            isActiveEntry = (target as? TimeBlockModalTarget.ExistingEntry)?.entry?.endedAt == null,
            isGapCreate = target is TimeBlockModalTarget.GapCreate,
            taskActionState = taskActionState,
            onConfirmTimeEntry = { dimension, taskId, startDate, startTime, endDate, endTime, focusRating, focusNote, tags ->
                logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "confirm"))
                /** When. */
                when (target) {
                    is TimeBlockModalTarget.ExistingEntry -> {
                        viewModel.updateTimeEntry(
                            entryId = target.entry.id,
                            dimensionId = dimension.id,
                            dimensionLabel = dimension.label,
                            taskId = taskId,
                            startDate = startDate,
                            startTime = startTime,
                            endDate = endDate,
                            endTime = endTime,
                            focusRating = focusRating,
                            focusNote = focusNote,
                            focusRatedAt = target.entry.focusRatedAt,
                        )
                        timeTagEditorViewModel.saveEntryTags(target.entry.id, tags)
                    }

                    else -> {
                        /** Safe end date. */
                        val safeEndDate = endDate ?: startDate
                        /** Safe end time. */
                        val safeEndTime = endTime ?: startTime
                        viewModel.createManualEntry(
                            dimensionId = dimension.id,
                            dimensionLabel = dimension.label,
                            taskId = taskId,
                            startDate = startDate,
                            startTime = startTime,
                            endDate = safeEndDate,
                            endTime = safeEndTime,
                            focusRating = focusRating,
                            focusNote = focusNote,
                            onCreated = { created ->
                                timeTagEditorViewModel.saveEntryTags(created.id, tags)
                            },
                        )
                        /** If. */
                        if (target is TimeBlockModalTarget.TaskBlock) {
                            timeTagEditorViewModel.saveTaskTags(target.task.id, tags)
                        }
                    }
                }
                activeModalTarget = null
            },
            onDeleteEntry = (target as? TimeBlockModalTarget.ExistingEntry)?.let { existing ->
                {
                    logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "delete"))
                    viewModel.deleteTimeEntry(existing.entry.id)
                    activeModalTarget = null
                }
            },
            onContinueEntry = (target as? TimeBlockModalTarget.ExistingEntry)
                ?.takeIf { it.entry.endedAt != null }
                ?.let { existing ->
                    {
                        logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "continue"))
                        viewModel.continueEntry(existing.entry.id)
                        activeModalTarget = null
                    }
                },
            onSetAndContinue = (target as? TimeBlockModalTarget.GapCreate)?.let {
                { dimension, taskId, startDate, startTime ->
                    logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "set_and_continue"))
                    viewModel.startTracking(
                        dimensionId = dimension.canonicalId,
                        dimensionLabel = dimension.label,
                        taskId = taskId,
                        startedAt = LocalDateTime.of(startDate, startTime),
                        onSuccess = { activeModalTarget = null },
                    )
                }
            },
            onStartTaskTracking = (target as? TimeBlockModalTarget.TaskBlock)?.let { taskBlock ->
                {
                    logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "start_task_tracking"))
                    /** Dimension. */
                    val dimension = dimensionOptions.firstOrNull { it.id == taskBlock.task.dimensionId }
                        ?: DimensionOption(
                            id = taskBlock.task.dimensionId ?: fallbackDimension.id,
                            label = prefs.labelForDimension(
                                dimensionId = taskBlock.task.dimensionId,
                                dimensionName = taskBlock.task.lifeIntentionCategory,
                            ) ?: fallbackDimension.label,
                            color = fallbackDimension.color,
                            isVisible = true,
                        )
                    viewModel.startTracking(
                        dimensionId = dimension.canonicalId,
                        dimensionLabel = dimension.label,
                        taskId = taskBlock.task.id,
                        onSuccess = { activeModalTarget = null },
                    )
                }
            },
            onCompleteTask = (target as? TimeBlockModalTarget.TaskBlock)?.let { taskBlock ->
                { note, completedAt, durationMinutes, tags ->
                    logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "complete_task"))
                    timeTagEditorViewModel.saveTaskTags(taskBlock.task.id, tags)
                    viewModel.completeTaskWithDetails(
                        taskId = taskBlock.task.id,
                        note = note,
                        actualCompletedAt = completedAt,
                        actualDurationMinutes = durationMinutes,
                    )
                    activeModalTarget = null
                }
            },
            onSkipTask = (target as? TimeBlockModalTarget.TaskBlock)?.let { taskBlock ->
                { note, tags ->
                    logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "skip_task"))
                    timeTagEditorViewModel.saveTaskTags(taskBlock.task.id, tags)
                    viewModel.skipTask(taskBlock.task.id, note)
                    activeModalTarget = null
                }
            },
            onMissTask = (target as? TimeBlockModalTarget.TaskBlock)?.let { taskBlock ->
                { note, tags ->
                    logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "miss_task"))
                    timeTagEditorViewModel.saveTaskTags(taskBlock.task.id, tags)
                    viewModel.missTask(taskBlock.task.id, note)
                    activeModalTarget = null
                }
            },
            onArchiveTask = (target as? TimeBlockModalTarget.TaskBlock)?.let { taskBlock ->
                { tags ->
                    logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "archive_task"))
                    timeTagEditorViewModel.saveTaskTags(taskBlock.task.id, tags)
                    viewModel.archiveTask(taskBlock.task.id)
                    activeModalTarget = null
                }
            },
            onDeleteTask = (target as? TimeBlockModalTarget.TaskBlock)?.let { taskBlock ->
                { _ ->
                    logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "delete_task"))
                    viewModel.deleteTask(taskBlock.task.id)
                    activeModalTarget = null
                }
            },
            onEditTask = (target as? TimeBlockModalTarget.TaskBlock)?.let { taskBlock ->
                {
                    logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "edit_task"))
                    activeModalTarget = null
                    /** On navigate to task. */
                    onNavigateToTask(taskBlock.task.id)
                }
            },
            onDismiss = {
                logger.d("TimeBlockModal.actionTapped", "Modal action tapped", mapOf("action" to "dismiss"))
                activeModalTarget = null
            },
        )
    }
}

internal fun shouldAutoScrollForHourHeightChange(
    /** Selected date. */
    selectedDate: LocalDate,
    /** Today. */
    today: LocalDate,
    /** Current hour height dp. */
    currentHourHeightDp: Float,
    lastAutoScrollHourHeightDp: Float?,
    epsilonDp: Float = 0.1f,
): Boolean {
    /** If. */
    if (selectedDate != today) return false
    /** Previous. */
    val previous = lastAutoScrollHourHeightDp ?: return false
    return abs(previous - currentHourHeightDp) > epsilonDp
}

internal fun shouldAutoScrollOnInitialDateSelection(
    /** Selected date. */
    selectedDate: LocalDate,
    /** Today. */
    today: LocalDate,
    /** Preferences loading. */
    preferencesLoading: Boolean,
): Boolean = selectedDate == today && !preferencesLoading

internal fun shouldShowTimelineLoadingPlaceholder(
    /** Is loading. */
    isLoading: Boolean,
    /** Is date content ready. */
    isDateContentReady: Boolean,
): Boolean = isLoading && !isDateContentReady
