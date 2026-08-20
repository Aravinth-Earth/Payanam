//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

/**
 * DesktopThemeMode.
 */
enum class DesktopThemeMode(
    /** Storage key. */
    val storageKey: String,
    /** Display name. */
    val displayName: String,
) {
    /** S y s t e m. */
    SYSTEM(storageKey = "system", displayName = "System"),
    /** L i g h t. */
    LIGHT(storageKey = "light", displayName = "Light"),
    /** D a r k. */
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
    /** Storage key. */
    val storageKey: String,
    /** Display name. */
    val displayName: String,
) {
    /** S y s t e m. */
    SYSTEM(storageKey = "system", displayName = "System"),
    /** E n g l i s h. */
    ENGLISH(storageKey = "en", displayName = "English"),
    /** T a m i l. */
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
    /** Storage key. */
    val storageKey: String,
    /** Display name. */
    val displayName: String,
) {
    /** T a s k s. */
    TASKS(storageKey = "tasks", displayName = "Tasks"),
    /** H a b i t s. */
    HABITS(storageKey = "habits", displayName = "Habits"),
    /** T i m e. */
    TIME(storageKey = "time", displayName = "Time"),
    /** J o u r n a l. */
    JOURNAL(storageKey = "journal", displayName = "Journal"),
    /** N o t e s. */
    NOTES(storageKey = "notes", displayName = "Notes"),
    /** L e n s e s. */
    LENSES(storageKey = "lenses", displayName = "Lenses"),
    /** S e t t i n g s. */
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
    /** Schema version. */
    val schemaVersion: Int = DesktopSettingsContracts.SCHEMA_VERSION,
    /** Theme mode. */
    val themeMode: DesktopThemeMode = DesktopThemeMode.DARK,
    /** Language. */
    val language: DesktopLanguage = DesktopLanguage.SYSTEM,
    /** Launch route. */
    val launchRoute: DesktopTopLevelRoute = DesktopTopLevelRoute.SETTINGS,
    /** Active preset. */
    val activePreset: FocusModePreset = FocusModePreset.FULL_SUITE,
    /** Focus mode onboarding completed. */
    val focusModeOnboardingCompleted: Boolean = false,
    /** Route visibility. */
    val routeVisibility: Map<DesktopTopLevelRoute, Boolean> = DesktopSettingsContracts.defaultRouteVisibility(),
    /** Session logging enabled. */
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
    /** S c h e m a  v e r s i o n. */
    const val SCHEMA_VERSION = 3
    /** D e f a u l t  r o u t e  v i s i b l e. */
    const val DEFAULT_ROUTE_VISIBLE = true

    /**
     * Default snapshot.
     */
    fun defaultSnapshot(): DesktopSettingsSnapshot =
        /** Desktop settings snapshot. */
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
            /** If. */
            if (route == DesktopTopLevelRoute.SETTINGS) {
                /** True. */
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
