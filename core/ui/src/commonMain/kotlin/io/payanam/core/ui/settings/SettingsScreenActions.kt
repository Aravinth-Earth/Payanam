//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.ui.graphics.Color
import io.payanam.shared.settings.FocusModePreset
import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.preferences.TimeFormatOption
import io.payanam.presentation.tasks.TaskFilter
import kotlin.jvm.JvmOverloads

/**
 * Every write the settings screen can make, and the few platform facts it reads, named once.
 *
 * WHY A HOLDER AND NOT TWENTY-SEVEN PARAMETERS: [SettingsScreenContent] drives thirteen sections, each
 * of which already reports its own writes as callbacks, and a parameter list that long cannot be read,
 * cannot be defaulted in the right order, and cannot be filled without leaving a gap silently. Grouping
 * them also gives the desktop one place to satisfy rather than one constructor call to get right.
 *
 * WHY THE PICKERS REPORT AN INTENT RATHER THAN RETURNING A FILE: the three rows that need a chosen
 * document are an export destination, a database source and a uHabits source. An Android result is a
 * `Uri`, a desktop result a path, so neither can be the return type of a shared signature. Each callback
 * therefore takes the MIME filter the picker should offer - reusing [IMPORT_PICKER_MIME], the same
 * constant [io.payanam.core.ui.database.DatabaseInitScreen] hands its own import button - and the platform
 * holds the handle it gets back. The document then reaches the rest of the flow through
 * [importSourceSelected] plus [onImportConfirmed], which is why the confirmation dialog is in shared code:
 * the prompt is one thing both platforms need identically, the document is not.
 */
class SettingsScreenActions @JvmOverloads constructor(
    // Appearance
    val onAppLanguageChange: (AppLanguageOption) -> Unit = {},
    val onThemeModeChange: (ThemeModeOption) -> Unit = {},
    val onFontFamilyChange: (FontFamilyOption) -> Unit = {},
    val onTimeFormatChange: (TimeFormatOption) -> Unit = {},
    // Default landing
    val onLaunchDestinationRouteChange: (String) -> Unit = {},
    val onLaunchDestinationTimeChange: () -> Unit = {},
    val onLaunchDestinationTasksChange: (TaskFilter?) -> Unit = {},
    /**
     * Whether the focus-mode card is offered at all. A build-time flag on Android, a preference on the
     * desktop, so it arrives as a value rather than a constant read inside shared code.
     */
    val focusModeSettingsEnabled: Boolean = true,
    /** Selects the minimal data-management card, which carries no file rows. */
    val minimalModeEnabled: Boolean = false,
    val onSetActivePreset: (FocusModePreset) -> Unit = {},
    val onSetTabVisibility: (String, Boolean) -> Unit = { _, _ -> },
    // Dimensions
    val onDimensionLabelChanged: (String, String) -> Unit = { _, _ -> },
    val onDimensionLabelReset: (String) -> Unit = {},
    val onDimensionColorChanged: (String, Color) -> Unit = { _, _ -> },
    val onDimensionIconChanged: (String, String) -> Unit = { _, _ -> },
    val onDimensionWeightChanged: (String, Double) -> Unit = { _, _ -> },
    val onDimensionVisibilityChanged: (String, Boolean) -> Unit = { _, _ -> },
    // Auto tracking
    val onAutoTrackHabitTimeGlobalChanged: (Boolean) -> Unit = {},
    val onAutoTrackDimensionPreferenceChanged: (String, Boolean) -> Unit = { _, _ -> },
    // Time insights
    val onChartVisibilityChanged: (String, Boolean) -> Unit = { _, _ -> },
    // Auto backup
    val onAutoBackupEnabledChanged: (Boolean) -> Unit = {},
    val onAutoBackupIntervalChanged: (BackupInterval) -> Unit = {},
    /**
     * Asks the platform to run (or cancel) the scheduled backup for this enable-state and interval.
     *
     * The worker is an OS component, so the shared screen cannot run it - but it is also the only thing
     * that knows WHICH interval the row was left on, so the toggle reports both rather than the platform
     * re-deriving it from state that may already have moved.
     */
    val onAutoBackupScheduleRequested: (Boolean, BackupInterval) -> Unit = { _, _ -> },
    val onManualBackupRequested: () -> Unit = {},
    val onBackupRotationEnabledChanged: (Boolean) -> Unit = {},
    val onBackupRotationCountChanged: (Int) -> Unit = {},
    val onAutoBackupFailureDismissed: () -> Unit = {},
    val manualBackupInProgress: Boolean = false,
    // Scoring
    val scoreSettingsEnabled: Boolean = false,
    val onNavigateToScoringConfig: () -> Unit = {},
    // Debug
    val onDebugLoggingEnabledChanged: (Boolean) -> Unit = {},
    // Security
    val onUnlockTimeoutChanged: (Int) -> Unit = {},
    val onBiometricUnlockDisabled: () -> Unit = {},
    val onEnableBiometricRequested: (String, (Boolean) -> Unit) -> Unit = { _, _ -> },
    val biometricActions: DatabaseSecurityBiometricActions? = null,
    // Database
    val onDeleteArtifact: (String) -> Unit = {},
    val onCleanStaleArtifacts: () -> Unit = {},
    // Data management - files
    val exportFileName: String = "payanam.db",
    val onExportDestinationRequested: (String) -> Unit = {},
    /** Takes [io.payanam.core.ui.components.IMPORT_PICKER_MIME], as the database-init import row does. */
    val onImportSourceRequested: (Array<String>) -> Unit = {},
    val onUhabitsSourceRequested: (Array<String>) -> Unit = {},
    val onImportConfirmed: () -> Unit = {},
    val onImportDismissed: () -> Unit = {},
    val onDeleteExportPromptDismissed: () -> Unit = {},
    val onDeleteAllDataRequested: () -> Unit = {},
    val onDeleteAllDataConfirmed: () -> Unit = {},
    val onImportPassphraseSubmitted: (String) -> Unit = {},
    val onImportPassphraseDismissed: () -> Unit = {},
    val onBulkMapImportedHabitsConfirmed: (String, String) -> Unit = { _, _ -> },
    val encryptionEnabled: Boolean = false,
    // Navigation
    val onNavigateToPassphraseChange: () -> Unit = {},
)

/**
 * The MIME filters the three document rows ask their pickers for.
 *
 * The uHabits filter is named here because it is the one row whose accepted types are not "any file": the
 * importer reads a SQLite database, and the list is the list the Android screen was already passing to
 * `OpenDocument`. It is a `val` on the companion rather than a default argument so a caller can reuse the
 * exact array it will hand the picker.
 */
val UHABITS_IMPORT_MIME_TYPES: Array<String> = arrayOf("application/x-sqlite3", "application/vnd.sqlite3", "application/octet-stream", "*/*")
