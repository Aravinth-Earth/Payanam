//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Note
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.payanam.ExternalNavigationCommand
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.ui.SettingsScreen
import io.payanam.ui.screens.AddTaskScreen
import io.payanam.ui.screens.DatabaseInitScreen
import io.payanam.ui.screens.DatabasePassphraseChangeScreen
import io.payanam.ui.screens.DatabasePassphraseSetupScreen
import io.payanam.ui.screens.DatabasePassphraseUnlockScreen
import io.payanam.ui.screens.DayScreen
import io.payanam.ui.screens.DayScreenMode
import io.payanam.ui.screens.EditTaskScreen
import io.payanam.ui.screens.FocusModeSelectionScreen
import io.payanam.ui.screens.LensesScreen
import io.payanam.ui.screens.NotesScreen
import io.payanam.ui.screens.ScoringConfigScreen
import io.payanam.ui.screens.ScoreDetailScreen
import io.payanam.ui.screens.ScoreDetailType
import io.payanam.ui.screens.TaskDetailScreen
import io.payanam.ui.screens.TasksScreen
import io.payanam.ui.screens.TasksScreenMode
import io.payanam.ui.screens.TimeScreen
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.AppPreferencesViewModel
/**
 * Provides the screen.
 */
sealed class Screen(
    val route: String,
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    data object Tasks : Screen(
        route = "tasks",
        titleRes = io.payanam.R.string.settings_database_tasks,
        selectedIcon = Icons.Filled.Checklist,
        unselectedIcon = Icons.Outlined.Checklist,
    )

    data object Time : Screen(
        route = "time",
        titleRes = io.payanam.R.string.loc_time,
        selectedIcon = Icons.Filled.Timer,
        unselectedIcon = Icons.Outlined.Timer,
    )

    data object Notes : Screen(
        route = "notes",
        titleRes = io.payanam.R.string.settings_database_notes,
        selectedIcon = Icons.Filled.Note,
        unselectedIcon = Icons.Outlined.Note,
    )

    data object Habits : Screen(
        route = "habits",
        titleRes = io.payanam.R.string.loc_habits,
        selectedIcon = Icons.Filled.Repeat,
        unselectedIcon = Icons.Outlined.Repeat,
    )

    data object Journal : Screen(
        route = "journal",
        titleRes = io.payanam.R.string.loc_journal,
        selectedIcon = Icons.Filled.Edit,
        unselectedIcon = Icons.Outlined.Edit,
    )

    data object Lenses : Screen(
        route = "lenses",
        titleRes = io.payanam.R.string.loc_lenses,
        selectedIcon = Icons.Filled.Visibility,
        unselectedIcon = Icons.Outlined.Visibility,
    )

    data object Settings : Screen(
        route = "settings",
        titleRes = io.payanam.R.string.settings_title,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    )
}

// Non-bottom-nav routes
object Routes {
    const val PASSPHRASE_UNLOCK = "passphrase_unlock"
    const val PASSPHRASE_SETUP = "passphrase_setup"
    const val PASSPHRASE_CHANGE = "passphrase_change"
    const val DATABASE_INIT = "database_init"
    const val FOCUS_MODE_SELECTION = "focus_mode_selection"
    const val ADD_TASK = "add_task"
    const val TASK_DETAIL = "task_detail/{taskId}"
    const val EDIT_TASK = "edit_task/{taskId}"
    const val SCORE_DETAIL = "score_detail/{type}/{key}"
    const val SCORING_CONFIG = "scoring_config"
    /**
     * Performs the task detail.
     */
    fun taskDetail(taskId: String) = "task_detail/$taskId"
    /**
     * Performs the edit task.
     */
    fun editTask(taskId: String) = "edit_task/$taskId"
    /**
     * Performs the score detail.
     */
    fun scoreDetail(type: String, key: String) = "score_detail/$type/$key"
}
val bottomNavItems = listOf(
    Screen.Tasks,
    Screen.Habits,
    Screen.Time,
    Screen.Journal,
    Screen.Notes,
    Screen.Lenses,
    Screen.Settings,
)

@Composable
/**
 * Performs the payanam nav host.
 */
fun PayanamNavHost(
    shouldShowPassphraseUnlock: Boolean = false,
    shouldShowPassphraseSetup: Boolean = false,
    shouldShowDatabaseInit: Boolean = false,
    shouldShowFocusModeOnboarding: Boolean = false,
    resumeToRouteAfterUnlock: String? = null,
    onPassphraseUnlocked: () -> Unit = {},
    onDatabaseReady: () -> Unit = {},
    onRestartAfterDelete: () -> Unit = {},
    externalCommand: ExternalNavigationCommand? = null,
    onExternalCommandConsumed: () -> Unit = {},
    onUnlockReturnRouteConsumed: () -> Unit = {},
    appPreferencesViewModel: AppPreferencesViewModel? = null,
) {
    val logger = UnifiedLogger.getInstance()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentResolvedRoute = navBackStackEntry?.resolveConcreteRoute()
    var pendingTimeQuickStartRequestId by remember { mutableStateOf<Long?>(null) }
    var pendingTimeStopTrackingRequestId by remember { mutableStateOf<Long?>(null) }
    var pendingReturnRouteAfterUnlock by remember { mutableStateOf<String?>(resumeToRouteAfterUnlock) }

    // Get preferences state for tab visibility filtering
    val preferencesState by (
        appPreferencesViewModel?.uiState?.collectAsState()
            ?: remember { mutableStateOf(AppPreferencesState()) }
        )
    val backupFailureMessage = preferencesState.autoBackupLastErrorMessage
    val backupFailureAt = preferencesState.autoBackupLastErrorAt

    // Check if a route is allowed given current feature flags and preferences.
    // Delegates to NavRoutePolicy for testable logic; logs outcomes for observability.
    /**
     * Returns true when the is route allowed.
     */
    fun isRouteAllowed(route: String): Boolean {
        val allowed = NavRoutePolicy.isAllowed(route, FeatureFlags.minimalModeEnabled)
        if (!allowed) {
            logger.d("PayanamNavHost.isRouteAllowed", "Route blocked by minimal mode", mapOf("route" to route))
        } else if (route in NavRoutePolicy.startupGateRoutes) {
            logger.d("PayanamNavHost.isRouteAllowed", "Startup gate route allowed", mapOf("route" to route))
        }
        return allowed
    }

    // Filter bottom navigation items based on tab visibility and minimal mode
    val visibleBottomNavItems = remember(preferencesState.tabVisibility, FeatureFlags.minimalModeEnabled) {
        bottomNavItems.filter { screen ->
            val isAllowed = if (FeatureFlags.minimalModeEnabled) {
                screen.route in NavRoutePolicy.minimalModeAllowedTabs
            } else {
                true
            }
            (
                screen.route == "settings" || // Settings tab is always visible
                    preferencesState.tabVisibility[screen.route] != false
                ) && // Show if not explicitly hidden
                isAllowed // And allowed by feature flags
        }
    }

    // Hide bottom bar on first-run setup routes.
    val showBottomBar = currentRoute != Routes.PASSPHRASE_SETUP &&
        currentRoute != Routes.PASSPHRASE_UNLOCK &&
        currentRoute != Routes.PASSPHRASE_CHANGE &&
        currentRoute != Routes.DATABASE_INIT &&
        currentRoute != Routes.FOCUS_MODE_SELECTION
    val navigateToTopLevel: (String) -> Unit = remember(navController) {
        { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    LaunchedEffect(
        externalCommand,
        currentRoute,
        shouldShowPassphraseUnlock,
        shouldShowPassphraseSetup,
        shouldShowDatabaseInit,
        shouldShowFocusModeOnboarding,
    ) {
        if (shouldShowPassphraseUnlock || currentRoute == Routes.PASSPHRASE_UNLOCK ||
            shouldShowPassphraseSetup || currentRoute == Routes.PASSPHRASE_SETUP ||
            currentRoute == Routes.PASSPHRASE_CHANGE ||
            shouldShowDatabaseInit || currentRoute == Routes.DATABASE_INIT ||
            shouldShowFocusModeOnboarding || currentRoute == Routes.FOCUS_MODE_SELECTION
        ) {
            return@LaunchedEffect
        }
        when (val command = externalCommand) {
            is ExternalNavigationCommand.OpenTimeScreen -> {
                if (command.openQuickStart) {
                    pendingTimeQuickStartRequestId = command.requestId
                }
                if (command.openStopTracking) {
                    pendingTimeStopTrackingRequestId = command.requestId
                }
                navigateToTopLevel(Screen.Time.route)
                logger.i(
                    "PayanamNavHost",
                    "Handled external navigation command",
                    mapOf(
                        "route" to Screen.Time.route,
                        "source" to command.source,
                        "openQuickStart" to command.openQuickStart,
                        "openStopTracking" to command.openStopTracking,
                    ),
                )
                onExternalCommandConsumed()
            }

            null -> Unit
        }
    }
    LaunchedEffect(shouldShowPassphraseUnlock, currentRoute, resumeToRouteAfterUnlock) {
        if (!shouldShowPassphraseUnlock || currentResolvedRoute == null) {
            return@LaunchedEffect
        }
        if (resumeToRouteAfterUnlock != null && pendingReturnRouteAfterUnlock != resumeToRouteAfterUnlock) {
            pendingReturnRouteAfterUnlock = resumeToRouteAfterUnlock
        }
        if (currentResolvedRoute == Routes.PASSPHRASE_UNLOCK) {
            return@LaunchedEffect
        }
        if (shouldCaptureReturnRouteForUnlock(currentResolvedRoute)) {
            pendingReturnRouteAfterUnlock = currentResolvedRoute
            (navController.context as? io.payanam.MainActivity)?.requestSilentUnlock(currentResolvedRoute)
        }
        logger.i(
            "PayanamNavHost",
            "Routing to passphrase unlock for silent re-auth",
            mapOf(
                "currentRoute" to currentResolvedRoute,
                "returnRoute" to (pendingReturnRouteAfterUnlock ?: "none"),
            ),
        )
        navController.navigate(Routes.PASSPHRASE_UNLOCK) {
            launchSingleTop = true
        }
    }
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { controller, destination, _ ->
            val route = destination.route ?: return@OnDestinationChangedListener
            logger.i(
                "PayanamNavHost.destinationChanged",
                "Navigation event",
                mapOf("route" to route),
            )
            if (FeatureFlags.minimalModeEnabled && !isRouteAllowed(route)) {
                logger.w(
                    "PayanamNavHost.backStackGuard",
                    "Popping disabled route from back stack",
                    mapOf("route" to route),
                )
                controller.popBackStack()
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val currentDestination = navBackStackEntry?.destination

                    visibleBottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true
                        val tabLabel = androidx.compose.ui.res.stringResource(id = screen.titleRes)
                        NavigationBarItem(
                            modifier = Modifier.semantics {
                                contentDescription = tabLabel
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = null,
                                )
                            },
                            label = null,
                            alwaysShowLabel = false,
                            selected = selected,
                            onClick = {
                                if (isRouteAllowed(screen.route)) {
                                    logger.i("PayanamNavHost", "Navigating to screen", mapOf("route" to screen.route))
                                    navigateToTopLevel(screen.route)
                                } else {
                                    logger.w(
                                        "PayanamNavHost",
                                        "Navigation blocked by feature flags",
                                        mapOf(
                                            "route" to screen.route,
                                            "minimalModeEnabled" to FeatureFlags.minimalModeEnabled,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (!backupFailureMessage.isNullOrBlank()) {
            AlertDialog(
                onDismissRequest = {
                    appPreferencesViewModel?.dismissAutoBackupFailureMessage()
                },
                title = {
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.backup_failure_dialog_title))
                },
                text = {
                    Text(
                        backupFailureAt?.let {
                            androidx.compose.ui.res.stringResource(
                                id = io.payanam.R.string.backup_failure_dialog_message_with_time,
                                it,
                                backupFailureMessage,
                            )
                        } ?: androidx.compose.ui.res.stringResource(
                            id = io.payanam.R.string.backup_failure_dialog_message_without_time,
                            backupFailureMessage,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { appPreferencesViewModel?.dismissAutoBackupFailureMessage() },
                    ) {
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_dismiss_error_message))
                    }
                },
            )
        }
        val landingRoute = preferencesState.launchDestination.route
        val landingReady = !preferencesState.isLoading && landingRoute.isNotEmpty()
            && !shouldShowPassphraseSetup && !shouldShowPassphraseUnlock
            && !shouldShowDatabaseInit && !shouldShowFocusModeOnboarding
        val startDestination = remember(landingReady) {
            if (landingReady) {
                landingRoute
            } else {
                when {
                    shouldShowPassphraseSetup -> Routes.PASSPHRASE_SETUP
                    shouldShowPassphraseUnlock -> Routes.PASSPHRASE_UNLOCK
                    shouldShowDatabaseInit -> Routes.DATABASE_INIT
                    shouldShowFocusModeOnboarding -> Routes.FOCUS_MODE_SELECTION
                    else -> null
                }
            }
        }
        if (startDestination == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.PASSPHRASE_SETUP) {
                DatabasePassphraseSetupScreen(
                    onPassphraseConfigured = {
                        val nextRoute = when {
                            shouldShowDatabaseInit -> Routes.DATABASE_INIT
                            shouldShowFocusModeOnboarding -> Routes.FOCUS_MODE_SELECTION
                            else -> Screen.Lenses.route
                        }
                        logger.i("PayanamNavHost", "Passphrase configured, continuing startup flow", mapOf("nextRoute" to nextRoute))
                        navController.navigate(nextRoute) {
                            popUpTo(Routes.PASSPHRASE_SETUP) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.PASSPHRASE_UNLOCK) {
                DatabasePassphraseUnlockScreen(
                    onPassphraseUnlocked = {
                        logger.i("PayanamNavHost", "Passphrase unlocked; continuing startup flow")
                        onPassphraseUnlocked()
                        val nextRoute = when {
                            shouldShowDatabaseInit -> Routes.DATABASE_INIT
                            shouldShowFocusModeOnboarding -> Routes.FOCUS_MODE_SELECTION
                            pendingReturnRouteAfterUnlock != null -> pendingReturnRouteAfterUnlock!!
                            else -> Screen.Lenses.route
                        }
                        pendingReturnRouteAfterUnlock = null
                        onUnlockReturnRouteConsumed()
                        navController.navigate(nextRoute) {
                            popUpTo(Routes.PASSPHRASE_UNLOCK) { inclusive = true }
                        }
                    },
                    onForgotPassphraseReset = {
                        logger.w("PayanamNavHost", "Forgot-passphrase reset completed; navigating to setup")
                        navController.navigate(Routes.PASSPHRASE_SETUP) {
                            popUpTo(Routes.PASSPHRASE_UNLOCK) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.PASSPHRASE_CHANGE) {
                DatabasePassphraseChangeScreen(
                    onPassphraseChanged = {
                        logger.i("PayanamNavHost", "Passphrase changed successfully")
                        navController.popBackStack()
                    },
                )
            }

            // Database initialization screen (shown only on first launch or when no DB)
            composable(Routes.DATABASE_INIT) {
                DatabaseInitScreen(
                    onDatabaseReady = {
                        logger.i("PayanamNavHost", "Database init reported ready; delegating startup flow re-evaluation", mapOf("from" to "DatabaseInit"))
                        onDatabaseReady()
                    },
                )
            }

            // Focus Mode Selection screen (shown after database init on first launch)
            composable(Routes.FOCUS_MODE_SELECTION) {
                FocusModeSelectionScreen(
                    onPresetSelected = { preset ->
                        logger.i(
                            "PayanamNavHost",
                            "Focus mode preset selected, navigating to Lenses screen",
                            mapOf("preset" to preset.presetId),
                        )
                        val prefsViewModel = appPreferencesViewModel
                        checkNotNull(prefsViewModel) {
                            "AppPreferencesViewModel is required when showing Focus Mode onboarding."
                        }
                        prefsViewModel.setActivePreset(preset)
                        prefsViewModel.markFocusModeOnboardingCompleted()
                        navController.navigate(Screen.Lenses.route) {
                            popUpTo(Routes.FOCUS_MODE_SELECTION) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Tasks.route) {
                TasksScreen(
                    mode = TasksScreenMode.TASKS_ONLY,
                    onNavigateToAddTask = {
                        logger.i("PayanamNavHost", "Navigating to add task", mapOf())
                        navController.navigate(Routes.ADD_TASK)
                    },
                    onNavigateToTaskDetail = { taskId ->
                        logger.i("PayanamNavHost", "Navigating to task detail from Tasks", mapOf("taskId" to taskId))
                        navController.navigate(Routes.taskDetail(taskId))
                    },
                )
            }
            composable(Screen.Time.route) {
                TimeScreen(
                    openStartTrackingDialogRequestId = pendingTimeQuickStartRequestId,
                    onOpenStartTrackingDialogHandled = { handledRequestId ->
                        if (pendingTimeQuickStartRequestId == handledRequestId) {
                            pendingTimeQuickStartRequestId = null
                        }
                    },
                    openStopTrackingDialogRequestId = pendingTimeStopTrackingRequestId,
                    onOpenStopTrackingDialogHandled = { handledRequestId ->
                        if (pendingTimeStopTrackingRequestId == handledRequestId) {
                            pendingTimeStopTrackingRequestId = null
                        }
                    },
                    onNavigateToTask = { taskId ->
                        logger.i("PayanamNavHost", "Navigating to edit task from Time", mapOf("taskId" to taskId))
                        navController.navigate(Routes.editTask(taskId))
                    },
                )
            }
            composable(Screen.Notes.route) {
                NotesScreen()
            }
            composable(Screen.Habits.route) {
                TasksScreen(
                    mode = TasksScreenMode.HABITS_ONLY,
                    onNavigateToAddTask = {
                        logger.i("PayanamNavHost", "Navigating to add task from Habits", mapOf())
                        navController.navigate(Routes.ADD_TASK)
                    },
                    onNavigateToTaskDetail = { taskId ->
                        logger.i("PayanamNavHost", "Navigating to task detail from Habits", mapOf("taskId" to taskId))
                        navController.navigate(Routes.taskDetail(taskId))
                    },
                )
            }
            composable(Screen.Journal.route) {
                DayScreen(
                    mode = DayScreenMode.JOURNAL_ONLY,
                )
            }
            composable(Screen.Lenses.route) {
                LensesScreen(
                    onOpenTime = {
                        logger.i("PayanamNavHost", "Navigating to time from unified lenses", mapOf())
                        navigateToTopLevel(Screen.Time.route)
                    },
                    onOpenTasks = {
                        logger.i("PayanamNavHost", "Navigating to tasks from unified lenses", mapOf())
                        navigateToTopLevel(Screen.Tasks.route)
                    },
                    onOpenHabits = {
                        logger.i("PayanamNavHost", "Navigating to habits from unified lenses", mapOf())
                        navigateToTopLevel(Screen.Habits.route)
                    },
                    onOpenJournal = {
                        logger.i("PayanamNavHost", "Navigating to journal from unified lenses", mapOf())
                        navigateToTopLevel(Screen.Journal.route)
                    },
                    onOpenNotes = {
                        logger.i("PayanamNavHost", "Navigating to notes from unified lenses", mapOf())
                        navigateToTopLevel(Screen.Notes.route)
                    },
                    onOpenScoreDetail = { type, key ->
                        logger.i("PayanamNavHost", "Opening score detail from lenses", mapOf("type" to type, "key" to key))
                        navController.navigate(Routes.scoreDetail(type, key))
                    },
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToPassphraseChange = {
                        logger.i("PayanamNavHost", "Navigating to passphrase change", mapOf())
                        navController.navigate(Routes.PASSPHRASE_CHANGE)
                    },
                    onNavigateToScoringConfig = {
                        logger.i("PayanamNavHost", "Navigating to scoring config", mapOf())
                        navController.navigate(Routes.SCORING_CONFIG)
                    },
                    onNavigateToDatabaseInit = {
                        logger.i("PayanamNavHost", "Delete all data complete; restarting process for clean DB init", mapOf())
                        onRestartAfterDelete()
                    },
                )
            }

            // Non-bottom-nav routes
            composable(Routes.ADD_TASK) {
                AddTaskScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onTaskSaved = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.TASK_DETAIL,
                arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
                TaskDetailScreen(
                    taskId = taskId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToEdit = {
                        logger.i("PayanamNavHost", "Navigating to edit task", mapOf("taskId" to taskId))
                        navController.navigate(Routes.editTask(taskId))
                    },
                )
            }
            composable(
                route = Routes.SCORE_DETAIL,
                arguments =
                    listOf(
                        navArgument("type") { type = NavType.StringType },
                        navArgument("key") { type = NavType.StringType },
                    ),
            ) { backStackEntry ->
                val typeName = backStackEntry.arguments?.getString("type") ?: return@composable
                val key = backStackEntry.arguments?.getString("key") ?: return@composable
                val type =
                    if (typeName == "DAY") ScoreDetailType.DAY
                    else ScoreDetailType.DIMENSION
                logger.i("PayanamNavHost", "Opening score detail", mapOf("type" to typeName, "key" to key))
                ScoreDetailScreen(
                    type = type,
                    key = key,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.EDIT_TASK,
                arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
                EditTaskScreen(
                    taskId = taskId,
                    onNavigateBack = { navController.popBackStack() },
                    onTaskSaved = { navController.popBackStack() },
                )
            }
            composable(Routes.SCORING_CONFIG) {
                ScoringConfigScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }


        }
    }
}

internal fun shouldCaptureReturnRouteForUnlock(route: String): Boolean = route != Routes.PASSPHRASE_UNLOCK &&
    route != Routes.PASSPHRASE_SETUP &&
    route != Routes.PASSPHRASE_CHANGE &&
    route != Routes.DATABASE_INIT &&
    route != Routes.FOCUS_MODE_SELECTION

internal fun NavBackStackEntry.resolveConcreteRoute(): String? {
    val route = destination.route ?: return null
    val taskId = arguments?.getString("taskId")
    return resolveConcreteRoute(route, taskId)
}

internal fun resolveConcreteRoute(route: String, taskId: String? = null): String = when (route) {
    Routes.TASK_DETAIL -> {
        taskId?.let { Routes.taskDetail(it) } ?: route
    }

    Routes.EDIT_TASK -> {
        taskId?.let { Routes.editTask(it) } ?: route
    }

    else -> route
}
