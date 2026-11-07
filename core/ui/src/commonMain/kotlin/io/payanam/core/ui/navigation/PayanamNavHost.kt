//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.components.TestTags
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.settings.FocusModePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The application's navigation graph, rendered identically by every platform; destination bodies arrive
 * through [PayanamNavHostScreens]. [landingRoute] is where the app opens once [isLandingReady] is true, and
 * while it is false the host shows a spinner rather than guessing a start destination. */
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
    shouldShowSecurityStateUnavailable: Boolean = false,
    shouldShowOnboardingIntro: Boolean = false,
    resumeToRouteAfterUnlock: String? = null,
    externalCommand: PayanamExternalNavigationCommand? = null,
    backupFailureMessage: String? = null,
    backupFailureAt: String? = null,
    onDismissBackupFailure: () -> Unit = {},
    onFocusModePresetSelected: (FocusModePreset) -> Unit = {},
    onPassphraseUnlocked: () -> Unit = {},
    onDatabaseReady: () -> Unit = {},
    onOnboardingIntroFinished: () -> Unit = {},
    onSecurityResetRequested: () -> Unit = {},
    onQuitRequested: () -> Unit = {},
    onRestartAfterDelete: () -> Unit = {},
    /** The platform's own exit path, called after the change has popped the back stack. */
    onRestartAfterPassphraseChange: () -> Unit = {},
    onExternalCommandConsumed: () -> Unit = {},
    /** A top-level (tab) route requested from outside the graph; consumed once honoured or refused. */
    topLevelNavigationRequest: String? = null,
    /** Called once the request has been honoured or refused; the caller clears its stored value. */
    onTopLevelNavigationRequestConsumed: () -> Unit = {},
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
    var pendingTaskStatusRequest by remember { mutableStateOf<TaskStatusDialogRequest?>(null) }

    // True while the init gate continues a reset, so the first-run tour is not shown again.
    var databaseInitFollowsReset by remember { mutableStateOf(false) }

    // Route policy check; delegates to PayanamNavRoutePolicy and logs the outcome.
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
                // A tap can land before NavHost installs its graph, where `navController.graph` throws;
                // `currentBackStackEntry` is the safe probe, and false means "retry, do not drop".
                if (navController.currentBackStackEntry != null) {
                    // The mutation must run on `Dispatchers.Main.immediate` - the thread that owns the
                    // composition - because navigation mutates back-stack and lifecycle state.
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
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
                navigateToRoute = { route ->
                    // Same rule as `navigateToTopLevel`, and for the same reason: this is the entry
                    // point Android's screens and the desktop's own screens call alike, so it is the
                    // one that must be on the main dispatcher for both.
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        navController.navigate(route)
                    }
                },
                navigateToTopLevelRoute = navigateToTopLevel,
                popBackStackAction = {
                    // Same main-dispatcher rule as `navigateToRoute`, for the same reason: popping
                    // mutates back-stack lifecycle state, so it belongs on the thread that owns the
                    // composition - the framework main thread on Android, the Swing EDT on the desktop.
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        navController.popBackStack()
                    }
                },
                onRestartAfterDataDelete = onRestartAfterDelete,
            )
        }
    // A request must not navigate over a startup gate, and answers to the same route policy as the bar.
    LaunchedEffect(
        topLevelNavigationRequest,
        currentRoute,
        minimalModeEnabled,
        shouldShowPassphraseUnlock,
        shouldShowPassphraseSetup,
        shouldShowDatabaseInit,
        shouldShowFocusModeOnboarding,
        shouldShowSecurityStateUnavailable,
        shouldShowOnboardingIntro,
    ) {
        val request = topLevelNavigationRequest ?: return@LaunchedEffect
        if (shouldShowPassphraseUnlock || currentRoute == PayanamRoutes.PASSPHRASE_UNLOCK ||
            shouldShowPassphraseSetup || currentRoute == PayanamRoutes.PASSPHRASE_SETUP ||
            currentRoute == PayanamRoutes.PASSPHRASE_CHANGE ||
            shouldShowDatabaseInit || currentRoute == PayanamRoutes.DATABASE_INIT ||
            shouldShowFocusModeOnboarding || currentRoute == PayanamRoutes.FOCUS_MODE_SELECTION ||
            shouldShowSecurityStateUnavailable || currentRoute == PayanamRoutes.SECURITY_STATE_UNAVAILABLE ||
            shouldShowOnboardingIntro || currentRoute == PayanamRoutes.ONBOARDING_INTRO
        ) {
            // Wait rather than drop: the gate is showing, and the key list re-runs this effect when it stops.
            return@LaunchedEffect
        }
        if (isRouteAllowed(request)) {
            if (navigateToTopLevel(request)) {
                onTopLevelNavigationRequestConsumed()
            }
        } else {
            logger.w(
                "PayanamNavHost.topLevelNavigationRequest",
                "Top-level navigation request refused by route policy",
                mapOf("route" to request),
            )
            // Consumed even though it was refused: leaving it non-null would re-run on every
            // `currentRoute` change and could fire later, when the policy has moved.
            onTopLevelNavigationRequestConsumed()
        }
    }
    LaunchedEffect(
        externalCommand,
        currentRoute,
        tabVisibility,
        shouldShowPassphraseUnlock,
        shouldShowPassphraseSetup,
        shouldShowDatabaseInit,
        shouldShowFocusModeOnboarding,
        shouldShowSecurityStateUnavailable,
        shouldShowOnboardingIntro,
    ) {
        if (shouldShowPassphraseUnlock || currentRoute == PayanamRoutes.PASSPHRASE_UNLOCK ||
            shouldShowPassphraseSetup || currentRoute == PayanamRoutes.PASSPHRASE_SETUP ||
            currentRoute == PayanamRoutes.PASSPHRASE_CHANGE ||
            shouldShowDatabaseInit || currentRoute == PayanamRoutes.DATABASE_INIT ||
            shouldShowFocusModeOnboarding || currentRoute == PayanamRoutes.FOCUS_MODE_SELECTION ||
            shouldShowSecurityStateUnavailable || currentRoute == PayanamRoutes.SECURITY_STATE_UNAVAILABLE ||
            shouldShowOnboardingIntro || currentRoute == PayanamRoutes.ONBOARDING_INTRO
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

            is PayanamExternalNavigationCommand.OpenTaskStatusDialog -> {
                if (navController.currentBackStackEntry == null) {
                    // Same readiness rule as the Time command: retry when the graph installs, do not drop.
                    logger.d(
                        "PayanamNavHost",
                        "Task status dialog request deferred until the navigation graph is ready",
                        mapOf("taskId" to command.taskId, "source" to command.source),
                    )
                } else {
                    navActions.openTaskDetail(command.taskId)
                    pendingTaskStatusRequest =
                        TaskStatusDialogRequest(command.taskId, command.requestId) { handledRequestId ->
                            if (pendingTaskStatusRequest?.requestId == handledRequestId) {
                                pendingTaskStatusRequest = null
                            }
                        }
                    logger.i(
                        "PayanamNavHost",
                        "Opening task status dialog for external request",
                        mapOf("taskId" to command.taskId, "source" to command.source),
                    )
                    onExternalCommandConsumed()
                }
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
        // Never yank a startup gate the user is standing on: the passphrase change and the
        // forgot-passphrase reset each drive their own navigation, and overriding it cancels or strands them.
        if (isStartupGateRoute(currentResolvedRoute)) {
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
        // A `LaunchedEffect` body runs on whatever dispatcher the recomposer supplies, so the back-stack
        // mutation is handed to the main dispatcher rather than assumed to be there.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            navController.navigate(PayanamRoutes.PASSPHRASE_UNLOCK) {
                launchSingleTop = true
            }
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
                        // This listener is answered on whichever thread performed the navigation that
                        // produced the destination, and that navigation can originate anywhere that holds
                        // a controller, so the guard pop joins them on the main dispatcher.
                        CoroutineScope(Dispatchers.Main.immediate).launch {
                            controller.popBackStack()
                        }
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
                                    Modifier
                                        .semantics {
                                            contentDescription = tabLabel
                                        }
                                        .testTag(TestTags.Nav.tab(tab.route)),
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = null,
                                    )
                                },
                                // `maxLines = 1`: the bar's fixed height clips a wrapped label mid-word.
                                label = { Text(tabLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            if (!backupFailureMessage.isNullOrBlank() && !shouldShowPassphraseUnlock && currentRoute != PayanamRoutes.PASSPHRASE_UNLOCK) {
                PayanamAlertDialog(
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
                    !shouldShowDatabaseInit && !shouldShowFocusModeOnboarding &&
                    !shouldShowSecurityStateUnavailable &&
                    !shouldShowOnboardingIntro
            // The startup gates are STATE: `startDestination` is read once at graph creation, so a gate
            // that advances without this effect leaves the user on the old screen. PASSPHRASE_UNLOCK is
            // excluded - it owns its own effect because it carries a return route.
            LaunchedEffect(shouldShowOnboardingIntro, currentResolvedRoute) {
                if (!shouldShowOnboardingIntro) return@LaunchedEffect
                if (currentResolvedRoute == PayanamRoutes.ONBOARDING_INTRO) return@LaunchedEffect
                // A first-composition effect runs before NavHost sets its graph, and navigating then
                // throws; an empty back stack means "not yet".
                if (navController.currentBackStackEntry == null) return@LaunchedEffect
                navController.navigate(PayanamRoutes.ONBOARDING_INTRO) {
                    popUpTo(0) { inclusive = true }
                }
            }

            LaunchedEffect(shouldShowSecurityStateUnavailable, currentResolvedRoute) {
                if (!shouldShowSecurityStateUnavailable) return@LaunchedEffect
                if (currentResolvedRoute == PayanamRoutes.SECURITY_STATE_UNAVAILABLE) return@LaunchedEffect
                if (navController.currentBackStackEntry == null) return@LaunchedEffect
                navController.navigate(PayanamRoutes.SECURITY_STATE_UNAVAILABLE) {
                    popUpTo(0) { inclusive = true }
                }
            }

            LaunchedEffect(
                shouldShowPassphraseSetup,
                shouldShowDatabaseInit,
                shouldShowFocusModeOnboarding,
                currentResolvedRoute,
                landingReady,
            ) {
                val route = currentResolvedRoute ?: return@LaunchedEffect
                if (shouldShowPassphraseUnlock || route == PayanamRoutes.PASSPHRASE_UNLOCK) {
                    return@LaunchedEffect
                }
                val raisedGate =
                    when {
                        shouldShowPassphraseSetup -> PayanamRoutes.PASSPHRASE_SETUP
                        shouldShowDatabaseInit -> PayanamRoutes.DATABASE_INIT
                        shouldShowFocusModeOnboarding -> PayanamRoutes.FOCUS_MODE_SELECTION
                        else -> null
                    }
                val destination =
                    when {
                        raisedGate != null && route != raisedGate -> {
                            raisedGate
                        }

                        // Only the init gate is answered here: the setup and focus destinations navigate from
                        // their own success callbacks, and a second navigation out of this effect would race
                        // the one they already perform.
                        raisedGate == null && route == PayanamRoutes.DATABASE_INIT && landingReady -> {
                            landingRoute
                        }

                        else -> {
                            null
                        }
                    } ?: return@LaunchedEffect
                logger.i(
                    "PayanamNavHost",
                    "Startup gate moved; the graph follows it",
                    mapOf("from" to route, "to" to destination),
                )
                // Same main-dispatcher rule as the other back-stack mutations in this file: the gate
                // moves the graph, and a graph is owned by the thread its composition runs on.
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    navController.navigate(destination) {
                        popUpTo(route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            val startDestination =
                remember(landingReady, landingRoute) {
                    if (landingReady) {
                        landingRoute
                    } else {
                        when {
                            // The refusal outranks every other gate (see the effect above): a store that
                            // cannot be read must not fall through to a first-run tour or an unlock
                            // screen it cannot honour.
                            shouldShowSecurityStateUnavailable -> PayanamRoutes.SECURITY_STATE_UNAVAILABLE

                            shouldShowPassphraseSetup -> PayanamRoutes.PASSPHRASE_SETUP

                            shouldShowPassphraseUnlock -> PayanamRoutes.PASSPHRASE_UNLOCK

                            shouldShowDatabaseInit -> PayanamRoutes.DATABASE_INIT

                            shouldShowOnboardingIntro -> PayanamRoutes.ONBOARDING_INTRO

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
                            // A screen's own success callback, not a composition scope: the platform's view
                            // model answers it on the dispatcher that model runs on, so the navigation is
                            // handed to the main dispatcher rather than assumed to be there.
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                // Same double-transition guard as the unlock callback below: clearing the
                                // setup gate changes `startDestination`, so the graph may already be on
                                // `nextRoute` and a second push would pop an entry that never reached CREATED.
                                if (navController.currentBackStackEntry?.resolveConcreteRoute() != nextRoute) {
                                    navController.navigate(nextRoute) {
                                        popUpTo(PayanamRoutes.PASSPHRASE_SETUP) { inclusive = true }
                                    }
                                }
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
                            // Answered from the database work, not the composition: navigating off the main
                            // thread throws `Method setCurrentState must be called on the main thread`.
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                // Do not navigate when the gate already moved the graph: clearing the flag
                                // changes `startDestination`, so a second push pops an entry before it
                                // reaches CREATED, which the lifecycle registry rejects.
                                if (navController.currentBackStackEntry?.resolveConcreteRoute() != nextRoute) {
                                    navController.navigate(nextRoute) {
                                        popUpTo(PayanamRoutes.PASSPHRASE_UNLOCK) { inclusive = true }
                                    }
                                }
                            }
                        },
                        onForgotPassphraseReset = {
                            logger.w("PayanamNavHost", "Forgot-passphrase reset completed; continuing into the database-init gate")
                            // The reset deleted the database and the credential, and both platforms answer
                            // that with the init gate, so continue there - it collects the passphrase and
                            // confirms the mandatory dimensions a replaced database needs again.
                            databaseInitFollowsReset = true
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                navController.navigate(PayanamRoutes.DATABASE_INIT) {
                                    popUpTo(PayanamRoutes.PASSPHRASE_UNLOCK) { inclusive = true }
                                }
                            }
                        },
                    )
                }
                composable(PayanamRoutes.PASSPHRASE_CHANGE) {
                    screens.passphraseChange(
                        nav = navActions,
                        onPassphraseChanged = {
                            logger.i("PayanamNavHost", "Passphrase changed successfully")
                            // Popping moves back-stack lifecycle state, so it belongs on the main
                            // dispatcher; on desktop this callback arrives on the interaction thread.
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                navController.popBackStack()
                            }
                            // The host's own outcome runs first, so a platform that must end this process can.
                            onRestartAfterPassphraseChange()
                        },
                    )
                }

                // Database initialization screen (shown only on first launch or when no DB)
                composable(PayanamRoutes.DATABASE_INIT) {
                    screens.databaseInit(
                        nav = navActions,
                        // The reset's continuation: open at the create form, not the tour or the chooser.
                        resumesAfterReset = databaseInitFollowsReset,
                        onDatabaseReady = {
                            logger.i(
                                "PayanamNavHost",
                                "Database init reported ready; delegating startup flow re-evaluation",
                                mapOf("from" to "DatabaseInit", "followedReset" to databaseInitFollowsReset),
                            )
                            databaseInitFollowsReset = false
                            onDatabaseReady()
                        },
                    )
                }

                // The first-run tour, reached the same way every other startup gate is.
                composable(PayanamRoutes.ONBOARDING_INTRO) {
                    screens.onboardingIntro(onFinished = onOnboardingIntroFinished)
                }

                // The fail-closed refusal. It is a destination rather than platform chrome so that a
                // platform that cannot read its passphrase store has exactly one surface to show, drawn
                // by the shared screen and reached the same way every other startup gate is.
                composable(PayanamRoutes.SECURITY_STATE_UNAVAILABLE) {
                    screens.securityStateUnavailable(
                        onSecurityResetRequested = onSecurityResetRequested,
                        onQuitRequested = onQuitRequested,
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
                            // Same main-dispatcher rule as the other screen callbacks: the preset arrives on
                            // the dispatcher that loaded it, and popping the gate mutates back-stack state.
                            CoroutineScope(Dispatchers.Main.immediate).launch {
                                navController.navigate(PayanamTab.Lenses.route) {
                                    popUpTo(PayanamRoutes.FOCUS_MODE_SELECTION) { inclusive = true }
                                }
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
                    // Only this destination reads the request: it is the screen that owns the dialog.
                    CompositionLocalProvider(LocalTaskStatusDialogRequest provides pendingTaskStatusRequest) {
                        screens.taskDetail(nav = navActions, taskId = taskId)
                    }
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
                    logger.i("PayanamNavHost", "Opening score detail", mapOf("type" to typeName, "dimensionKey" to key))
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
