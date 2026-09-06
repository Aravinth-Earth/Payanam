//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.settings.DesktopLanguage
import io.payanam.shared.settings.DesktopSettingsContracts
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopThemeMode
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.FocusModePreset
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import java.util.Properties

internal class DesktopSettingsStore(
    preferencesDirectory: Path = DesktopAppPaths.resolvePreferencesDirectory(),
    private val persistenceDatabase: DesktopPersistenceDatabase =
        DesktopPersistenceDatabase(
            databaseDirectory = preferencesDirectory,
            preferencesDirectory = preferencesDirectory,
        ),
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    /**
     * Settings parsed from persisted properties with schema migrations
     * (legacy home-surface/theme fallbacks); defaults when absent.
     */
    fun loadSnapshot(): DesktopSettingsSnapshot {
        val storedPayload = persistenceDatabase.readEntry(STATE_ENTRY_KEY)
        if (storedPayload.isNullOrBlank()) {
            val defaultSnapshot = DesktopSettingsContracts.defaultSnapshot()
            logEvent(
                "DesktopSettingsStore.loadSnapshot",
                "Using default desktop settings snapshot",
                emptyMap(),
            )
            return defaultSnapshot
        }

        val properties = Properties()
        StringReader(storedPayload).use(properties::load)

        val schemaVersion = properties.getProperty(KEY_SCHEMA_VERSION)?.toIntOrNull() ?: DesktopSettingsContracts.SCHEMA_VERSION
        val hasLaunchRoute = !properties.getProperty(KEY_LAUNCH_ROUTE).isNullOrBlank()
        val legacyHomeSurface = properties.getProperty(KEY_HOME_SURFACE)
        val persistedThemeMode = DesktopThemeMode.fromStorageKey(properties.getProperty(KEY_THEME_MODE))
        val persistedLaunchRoute = DesktopTopLevelRoute.fromStorageKey(properties.getProperty(KEY_LAUNCH_ROUTE))
        val migratedThemeMode =
            if (schemaVersion < DesktopSettingsContracts.SCHEMA_VERSION && persistedThemeMode == DesktopThemeMode.SYSTEM) {
                DesktopThemeMode.DARK
            } else {
                persistedThemeMode
            }
        val activePreset = FocusModePreset.fromPresetId(properties.getProperty(KEY_ACTIVE_PRESET))
        val focusModeOnboardingCompleted = properties.getProperty(KEY_FOCUS_MODE_ONBOARDING_COMPLETED)?.toBooleanStrictOrNull() ?: false
        val migratedLaunchRoute =
            when {
                schemaVersion < DesktopSettingsContracts.SCHEMA_VERSION && (hasLaunchRoute || !legacyHomeSurface.isNullOrBlank()) -> {
                    DesktopTopLevelRoute.SETTINGS
                }

                hasLaunchRoute -> {
                    persistedLaunchRoute
                }

                else -> {
                    DesktopTopLevelRoute.SETTINGS
                }
            }
        val snapshot =
            DesktopSettingsSnapshot(
                schemaVersion = DesktopSettingsContracts.SCHEMA_VERSION,
                themeMode = migratedThemeMode,
                language = DesktopLanguage.fromStorageKey(properties.getProperty(KEY_LANGUAGE)),
                launchRoute = migratedLaunchRoute,
                activePreset = activePreset,
                focusModeOnboardingCompleted = focusModeOnboardingCompleted,
                routeVisibility = loadRouteVisibility(properties),
                sessionLoggingEnabled = properties.getProperty(KEY_SESSION_LOGGING_ENABLED)?.toBooleanStrictOrNull() ?: true,
            )
        logEvent(
            "DesktopSettingsStore.loadSnapshot",
            "Loaded desktop settings snapshot",
            mapOf(
                "themeMode" to snapshot.themeMode.storageKey,
                "language" to snapshot.language.storageKey,
                "launchRoute" to snapshot.launchRoute.storageKey,
                "visibleRouteCount" to snapshot.visibleRoutes().size,
            ),
        )
        return snapshot
    }
    /**
     * Serializes the settings snapshot (including per-route visibility) to
     * properties and persists them.
     */
    fun saveSnapshot(snapshot: DesktopSettingsSnapshot) {
        val properties =
            Properties().apply {
                setProperty(KEY_SCHEMA_VERSION, snapshot.schemaVersion.toString())
                setProperty(KEY_THEME_MODE, snapshot.themeMode.storageKey)
                setProperty(KEY_LANGUAGE, snapshot.language.storageKey)
                setProperty(KEY_LAUNCH_ROUTE, snapshot.launchRoute.storageKey)
                setProperty(KEY_ACTIVE_PRESET, snapshot.activePreset.presetId)
                setProperty(KEY_FOCUS_MODE_ONBOARDING_COMPLETED, snapshot.focusModeOnboardingCompleted.toString())
                setProperty(KEY_SESSION_LOGGING_ENABLED, snapshot.sessionLoggingEnabled.toString())
                DesktopTopLevelRoute.entries.forEach { route ->
                    setProperty(routeVisibilityKey(route), snapshot.isRouteVisible(route).toString())
                }
            }
        val payload =
            StringWriter().use { writer ->
                properties.store(writer, "Payanam Desktop Settings")
                writer.toString()
            }
        persistenceDatabase.writeEntry(STATE_ENTRY_KEY, payload)
        logEvent(
            "DesktopSettingsStore.saveSnapshot",
            "Saved desktop settings snapshot",
            mapOf(
                "themeMode" to snapshot.themeMode.storageKey,
                "language" to snapshot.language.storageKey,
                "launchRoute" to snapshot.launchRoute.storageKey,
                "visibleRouteCount" to snapshot.visibleRoutes().size,
            ),
        )
    }
    /**
     * Path of the database file holding the settings payload.
     */
    fun getSettingsFilePath(): Path = persistenceDatabase.getDatabaseFilePath()

    private fun loadRouteVisibility(properties: Properties): Map<DesktopTopLevelRoute, Boolean> =
        DesktopSettingsContracts.normalizeRouteVisibility(
            DesktopTopLevelRoute.entries
                .associateWith { route ->
                    properties.getProperty(routeVisibilityKey(route))?.toBooleanStrictOrNull()
                }.filterValues { it != null }
                .mapValues { (_, value) -> checkNotNull(value) },
        )

    private fun routeVisibilityKey(route: DesktopTopLevelRoute): String = "$KEY_ROUTE_VISIBILITY_PREFIX${route.storageKey}"

    internal companion object {
        internal const val STATE_ENTRY_KEY = "desktop/settings"
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LAUNCH_ROUTE = "launchRoute"
        private const val KEY_HOME_SURFACE = "homeSurface"
        private const val KEY_ROUTE_VISIBILITY_PREFIX = "routeVisible."
        private const val KEY_SESSION_LOGGING_ENABLED = "sessionLoggingEnabled"
        private const val KEY_ACTIVE_PRESET = "activePreset"
        private const val KEY_FOCUS_MODE_ONBOARDING_COMPLETED = "focusModeOnboardingCompleted"
    }
}
