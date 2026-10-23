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
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Provides the desktop startup gate ui test.
 */
class DesktopStartupGateUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun resetSingleton() {
        // These tests never close the memoized session logger; dropping it here keeps a
        // leaked instance from pointing later test classes at a deleted TemporaryFolder.
        UnifiedLogger.resetForTests()
    }

    @After
    fun releaseSingleton() {
        // A failing assertion aborts the test body before it can close the logger, which would
        // leave the singleton pointing into a TemporaryFolder this rule then deletes.
        UnifiedLogger.resetForTests()
    }

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
                    dimensionInputs = emptyList(),
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
    fun `startup gate keeps the passphrase and its length out of the session log`() {
        val logger =
            UnifiedLogger.initialize(
                logsDirectory = temporaryFolder.newFolder("gate-passphrase-log").toPath(),
                clock = { java.time.Instant.EPOCH },
            )
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopStartupGateSurface(
                    snapshot = testStartupSnapshot(hasPassphrase = false, sessionOpen = false, databaseReady = false),
                    startupMode = DesktopStartupMode.SetupPassphrase,
                    runtimeState = testRuntimeState(hasPassphrase = false, sessionOpen = false, databaseReady = false),
                    databaseSnapshot = testDatabaseSnapshot(databaseReady = false),
                    dimensionInputs = emptyList(),
                    sessionLogger = logger,
                    callbacks =
                        testShellCallbacks(
                            onConfigurePassphrase = { DesktopPassphraseActionResult.Success },
                        ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Desktop passphrase field").performTextInput("LocalPass123!")
        composeRule.onNodeWithContentDescription("Desktop confirm passphrase field").performTextInput("LocalPass123!")
        composeRule.onNodeWithContentDescription("Save passphrase").performClick()

        composeRule.runOnIdle {
            val contents =
                java.nio.file.Files
                    .readString(logger.getLogPath())
            assertThat(contents).contains("Desktop passphrase setup requested")
            assertThat(contents).doesNotContain("passphraseLength")
            assertThat(contents).doesNotContain("LocalPass123!")
        }
        logger.close()
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
                    dimensionInputs = emptyList(),
                    sessionLogger = testSessionLogger(),
                    callbacks =
                        testShellCallbacks(
                            onImportDatabase = { importInvoked = true },
                            onInitializeDatabase = { databaseInitialized = true },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Create local data space").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Import database from Android action").performClick()
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
                    dimensionInputs = emptyList(),
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
        val logger = testSessionLogger()
        composeRule.setContent {
            MaterialTheme(colors = desktopColorPalette().materialColors) {
                desktopStartupGateSurface(
                    snapshot = testStartupSnapshot(hasPassphrase = false, sessionOpen = false, databaseReady = false),
                    startupMode = DesktopStartupMode.SetupPassphrase,
                    runtimeState = testRuntimeState(hasPassphrase = false, sessionOpen = false, databaseReady = false),
                    databaseSnapshot = testDatabaseSnapshot(databaseReady = false),
                    dimensionInputs = emptyList(),
                    sessionLogger = logger,
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

        // The mismatch must also reach the session log — the log is the only post-mortem surface.
        val logContents =
            java.nio.file.Files
                .readString(logger.getLogPath())
        assertThat(logContents).contains("Passphrase confirmation does not match")
        assertThat(logContents).contains("| WARN  |")
        // The passphrase value itself must never be written to the log.
        assertThat(logContents).doesNotContain("Mismatch123!")
    }

    private fun testStartupSnapshot(
        hasPassphrase: Boolean,
        sessionOpen: Boolean,
        databaseReady: Boolean,
    ) = desktopStartupSnapshot(
        launchRoute = DesktopTopLevelRoute.SETTINGS,
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
        onImportDatabase: () -> Unit = {},
        onInitializeDatabase: () -> Unit = {},
    ) = DesktopShellCallbacks(
        onRouteSelected = {},
        onGraphDatabaseReady = {},
        onRailRouteRequested = {},
        onSettingChanged = { _, _ -> },
        onSelectionChanged = {},
        onConfigurePassphrase = onConfigurePassphrase,
        onUnlockPassphrase = { DesktopPassphraseActionResult.Success },
        onForgotPassphraseReset = onForgotPassphraseReset,
        onOnboardingIntroAdvanced = {},
        onDatabaseChoiceSelected = {},
        onDimensionSetupSaved = {},
        onInitializeDatabase = onInitializeDatabase,
        onCompleteFocusModeOnboarding = {},
        onExportDatabase = {},
        onImportDatabase = onImportDatabase,
        // The graph's backup-banner and return-route inputs. This gate test renders the startup surface,
        // not the graph, so these are inert here - supplied because the callbacks are built once for the
        // whole shell rather than per surface.
        onDismissBackupFailure = {},
        onSilentUnlockRequested = { },
        onUnlockReturnRouteConsumed = {},
        onSecurityResetRequested = {},
        onQuitRequested = {},
    )

    private val sessionLogsDirectory: java.nio.file.Path by lazy {
        // Per-test directory: a shared tmpdir path let a previous run's entries satisfy
        // `doesNotContain` assertions (and let concurrent JVM workers collide).
        temporaryFolder.newFolder("session-logs").toPath()
    }

    private fun testSessionLogger(): UnifiedLogger =
        UnifiedLogger.initialize(
            logsDirectory = sessionLogsDirectory,
            clock = { java.time.Instant.EPOCH },
        )
}
