//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

/**
 * Desktop task filter for the launch destination, persisted by [storageKey].
 * Mirrors Android's [io.payanam.ui.viewmodel.TaskFilter] for settings parity.
 *
 * This one adds [NONE] for "no filter" (Android models that as a null filter) and [ALL], and its
 * [Companion.fromStorageKey] falls back to [NONE]. It was deliberately DISTINCT from a second enum,
 * `io.payanam.shared.tasks.DesktopTaskFilter` (the task-board filter), which shared 7 identical
 * storage keys with it by design - they were previously the same simple name in one module, which is
 * how drift hides. That enum went with the desktop's JSON task board (deleted 2026-09-22, its stores
 * replaced by TaskRepository), so there is no second enum left to rename alongside this one.
 */
enum class DesktopLaunchTaskFilter(
    val storageKey: String,
    val displayName: String,
) {
    NONE(storageKey = "none", displayName = "No filter"),
    ALL(storageKey = "all", displayName = "All tasks"),
    ACTIVE(storageKey = "active", displayName = "Active"),
    TODAY(storageKey = "today", displayName = "Today"),
    OVERDUE(storageKey = "overdue", displayName = "Overdue"),
    FUTURE(storageKey = "future", displayName = "Future"),
    COMPLETED(storageKey = "completed", displayName = "Completed"),
    ARCHIVED(storageKey = "archived", displayName = "Archived"),
    NOT_ACTIVE(storageKey = "not_active", displayName = "Not active"),
    ;

    companion object {
        /**
         * Resolves a persisted [key] to its [DesktopLaunchTaskFilter], defaulting to
         * [NONE] when the key is null or unrecognized.
         */
        fun fromStorageKey(key: String?): DesktopLaunchTaskFilter = entries.firstOrNull { it.storageKey == key } ?: NONE
    }
}

/**
 * UI theme selection persisted on desktop (system / light / dark).
 */
enum class DesktopThemeMode(
    val storageKey: String,
    val displayName: String,
) {
    SYSTEM(storageKey = "system", displayName = "System"),
    LIGHT(storageKey = "light", displayName = "Light"),
    DARK(storageKey = "dark", displayName = "Dark"),
    ;

    companion object {
        /**
         * Resolves a persisted [key] to its [DesktopThemeMode], defaulting to
         * [SYSTEM] when the key is null or unrecognized.
         */
        fun fromStorageKey(key: String?): DesktopThemeMode = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

/**
 * UI language selection persisted on desktop (system / English / Tamil).
 */
enum class DesktopLanguage(
    val storageKey: String,
    val displayName: String,
) {
    SYSTEM(storageKey = "system", displayName = "System"),
    ENGLISH(storageKey = "en", displayName = "English"),
    TAMIL(storageKey = "ta", displayName = "Tamil"),
    ;

    companion object {
        /**
         * Resolves a persisted [key] to its [DesktopLanguage], defaulting to
         * [SYSTEM] when the key is null.
         */
        fun fromStorageKey(key: String?): DesktopLanguage = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

/**
 * Top-level navigation destinations for the desktop shell.
 */
enum class DesktopTopLevelRoute(
    val storageKey: String,
    val displayName: String,
) {
    TASKS(storageKey = "tasks", displayName = "Tasks"),
    HABITS(storageKey = "habits", displayName = "Habits"),
    TIME(storageKey = "time", displayName = "Time"),
    JOURNAL(storageKey = "journal", displayName = "Journal"),
    NOTES(storageKey = "notes", displayName = "Notes"),
    LENSES(storageKey = "lenses", displayName = "Lenses"),
    SETTINGS(storageKey = "settings", displayName = "Settings"),
    ;

    companion object {
        /**
         * Resolves a persisted [key] to its [DesktopTopLevelRoute], defaulting
         * to [SETTINGS] when the key is null or unrecognized.
         */
        fun fromStorageKey(key: String?): DesktopTopLevelRoute = entries.firstOrNull { it.storageKey == key } ?: SETTINGS
    }
}

/**
 * Serializable desktop settings state (theme, language, launch route, focus preset,
 * route visibility) for the desktop<->mobile sync.
 */
data class DesktopSettingsSnapshot(
    val schemaVersion: Int = DesktopSettingsContracts.SCHEMA_VERSION,
    val themeMode: DesktopThemeMode = DesktopThemeMode.DARK,
    val language: DesktopLanguage = DesktopLanguage.SYSTEM,
    val launchRoute: DesktopTopLevelRoute = DesktopTopLevelRoute.SETTINGS,
    val activePreset: FocusModePreset = FocusModePreset.FULL_SUITE,
    val focusModeOnboardingCompleted: Boolean = false,
    val routeVisibility: Map<DesktopTopLevelRoute, Boolean> = DesktopSettingsContracts.defaultRouteVisibility(),
    val sessionLoggingEnabled: Boolean = true,
    // Scope A: controls that previously lived as remember state in DesktopSettingsRoute
    val focusModeEnabled: Boolean = true,
    val habitTrackingEnabled: Boolean = false,
    val timeInsightsEnabled: Boolean = true,
    val autoBackupEnabled: Boolean = false,
    val backupFrequency: String = "60m",
    // NOTE: deliberately NO separate retention field. Android has exactly ONE setting here -
    // `backupRotationCount: Int` (AppPreferencesViewModel.kt:249) - so the desktop UI's retention
    // option labels map onto Scope B's `backupRotationCount` below rather than persisting a second,
    // divergent copy of the same value.
    val scoringModel: String = "balanced",
    // Session lock timeout in minutes. Android has no literal default: `SettingsViewModel.syncTimeoutFromDb`
    // DERIVES it as 2x the auto-backup interval when the `session_timeout_minutes` row is absent, which with
    // the defaults is 2 x 60 = 120, then mirrors it into the security preferences that enforce the lock. The
    // security preferences' own fallback (10 minutes) only covers the window before that sync runs, and the
    // manager clamps whatever it is given to 1..240. 15 is an option, not the default.
    val unlockTimeout: Int = 120,
    // REMOVED: exportMode, importSource, includeTasks, includeTimeEntries, includeNotes. All five existed
    // only to configure the retired zip/"plaintext bridge" handoff, and Android has none of them - the
    // export is a copy of the encrypted database, so there is no mode to choose and no module to include.
    // Scope B: genuinely missing desktop settings
    val timeFormat: String = "24h",
    val launchTaskFilter: DesktopLaunchTaskFilter = DesktopLaunchTaskFilter.NONE,
    val backupRotationEnabled: Boolean = false,
    val backupRotationCount: Int = 50,
) {
    /**
     * Whether [route] is currently shown in the desktop nav rail.
     */
    fun isRouteVisible(route: DesktopTopLevelRoute): Boolean = routeVisibility[route] ?: DesktopSettingsContracts.DEFAULT_ROUTE_VISIBLE

    /**
     * Returns all currently-visible top-level routes.
     */
    fun visibleRoutes(): List<DesktopTopLevelRoute> = DesktopTopLevelRoute.entries.filter(::isRouteVisible)
}

object DesktopSettingsContracts {
    const val SCHEMA_VERSION = 4
    const val DEFAULT_ROUTE_VISIBLE = true

    /**
     * Returns a settings snapshot populated with defaults (dark theme, all routes visible).
     */
    fun defaultSnapshot(): DesktopSettingsSnapshot =
        DesktopSettingsSnapshot(
            routeVisibility = defaultRouteVisibility(),
        )

    /**
     * Returns a map with every route marked visible by default.
     */
    fun defaultRouteVisibility(): Map<DesktopTopLevelRoute, Boolean> = DesktopTopLevelRoute.entries.associateWith { DEFAULT_ROUTE_VISIBLE }

    /**
     * Fills missing routes (SETTINGS is forced visible) from [routeVisibility].
     */
    fun normalizeRouteVisibility(routeVisibility: Map<DesktopTopLevelRoute, Boolean>): Map<DesktopTopLevelRoute, Boolean> =
        DesktopTopLevelRoute.entries.associateWith { route ->
            if (route == DesktopTopLevelRoute.SETTINGS) {
                true
            } else {
                routeVisibility[route] ?: DEFAULT_ROUTE_VISIBLE
            }
        }

    /**
     * Returns the route-visibility map implied by [preset] (SETTINGS always visible).
     */
    fun routeVisibilityForPreset(preset: FocusModePreset): Map<DesktopTopLevelRoute, Boolean> =
        DesktopTopLevelRoute.entries.associateWith { route ->
            route == DesktopTopLevelRoute.SETTINGS || preset.visibleRoutes.contains(route)
        }
}
