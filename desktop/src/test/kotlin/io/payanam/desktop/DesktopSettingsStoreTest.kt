//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopLanguage
import io.payanam.shared.settings.DesktopSettingsContracts
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopThemeMode
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
/**
 * Provides the desktop settings store test.
 */
class DesktopSettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `load snapshot falls back to defaults when file is missing`() {
        val store = DesktopSettingsStore(preferencesDirectory = temporaryFolder.newFolder("prefs-default").toPath())

        val snapshot = store.loadSnapshot()

        assertThat(snapshot).isEqualTo(DesktopSettingsContracts.defaultSnapshot())
    }

    @Test
    fun `save snapshot persists desktop settings for reload`() {
        val preferencesDirectory = temporaryFolder.newFolder("prefs-persisted").toPath()
        val persistenceDatabase =
            DesktopPersistenceDatabase(
                databaseDirectory = preferencesDirectory,
                preferencesDirectory = preferencesDirectory,
            )
        val store = DesktopSettingsStore(persistenceDatabase = persistenceDatabase)
        val savedSnapshot =
            DesktopSettingsSnapshot(
                themeMode = DesktopThemeMode.DARK,
                language = DesktopLanguage.TAMIL,
                launchRoute = DesktopTopLevelRoute.TASKS,
                routeVisibility =
                    mapOf(
                        DesktopTopLevelRoute.TASKS to true,
                        DesktopTopLevelRoute.HABITS to false,
                        DesktopTopLevelRoute.TIME to true,
                        DesktopTopLevelRoute.JOURNAL to true,
                        DesktopTopLevelRoute.NOTES to true,
                        DesktopTopLevelRoute.LENSES to false,
                        DesktopTopLevelRoute.SETTINGS to true,
                    ),
                sessionLoggingEnabled = false,
            )

        store.saveSnapshot(savedSnapshot)

        val reloadedSnapshot = store.loadSnapshot()
        val settingsEntryPayload = persistenceDatabase.readEntry(DesktopSettingsStore.STATE_ENTRY_KEY).orEmpty()
        assertThat(reloadedSnapshot).isEqualTo(savedSnapshot)
        assertThat(settingsEntryPayload).contains("themeMode=dark")
        assertThat(settingsEntryPayload).contains("language=ta")
        assertThat(settingsEntryPayload).contains("launchRoute=tasks")
        assertThat(settingsEntryPayload).contains("routeVisible.habits=false")
        assertThat(settingsEntryPayload).contains("routeVisible.settings=true")
    }

    @Test
    fun `load snapshot migrates legacy home surface installs to settings route`() {
        val preferencesDirectory = temporaryFolder.newFolder("prefs-legacy").toPath()
        Files.createDirectories(preferencesDirectory)
        Files.writeString(
            preferencesDirectory.resolve("desktop-settings.properties"),
            "schemaVersion=1\nhomeSurface=notes\nsessionLoggingEnabled=true\n",
        )
        val store = DesktopSettingsStore(preferencesDirectory = preferencesDirectory)

        val snapshot = store.loadSnapshot()

        assertThat(snapshot.schemaVersion).isEqualTo(DesktopSettingsContracts.SCHEMA_VERSION)
        assertThat(snapshot.launchRoute).isEqualTo(DesktopTopLevelRoute.SETTINGS)
        assertThat(snapshot.themeMode).isEqualTo(DesktopThemeMode.DARK)
    }

    @Test
    fun `load snapshot migrates older explicit launch route to settings`() {
        val preferencesDirectory = temporaryFolder.newFolder("prefs-legacy-launch-route").toPath()
        Files.createDirectories(preferencesDirectory)
        Files.writeString(
            preferencesDirectory.resolve("desktop-settings.properties"),
            "schemaVersion=1\nlaunchRoute=tasks\nthemeMode=system\nsessionLoggingEnabled=true\n",
        )
        val store = DesktopSettingsStore(preferencesDirectory = preferencesDirectory)

        val snapshot = store.loadSnapshot()

        assertThat(snapshot.schemaVersion).isEqualTo(DesktopSettingsContracts.SCHEMA_VERSION)
        assertThat(snapshot.launchRoute).isEqualTo(DesktopTopLevelRoute.SETTINGS)
        assertThat(snapshot.themeMode).isEqualTo(DesktopThemeMode.DARK)
    }

    @Test
    fun `load snapshot keeps settings visible even if persisted as hidden`() {
        val preferencesDirectory = temporaryFolder.newFolder("prefs-route-visibility").toPath()
        Files.createDirectories(preferencesDirectory)
        Files.writeString(
            preferencesDirectory.resolve("desktop-settings.properties"),
            "schemaVersion=2\nlaunchRoute=tasks\nrouteVisible.tasks=false\nrouteVisible.settings=false\n",
        )
        val store = DesktopSettingsStore(preferencesDirectory = preferencesDirectory)

        val snapshot = store.loadSnapshot()

        assertThat(snapshot.isRouteVisible(DesktopTopLevelRoute.TASKS)).isFalse()
        assertThat(snapshot.isRouteVisible(DesktopTopLevelRoute.SETTINGS)).isTrue()
        assertThat(snapshot.visibleRoutes()).contains(DesktopTopLevelRoute.SETTINGS)
    }
}
