//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

/**
 * DesktopStartupRuntimeState.

 */
data class DesktopStartupRuntimeState(
    val hasPassphraseConfigured: Boolean,
    val hasDatabaseArtifacts: Boolean,
    val databaseLifecycleReady: Boolean,
    val sessionOpen: Boolean,
    val focusModeOnboardingCompleted: Boolean,
    val securityFilePath: String,
    val databaseFilePath: String,
    val lockoutSecondsRemaining: Long = 0L,
)
/**
 * Which startup gate the desktop app must show before reaching the main UI.
 */
enum class DesktopStartupMode {
    /** Passphrase has not been configured yet; user must set it up. */
    SetupPassphrase,
    /** Passphrase is configured but the session is locked; user must unlock. */
    UnlockPassphrase,
    /** Database lifecycle is not yet ready; initialization is required. */
    InitializeDatabase,
    /** Passphrase and database ready; focus-mode onboarding remains. */
    FocusModeSelection,
    /** All startup steps complete; the app is ready to use. */
    Ready,
}
/**
 * Picks the startup gate to show, in order: passphrase setup → unlock →
 * database init → focus-mode onboarding → ready.
 */
fun resolveDesktopStartupMode(runtimeState: DesktopStartupRuntimeState): DesktopStartupMode =
    when {
        !runtimeState.hasPassphraseConfigured -> DesktopStartupMode.SetupPassphrase
        !runtimeState.sessionOpen -> DesktopStartupMode.UnlockPassphrase
        !runtimeState.databaseLifecycleReady -> DesktopStartupMode.InitializeDatabase
        !runtimeState.focusModeOnboardingCompleted -> DesktopStartupMode.FocusModeSelection
        else -> DesktopStartupMode.Ready
    }
/**
 * Aggregates the settings/bootstrap/security/database snapshots into the
 * single state the startup-mode resolver consumes.
 */
fun buildDesktopStartupRuntimeState(
    settingsSnapshot: io.payanam.shared.settings.DesktopSettingsSnapshot,
    bootstrapSnapshot: DesktopBootstrapSnapshot,
    securitySnapshot: DesktopSecuritySnapshot,
    databaseSnapshot: DesktopDatabaseSnapshot,
    sessionOpen: Boolean,
    nowEpochMillis: Long,
): DesktopStartupRuntimeState {
    val lockoutSecondsRemaining =
        ((securitySnapshot.lockedUntilEpochMillis ?: 0L) - nowEpochMillis)
            .div(1000L)
            .coerceAtLeast(0L)
    val databaseLifecycleReady =
        bootstrapSnapshot.databaseLifecycleReady &&
            databaseSnapshot.hasArtifacts &&
            databaseSnapshot.initCompleted
    return DesktopStartupRuntimeState(
        hasPassphraseConfigured = securitySnapshot.hasPassphraseConfigured,
        hasDatabaseArtifacts = databaseSnapshot.hasArtifacts,
        databaseLifecycleReady = databaseLifecycleReady,
        sessionOpen = sessionOpen,
        focusModeOnboardingCompleted = settingsSnapshot.focusModeOnboardingCompleted,
        securityFilePath = "",
        databaseFilePath = databaseSnapshot.databaseFilePath,
        lockoutSecondsRemaining = lockoutSecondsRemaining,
    )
}
