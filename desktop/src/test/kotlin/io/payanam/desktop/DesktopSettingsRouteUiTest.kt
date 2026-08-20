//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.DesktopBootstrapSnapshot
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopThemeMode
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.SettingsFoundationContracts
import io.payanam.shared.settings.SettingsFoundationSnapshot
import io.payanam.shared.transfer.DataModuleSelection
import org.junit.Rule
import org.junit.Test

/**
 * DesktopSettingsRouteUiTest.
 */
class DesktopSettingsRouteUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings route renders overview and accordion sections`() {
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopSettingsRoute(
                    snapshot = testFoundationSnapshot(),
                    desktopSettings = DesktopSettingsSnapshot(),
                    lifecycleState = testLifecycleState(),
                    onSettingsChanged = {},
                    dataManagementCallbacks = testDataManagementCallbacks(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Desktop settings overview").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Toggle Appearance settings section").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Toggle Data management settings section").assertIsDisplayed()
        composeRule.onNodeWithText("Theme mode").assertIsDisplayed()
        composeRule.onNodeWithText("Language").assertIsDisplayed()
    }

    @Test
    fun `settings route expands default landing and focus mode sections`() {
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopSettingsRoute(
                    snapshot = testFoundationSnapshot(),
                    desktopSettings = DesktopSettingsSnapshot(),
                    lifecycleState = testLifecycleState(),
                    onSettingsChanged = {},
                    dataManagementCallbacks = testDataManagementCallbacks(),
                    initialSection = DesktopSettingsSection.DEFAULT_LANDING,
                )
            }
        }

        composeRule.onNodeWithText("Launch surface").assertIsDisplayed()

        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopSettingsRoute(
                    snapshot = testFoundationSnapshot(),
                    desktopSettings = DesktopSettingsSnapshot(),
                    lifecycleState = testLifecycleState(),
                    onSettingsChanged = {},
                    dataManagementCallbacks = testDataManagementCallbacks(),
                    initialSection = DesktopSettingsSection.FOCUS_MODE,
                )
            }
        }

        composeRule.onNodeWithText("Show Tasks").assertIsDisplayed()
        composeRule.onNodeWithText("Show Time").assertIsDisplayed()
    }

    @Test
    fun `settings route theme choice updates visible state`() {
        var updatedSettings = DesktopSettingsSnapshot(themeMode = DesktopThemeMode.DARK)
        composeRule.setContent {
            var settings by mutableStateOf(updatedSettings)
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopSettingsRoute(
                    snapshot = testFoundationSnapshot(),
                    desktopSettings = settings,
                    lifecycleState = testLifecycleState(),
                    onSettingsChanged = {
                        settings = it
                        updatedSettings = it
                    },
                    dataManagementCallbacks = testDataManagementCallbacks(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Theme mode option System").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Theme mode option System").performClick()
        composeRule.runOnIdle {
            assertThat(updatedSettings.themeMode).isEqualTo(DesktopThemeMode.SYSTEM)
        }
    }

    @Test
    fun `settings route updates launch route and focus visibility`() {
        var updatedSettings = DesktopSettingsSnapshot()
        val initialRoute = updatedSettings.launchRoute
        composeRule.setContent {
            var settings by mutableStateOf(updatedSettings)
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopSettingsRoute(
                    snapshot = testFoundationSnapshot(),
                    desktopSettings = settings,
                    lifecycleState = testLifecycleState(),
                    onSettingsChanged = {
                        settings = it
                        updatedSettings = it
                    },
                    dataManagementCallbacks = testDataManagementCallbacks(),
                    initialSection = DesktopSettingsSection.DEFAULT_LANDING,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Launch surface option Tasks").performClick()
        composeRule.runOnIdle {
            assertThat(updatedSettings.launchRoute).isNotEqualTo(initialRoute)
        }

        composeRule.setContent {
            var settings by mutableStateOf(updatedSettings)
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopSettingsRoute(
                    snapshot = testFoundationSnapshot(),
                    desktopSettings = settings,
                    lifecycleState = testLifecycleState(),
                    onSettingsChanged = {
                        settings = it
                        updatedSettings = it
                    },
                    dataManagementCallbacks = testDataManagementCallbacks(),
                    initialSection = DesktopSettingsSection.FOCUS_MODE,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Show Tasks toggle").performClick()
        composeRule.runOnIdle {
            assertThat(updatedSettings.isRouteVisible(DesktopTopLevelRoute.TASKS)).isFalse()
        }
    }

    @Test
    fun `settings route cycles backup and data management controls`() {
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopSettingsRoute(
                    snapshot = testFoundationSnapshot(),
                    desktopSettings = DesktopSettingsSnapshot(),
                    lifecycleState = testLifecycleState(),
                    onSettingsChanged = {},
                    dataManagementCallbacks = testDataManagementCallbacks(),
                    initialSection = DesktopSettingsSection.AUTO_BACKUP,
                )
            }
        }

        composeRule.onNodeWithText("Daily").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Backup frequency option Weekly").performClick()
        composeRule.onNodeWithText("Weekly").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Retention option Keep last 10 backups").performClick()
        composeRule.onNodeWithText("Keep last 10 backups").assertIsDisplayed()

        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopSettingsRoute(
                    snapshot = testFoundationSnapshot(),
                    desktopSettings = DesktopSettingsSnapshot(),
                    lifecycleState = testLifecycleState(),
                    onSettingsChanged = {},
                    dataManagementCallbacks = testDataManagementCallbacks(),
                    initialSection = DesktopSettingsSection.DATA_MANAGEMENT,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Export mode option Plaintext DB (one-time)").performClick()
        composeRule.onNodeWithText("Plaintext DB (one-time)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Import source option Plaintext DB (legacy bridge)").performClick()
        composeRule.onNodeWithText("Plaintext DB (legacy bridge)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Include tasks toggle").performClick()
        composeRule.onNodeWithText("Off").assertIsDisplayed()
        composeRule.onNodeWithText("Local handoff export").assertIsDisplayed()
        composeRule.onNodeWithText("Local handoff import").assertIsDisplayed()
    }

    @Test
    fun `settings route shows habit timing shell controls`() {
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopSettingsRoute(
                    snapshot = testFoundationSnapshot(),
                    desktopSettings = DesktopSettingsSnapshot(),
                    lifecycleState = testLifecycleState(),
                    onSettingsChanged = {},
                    dataManagementCallbacks = testDataManagementCallbacks(),
                    initialSection = DesktopSettingsSection.AUTO_TRACK_HABIT_TIME,
                )
            }
        }

        composeRule.onNodeWithText("Enable habit timing shell").assertIsDisplayed()
        composeRule.onNodeWithText("Timing preset").assertIsDisplayed()
        composeRule.onNodeWithText("Follow task board").assertIsDisplayed()
    }

    private fun testFoundationSnapshot(): SettingsFoundationSnapshot =
        SettingsFoundationContracts.snapshot(moduleSelection = DataModuleSelection())

    private fun testLifecycleState(): DesktopLifecycleState =
        DesktopLifecycleState(
            desktopSettings = DesktopSettingsSnapshot(),
            settingsFilePath = "C:/Users/example/AppData/Local/Payanam/preferences/desktop-settings.properties",
            bootstrapSnapshot = DesktopBootstrapSnapshot(),
            bootstrapFilePath = "C:/Users/example/AppData/Local/Payanam/bootstrap/desktop-bootstrap.properties",
            securityFilePath = "C:/Users/example/AppData/Local/Payanam/security/desktop-security.properties",
            databaseFilePath = "C:/Users/example/AppData/Local/Payanam/database/payanam-desktop.db",
            exportDirectoryPath = "C:/Users/example/AppData/Local/Payanam/exports",
        )

    private fun testDataManagementCallbacks(): DesktopDataManagementCallbacks =
        DesktopDataManagementCallbacks(
            onExportLocalState = {
                DesktopDataHandoffSnapshot(
                    "C:/Users/example/AppData/Local/Payanam/exports/export.zip",
                    true,
                    false,
                )
            },
            onImportLocalState = {
                DesktopDataHandoffSnapshot(
                    "C:/Users/example/AppData/Local/Payanam/exports/export.zip",
                    true,
                    true,
                )
            },
        )
}
