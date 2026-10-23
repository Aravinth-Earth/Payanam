//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.SavedState
import androidx.savedstate.read
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.settings.FocusModePreset

/**
 * The application's navigation graph, rendered identically by every platform.
 *
 * MOVED FROM `:app` - this is the keystone of the shared UI. Sharing screens without the graph hands the
 * desktop a set of screens it cannot reach, and leaves every later screen needing a per-platform call
 * site; the graph is what makes "the same app on every platform" structurally true rather than a claim
 * about individual screens.
 *
 * What changed in the move, and why:
 *
 *  - **Navigation is Compose Multiplatform navigation** (`org.jetbrains.androidx.navigation`) rather than
 *    the Android artifact, so `NavHost`/`composable`/`navArgument` exist on the desktop too. The routes,
 *    the arguments and the back-stack behaviour are the ones Android already had.
 *  - **The destination bodies arrive through [PayanamNavHostScreens].** Their wiring is a Hilt ViewModel on
 *    Android and the desktop's stores on the desktop, and Hilt does not exist off-Android, so the bodies
 *    cannot live here. This object is not a per-screen wrapper: it is one implementation per platform.
 *  - **The Android-only inputs are parameters.** The previous version read the app's
 *    `AppPreferencesViewModel` for tab visibility, the landing route and the backup-failure banner; it
 *    reached `MainActivity` through `navController.context` to request a silent unlock; and it read
 *    `FeatureFlags`/`UnifiedLogger` through Android singletons. All of it arrives as arguments now, which
 *    is also what makes the graph testable without an Activity.
 *  - **Logging goes through [AppLogger]**, the process-wide holder every platform installs its real logger
 *    into. The event names and payloads are unchanged, so an existing log grep still works.
 *
 * @param screens the destination bodies for the platform currently running.
 * @param language the language [LocalUiStrings] is installed with; supplied by the caller so this
 *   composable can label the bottom bar and can provide the table for everything below it.
 * @param minimalModeEnabled whether minimal mode is on (an Android build flag; a parameter so the desktop
 *   resolves it the same way).
 * @param tabVisibility per-tab visibility the user chose in Settings.
 * @param landingRoute the route to open on when the app is ready.
 * @param isLandingReady false while preferences are still loading - the host shows a spinner instead of
 *   guessing a start destination.
 */
@Composable
fun PayanamNavHost(
    screens: PayanamNavHostScreens,
    language: UiLanguage,
    minimalModeEnabled: Boolean,
    tabVisibility: Map<String, Boolean> = emptyMap(),
    landingRoute: String = "",
    isLandingReady: Boolean = false,
    shouldShowPassphraseUnlock: Boolean = false,
    shouldShowPassphraseSetup: Boolean = false,
    shouldShowDatabaseInit: Boolean = false,
    shouldShowFocusModeOnboarding: Boolean = false,
    resumeToRouteAfterUnlock: String? = null,
    externalCommand: PayanamExternalNavigationCommand? = null,
    backupFailureMessage: String? = null,
    backupFailureAt: String? = null,
    onDismissBackupFailure: () -> Unit = {},
    onFocusModePresetSelected: (FocusModePreset) -> Unit = {},
    onPassphraseUnlocked: () -> Unit = {},
    onDatabaseReady: () -> Unit = {},
    onRestartAfterDelete: () -> Unit = {},
    onExternalCommandConsumed: () -> Unit = {},
    onUnlockReturnRouteConsumed: () -> Unit = {},
    onSilentUnlockRequested: (route: String) -> Unit = {},
) {
    val strings = UiStrings.of(language)
    val logger = AppLogger.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentResolvedRoute = navBackStackEntry?.resolveConcreteRoute()
    var pendingTimeQuickStartRequestId by remember { mutableStateOf<Long?>(null) }
    var pendingTimeStopTrackingRequestId by remember { mutableStateOf<Long?>(null) }
    var pendingReturnRouteAfterUnlock by remember { mutableStateOf<String?>(resumeToRouteAfterUnlock) }

    // Check if a route is allowed given current feature flags and preferences.
    // Delegates to PayanamNavRoutePolicy for testable logic; logs outcomes for observability.
    fun isRouteAllowed(route: String): Boolean {
        val allowed = PayanamNavRoutePolicy.isAllowed(route, minimalModeEnabled)
        if (!allowed) {
            logger.d("PayanamNavHost.isRouteAllowed", "Route blocked by minimal mode", mapOf("route" to route))
        } else if (route in PayanamNavRoutePolicy.startupGateRoutes) {
            logger.d("PayanamNavHost.isRouteAllowed", "Startup gate route allowed", mapOf("route" to route))
        }
        return allowed
    }

    // Bottom navigation items filtered by tab visibility and minimal mode.
    val visibleBottomNavItems =
        remember(tabVisibility, minimalModeEnabled) {
            visiblePayanamTabs(tabVisibility = tabVisibility, minimalModeEnabled = minimalModeEnabled)
        }

    // Hide bottom bar on first-run setup routes.
    val showBottomBar =
        currentRoute != PayanamRoutes.PASSPHRASE_SETUP &&
            currentRoute != PayanamRoutes.PASSPHRASE_UNLOCK &&
            currentRoute != PayanamRoutes.PASSPHRASE_CHANGE &&
            currentRoute != PayanamRoutes.DATABASE_INIT &&
            currentRoute != PayanamRoutes.FOCUS_MODE_SELECTION
    val navigateToTopLevel: (String) -> Boolean =
        remember(navController) {
            { route ->
                // The bottom bar can be composed a frame before NavHost installs its graph, and
                // navController.graph throws inside that window - a real crash on a very early tap.
                // currentBackStackEntry does not touch the graph, so it is a safe readiness probe.
                // Returns false while the graph is not ready: callers that must not lose the
                // request (external commands) retry instead of dropping the navigation.
                if (navController.currentBackStackEntry != null) {
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                    true
                } else {
                    false
                }
            }
        }
    val navActions =
        remember(navController, navigateToTopLevel) {
            PayanamNavActions(
                navigateToRoute = { route -> navController.navigate(route) },
                navigateToTopLevelRoute = navigateToTopLevel,
                popBackStackAction = { navController.popBackStack() },
                onRestartAfterDataDelete = onRestartAfterDelete,
            )
        }
    LaunchedEffect(
        externalCommand,
        currentRoute,
        tabVisibility,
        shouldShowPassphraseUnlock,
        shouldShowPassphraseSetup,
        shouldShowDatabaseInit,
        shouldShowFocusModeOnboarding,
    ) {
        if (shouldShowPassphraseUnlock || currentRoute == PayanamRoutes.PASSPHRASE_UNLOCK ||
            shouldShowPassphraseSetup || currentRoute == PayanamRoutes.PASSPHRASE_SETUP ||
            currentRoute == PayanamRoutes.PASSPHRASE_CHANGE ||
            shouldShowDatabaseInit || currentRoute == PayanamRoutes.DATABASE_INIT ||
            shouldShowFocusModeOnboarding || currentRoute == PayanamRoutes.FOCUS_MODE_SELECTION
        ) {
            return@LaunchedEffect
        }
        when (val command = externalCommand) {
            is PayanamExternalNavigationCommand.OpenTimeScreen -> {
                val targetModuleDisabled =
                    PayanamNavRoutePolicy.isModuleDisabled(
                        route = PayanamTab.Time.route,
                        minimalModeEnabled = minimalModeEnabled,
                        tabVisibility = tabVisibility,
                    )
                if (targetModuleDisabled) {
                    // Respect a disabled module: drop the command and log it instead of opening a
                    // surface the user turned off. Consuming it keeps it from firing later when
                    // the module is re-enabled.
                    logger.w(
                        "PayanamNavHost",
                        "External navigation command blocked: target module is disabled",
                        mapOf(
                            "route" to PayanamTab.Time.route,
                            "source" to command.source,
                            "tabVisibility" to tabVisibility[PayanamTab.Time.route],
                        ),
                    )
                    onExternalCommandConsumed()
                } else if (navigateToTopLevel(PayanamTab.Time.route)) {
                    if (command.openQuickStart) {
                        pendingTimeQuickStartRequestId = command.requestId
                    }
                    if (command.openStopTracking) {
                        pendingTimeStopTrackingRequestId = command.requestId
                    }
                    logger.i(
                        "PayanamNavHost",
                        "Handled external navigation command",
                        mapOf(
                            "route" to PayanamTab.Time.route,
                            "source" to command.source,
                            "openQuickStart" to command.openQuickStart,
                            "openStopTracking" to command.openStopTracking,
                        ),
                    )
                    onExternalCommandConsumed()
                } else {
                    // The graph is not installed yet (preferences still loading). Keep the command
                    // pending; the effect re-runs as currentRoute/tabVisibility change while the
                    // app becomes ready, and the navigation is retried then.
                    logger.d(
                        "PayanamNavHost",
                        "External navigation command deferred until the navigation graph is ready",
                        mapOf(
                            "route" to PayanamTab.Time.route,
                            "source" to command.source,
                        ),
                    )
                }
            }

            null -> {
                Unit
            }
        }
    }
    LaunchedEffect(shouldShowPassphraseUnlock, currentRoute, resumeToRouteAfterUnlock) {
        if (!shouldShowPassphraseUnlock || currentResolvedRoute == null) {
            return@LaunchedEffect
        }
        if (resumeToRouteAfterUnlock != null && pendingReturnRouteAfterUnlock != resumeToRouteAfterUnlock) {
            pendingReturnRouteAfterUnlock = resumeToRouteAfterUnlock
        }
        if (currentResolvedRoute == PayanamRoutes.PASSPHRASE_UNLOCK) {
            return@LaunchedEffect
        }
        if (shouldCaptureReturnRouteForUnlock(currentResolvedRoute)) {
            pendingReturnRouteAfterUnlock = currentResolvedRoute
            onSilentUnlockRequested(currentResolvedRoute)
        }
        logger.i(
            "PayanamNavHost",
            "Routing to passphrase unlock for silent re-auth",
            mapOf(
                "currentRoute" to currentResolvedRoute,
                "returnRoute" to (pendingReturnRouteAfterUnlock ?: "none"),
            ),
        )
        navController.navigate(PayanamRoutes.PASSPHRASE_UNLOCK) {
            launchSingleTop = true
        }
    }
    DisposableEffect(navController) {
        // A named object rather than a lambda: the multiplatform navigation artifact declares
        // OnDestinationChangedListener as a plain interface, so Kotlin's SAM conversion does not apply.
        val listener =
            object : NavController.OnDestinationChangedListener {
                override fun onDestinationChanged(
                    controller: NavController,
                    destination: NavDestination,
                    arguments: SavedState?,
                ) {
                    val route = destination.route ?: return
                    logger.i(
                        "PayanamNavHost.destinationChanged",
                        "Navigation event",
                        mapOf("route" to route),
                    )
                    if (minimalModeEnabled && !isRouteAllowed(route)) {
                        logger.w(
                            "PayanamNavHost.backStackGuard",
                            "Popping disabled route from back stack",
                            mapOf("route" to route),
                        )
                        controller.popBackStack()
                    }
                }
            }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    // Every destination body reads its copy from the ambient table rather than taking the language as
    // a parameter, so the table is installed once here for the whole graph.
    CompositionLocalProvider(LocalUiStrings provides strings) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        val currentDestination = navBackStackEntry?.destination

                        visibleBottomNavItems.forEach { tab ->
                            val selected =
                                currentDestination?.hierarchy?.any {
                                    it.route == tab.route
                                } == true
                            val tabLabel = tab.title(strings)
                            NavigationBarItem(
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = tabLabel
                                    },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(tabLabel) },
                                alwaysShowLabel = true,
                                selected = selected,
                                onClick = {
                                    if (isRouteAllowed(tab.route)) {
                                        logger.i("PayanamNavHost", "Navigating to screen", mapOf("route" to tab.route))
                                        navigateToTopLevel(tab.route)
                                    } else {
                                        logger.w(
                                            "PayanamNavHost",
                                            "Navigation blocked by feature flags",
                                            mapOf(
                                                "route" to tab.route,
                                                "minimalModeEnabled" to minimalModeEnabled,
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
                    onDismissRequest = onDismissBackupFailure,
                    title = { Text(strings.backupFailureDialogTitle) },
                    text = {
                        Text(
                            backupFailureAt?.let {
                                strings.backupFailureDialogMessageWithTime.formatArgs(
                                    it,
                                    backupFailureMessage,
                                )
                            } ?: strings.backupFailureDialogMessageWithoutTime.formatArgs(
                                backupFailureMessage,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = onDismissBackupFailure) {
                            Text(strings.settingsActionDismissErrorMessage)
                        }
                    },
                )
            }
            val landingReady =
                isLandingReady && landingRoute.isNotEmpty() &&
                    !shouldShowPassphraseSetup && !shouldShowPassphraseUnlock &&
                    !shouldShowDatabaseInit && !shouldShowFocusModeOnboarding
            val startDestination =
                remember(landingReady, landingRoute) {
                    if (landingReady) {
                        landingRoute
                    } else {
                        when {
                            shouldShowPassphraseSetup -> PayanamRoutes.PASSPHRASE_SETUP
                            shouldShowPassphraseUnlock -> PayanamRoutes.PASSPHRASE_UNLOCK
                            shouldShowDatabaseInit -> PayanamRoutes.DATABASE_INIT
                            shouldShowFocusModeOnboarding -> PayanamRoutes.FOCUS_MODE_SELECTION
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
                composable(PayanamRoutes.PASSPHRASE_SETUP) {
                    screens.passphraseSetup(
                        nav = navActions,
                        onConfigured = {
                            val nextRoute =
                                when {
                                    shouldShowDatabaseInit -> PayanamRoutes.DATABASE_INIT
                                    shouldShowFocusModeOnboarding -> PayanamRoutes.FOCUS_MODE_SELECTION
                                    else -> PayanamTab.Lenses.route
                                }
                            logger.i("PayanamNavHost", "Passphrase configured, continuing startup flow", mapOf("nextRoute" to nextRoute))
                            navController.navigate(nextRoute) {
                                popUpTo(PayanamRoutes.PASSPHRASE_SETUP) { inclusive = true }
                            }
                        },
                    )
                }
                composable(PayanamRoutes.PASSPHRASE_UNLOCK) {
                    screens.passphraseUnlock(
                        nav = navActions,
                        onUnlocked = {
                            logger.i("PayanamNavHost", "Passphrase unlocked; continuing startup flow")
                            onPassphraseUnlocked()
                            val nextRoute =
                                when {
                                    shouldShowDatabaseInit -> PayanamRoutes.DATABASE_INIT
                                    shouldShowFocusModeOnboarding -> PayanamRoutes.FOCUS_MODE_SELECTION
                                    pendingReturnRouteAfterUnlock != null -> pendingReturnRouteAfterUnlock!!
                                    else -> PayanamTab.Lenses.route
                                }
                            pendingReturnRouteAfterUnlock = null
                            onUnlockReturnRouteConsumed()
                            navController.navigate(nextRoute) {
                                popUpTo(PayanamRoutes.PASSPHRASE_UNLOCK) { inclusive = true }
                            }
                        },
                        onForgotPassphraseReset = {
                            logger.w("PayanamNavHost", "Forgot-passphrase reset completed; navigating to setup")
                            navController.navigate(PayanamRoutes.PASSPHRASE_SETUP) {
                                popUpTo(PayanamRoutes.PASSPHRASE_UNLOCK) { inclusive = true }
                            }
                        },
                    )
                }
                composable(PayanamRoutes.PASSPHRASE_CHANGE) {
                    screens.passphraseChange(
                        nav = navActions,
                        onPassphraseChanged = {
                            logger.i("PayanamNavHost", "Passphrase changed successfully")
                            navController.popBackStack()
                        },
                    )
                }

                // Database initialization screen (shown only on first launch or when no DB)
                composable(PayanamRoutes.DATABASE_INIT) {
                    screens.databaseInit(
                        nav = navActions,
                        onDatabaseReady = {
                            logger.i(
                                "PayanamNavHost",
                                "Database init reported ready; delegating startup flow re-evaluation",
                                mapOf("from" to "DatabaseInit"),
                            )
                            onDatabaseReady()
                        },
                    )
                }

                // Focus Mode Selection screen (shown after database init on first launch)
                composable(PayanamRoutes.FOCUS_MODE_SELECTION) {
                    screens.focusModeSelection(
                        nav = navActions,
                        onPresetSelected = { preset ->
                            logger.i(
                                "PayanamNavHost",
                                "Focus mode preset selected, navigating to Lenses screen",
                                mapOf("preset" to preset.presetId),
                            )
                            onFocusModePresetSelected(preset)
                            navController.navigate(PayanamTab.Lenses.route) {
                                popUpTo(PayanamRoutes.FOCUS_MODE_SELECTION) { inclusive = true }
                            }
                        },
                    )
                }
                composable(PayanamTab.Tasks.route) {
                    screens.tasks(nav = navActions)
                }
                composable(PayanamTab.Time.route) {
                    screens.time(
                        nav = navActions,
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
                    )
                }
                composable(PayanamTab.Notes.route) {
                    screens.notes(nav = navActions)
                }
                composable(PayanamTab.Habits.route) {
                    screens.habits(nav = navActions)
                }
                composable(PayanamTab.Journal.route) {
                    screens.journal(nav = navActions)
                }
                composable(PayanamTab.Lenses.route) {
                    screens.lenses(nav = navActions)
                }
                composable(PayanamTab.Settings.route) {
                    screens.settings(nav = navActions)
                }

                // Non-bottom-nav routes
                composable(PayanamRoutes.ADD_TASK) {
                    screens.addTask(nav = navActions)
                }
                composable(
                    route = PayanamRoutes.TASK_DETAIL,
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val taskId = backStackEntry.arguments?.read { getStringOrNull("taskId") } ?: return@composable
                    screens.taskDetail(nav = navActions, taskId = taskId)
                }
                composable(
                    route = PayanamRoutes.SCORE_DETAIL,
                    arguments =
                        listOf(
                            navArgument("type") { type = NavType.StringType },
                            navArgument("key") { type = NavType.StringType },
                        ),
                ) { backStackEntry ->
                    val typeName = backStackEntry.arguments?.read { getStringOrNull("type") } ?: return@composable
                    val key = backStackEntry.arguments?.read { getStringOrNull("key") } ?: return@composable
                    logger.i("PayanamNavHost", "Opening score detail", mapOf("type" to typeName, "key" to key))
                    screens.scoreDetail(nav = navActions, type = typeName, key = key)
                }
                composable(
                    route = PayanamRoutes.EDIT_TASK,
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val taskId = backStackEntry.arguments?.read { getStringOrNull("taskId") } ?: return@composable
                    screens.editTask(nav = navActions, taskId = taskId)
                }
                composable(PayanamRoutes.SCORING_CONFIG) {
                    screens.scoringConfig(nav = navActions)
                }
                // Unconditional: the assistant is a permanent capability, not a build-time option. The
                // destinations always exist, so the entry card's tap and any future external navigation
                // always resolve to a real screen.
                composable(PayanamRoutes.ASSISTANT) {
                    screens.assistant(nav = navActions)
                }
                composable(PayanamRoutes.ASSISTANT_SETTINGS) {
                    screens.assistantSettings(nav = navActions)
                }
            }
        }
    }
}

/** The concrete route for the entry currently on the back stack, arguments expanded. */
internal fun NavBackStackEntry.resolveConcreteRoute(): String? {
    val route = destination.route ?: return null
    val taskId = arguments?.read { getStringOrNull("taskId") }
    return resolveConcreteRoute(route, taskId)
}
