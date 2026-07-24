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
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimeScreen(
    viewModel: TimeViewModel = hiltViewModel(),
    openStartTrackingDialogRequestId: Long? = null,
    onOpenStartTrackingDialogHandled: (Long) -> Unit = {},
    openStopTrackingDialogRequestId: Long? = null,
    onOpenStopTrackingDialogHandled: (Long) -> Unit = {},
    onNavigateToTask: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val timeTagEditorViewModel: TimeTagEditorViewModel = hiltViewModel()
    val timeTagEditorState by timeTagEditorViewModel.uiState.collectAsState()
    val timeVisualsViewModel: TimeVisualsViewModel = hiltViewModel()
    val timeVisualsState by timeVisualsViewModel.uiState.collectAsState()
    val dayPlanViewModel: DayPlanViewModel = hiltViewModel()
    val dayPlanState by dayPlanViewModel.uiState.collectAsState()
    val prefsViewModel: AppPreferencesViewModel = hiltViewModel()
    val logger = remember { UnifiedLogger.getInstance() }
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
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val actionFailedReasonPlaceholder = "__TIME_ERROR_REASON__"
    val actionFailedMessageTemplate = stringResource(
        id = R.string.loc_action_failed_with_reason,
        actionFailedReasonPlaceholder,
    )
    val taggedTitleLabelPlaceholder = "__TAG_LABEL__"
    val taggedTitleHintPlaceholder = "__TAG_HINT__"
    val taggedTitleTemplate = stringResource(
        id = R.string.loc_tagged_title,
        taggedTitleLabelPlaceholder,
        taggedTitleHintPlaceholder,
    )
    LaunchedEffect(prefs.timeHourHeightDp) {
        timeHourHeightDp = prefs.timeHourHeightDp
    }
    LaunchedEffect(openStartTrackingDialogRequestId) {
        val requestId = openStartTrackingDialogRequestId ?: return@LaunchedEffect
        showStartTrackingDialog = true
        logger.i(
            "TimeScreen.externalQuickStart",
            "Opened start tracking dialog from external command",
            mapOf(
                "requestId" to requestId,
            ),
        )
        onOpenStartTrackingDialogHandled(requestId)
    }
    LaunchedEffect(openStopTrackingDialogRequestId, uiState.activeEntry?.id) {
        val requestId = openStopTrackingDialogRequestId ?: return@LaunchedEffect
        val activeEntry = uiState.activeEntry
        if (activeEntry != null) {
            activeModalTarget = TimeBlockModalTarget.ExistingEntry(activeEntry)
            logger.i(
                "TimeScreen.externalStop",
                "Opened stop tracking dialog from external command",
                mapOf(
                    "requestId" to requestId,
                ),
            )
        }
        onOpenStopTrackingDialogHandled(requestId)
    }
    LaunchedEffect(activeModalTarget) {
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
    LaunchedEffect(uiState.selectedDate, uiState.activeEntry?.id, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                currentTime = LocalDateTime.now()
                val delayMillis = if (uiState.activeEntry != null) 1_000L else 60_000L
                delay(delayMillis)
            }
        }
    }
    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        val message = actionFailedMessageTemplate.replace(actionFailedReasonPlaceholder, error)
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        viewModel.clearError()
    }
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    }
    val isToday = uiState.selectedDate == currentTime.toLocalDate()
    val dayPlanActionLabel = resolveDayPlanActionLabel(
        dayMode = dayPlanState.dayMode,
        resolvedTemplateName = dayPlanState.resolvedTemplateForDay?.name,
        planLabel = stringResource(id = R.string.loc_day_plan_short),
        customModeLabel = stringResource(id = R.string.loc_day_plan_mode_custom),
        formatLabelWithHint = { label, hint ->
            taggedTitleTemplate
                .replace(taggedTitleLabelPlaceholder, label)
                .replace(taggedTitleHintPlaceholder, hint)
        },
    )
    val selectedScalePreset = remember(timeHourHeightDp) {
        nearestTimeScalePreset(timeHourHeightDp)
    }
    suspend fun autoScrollToCurrentTime(reason: String) {
        if (uiState.selectedDate != LocalDate.now()) {
            return
        }
        val now = LocalDateTime.now()
        currentTime = now
        val currentMinutes = now.hour * 60 + now.minute
        val hourHeight = timeHourHeightDp.dp
        val minuteHeightPx = with(density) { (hourHeight / 60f).toPx() }
        val targetPx = (currentMinutes * minuteHeightPx - with(density) { (hourHeight * 2).toPx() })
        scrollState.animateScrollTo(targetPx.coerceAtLeast(0f).toInt())
        lastAutoScrollHourHeightDp = timeHourHeightDp
        logger.d(
            "TimeScreen.autoScroll",
            "Auto-scrolled to current time",
            mapOf(
                "date" to uiState.selectedDate.toString(),
                "hourHeightDp" to String.format(Locale.US, "%.1f", timeHourHeightDp),
                "reason" to reason,
            ),
        )
    }
    LaunchedEffect(uiState.selectedDate, prefs.isLoading) {
        if (prefs.isLoading) return@LaunchedEffect
        if (
            shouldAutoScrollOnInitialDateSelection(
                selectedDate = uiState.selectedDate,
                today = LocalDate.now(),
                preferencesLoading = prefs.isLoading,
            )
        ) {
            autoScrollToCurrentTime("date_change")
        }
        if (!FeatureFlags.minimalModeEnabled) {
            timeVisualsViewModel.loadForDate(uiState.selectedDate)
        }
        if (FeatureFlags.plansCtaEnabled) {
            dayPlanViewModel.loadDayPlan(uiState.selectedDate.toString())
        }
    }
    LaunchedEffect(timeHourHeightDp) {
        if (
            shouldAutoScrollForHourHeightChange(
                selectedDate = uiState.selectedDate,
                today = LocalDate.now(),
                currentHourHeightDp = timeHourHeightDp,
                lastAutoScrollHourHeightDp = lastAutoScrollHourHeightDp,
            )
        ) {
            autoScrollToCurrentTime("hour_height_change")
        }
    }
    LaunchedEffect(uiState.timeEntries, uiState.pastOccurrences) {
        if (!hasObservedTimelineRefresh) {
            hasObservedTimelineRefresh = true
            return@LaunchedEffect
        }
        logger.d(
            "TimeScreen.visualRefresh",
            "Refreshing top time visuals from latest timeline data",
            mapOf(
                "date" to uiState.selectedDate.toString(),
                "entryCount" to uiState.timeEntries.size.toString(),
                "occurrenceCount" to uiState.pastOccurrences.size.toString(),
            ),
        )
        if (!FeatureFlags.minimalModeEnabled) {
            timeVisualsViewModel.loadForDate(uiState.selectedDate)
        }
    }
    if (showDayPlanTemplateScreen) {
        DayPlanTemplateScreen(viewModel = dayPlanViewModel, onNavigateBack = {
            showDayPlanTemplateScreen = false
            dayPlanViewModel.loadDayPlan(uiState.selectedDate.toString())
        })
        return
    }
    DisposableEffect(lifecycleOwner, uiState.selectedDate) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_RESUME &&
                uiState.selectedDate == LocalDate.now()
            ) {
                coroutineScope.launch {
                    autoScrollToCurrentTime("resume")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = {
                                val targetDate = uiState.selectedDate.minusDays(1)
                                logger.d(
                                    "TimeScreen.dayNav",
                                    "Navigating to previous day",
                                    mapOf("fromDate" to uiState.selectedDate.toString(), "toDate" to targetDate.toString()),
                                )
                                viewModel.navigateToPreviousDay()
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                stringResource(id = R.string.loc_previous_day),
                            )
                        }
                        TextButton(onClick = {
                            logger.d("TimeScreen.dateTapped", "Date header tapped to open picker")
                            showDatePicker = true
                        }) {
                            Text(
                                text = if (isToday) {
                                    stringResource(id = R.string.loc_today)
                                } else {
                                    uiState.selectedDate.format(dateFormatter)
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        IconButton(
                            onClick = {
                                val targetDate = uiState.selectedDate.plusDays(1)
                                logger.d(
                                    "TimeScreen.dayNav",
                                    "Navigating to next day",
                                    mapOf("fromDate" to uiState.selectedDate.toString(), "toDate" to targetDate.toString()),
                                )
                                viewModel.navigateToNextDay()
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                stringResource(id = R.string.loc_next_day),
                            )
                        }
                    }
                },
                actions = {
                    if (!isToday) {
                        TextButton(
                            onClick = {
                                logger.d(
                                    "TimeScreen.dayNav",
                                    "Navigating to today",
                                    mapOf("fromDate" to uiState.selectedDate.toString(), "toDate" to LocalDate.now().toString()),
                                )
                                viewModel.navigateToToday()
                            },
                        ) {
                            Text(stringResource(id = R.string.loc_today))
                        }
                    }
                    TimeScalePresetSelector(
                        selectedPreset = selectedScalePreset,
                        onApplyPreset = { preset ->
                            val presetHourHeightDp = hourHeightDpForSlotMinutes(preset.slotMinutes)
                            timeHourHeightDp = presetHourHeightDp
                            prefsViewModel.setTimeHourHeightDp(presetHourHeightDp)
                            logger.d(
                                "TimeScreen.scalePreset",
                                "Applied explicit time scale preset",
                                mapOf(
                                    "slotMinutes" to preset.slotMinutes.toString(),
                                    "hourHeightDp" to String.format(Locale.US, "%.2f", presetHourHeightDp),
                                ),
                            )
                        },
                    )
                    uiState.activeEntry?.let { active ->
                        val duration = Duration.between(active.startedAt, currentTime)
                        val minutes = duration.toMinutes()
                        val hours = minutes / 60
                        val mins = minutes % 60
                        FilledIconButton(
                            onClick = {
                                logger.d("TimeScreen.stopTrackingTapped", "Stop tracking button tapped")
                                activeModalTarget = TimeBlockModalTarget.ExistingEntry(active)
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Default.Stop, stringResource(id = R.string.loc_stop_tracking))
                        }
                        Text(
                            text = stringResource(
                                id = R.string.loc_duration_hours_minutes_compact,
                                hours,
                                mins,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    if (FeatureFlags.plansCtaEnabled) {
                        IconButton(onClick = {
                            logger.d("TimeScreen.dayPlanTapped", "Day plan button tapped")
                            showDayPlanDialog = true
                        }) {
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
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FloatingActionButton(
                    onClick = {
                        logger.d("TimeScreen.addEntryFabTapped", "Add time entry FAB tapped")
                        activeModalTarget = TimeBlockModalTarget.ManualCreate(uiState.selectedDate)
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Default.Add, stringResource(id = R.string.loc_add_time_entry))
                }
                if (uiState.activeEntry == null) {
                    FloatingActionButton(
                        onClick = {
                            logger.d("TimeScreen.startTrackingFabTapped", "Start tracking FAB tapped")
                            showStartTrackingDialog = true
                        },
                    ) {
                        Icon(Icons.Default.PlayArrow, stringResource(id = R.string.loc_start_tracking))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!FeatureFlags.minimalModeEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TimeDimensionCompactOverviewPanel(
                        rows = timeVisualsState.perDimension,
                        visibleDimensions = prefs.visibleDimensions(),
                        selectedDimensionId = timeVisualsState.selectedDimensionFilterId,
                        onSelectDimension = timeVisualsViewModel::toggleDimensionFilter,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                if (shouldShowTimelineLoadingPlaceholder(uiState.isLoading, uiState.isDateContentReady)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(id = R.string.loc_time_screen_loading_timeline),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                } else {
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
                                mapOf("taskId" to task.id, "date" to uiState.selectedDate.toString()),
                            )
                            activeModalTarget = TimeBlockModalTarget.TaskBlock(task = task, occurrence = null)
                        },
                        onPastOccurrenceClick = { occurrence, task ->
                            if (task != null) {
                                logger.d(
                                    "TimeScreen.pastOccurrenceTap",
                                    "Tapped past occurrence block",
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
                            val (gapStart, gapEnd) = resolveGapConvertDateTimeRange(
                                selectedDate = uiState.selectedDate,
                                gapStartTime = startTime,
                                gapEndTime = endTime,
                                lastEntryEndDateTime = uiState.lastEntry?.endedAt,
                            )
                            logger.d(
                                "TimeScreen.gapTap",
                                "Tapped empty time gap",
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
        val fallbackDimensionDefinition = DimensionTaxonomyCatalog.WORK_LIVELIHOOD
        val fallbackDimension = prefs.visibleDimensionOptions().firstOrNull() ?: DimensionOption(
            id = fallbackDimensionDefinition.id,
            canonicalId = fallbackDimensionDefinition.id,
            label = prefs.labelForDimensionId(fallbackDimensionDefinition.id) ?: fallbackDimensionDefinition.fallbackLabel,
            color = MaterialTheme.colorScheme.primary,
            isVisible = true,
            iconKey = fallbackDimensionDefinition.defaultIconKey,
        )
        val initialContext = buildTimeBlockModalInitialContext(
            target = target,
            selectedDate = uiState.selectedDate,
            appPreferences = prefs,
            fallbackDimensionId = fallbackDimension.id,
            fallbackDimensionLabel = fallbackDimension.label,
        )
        val dimensionOptions = if (target is TimeBlockModalTarget.ExistingEntry) {
            prefs.optionsForSelection(initialContext.initialDimensionId)
        } else {
            prefs.visibleDimensionOptions()
        }
        val initialDimensionOption = dimensionOptions.firstOrNull { it.id == initialContext.initialDimensionId }
            ?: DimensionOption(
                id = initialContext.initialDimensionId,
                label = initialContext.initialDimensionLabel,
                color = MaterialTheme.colorScheme.primary,
                isVisible = true,
            )
        val initialTags = when (target) {
            is TimeBlockModalTarget.ExistingEntry -> timeTagEditorState.editingEntryTags
            is TimeBlockModalTarget.TaskBlock -> timeTagEditorState.editingTaskTags
            else -> emptyList()
        }
        val taskActionState = when (target) {
            is TimeBlockModalTarget.TaskBlock -> {
                TaskBlockActionState(
                    task = target.task,
                    isCompletedBlock = target.occurrence != null || target.task.status == "completed",
                )
            }

            else -> null
        }
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
                        val safeEndDate = endDate ?: startDate
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
    selectedDate: LocalDate,
    today: LocalDate,
    currentHourHeightDp: Float,
    lastAutoScrollHourHeightDp: Float?,
    epsilonDp: Float = 0.1f,
): Boolean {
    if (selectedDate != today) return false
    val previous = lastAutoScrollHourHeightDp ?: return false
    return abs(previous - currentHourHeightDp) > epsilonDp
}

internal fun shouldAutoScrollOnInitialDateSelection(
    selectedDate: LocalDate,
    today: LocalDate,
    preferencesLoading: Boolean,
): Boolean = selectedDate == today && !preferencesLoading

internal fun shouldShowTimelineLoadingPlaceholder(
    isLoading: Boolean,
    isDateContentReady: Boolean,
): Boolean = isLoading && !isDateContentReady
