//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.FoundationArea
import io.payanam.shared.settings.FoundationReadiness
import io.payanam.shared.settings.SettingsFoundationContracts
import io.payanam.shared.settings.SettingsFoundationSnapshot
import io.payanam.shared.transfer.DataModuleSelection

@Composable
internal fun desktopApp() {
    val sessionLogger = remember { DesktopSessionLogger.getInstance() }
    val stores = rememberDesktopPersistenceStores(sessionLogger)
    val rememberedState = rememberDesktopMutableState(stores)
    var activeRoute by remember {
        androidx.compose.runtime.mutableStateOf(DesktopTopLevelRoute.SETTINGS)
    }
    val models = rememberDesktopAppModels(stores = stores, rememberedState = rememberedState, activeRoute = activeRoute)
    val startupLogState =
        remember(models.startupSnapshot, models.startupMode) {
            mapOf(
                "launchRoute" to models.startupSnapshot.launchRoute.storageKey,
                "readyChecks" to models.startupSnapshot.readyChecks(),
                "totalChecks" to models.startupSnapshot.checks.size,
                "requiresAttention" to models.startupSnapshot.requiresAttention(),
                "startupMode" to models.startupMode.name,
            )
        }
    LaunchedEffect(startupLogState) {
        sessionLogger.i(
            source = "DesktopApp.desktopStartupState",
            message = "Desktop startup state resolved",
            data = startupLogState,
        )
    }

    desktopStateEffects(
        snapshot = models.shellRenderState.foundationSnapshot,
        activeRoute = activeRoute,
        startupMode = models.startupMode,
        lifecycleState = models.desktopLifecycleState,
        navigationModel = models.navigationModel,
        onRouteReset = { activeRoute = it },
        sessionLogger = sessionLogger,
    )
    val callbacks =
        buildDesktopShellCallbacks(
            stores = stores,
            rememberedState = rememberedState,
        ) { route ->
            activeRoute = route
        }
    if (models.startupMode == DesktopStartupMode.Ready) {
        desktopShellSurface(
            state = models.shellRenderState,
            sessionLogger = sessionLogger,
            callbacks = callbacks,
        )
    } else {
        desktopStartupGateSurface(
            snapshot = models.startupSnapshot,
            startupMode = models.startupMode,
            runtimeState = models.startupRuntimeState,
            databaseSnapshot = models.databaseSnapshot,
            callbacks = callbacks,
            sessionLogger = sessionLogger,
        )
    }
}

@Composable
private fun desktopShellSurface(
    state: DesktopShellRenderState,
    sessionLogger: DesktopSessionLogger,
    callbacks: DesktopShellCallbacks,
) {
    desktopRootSurface {
        Row(modifier = Modifier.fillMaxSize()) {
            desktopNavigationRail(
                activeRoute = state.activeRoute,
                routes = state.navigationModel.primaryRoutes,
                onNavigate = { route ->
                    callbacks.onRouteSelected(route)
                    sessionLogger.i(
                        source = "DesktopApp.desktopNavigationRail",
                        message = "Desktop route selected",
                        data = mapOf("route" to route.storageKey),
                    )
                },
            )
            Box(modifier = Modifier.weight(1f)) {
                desktopContentPane(
                    state = state,
                    callbacks = callbacks,
                )
            }
        }
    }
}

@Composable
private fun desktopStateEffects(
    snapshot: SettingsFoundationSnapshot,
    activeRoute: DesktopTopLevelRoute,
    startupMode: DesktopStartupMode,
    lifecycleState: DesktopLifecycleState,
    navigationModel: DesktopNavigationModel,
    onRouteReset: (DesktopTopLevelRoute) -> Unit,
    sessionLogger: DesktopSessionLogger,
) {
    LaunchedEffect(snapshot.schemaVersion, activeRoute.storageKey) {
        sessionLogger.i(
            source = "DesktopApp.desktopApp",
            message = "Desktop app state rendered",
            data =
                mapOf(
                    "schemaVersion" to snapshot.schemaVersion,
                    "activeRoute" to activeRoute.storageKey,
                    "startupGateActive" to (startupMode != DesktopStartupMode.Ready),
                    "startupMode" to startupMode.name,
                    "sharedReadyAreas" to snapshot.areasWithStatus(FoundationReadiness.SharedReady),
                    "extractionNextAreas" to snapshot.areasWithStatus(FoundationReadiness.ExtractionNext),
                    "androidOnlyAreas" to snapshot.areasWithStatus(FoundationReadiness.AndroidOnly),
                ),
        )
    }
    LaunchedEffect(lifecycleState.desktopSettings) {
        sessionLogger.i(
            source = "DesktopApp.desktopSettings",
            message = "Desktop settings state active",
            data =
                mapOf(
                    "themeMode" to lifecycleState.desktopSettings.themeMode.storageKey,
                    "language" to lifecycleState.desktopSettings.language.storageKey,
                    "launchRoute" to lifecycleState.desktopSettings.launchRoute.storageKey,
                    "visibleRouteCount" to lifecycleState.desktopSettings.visibleRoutes().size,
                ),
        )
    }
    LaunchedEffect(lifecycleState.bootstrapSnapshot) {
        sessionLogger.i(
            source = "DesktopApp.desktopBootstrap",
            message = "Desktop bootstrap state active",
            data =
                mapOf(
                    "databaseLifecycleReady" to lifecycleState.bootstrapSnapshot.databaseLifecycleReady,
                    "hasLastStartupCompletedAt" to
                        (lifecycleState.bootstrapSnapshot.lastStartupCompletedAtEpochMillis != null),
                    "lastLaunchRoute" to lifecycleState.bootstrapSnapshot.lastLaunchRouteStorageKey,
                ),
        )
    }
    LaunchedEffect(startupMode) {
        sessionLogger.i(
            source = "DesktopApp.desktopStartupMode",
            message = "Desktop startup mode active",
            data = mapOf("startupMode" to startupMode.name),
        )
    }
    LaunchedEffect(navigationModel.primaryRoutes, navigationModel.launchRoute) {
        if (activeRoute !in navigationModel.primaryRoutes) {
            onRouteReset(navigationModel.launchRoute)
        }
    }
}

@Composable
internal fun desktopRootSurface(content: @Composable () -> Unit) {
    val desktopColors = desktopColorPalette()
    MaterialTheme(colors = desktopColors.materialColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = desktopColors.background,
        ) {
            content()
        }
    }
}

@Composable
private fun desktopNavigationRail(
    activeRoute: DesktopTopLevelRoute,
    routes: List<DesktopTopLevelRoute>,
    onNavigate: (DesktopTopLevelRoute) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(desktopChromeBackgroundColor())
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Payanam",
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Local-first desktop",
            style = MaterialTheme.typography.body2,
            color = desktopMutedTextColor(),
        )
        routes.forEach { route ->
            Card(
                backgroundColor =
                    if (route == activeRoute) {
                        desktopSelectedCardColor()
                    } else {
                        desktopCardColor()
                    },
                shape = RoundedCornerShape(16.dp),
                elevation = 0.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics {
                                role = Role.Button
                                contentDescription = "Navigate to ${route.displayName}"
                                selected = route == activeRoute
                                stateDescription =
                                    if (route == activeRoute) {
                                        "${route.displayName} route selected"
                                    } else {
                                        "${route.displayName} route not selected"
                                    }
                            }.clickable { onNavigate(route) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = route.displayName,
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = route.summary(),
                        style = MaterialTheme.typography.caption,
                        color = desktopMutedTextColor(),
                    )
                }
            }
        }
    }
}

@Composable
private fun desktopContentPane(
    state: DesktopShellRenderState,
    callbacks: DesktopShellCallbacks,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Desktop content pane for ${state.activeRoute.displayName}" }
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        desktopHeader(activeRoute = state.activeRoute)
        when (state.activeRoute) {
            DesktopTopLevelRoute.SETTINGS -> {
                desktopSettingsRoute(
                    snapshot = state.foundationSnapshot,
                    desktopSettings = state.desktopSettings,
                    lifecycleState = state.desktopLifecycleState,
                    onSettingsChanged = callbacks.onSettingsChanged,
                    dataManagementCallbacks =
                        DesktopDataManagementCallbacks(
                            onExportLocalState = callbacks.onExportLocalState,
                            onImportLocalState = callbacks.onImportLocalState,
                        ),
                )
            }

            DesktopTopLevelRoute.TASKS -> {
                desktopTasksRoute(
                    snapshot = state.taskBoardSnapshot,
                    onSnapshotChanged = callbacks.onTaskBoardChanged,
                )
            }

            DesktopTopLevelRoute.TIME -> {
                desktopTimeRoute()
            }

            DesktopTopLevelRoute.NOTES -> {
                desktopNotesRoute(
                    state = state.notesState,
                    onCreateNote = callbacks.onCreateNote,
                    onUpdateNote = callbacks.onUpdateNote,
                    onDeleteNote = callbacks.onDeleteNote,
                )
            }

            DesktopTopLevelRoute.JOURNAL -> {
                desktopJournalRoute(
                    state = state.journalState,
                    onSelectDate = callbacks.onJournalDateSelected,
                    onSaveOverallResponse = callbacks.onJournalOverallResponseChanged,
                    onSaveDimensionResponse = callbacks.onJournalDimensionResponseChanged,
                )
            }

            DesktopTopLevelRoute.HABITS -> {
                desktopHabitsRoute(
                    snapshot = state.taskBoardSnapshot,
                    onSnapshotChanged = callbacks.onTaskBoardChanged,
                )
            }

            DesktopTopLevelRoute.LENSES -> {
                desktopLensesRoute()
            }
        }
    }
}

@Composable
private fun desktopHeader(activeRoute: DesktopTopLevelRoute) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = activeRoute.displayName,
            style = MaterialTheme.typography.h4,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = activeRoute.summary(),
            style = MaterialTheme.typography.body1,
            color = desktopBodyTextColor(),
        )
    }
}

@Composable
internal fun desktopSettingsCard(
    settings: DesktopSettingsSnapshot,
    onSettingsChanged: (DesktopSettingsSnapshot) -> Unit,
) {
    Card(
        backgroundColor = desktopAccentCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Desktop settings",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Backed by the local desktop database.",
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            desktopChoiceRow(
                label = "Theme mode",
                value = settings.themeMode.displayName,
                onAdvance = {
                    onSettingsChanged(settings.copy(themeMode = settings.themeMode.nextDesktopOption()))
                },
            )
            desktopChoiceRow(
                label = "Language",
                value = settings.language.displayName,
                onAdvance = {
                    onSettingsChanged(settings.copy(language = settings.language.nextDesktopOption()))
                },
            )
            desktopChoiceRow(
                label = "Launch surface",
                value = settings.launchRoute.displayName,
                onAdvance = {
                    onSettingsChanged(settings.copy(launchRoute = settings.launchRoute.nextDesktopOption()))
                },
            )
            DesktopTopLevelRoute.entries.forEach { route ->
                if (route != DesktopTopLevelRoute.SETTINGS) {
                    desktopToggleRow(
                        label = "${route.displayName} tab",
                        enabled = settings.isRouteVisible(route),
                        onToggle = {
                            onSettingsChanged(
                                settings.copy(
                                    routeVisibility =
                                        settings.routeVisibility + (route to !settings.isRouteVisible(route)),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun statusBanner(snapshot: SettingsFoundationSnapshot) {
    Card(
        backgroundColor = desktopBannerCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Shared desktop foundation v${snapshot.schemaVersion}",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    "${DesktopBuildInfo.VERSION_DISPLAY_NAME} | " +
                        "${snapshot.areasWithStatus(FoundationReadiness.SharedReady)} shared-ready, " +
                        "${snapshot.areasWithStatus(FoundationReadiness.ExtractionNext)} extraction-next, " +
                        "${snapshot.areasWithStatus(FoundationReadiness.AndroidOnly)} Android-only areas",
                style = MaterialTheme.typography.body2,
            )
        }
    }
}

@Composable
internal fun featureGrid(cards: List<FoundationArea>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.forEach { card ->
            Card(
                backgroundColor = desktopSurfaceColor(),
                shape = RoundedCornerShape(18.dp),
                elevation = 0.dp,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .background(card.status.swatch(), RoundedCornerShape(50)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = card.title,
                            style = MaterialTheme.typography.h6,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = card.summary,
                            style = MaterialTheme.typography.body2,
                            color = desktopMutedTextColor(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun selectionPreview(
    selection: DataModuleSelection,
    sessionLogger: DesktopSessionLogger,
    onSelectionChanged: (DataModuleSelection) -> Unit,
) {
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Desktop-safe shared model check",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            desktopToggleRow(
                label = "Tasks",
                enabled = selection.tasks,
                onToggle = {
                    val nextSelection = selection.copy(tasks = !selection.tasks)
                    sessionLogger.i(
                        source = "DesktopApp.selectionPreview",
                        message = "Toggled module selection",
                        data = mapOf("module" to "tasks", "enabled" to nextSelection.tasks),
                    )
                    onSelectionChanged(nextSelection)
                },
            )
            desktopToggleRow(
                label = "Time entries",
                enabled = selection.timeEntries,
                onToggle = {
                    val nextSelection = selection.copy(timeEntries = !selection.timeEntries)
                    sessionLogger.i(
                        source = "DesktopApp.selectionPreview",
                        message = "Toggled module selection",
                        data = mapOf("module" to "timeEntries", "enabled" to nextSelection.timeEntries),
                    )
                    onSelectionChanged(nextSelection)
                },
            )
            desktopToggleRow(
                label = "Notes",
                enabled = selection.notes,
                onToggle = {
                    val nextSelection = selection.copy(notes = !selection.notes)
                    sessionLogger.i(
                        source = "DesktopApp.selectionPreview",
                        message = "Toggled module selection",
                        data = mapOf("module" to "notes", "enabled" to nextSelection.notes),
                    )
                    onSelectionChanged(nextSelection)
                },
            )
        }
    }
}

@Composable
internal fun desktopToggleRow(
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        backgroundColor = if (enabled) desktopSelectedCardColor() else desktopSurfaceColor(),
        shape = RoundedCornerShape(14.dp),
        elevation = 0.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        contentDescription = "$label toggle"
                        stateDescription = if (enabled) "$label enabled" else "$label disabled"
                    }.clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.body1)
            Text(
                text = if (enabled) "On" else "Off",
                style = MaterialTheme.typography.button,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun desktopChoiceRow(
    label: String,
    value: String,
    onAdvance: () -> Unit,
) {
    Card(
        backgroundColor = desktopSurfaceColor(),
        shape = RoundedCornerShape(14.dp),
        elevation = 0.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        contentDescription = "$label choice"
                        stateDescription = "$label currently $value"
                    }.clickable(onClick = onAdvance)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.body1)
            Text(
                text = value,
                style = MaterialTheme.typography.button,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
/**
 * Performs the desktop foundation snapshot.
 */
fun desktopFoundationSnapshot(selection: DataModuleSelection): SettingsFoundationSnapshot = SettingsFoundationContracts.snapshot(selection)
