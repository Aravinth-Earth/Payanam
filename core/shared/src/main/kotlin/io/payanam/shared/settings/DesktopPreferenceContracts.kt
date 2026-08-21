//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

/**
 * DesktopThemeMode.
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
         * From storage key.
         */
        fun fromStorageKey(key: String?): DesktopThemeMode = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

/**
 * DesktopLanguage.
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
         * From storage key.
         */
        fun fromStorageKey(key: String?): DesktopLanguage = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

/**
 * DesktopTopLevelRoute.
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
         * From storage key.
         */
        fun fromStorageKey(key: String?): DesktopTopLevelRoute = entries.firstOrNull { it.storageKey == key } ?: SETTINGS
    }
}

/**
 * DesktopSettingsSnapshot.

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
) {
    /**
     * Is route visible.
     */
    fun isRouteVisible(route: DesktopTopLevelRoute): Boolean = routeVisibility[route] ?: DesktopSettingsContracts.DEFAULT_ROUTE_VISIBLE

    /**
     * Visible routes.
     */
    fun visibleRoutes(): List<DesktopTopLevelRoute> = DesktopTopLevelRoute.entries.filter(::isRouteVisible)
}

/**
 * DesktopSettingsContracts.
 */
object DesktopSettingsContracts {
    const val SCHEMA_VERSION = 3
    const val DEFAULT_ROUTE_VISIBLE = true

    /**
     * Default snapshot.
     */
    fun defaultSnapshot(): DesktopSettingsSnapshot =
        DesktopSettingsSnapshot(
            routeVisibility = defaultRouteVisibility(),
        )

    /**
     * Default route visibility.
     */
    fun defaultRouteVisibility(): Map<DesktopTopLevelRoute, Boolean> =
        DesktopTopLevelRoute.entries.associateWith { DEFAULT_ROUTE_VISIBLE }

    /**
     * Normalize route visibility.
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
     * Route visibility for preset.
     */
    fun routeVisibilityForPreset(preset: FocusModePreset): Map<DesktopTopLevelRoute, Boolean> =
        DesktopTopLevelRoute.entries.associateWith { route ->
            route == DesktopTopLevelRoute.SETTINGS || preset.visibleRoutes.contains(route)
        }
}
