//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

enum class DesktopThemeMode(
    val storageKey: String,
    val displayName: String,
) {
    SYSTEM(storageKey = "system", displayName = "System"),
    LIGHT(storageKey = "light", displayName = "Light"),
    DARK(storageKey = "dark", displayName = "Dark"),
    ;

    companion object {
        fun fromStorageKey(key: String?): DesktopThemeMode = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

enum class DesktopLanguage(
    val storageKey: String,
    val displayName: String,
) {
    SYSTEM(storageKey = "system", displayName = "System"),
    ENGLISH(storageKey = "en", displayName = "English"),
    TAMIL(storageKey = "ta", displayName = "Tamil"),
    ;

    companion object {
        fun fromStorageKey(key: String?): DesktopLanguage = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

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
        fun fromStorageKey(key: String?): DesktopTopLevelRoute = entries.firstOrNull { it.storageKey == key } ?: SETTINGS
    }
}

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
    fun isRouteVisible(route: DesktopTopLevelRoute): Boolean = routeVisibility[route] ?: DesktopSettingsContracts.DEFAULT_ROUTE_VISIBLE

    fun visibleRoutes(): List<DesktopTopLevelRoute> = DesktopTopLevelRoute.entries.filter(::isRouteVisible)
}

object DesktopSettingsContracts {
    const val SCHEMA_VERSION = 3
    const val DEFAULT_ROUTE_VISIBLE = true

    fun defaultSnapshot(): DesktopSettingsSnapshot =
        DesktopSettingsSnapshot(
            routeVisibility = defaultRouteVisibility(),
        )

    fun defaultRouteVisibility(): Map<DesktopTopLevelRoute, Boolean> =
        DesktopTopLevelRoute.entries.associateWith { DEFAULT_ROUTE_VISIBLE }

    fun normalizeRouteVisibility(routeVisibility: Map<DesktopTopLevelRoute, Boolean>): Map<DesktopTopLevelRoute, Boolean> =
        DesktopTopLevelRoute.entries.associateWith { route ->
            if (route == DesktopTopLevelRoute.SETTINGS) {
                true
            } else {
                routeVisibility[route] ?: DEFAULT_ROUTE_VISIBLE
            }
        }

    fun routeVisibilityForPreset(preset: FocusModePreset): Map<DesktopTopLevelRoute, Boolean> =
        DesktopTopLevelRoute.entries.associateWith { route ->
            route == DesktopTopLevelRoute.SETTINGS || preset.visibleRoutes.contains(route)
        }
}
