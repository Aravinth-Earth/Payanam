//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Rule
import org.junit.Test

/**
 * DesktopStartupGateUiTest.
 */
class DesktopStartupGateUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `startup gate renders setup mode and forwards configured passphrase`() {
        var configuredPassphrase: String? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopStartupGateSurface(
                    snapshot = testStartupSnapshot(hasPassphrase = false, sessionOpen = false, databaseReady = false),
                    startupMode = DesktopStartupMode.SetupPassphrase,
                    runtimeState = testRuntimeState(hasPassphrase = false, sessionOpen = false, databaseReady = false),
                    databaseSnapshot = testDatabaseSnapshot(databaseReady = false),
                    sessionLogger = testSessionLogger(),
                    callbacks =
                        testShellCallbacks(
                            onConfigurePassphrase = { passphrase ->
                                configuredPassphrase = passphrase
                                DesktopPassphraseActionResult.Success
                            },
                        ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Desktop startup gate").assertIsDisplayed()
        composeRule.onNodeWithText("Set up local protection").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Desktop passphrase field").performTextInput("LocalPass123!")
        composeRule.onNodeWithContentDescription("Desktop confirm passphrase field").performTextInput("LocalPass123!")
        composeRule.onNodeWithContentDescription("Save passphrase").performClick()

        composeRule.runOnIdle {
            assertThat(configuredPassphrase).isEqualTo("LocalPass123!")
        }
    }

    @Test
    fun `startup gate renders database initialization action`() {
        var databaseInitialized = false
        var importInvoked = false
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopStartupGateSurface(
                    snapshot = testStartupSnapshot(hasPassphrase = true, sessionOpen = true, databaseReady = false),
                    startupMode = DesktopStartupMode.InitializeDatabase,
                    runtimeState = testRuntimeState(hasPassphrase = true, sessionOpen = true, databaseReady = false),
                    databaseSnapshot = testDatabaseSnapshot(databaseReady = false),
                    sessionLogger = testSessionLogger(),
                    callbacks =
                        testShellCallbacks(
                            onImportLocalState = {
                                importInvoked = true
                                DesktopDataHandoffSnapshot(
                                    "C:/Users/test/AppData/Local/Payanam/exports/export.zip",
                                    true,
                                    true,
                                )
                            },
                            onInitializeDatabase = { databaseInitialized = true },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Create local data space").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Import latest desktop handoff action").performClick()
        composeRule.onNodeWithContentDescription("Initialize desktop database action").performClick()

        composeRule.runOnIdle {
            assertThat(importInvoked).isTrue()
            assertThat(databaseInitialized).isTrue()
        }
    }

    @Test
    fun `startup gate renders forgot passphrase reset action`() {
        var resetInvoked = false
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopStartupGateSurface(
                    snapshot = testStartupSnapshot(hasPassphrase = true, sessionOpen = false, databaseReady = true),
                    startupMode = DesktopStartupMode.UnlockPassphrase,
                    runtimeState = testRuntimeState(hasPassphrase = true, sessionOpen = false, databaseReady = true),
                    databaseSnapshot = testDatabaseSnapshot(databaseReady = true),
                    sessionLogger = testSessionLogger(),
                    callbacks =
                        testShellCallbacks(
                            onForgotPassphraseReset = { resetInvoked = true },
                        ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Reset desktop data action").performClick()

        composeRule.runOnIdle {
            assertThat(resetInvoked).isFalse()
        }
    }

    @Test
    fun `setup gate blocks submit when confirmation does not match`() {
        var configuredPassphrase: String? = null
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopStartupGateSurface(
                    snapshot = testStartupSnapshot(hasPassphrase = false, sessionOpen = false, databaseReady = false),
                    startupMode = DesktopStartupMode.SetupPassphrase,
                    runtimeState = testRuntimeState(hasPassphrase = false, sessionOpen = false, databaseReady = false),
                    databaseSnapshot = testDatabaseSnapshot(databaseReady = false),
                    sessionLogger = testSessionLogger(),
                    callbacks =
                        testShellCallbacks(
                            onConfigurePassphrase = { passphrase ->
                                configuredPassphrase = passphrase
                                DesktopPassphraseActionResult.Success
                            },
                        ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Desktop passphrase field").performTextInput("LocalPass123!")
        composeRule.onNodeWithContentDescription("Desktop confirm passphrase field").performTextInput("Mismatch123!")
        composeRule.onNodeWithContentDescription("Save passphrase").performClick()
        composeRule.onNodeWithText("Passphrase confirmation does not match.").assertIsDisplayed()

        composeRule.runOnIdle {
            assertThat(configuredPassphrase).isNull()
        }
    }

    private fun testStartupSnapshot(
        hasPassphrase: Boolean,
        sessionOpen: Boolean,
        databaseReady: Boolean,
    ) = desktopStartupSnapshot(
        settings = DesktopSettingsSnapshot(launchRoute = DesktopTopLevelRoute.SETTINGS),
        settingsFilePath = "C:/Users/test/AppData/Local/Payanam/preferences/desktop-settings.properties",
        appDataRoot = "C:/Users/test/AppData/Local/Payanam",
        bootstrapFilePath = "C:/Users/test/AppData/Local/Payanam/bootstrap/desktop-bootstrap.properties",
        securityFilePath = "C:/Users/test/AppData/Local/Payanam/security/desktop-security.properties",
        databaseFilePath = "C:/Users/test/AppData/Local/Payanam/database/payanam-desktop.db",
        runtimeState =
            DesktopStartupRuntimeState(
                hasPassphraseConfigured = hasPassphrase,
                hasDatabaseArtifacts = databaseReady,
                databaseLifecycleReady = databaseReady,
                sessionOpen = sessionOpen,
                focusModeOnboardingCompleted = true,
                securityFilePath = "security",
                databaseFilePath = "database",
            ),
    )

    private fun testRuntimeState(
        hasPassphrase: Boolean,
        sessionOpen: Boolean,
        databaseReady: Boolean,
        focusOnboardingCompleted: Boolean = true,
    ) = DesktopStartupRuntimeState(
        hasPassphraseConfigured = hasPassphrase,
        hasDatabaseArtifacts = databaseReady,
        databaseLifecycleReady = databaseReady,
        sessionOpen = sessionOpen,
        focusModeOnboardingCompleted = focusOnboardingCompleted,
        securityFilePath = "security",
        databaseFilePath = "database",
    )

    private fun testDatabaseSnapshot(databaseReady: Boolean) =
        DesktopDatabaseSnapshot(
            databaseFilePath = "C:/Users/test/AppData/Local/Payanam/database/payanam-desktop.db",
            hasArtifacts = databaseReady,
            initCompleted = databaseReady,
            databaseSizeKb = if (databaseReady) 128L else 0L,
            databaseLastModifiedMs = if (databaseReady) 1_700_000_000_000L else 0L,
        )

    private fun testShellCallbacks(
        onConfigurePassphrase: (String) -> DesktopPassphraseActionResult = { DesktopPassphraseActionResult.Success },
        onForgotPassphraseReset: () -> Unit = {},
        onImportLocalState: () -> DesktopDataHandoffSnapshot =
            { DesktopDataHandoffSnapshot("C:/Users/test/AppData/Local/Payanam/exports/export.zip", true, true) },
        onInitializeDatabase: () -> Unit = {},
    ) = DesktopShellCallbacks(
        onRouteSelected = {},
        onSettingsChanged = {},
        onSelectionChanged = {},
        onTaskBoardChanged = {},
        onJournalDateSelected = {},
        onJournalOverallResponseChanged = { _, _ -> },
        onJournalDimensionResponseChanged = { _, _, _ -> },
        onCreateNote = { _, _, _, _, _ -> },
        onUpdateNote = { _, _, _, _, _, _ -> },
        onDeleteNote = {},
        onConfigurePassphrase = onConfigurePassphrase,
        onUnlockPassphrase = { DesktopPassphraseActionResult.Success },
        onForgotPassphraseReset = onForgotPassphraseReset,
        onInitializeDatabase = onInitializeDatabase,
        onCompleteFocusModeOnboarding = {},
        onExportLocalState = { DesktopDataHandoffSnapshot("C:/Users/test/AppData/Local/Payanam/exports/export.zip", true, false) },
        onImportLocalState = onImportLocalState,
    )

    private fun testSessionLogger(): DesktopSessionLogger =
        DesktopSessionLogger.initialize(
            logsDirectory =
                java.nio.file.Paths
                    .get(System.getProperty("java.io.tmpdir"), "payanam-desktop-test-logs"),
            clock = { java.time.Instant.EPOCH },
        )
}
