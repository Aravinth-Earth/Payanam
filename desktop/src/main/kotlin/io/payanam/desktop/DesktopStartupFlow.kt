//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

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

enum class DesktopStartupMode {
    SetupPassphrase,
    UnlockPassphrase,
    InitializeDatabase,
    FocusModeSelection,
    Ready,
}

fun resolveDesktopStartupMode(runtimeState: DesktopStartupRuntimeState): DesktopStartupMode =
    when {
        !runtimeState.hasPassphraseConfigured -> DesktopStartupMode.SetupPassphrase
        !runtimeState.sessionOpen -> DesktopStartupMode.UnlockPassphrase
        !runtimeState.databaseLifecycleReady -> DesktopStartupMode.InitializeDatabase
        !runtimeState.focusModeOnboardingCompleted -> DesktopStartupMode.FocusModeSelection
        else -> DesktopStartupMode.Ready
    }

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
