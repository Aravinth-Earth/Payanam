//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.LaunchDestination
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.SettingsFoundationContracts
import io.payanam.shared.settings.SettingsFoundationSnapshot
import io.payanam.shared.transfer.DataModuleSelection
import org.junit.Rule
import org.junit.Test

/**
 * Provides the desktop settings route ui test.
 *
 * Every control now writes ONE `app_settings` row through `onSettingChanged`, so the assertions name the
 * KEY and the VALUE - the row is the contract with Android, and asserting a desktop snapshot field would
 * pass even if the wrong row were written.
 */
class DesktopSettingsRouteUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings route renders overview and accordion sections`() {
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute()
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
                TestSettingsRoute(initialSection = DesktopSettingsSection.DEFAULT_LANDING)
            }
        }

        composeRule.onNodeWithText("Launch surface").assertIsDisplayed()

        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(initialSection = DesktopSettingsSection.FOCUS_MODE)
            }
        }

        composeRule.onNodeWithText("Show Tasks").assertIsDisplayed()
        composeRule.onNodeWithText("Show Time").assertIsDisplayed()
    }

    @Test
    fun `settings route theme choice writes Android's theme key`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(onSettingChanged = { key, value -> lastWrite = key to value })
            }
        }

        composeRule.onNodeWithContentDescription("Theme mode option System").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Theme mode option System").performClick()
        composeRule.runOnIdle {
            assertThat(lastWrite).isEqualTo(AppPreferenceKeys.KEY_THEME_MODE to "system")
        }
    }

    @Test
    fun `settings route launch surface writes the launch route row`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { key, value -> lastWrite = key to value },
                    initialSection = DesktopSettingsSection.DEFAULT_LANDING,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Launch surface option Tasks").performClick()
        composeRule.runOnIdle {
            assertThat(lastWrite).isEqualTo(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE to "tasks")
        }
    }

    @Test
    fun `settings route focus visibility toggle writes the tab row, and the coarse preset toggle is gone`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { key, value -> lastWrite = key to value },
                    initialSection = DesktopSettingsSection.FOCUS_MODE,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Show Tasks toggle").performClick()
        composeRule.runOnIdle {
            // `tab_visible_tasks`, and the click FLIPS the row (the state above has it visible) rather than
            // writing a fixed value - so the assertion would fail if the toggle ever wrote `true` blindly.
            assertThat(lastWrite).isEqualTo("${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}tasks" to "false")
        }

        // The coarse "enable focused navigation preset" control had no Android row (D2) and must not come back:
        // a visible-but-inert toggle is the shape this change removes.
        composeRule.onNodeWithContentDescription("Enable focused navigation preset toggle").assertDoesNotExist()
    }

    @Test
    fun `settings route cycles backup and data management controls`() {
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(initialSection = DesktopSettingsSection.AUTO_BACKUP)
            }
        }

        composeRule.onNodeWithText("Daily").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Backup frequency option Every 12 hours").performClick()
        composeRule.onNodeWithText("Every 12 hours").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Retention option Keep last 10 backups").performClick()
        composeRule.onNodeWithText("Keep last 10 backups").assertIsDisplayed()

        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(initialSection = DesktopSettingsSection.DATA_MANAGEMENT)
            }
        }

        // The zip/"plaintext bridge" controls are gone: the encrypted database file is the whole export, so
        // there is no mode to pick and no module to include. What replaced them is the two real transfers.
        composeRule.onNodeWithContentDescription("Database export action").performClick()
        composeRule.onNodeWithText("Database export").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Database import action").performClick()
        composeRule.onNodeWithText("Database import").assertIsDisplayed()
    }

    @Test
    fun `settings route habit section writes the real auto-track row`() {
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(initialSection = DesktopSettingsSection.AUTO_TRACK_HABIT_TIME)
            }
        }

        composeRule.onNodeWithText("Auto-track habit completion time").assertIsDisplayed()
        // The coarse shell toggle and its derived "timing preset" mirror are gone with the other two (D2).
        composeRule.onNodeWithContentDescription("Enable habit timing shell toggle").assertDoesNotExist()
        composeRule.onNodeWithText("Timing preset").assertDoesNotExist()
    }

    @Test
    fun `settings route auto backup toggle writes the auto backup row`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { key, value -> lastWrite = key to value },
                    initialSection = DesktopSettingsSection.AUTO_BACKUP,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Enable automatic backup toggle").performClick()
        composeRule.runOnIdle {
            assertThat(lastWrite).isEqualTo(AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED to "true")
        }
    }

    @Test
    fun `settings route backup frequency option writes the interval key`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { key, value -> lastWrite = key to value },
                    initialSection = DesktopSettingsSection.AUTO_BACKUP,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Backup frequency option Daily").performClick()
        composeRule.runOnIdle {
            assertThat(lastWrite).isEqualTo(AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL to "24h")
        }
    }

    @Test
    fun `settings route retention option writes the rotation count key`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { key, value -> lastWrite = key to value },
                    initialSection = DesktopSettingsSection.AUTO_BACKUP,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Retention option Keep last 10 backups").performClick()
        composeRule.runOnIdle {
            assertThat(lastWrite).isEqualTo(AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT to "10")
        }
    }

    @Test
    fun `settings route unlock timeout option writes the session timeout row`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { key, value -> lastWrite = key to value },
                    initialSection = DesktopSettingsSection.SECURITY,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Unlock timeout option 30 minutes").performClick()
        composeRule.runOnIdle {
            assertThat(lastWrite).isEqualTo(AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES to "30")
        }
    }

    @Test
    fun `settings route biometric toggle does not write anything`() {
        var changeCount = 0
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { _, _ -> changeCount++ },
                    initialSection = DesktopSettingsSection.SECURITY,
                )
            }
        }

        // Assert NON-CLICKABILITY DIRECTLY. Asserting only that no write happened would pass even if the row
        // were still clickable, because its onToggle is a no-op - so it could not catch a regression of the
        // interactive = false guarantee. With interactive = false no .clickable modifier is attached, so the
        // node has no click action at all.
        composeRule.onNodeWithContentDescription("Biometric unlock toggle").assertHasNoClickAction()
        composeRule.onNodeWithContentDescription("Biometric unlock toggle").performClick()
        composeRule.runOnIdle {
            assertThat(changeCount).isEqualTo(0)
        }
    }

    @Test
    fun `settings route scoring model option writes the desktop-scoped row`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { key, value -> lastWrite = key to value },
                    initialSection = DesktopSettingsSection.SCORING,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Scoring model option Strict").performClick()
        composeRule.runOnIdle {
            // Declared desktop-only row (the plan's Amendment): Android has no named scoring model, so this
            // must NOT be written under one of Android's keys.
            assertThat(lastWrite).isEqualTo(DESKTOP_SCORING_MODEL_KEY to "strict")
        }
    }

    @Test
    fun `settings route debug toggle writes Android's debug logging row`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { key, value -> lastWrite = key to value },
                    initialSection = DesktopSettingsSection.DEBUG,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Enable debug logging toggle").performClick()
        composeRule.runOnIdle {
            assertThat(lastWrite).isEqualTo(AppPreferenceKeys.KEY_DEBUG_LOGGING_ENABLED to "true")
        }
    }

    @Test
    fun `settings route time format option writes the time format key`() {
        var lastWrite: Pair<String, String?>? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(
                    onSettingChanged = { key, value -> lastWrite = key to value },
                    initialSection = DesktopSettingsSection.APPEARANCE,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Time format option 12-hour clock").performClick()
        composeRule.runOnIdle {
            assertThat(lastWrite).isEqualTo(AppPreferenceKeys.KEY_TIME_FORMAT to "12h")
        }
    }

    @Test
    fun `settings route surfaces the write failure above every section`() {
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                TestSettingsRoute(writeFailure = "theme_mode could not be saved (SQLException)")
            }
        }

        composeRule.onNodeWithText("Setting not saved").assertIsDisplayed()
        composeRule.onNodeWithText("theme_mode could not be saved (SQLException)").assertIsDisplayed()
    }

    @Composable
    private fun TestSettingsRoute(
        preferences: AppPreferencesState = allRoutesVisiblePreferences(),
        unlockTimeoutMinutes: Int = 120,
        scoringModel: String = "balanced",
        writeFailure: String? = null,
        onSettingChanged: (String, String?) -> Unit = { _, _ -> },
        initialSection: DesktopSettingsSection = DesktopSettingsSection.APPEARANCE,
    ) {
        desktopSettingsRoute(
            snapshot = testFoundationSnapshot(),
            preferences = preferences,
            unlockTimeoutMinutes = unlockTimeoutMinutes,
            scoringModel = scoringModel,
            writeFailure = writeFailure,
            lifecycleState = testLifecycleState(),
            onSettingChanged = onSettingChanged,
            dataManagementCallbacks = testDataManagementCallbacks(),
            initialSection = initialSection,
        )
    }

    /**
     * Every `tab_visible_*` row true, which is what the mapper writes for a fresh database. The route's
     * option groups enumerate the visible routes, so an empty map would leave the launch surface with one
     * option and make the test assert the wrong thing.
     */
    private fun allRoutesVisiblePreferences(): AppPreferencesState =
        AppPreferencesState(
            launchDestination = LaunchDestination(route = DesktopTopLevelRoute.SETTINGS.storageKey),
            tabVisibility = DesktopTopLevelRoute.entries.associate { it.storageKey to true },
        )

    private fun testFoundationSnapshot(): SettingsFoundationSnapshot =
        SettingsFoundationContracts.snapshot(moduleSelection = DataModuleSelection())

    private fun testLifecycleState(): DesktopLifecycleState =
        DesktopLifecycleState(
            bootstrapSnapshot = DesktopBootstrapSnapshot(),
            databaseExists = true,
            databaseSizeKb = 12L,
            bootstrapFilePath = "C:/Users/example/AppData/Local/Payanam/bootstrap/desktop-bootstrap.properties",
            securityFilePath = "C:/Users/example/AppData/Local/Payanam/security/desktop-security.properties",
            databaseFilePath = "C:/Users/example/AppData/Local/Payanam/database/payanam-desktop.db",
            exportDirectoryPath = "C:/Users/example/AppData/Local/Payanam/exports",
        )

    private fun testDataManagementCallbacks(): DesktopDataManagementCallbacks =
        DesktopDataManagementCallbacks(
            onExportDatabase = {},
            onImportDatabase = {},
            status = null,
        )
}
