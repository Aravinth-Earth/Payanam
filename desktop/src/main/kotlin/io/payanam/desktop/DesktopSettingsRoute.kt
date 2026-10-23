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
import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.TimeFormatOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.preferences.displayName
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.presentation.tasks.TaskFilter
import io.payanam.shared.settings.DesktopLanguage
import io.payanam.shared.settings.DesktopLaunchTaskFilter
import io.payanam.shared.settings.DesktopThemeMode
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.SettingsFoundationSnapshot

/**
 * The desktop-scoped scoring-model row. Declared, not shared: Android has no named scoring model (its
 * `ScoringConfig` row holds weights), so this value is the desktop's own - see the plan's Amendment.
 */
internal const val DESKTOP_SCORING_MODEL_KEY = "scoring_model"

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
    /** Picks a destination and writes a copy of the encrypted database there. */
    val onExportDatabase: () -> Unit,
    /** Picks a source, verifies it, and replaces the live database with it. */
    val onImportDatabase: () -> Unit,
    /** What the last transfer produced, or null before one has run. */
    val status: String? = null,
)

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun desktopSettingsRoute(
    snapshot: SettingsFoundationSnapshot,
    preferences: AppPreferencesState,
    unlockTimeoutMinutes: Int,
    scoringModel: String,
    writeFailure: String?,
    lifecycleState: DesktopLifecycleState,
    onSettingChanged: (String, String?) -> Unit,
    dataManagementCallbacks: DesktopDataManagementCallbacks,
    initialSection: DesktopSettingsSection = DesktopSettingsSection.APPEARANCE,
) {
    var expandedSection by remember(initialSection) { mutableStateOf<DesktopSettingsSection?>(initialSection) }

    desktopSettingsOverviewCard(
        preferences = preferences,
        expandedSection = expandedSection,
    )
    // Its own slot, above every section: a write that did not land is the one thing a user must not have to
    // go looking for. Cleared by the next successful write, never by navigation.
    writeFailure?.let { message ->
        desktopInfoCard(title = "Setting not saved", body = message)
    }

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
                    // The SHARED enums are the selection and the write; the label lookup is the desktop's
                    // (`ThemeModeOption.displayName` is its storage key, not a label). Declared: the shared
                    // option labels still live in the app module's resources, so moving them into `:core:ui`
                    // is what lets these label enums go.
                    desktopOptionGroup(
                        label = "Theme mode",
                        options =
                            ThemeModeOption.entries.map { mode ->
                                DesktopSettingsOption(
                                    label = DesktopThemeMode.fromStorageKey(mode.key).displayName,
                                    selected = preferences.themeMode == mode,
                                    onSelect = { onSettingChanged(AppPreferenceKeys.KEY_THEME_MODE, mode.key) },
                                )
                            },
                    )
                    desktopOptionGroup(
                        label = "Language",
                        options =
                            AppLanguageOption.entries.map { language ->
                                DesktopSettingsOption(
                                    label = DesktopLanguage.fromStorageKey(language.key).displayName,
                                    selected = preferences.appLanguage == language,
                                    onSelect = { onSettingChanged(AppPreferenceKeys.KEY_APP_LANGUAGE, language.key) },
                                )
                            },
                    )
                    desktopOptionGroup(
                        label = "Time format",
                        options =
                            TimeFormatOption.entries.map { format ->
                                DesktopSettingsOption(
                                    label = if (format.use24Hour) "24-hour clock" else "12-hour clock",
                                    selected = preferences.timeFormat == format,
                                    onSelect = { onSettingChanged(AppPreferenceKeys.KEY_TIME_FORMAT, format.key) },
                                )
                            },
                    )
                }

                DesktopSettingsSection.DEFAULT_LANDING -> {
                    desktopSectionSupportText(
                        "Keep the same mental model as Android: one clear first screen, with no extra explanation around it.",
                    )
                    // `tabVisibility` + `launchDestination` ARE the shared state (D4: no desktop divergence).
                    val launchRouteOptions =
                        DesktopTopLevelRoute.entries.filter { route ->
                            route.storageKey == preferences.launchDestination.route ||
                                preferences.tabVisibility[route.storageKey] == true
                        }
                    desktopOptionGroup(
                        label = "Launch surface",
                        options =
                            launchRouteOptions.map { route ->
                                DesktopSettingsOption(
                                    label = route.displayName,
                                    selected = preferences.launchDestination.route == route.storageKey,
                                    onSelect = {
                                        onSettingChanged(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE, route.storageKey)
                                    },
                                )
                            },
                    )
                    desktopOptionGroup(
                        label = "Launch task filter",
                        options =
                            TaskFilter.entries.map { filter ->
                                DesktopSettingsOption(
                                    label = DesktopLaunchTaskFilter.fromStorageKey(filter.key).displayName,
                                    selected = preferences.launchDestination.taskFilter == filter,
                                    onSelect = {
                                        onSettingChanged(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_TASK_FILTER, filter.key)
                                    },
                                )
                            },
                    )
                }

                DesktopSettingsSection.FOCUS_MODE -> {
                    desktopSectionSupportText("This section works best as quick visibility control, not as a separate feature explainer.")
                    // The coarse "enable preset" toggle is GONE (D2): Android has no such row, and the
                    // preset's own effect is the per-tab rows below, which are real `tab_visible_*` rows.
                    DesktopTopLevelRoute.entries.forEach { route ->
                        if (route != DesktopTopLevelRoute.SETTINGS) {
                            val visible = preferences.tabVisibility[route.storageKey] == true
                            desktopToggleRow(
                                label = "Show ${route.displayName}",
                                enabled = visible,
                                onToggle = {
                                    onSettingChanged(
                                        "${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}${route.storageKey}",
                                        (!visible).toString(),
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
                    // The coarse "habit timing shell" toggle and its derived "timing preset" mirror are GONE
                    // (D2): Android's row is `auto_track_habit_time`, which is what this now writes. The
                    // per-dimension rows are Android's too, and the desktop renders no dimension surface yet
                    // - a declared divergence, not a second control.
                    desktopToggleRow(
                        label = "Auto-track habit completion time",
                        enabled = preferences.autoTrackHabitTimeGlobal,
                        onToggle = {
                            onSettingChanged(
                                AppPreferenceKeys.KEY_AUTO_TRACK_HABIT_TIME,
                                (!preferences.autoTrackHabitTimeGlobal).toString(),
                            )
                        },
                    )
                }

                DesktopSettingsSection.TIME_INSIGHTS -> {
                    // The coarse summary toggle and its "insight density" mirror are GONE (D2): Android has
                    // no such rows. Android's real surface here is one key per chart, and rendering those 23
                    // rows is deliberately NOT part of this change - named as a follow-up rather than
                    // quietly leaving a control that writes nothing.
                    desktopSectionSupportText(
                        "Android controls this section one chart at a time. The desktop's chart rows are not " +
                            "rendered yet, so nothing here writes a value Android would read.",
                    )
                }

                DesktopSettingsSection.AUTO_BACKUP -> {
                    desktopToggleRow(
                        label = "Enable automatic backup",
                        enabled = preferences.autoBackupEnabled,
                        onToggle = {
                            onSettingChanged(
                                AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED,
                                (!preferences.autoBackupEnabled).toString(),
                            )
                        },
                    )
                    // These keys are Android's BackupInterval.key values VERBATIM
                    // (AppPreferencesViewModel.kt:177-185: 15m/30m/60m/2h/6h/12h/24h). A value written
                    // by the desktop must be readable by Android, so this is a parity contract rather
                    // than a display choice - do NOT invent keys like the fabricated "daily"/"weekly".
                    desktopOptionGroup(
                        label = "Backup frequency",
                        options =
                            listOf(
                                "Every 15 minutes" to "15m",
                                "Every 30 minutes" to "30m",
                                "Hourly" to "60m",
                                "Every 2 hours" to "2h",
                                "Every 6 hours" to "6h",
                                "Every 12 hours" to "12h",
                                "Daily" to "24h",
                            ).map { (label, key) ->
                                DesktopSettingsOption(
                                    label = label,
                                    selected = preferences.autoBackupInterval.key == key,
                                    onSelect = { onSettingChanged(AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL, key) },
                                )
                            },
                    )
                    desktopToggleRow(
                        label = "Rotate old backups",
                        enabled = preferences.backupRotationEnabled,
                        onToggle = {
                            onSettingChanged(
                                AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED,
                                (!preferences.backupRotationEnabled).toString(),
                            )
                        },
                    )
                    // 50 must stay in this list: it is Android's backupRotationCount default
                    // (AppPreferencesViewModel.kt:249), so omitting it would leave this control unable
                    // to display the persisted value on a fresh install.
                    desktopOptionGroup(
                        label = "Retention",
                        options =
                            listOf(
                                "Keep last 5 backups" to 5,
                                "Keep last 10 backups" to 10,
                                "Keep last 30 backups" to 30,
                                "Keep last 50 backups" to 50,
                            ).map { (label, count) ->
                                DesktopSettingsOption(
                                    label = label,
                                    selected = preferences.backupRotationCount == count,
                                    onSelect = { onSettingChanged(AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT, count.toString()) },
                                )
                            },
                    )
                    desktopSectionSupportText("Desktop backups should feel like normal product settings, not like filesystem diagnostics.")
                }

                DesktopSettingsSection.SCORING -> {
                    // A DECLARED desktop-only row (see the plan's Amendment): Android has no named scoring
                    // model - its `ScoringConfig` row holds weights, and its settings screen opens a
                    // weight-tuning view - so this picker keeps its own `scoring_model` row rather than
                    // pretending to be a preference Android reads.
                    desktopOptionGroup(
                        label = "Scoring model",
                        options =
                            listOf("Balanced" to "balanced", "Strict" to "strict", "Flexible" to "flexible").map { (label, key) ->
                                DesktopSettingsOption(
                                    label = label,
                                    selected = scoringModel == key,
                                    onSelect = { onSettingChanged(DESKTOP_SCORING_MODEL_KEY, key) },
                                )
                            },
                    )
                    desktopSectionSupportText("This should stay short and action-oriented, just like the Android scoring entry point.")
                }

                DesktopSettingsSection.DEBUG -> {
                    // Android's `debug_logging_enabled` row, and the desktop logger's gate follows it
                    // (Step 3) - so this control now moves something, instead of only recording a value.
                    desktopToggleRow(
                        label = "Enable debug logging",
                        enabled = preferences.debugLoggingEnabled,
                        onToggle = {
                            onSettingChanged(
                                AppPreferenceKeys.KEY_DEBUG_LOGGING_ENABLED,
                                (!preferences.debugLoggingEnabled).toString(),
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
                            // Android's nine choices, not four: Android can store any of 1..240, so a
                            // narrower list here means a timeout chosen on the phone is not merely
                            // unselected on the desktop - it is unselectable, and the first interaction
                            // would silently replace it with a value from this list.
                            listOf(
                                "1 minute" to 1,
                                "2 minutes" to 2,
                                "5 minutes" to 5,
                                "10 minutes" to 10,
                                "15 minutes" to 15,
                                "30 minutes" to 30,
                                "1 hour" to 60,
                                "2 hours" to 120,
                                "4 hours" to 240,
                            ).map { (label, minutes) ->
                                DesktopSettingsOption(
                                    label = label,
                                    selected = unlockTimeoutMinutes == minutes,
                                    onSelect = { onSettingChanged(AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES, minutes.toString()) },
                                )
                            },
                    )
                    desktopToggleRow(
                        label = "Biometric unlock",
                        enabled = false,
                        onToggle = { },
                        // Android-only capability: visible for section parity, but genuinely
                        // non-interactive (see desktopToggleRow) and never persisted.
                        interactive = false,
                    )
                    desktopSectionSupportText("Available on Android only")
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
                                "Present: ${lifecycleState.databaseExists} (${lifecycleState.databaseSizeKb} KB)\n" +
                                "Desktop settings, security, journal, notes, and task board state now live inside this database.",
                    )
                }

                DesktopSettingsSection.DATA_MANAGEMENT -> {
                    desktopSectionSupportText(
                        "The encrypted database file is the whole export: the same file the Android app " +
                            "opens, so one copy carries your data between the two apps.",
                    )
                    desktopActionRow(
                        label = "Database export",
                        actionLabel = "Export database",
                        onClick = { dataManagementCallbacks.onExportDatabase() },
                    )
                    desktopActionRow(
                        label = "Database import",
                        actionLabel = "Import database",
                        onClick = { dataManagementCallbacks.onImportDatabase() },
                    )
                    dataManagementCallbacks.status?.let { message ->
                        desktopInfoCard(
                            title = "Transfer status",
                            body = message,
                        )
                    }
                    desktopInfoCard(
                        title = "Local-first status",
                        body =
                            "Desktop database: ${lifecycleState.databaseFilePath}\n" +
                                "App data root: ${DesktopAppPaths.resolveRootDirectory()}\n" +
                                "Exports: ${lifecycleState.exportDirectoryPath}",
                    )
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
    preferences: AppPreferencesState,
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
                            "Expanded section ${expandedSection?.title ?: "none"}; launch route " +
                                DesktopTopLevelRoute.fromStorageKey(preferences.launchDestination.route).displayName
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
                    "Current setup: ${preferences.themeMode.displayName} theme, " +
                        "${DesktopLanguage.fromStorageKey(preferences.appLanguage.key).displayName} language, " +
                        "${DesktopTopLevelRoute.fromStorageKey(preferences.launchDestination.route).displayName} launch route",
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
    // Whether the chip is clickable. Display-only groups derive their `selected` value from an
    // adjacent toggle and have nothing to write, so they pass false rather than rendering as
    // clickable controls that do nothing when pressed.
    val interactive: Boolean = true,
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
                                }.then(
                                    if (option.interactive) {
                                        Modifier.clickable(onClick = option.onSelect)
                                    } else {
                                        Modifier
                                    },
                                ).padding(horizontal = 14.dp, vertical = 10.dp),
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
