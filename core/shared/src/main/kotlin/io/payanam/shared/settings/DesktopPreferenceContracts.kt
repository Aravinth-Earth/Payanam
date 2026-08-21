//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings
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
    const val SCHEMA_VERSION = 3
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
    fun defaultRouteVisibility(): Map<DesktopTopLevelRoute, Boolean> =
        DesktopTopLevelRoute.entries.associateWith { DEFAULT_ROUTE_VISIBLE }
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
