//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MatchingDeclarationName")

//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.payanam.shared.settings.SettingsFoundationSnapshot

internal enum class DesktopSettingsSection(
    val title: String,
    val summary: String,
) {
    APPEARANCE("Appearance", "Theme, language, and reading comfort"),
    DEFAULT_LANDING("Default landing", "Choose where Payanam opens first"),
    FOCUS_MODE("Focus mode", "Control which top-level areas stay visible"),
    DIMENSIONS("Life dimensions", "Review the desktop dimension set and editing direction"),
    AUTO_TRACK_HABIT_TIME("Auto-track habit time", "Timing defaults for future habit tracking"),
    TIME_INSIGHTS("Time insights", "Control summaries and chart density"),
    AUTO_BACKUP("Auto backup", "Backup timing, destination, and retention"),
    SCORING("Scoring", "Choose the scoring style that feels right"),
    DEBUG("Debug", "Logs, diagnostics, and support export actions"),
    SECURITY("Security", "Unlock timing and sign-in protection"),
    DATABASE("Database", "Database stats, files, and local storage state"),
    DATA_MANAGEMENT("Data management", "Export, import, passphrase, and delete flows"),
    ABOUT("About", "Version, privacy, feedback, and reports"),
}

internal data class DesktopDataManagementCallbacks(
    val onExportLocalState: () -> DesktopDataHandoffSnapshot,
    val onImportLocalState: () -> DesktopDataHandoffSnapshot,
)

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun desktopSettingsRoute(
    snapshot: SettingsFoundationSnapshot,
    desktopSettings: DesktopSettingsSnapshot,
    lifecycleState: DesktopLifecycleState,
    onSettingsChanged: (DesktopSettingsSnapshot) -> Unit,
    dataManagementCallbacks: DesktopDataManagementCallbacks,
    initialSection: DesktopSettingsSection = DesktopSettingsSection.APPEARANCE,
) {
    var expandedSection by remember(initialSection) { mutableStateOf<DesktopSettingsSection?>(initialSection) }
    var desktopFocusModeEnabled by remember { mutableStateOf(true) }
    var desktopHabitTrackingEnabled by remember { mutableStateOf(false) }
    var desktopTimeInsightsEnabled by remember { mutableStateOf(true) }
    var desktopAutoBackupEnabled by remember { mutableStateOf(false) }
    var desktopBackupFrequency by remember { mutableStateOf("Daily") }
    var desktopBackupRetention by remember { mutableStateOf("Keep last 5 backups") }
    var desktopScoringModel by remember { mutableStateOf("Balanced") }
    var desktopBiometricEnabled by remember { mutableStateOf(false) }
    var desktopUnlockTimeout by remember { mutableStateOf("15 minutes") }
    var desktopExportMode by remember { mutableStateOf("Encrypted DB") }
    var desktopImportSource by remember { mutableStateOf("Encrypted DB handoff") }
    var desktopHandoffStatus by remember { mutableStateOf<String?>(null) }
    var includeTasks by remember { mutableStateOf(true) }
    var includeTimeEntries by remember { mutableStateOf(true) }
    var includeNotes by remember { mutableStateOf(true) }

    desktopSettingsOverviewCard(
        settings = desktopSettings,
        expandedSection = expandedSection,
    )

    DesktopSettingsSection.entries.forEach { section ->
        desktopSettingsAccordionCard(
            section = section,
            expanded = expandedSection == section,
            onToggleExpanded = {
                expandedSection = if (expandedSection == section) null else section
            },
        ) {
            when (section) {
                DesktopSettingsSection.APPEARANCE -> {
                    desktopSectionSupportText(
                        "Match the Android appearance section, but keep the desktop layout denser and quicker to scan.",
                    )
                    desktopOptionGroup(
                        label = "Theme mode",
                        options =
                            io.payanam.shared.settings.DesktopThemeMode.entries.map { mode ->
                                DesktopSettingsOption(
                                    label = mode.displayName,
                                    selected = desktopSettings.themeMode == mode,
                                    onSelect = { onSettingsChanged(desktopSettings.copy(themeMode = mode)) },
                                )
                            },
                    )
                    desktopOptionGroup(
                        label = "Language",
                        options =
                            io.payanam.shared.settings.DesktopLanguage.entries.map { language ->
                                DesktopSettingsOption(
                                    label = language.displayName,
                                    selected = desktopSettings.language == language,
                                    onSelect = { onSettingsChanged(desktopSettings.copy(language = language)) },
                                )
                            },
                    )
                }

                DesktopSettingsSection.DEFAULT_LANDING -> {
                    desktopSectionSupportText(
                        "Keep the same mental model as Android: one clear first screen, with no extra explanation around it.",
                    )
                    val launchRouteOptions =
                        DesktopTopLevelRoute.entries.filter { route ->
                            route == desktopSettings.launchRoute || desktopSettings.isRouteVisible(route)
                        }
                    desktopOptionGroup(
                        label = "Launch surface",
                        options =
                            launchRouteOptions.map { route ->
                                DesktopSettingsOption(
                                    label = route.displayName,
                                    selected = desktopSettings.launchRoute == route,
                                    onSelect = { onSettingsChanged(desktopSettings.copy(launchRoute = route)) },
                                )
                            },
                    )
                }

                DesktopSettingsSection.FOCUS_MODE -> {
                    desktopSectionSupportText("This section works best as quick visibility control, not as a separate feature explainer.")
                    desktopToggleRow(
                        label = "Enable focused navigation preset",
                        enabled = desktopFocusModeEnabled,
                        onToggle = { desktopFocusModeEnabled = !desktopFocusModeEnabled },
                    )
                    DesktopTopLevelRoute.entries.forEach { route ->
                        if (route != DesktopTopLevelRoute.SETTINGS) {
                            desktopToggleRow(
                                label = "Show ${route.displayName}",
                                enabled = desktopSettings.isRouteVisible(route),
                                onToggle = {
                                    onSettingsChanged(
                                        desktopSettings.copy(
                                            routeVisibility =
                                                desktopSettings.routeVisibility + (route to !desktopSettings.isRouteVisible(route)),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                DesktopSettingsSection.DIMENSIONS -> {
                    desktopSectionSupportText(
                        "Android already treats dimensions as editable product settings. " +
                            "Desktop should move in that direction instead of reading like a placeholder.",
                    )
                    listOf(
                        "Work" to "Deep work, delivery, and output focus",
                        "Health" to "Rest, food, movement, and energy",
                        "Learning" to "Reading, study, and reflection",
                    ).forEach { (title, body) ->
                        desktopInfoCard(title = title, body = body)
                    }
                }

                DesktopSettingsSection.AUTO_TRACK_HABIT_TIME -> {
                    desktopToggleRow(
                        label = "Enable habit timing shell",
                        enabled = desktopHabitTrackingEnabled,
                        onToggle = { desktopHabitTrackingEnabled = !desktopHabitTrackingEnabled },
                    )
                    desktopOptionGroup(
                        label = "Timing preset",
                        options =
                            listOf("Follow task board", "Manual review").map { option ->
                                DesktopSettingsOption(
                                    label = option,
                                    selected =
                                        if (desktopHabitTrackingEnabled) {
                                            option == "Follow task board"
                                        } else {
                                            option == "Manual review"
                                        },
                                    onSelect = { desktopHabitTrackingEnabled = option == "Follow task board" },
                                )
                            },
                    )
                }

                DesktopSettingsSection.TIME_INSIGHTS -> {
                    desktopToggleRow(
                        label = "Enable time insight summaries",
                        enabled = desktopTimeInsightsEnabled,
                        onToggle = { desktopTimeInsightsEnabled = !desktopTimeInsightsEnabled },
                    )
                    desktopOptionGroup(
                        label = "Insight density",
                        options =
                            listOf("Balanced", "Minimal").map { option ->
                                DesktopSettingsOption(
                                    label = option,
                                    selected = if (desktopTimeInsightsEnabled) option == "Balanced" else option == "Minimal",
                                    onSelect = { desktopTimeInsightsEnabled = option == "Balanced" },
                                )
                            },
                    )
                }

                DesktopSettingsSection.AUTO_BACKUP -> {
                    desktopToggleRow(
                        label = "Enable automatic backup",
                        enabled = desktopAutoBackupEnabled,
                        onToggle = { desktopAutoBackupEnabled = !desktopAutoBackupEnabled },
                    )
                    desktopOptionGroup(
                        label = "Backup frequency",
                        options =
                            listOf("Daily", "Weekly", "Monthly").map { option ->
                                DesktopSettingsOption(
                                    label = option,
                                    selected = desktopBackupFrequency == option,
                                    onSelect = { desktopBackupFrequency = option },
                                )
                            },
                    )
                    desktopOptionGroup(
                        label = "Retention",
                        options =
                            listOf("Keep last 5 backups", "Keep last 10 backups", "Keep last 30 backups").map { option ->
                                DesktopSettingsOption(
                                    label = option,
                                    selected = desktopBackupRetention == option,
                                    onSelect = { desktopBackupRetention = option },
                                )
                            },
                    )
                    desktopSectionSupportText("Desktop backups should feel like normal product settings, not like filesystem diagnostics.")
                }

                DesktopSettingsSection.SCORING -> {
                    desktopOptionGroup(
                        label = "Scoring model",
                        options =
                            listOf("Balanced", "Strict", "Flexible").map { option ->
                                DesktopSettingsOption(
                                    label = option,
                                    selected = desktopScoringModel == option,
                                    onSelect = { desktopScoringModel = option },
                                )
                            },
                    )
                    desktopSectionSupportText("This should stay short and action-oriented, just like the Android scoring entry point.")
                }

                DesktopSettingsSection.DEBUG -> {
                    desktopToggleRow(
                        label = "Enable session logging",
                        enabled = desktopSettings.sessionLoggingEnabled,
                        onToggle = {
                            onSettingsChanged(
                                desktopSettings.copy(sessionLoggingEnabled = !desktopSettings.sessionLoggingEnabled),
                            )
                        },
                    )
                    desktopInfoCard(
                        title = "Log locations",
                        body =
                            "Logs: ${DesktopAppPaths.resolveLogsDirectory()}\n" +
                                "Desktop database: ${lifecycleState.databaseFilePath}",
                    )
                    desktopInfoCard(
                        title = "Diagnostics actions",
                        body = "Export latest log, export all logs, and run diagnostics will live here in the final desktop app.",
                    )
                }

                DesktopSettingsSection.SECURITY -> {
                    desktopSectionSupportText(
                        "Security should read as a real preferences surface even before the deeper Windows-specific flows are wired.",
                    )
                    desktopOptionGroup(
                        label = "Unlock timeout",
                        options =
                            listOf("15 minutes", "30 minutes", "1 hour").map { option ->
                                DesktopSettingsOption(
                                    label = option,
                                    selected = desktopUnlockTimeout == option,
                                    onSelect = { desktopUnlockTimeout = option },
                                )
                            },
                    )
                    desktopToggleRow(
                        label = "Enable biometric unlock",
                        enabled = desktopBiometricEnabled,
                        onToggle = { desktopBiometricEnabled = !desktopBiometricEnabled },
                    )
                }

                DesktopSettingsSection.DATABASE -> {
                    desktopInfoCard(
                        title = "Database stats",
                        body =
                            "Tasks module shared: ${snapshot.moduleSelection.tasks}\n" +
                                "Time module shared: ${snapshot.moduleSelection.timeEntries}\n" +
                                "Notes module shared: ${snapshot.moduleSelection.notes}",
                    )
                    desktopInfoCard(
                        title = "Desktop storage",
                        body =
                            "Database directory: ${DesktopAppPaths.resolveDatabaseDirectory()}\n" +
                                "App data root: ${DesktopAppPaths.resolveRootDirectory()}",
                    )
                    desktopInfoCard(
                        title = "Desktop database state",
                        body =
                            "Path: ${lifecycleState.databaseFilePath}\n" +
                                "Initialized: ${lifecycleState.bootstrapSnapshot.databaseLifecycleReady}\n" +
                                "Desktop settings, security, journal, notes, and task board state now live inside this database.",
                    )
                }

                DesktopSettingsSection.DATA_MANAGEMENT -> {
                    desktopOptionGroup(
                        label = "Export mode",
                        options =
                            listOf("Encrypted DB", "Plaintext DB (one-time)").map { option ->
                                DesktopSettingsOption(
                                    label = option,
                                    selected = desktopExportMode == option,
                                    onSelect = { desktopExportMode = option },
                                )
                            },
                    )
                    desktopOptionGroup(
                        label = "Import source",
                        options =
                            listOf("Encrypted DB handoff", "Plaintext DB (legacy bridge)").map { option ->
                                DesktopSettingsOption(
                                    label = option,
                                    selected = desktopImportSource == option,
                                    onSelect = { desktopImportSource = option },
                                )
                            },
                    )
                    desktopToggleRow(
                        label = "Include tasks",
                        enabled = includeTasks,
                        onToggle = { includeTasks = !includeTasks },
                    )
                    desktopToggleRow(
                        label = "Include time entries",
                        enabled = includeTimeEntries,
                        onToggle = { includeTimeEntries = !includeTimeEntries },
                    )
                    desktopToggleRow(
                        label = "Include notes",
                        enabled = includeNotes,
                        onToggle = { includeNotes = !includeNotes },
                    )
                    desktopSectionSupportText("Keep the Android action grouping, but let the desktop controls stay compact and direct.")
                    desktopActionRow(
                        label = "Local handoff export",
                        actionLabel = "Create bundle",
                        onClick = {
                            desktopHandoffStatus = "Exported to ${dataManagementCallbacks.onExportLocalState().exportFilePath}"
                        },
                    )
                    desktopActionRow(
                        label = "Local handoff import",
                        actionLabel = "Restore latest bundle",
                        onClick = {
                            desktopHandoffStatus = "Imported from ${dataManagementCallbacks.onImportLocalState().exportFilePath}"
                        },
                    )
                    desktopInfoCard(
                        title = "Local-first status",
                        body =
                            "Desktop database: ${lifecycleState.databaseFilePath}\n" +
                                "App data root: ${DesktopAppPaths.resolveRootDirectory()}\n" +
                                "Exports: ${lifecycleState.exportDirectoryPath}",
                    )
                    desktopHandoffStatus?.let { message ->
                        desktopInfoCard(
                            title = "Handoff status",
                            body = message,
                        )
                    }
                }

                DesktopSettingsSection.ABOUT -> {
                    desktopInfoCard(
                        title = "Build",
                        body =
                            "Windows ${DesktopBuildInfo.PLATFORM_BUILD_NUMBER}\n" +
                                "Overall ${DesktopBuildInfo.OVERALL_BUILD_NUMBER}\n" +
                                DesktopBuildInfo.VERSION_DISPLAY_NAME,
                    )
                    desktopInfoCard(
                        title = "Privacy",
                        body =
                            "Local-first by default. Desktop data lives in your Windows app-data area, not in the install folder.",
                    )
                    desktopInfoCard(
                        title = "Feedback and reports",
                        body = "Feedback, GitHub, and reports entry points are represented here for parity review.",
                    )
                }
            }
        }
    }
}

@Composable
private fun desktopSettingsOverviewCard(
    settings: DesktopSettingsSnapshot,
    expandedSection: DesktopSettingsSection?,
) {
    Card(
        backgroundColor = desktopAccentCardColor(),
        shape = RoundedCornerShape(22.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Desktop settings overview"
                        stateDescription =
                            "Expanded section ${expandedSection?.title ?: "none"}; launch route ${settings.launchRoute.displayName}"
                    }.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text =
                    "Choose how Payanam should look, open, and protect your local data on this desktop.",
                style = MaterialTheme.typography.body1,
                color = desktopBodyTextColor(),
            )
            Text(
                text =
                    "Current setup: ${settings.themeMode.displayName} theme, " +
                        "${settings.language.displayName} language, " +
                        "${settings.launchRoute.displayName} launch route",
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
        }
    }
}

@Composable
private fun desktopSettingsAccordionCard(
    section: DesktopSettingsSection,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            role = Role.Button
                            contentDescription = "Toggle ${section.title} settings section"
                            selected = expanded
                            stateDescription =
                                if (expanded) {
                                    "${section.title} section expanded"
                                } else {
                                    "${section.title} section collapsed"
                                }
                        }.clickable { onToggleExpanded() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.h6,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = section.summary,
                        style = MaterialTheme.typography.body2,
                        color = desktopMutedTextColor(),
                    )
                }
                Text(
                    text = if (expanded) "Collapse" else "Expand",
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                    color = desktopBodyTextColor(),
                )
            }

            if (expanded) {
                content()
            }
        }
    }
}

private data class DesktopSettingsOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

@Composable
private fun desktopSectionSupportText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        color = desktopMutedTextColor(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun desktopOptionGroup(
    label: String,
    options: List<DesktopSettingsOption>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            options.forEach { option ->
                Card(
                    backgroundColor = if (option.selected) desktopSelectedCardColor() else desktopSurfaceColor(),
                    shape = RoundedCornerShape(14.dp),
                    elevation = 0.dp,
                ) {
                    Text(
                        text = option.label,
                        modifier =
                            Modifier
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "$label option ${option.label}"
                                    selected = option.selected
                                    stateDescription =
                                        if (option.selected) {
                                            "${option.label} selected"
                                        } else {
                                            "${option.label} not selected"
                                        }
                                }.clickable(onClick = option.onSelect)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.body2,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun desktopActionRow(
    label: String,
    actionLabel: String,
    onClick: () -> Unit,
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
                        contentDescription = "$label action"
                        stateDescription = actionLabel
                    }.clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.body1)
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.button,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun desktopInfoCard(
    title: String,
    body: String,
) {
    Card(
        backgroundColor = desktopSurfaceColor(),
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "$title details"
                        stateDescription = body.replace('\n', ' ')
                    }.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.SemiBold,
            )
            body.lines().forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.body2,
                    color = desktopMutedTextColor(),
                )
            }
        }
    }
}
