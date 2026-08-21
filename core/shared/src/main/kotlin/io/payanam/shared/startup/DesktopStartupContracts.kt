//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.startup

import io.payanam.shared.settings.DesktopTopLevelRoute
/**
 * Defines the contract for desktop startup check status.
 */
enum class DesktopStartupCheckStatus {
    Ready,
    AttentionRequired,
}

/**
 * DesktopStartupCheck.

 */
data class DesktopStartupCheck(
    val id: String,
    val title: String,
    val status: DesktopStartupCheckStatus,
    val summary: String,
)

/**
 * DesktopStartupSnapshot.

 */
data class DesktopStartupSnapshot(
    val schemaVersion: Int,
    val launchRoute: DesktopTopLevelRoute,
    val checks: List<DesktopStartupCheck>,
) {
    /**
     * Performs the requires attention.
     */
    fun requiresAttention(): Boolean = checks.any { it.status == DesktopStartupCheckStatus.AttentionRequired }
    /**
     * Loads the ready checks.
     */
    fun readyChecks(): Int = checks.count { it.status == DesktopStartupCheckStatus.Ready }
}

/**
 * DesktopStartupPaths.

 */
data class DesktopStartupPaths(
    val settingsFilePath: String,
    val appDataRoot: String,
    val bootstrapFilePath: String,
    val securityFilePath: String,
    val databaseFilePath: String,
)

/**
 * DesktopStartupState.

 */
data class DesktopStartupState(
    val passphraseConfigured: Boolean,
    val sessionOpen: Boolean,
    val desktopDatabaseReady: Boolean = false,
)
object DesktopStartupContracts {
    const val SCHEMA_VERSION = 1
    /**
     * Performs the snapshot.
     */
    fun snapshot(
        launchRoute: DesktopTopLevelRoute,
        paths: DesktopStartupPaths,
        state: DesktopStartupState,
    ): DesktopStartupSnapshot =
        DesktopStartupSnapshot(
            schemaVersion = SCHEMA_VERSION,
            launchRoute = launchRoute,
            checks =
                listOf(
                    DesktopStartupCheck(
                        id = "desktop_settings",
                        title = "Desktop settings",
                        status = DesktopStartupCheckStatus.Ready,
                        summary = "Desktop settings are stored in the local database at ${paths.databaseFilePath}.",
                    ),
                    DesktopStartupCheck(
                        id = "desktop_app_data",
                        title = "App data root",
                        status = DesktopStartupCheckStatus.Ready,
                        summary = "Desktop state is anchored at ${paths.appDataRoot}.",
                    ),
                    DesktopStartupCheck(
                        id = "desktop_bootstrap",
                        title = "Desktop bootstrap state",
                        status = DesktopStartupCheckStatus.Ready,
                        summary = "Startup handoff state is tracked inside the local desktop database.",
                    ),
                    DesktopStartupCheck(
                        id = "desktop_passphrase",
                        title = "Desktop passphrase",
                        status = if (state.passphraseConfigured) DesktopStartupCheckStatus.Ready else DesktopStartupCheckStatus.AttentionRequired,
                        summary =
                            if (state.passphraseConfigured) {
                                "Desktop passphrase verifier is persisted inside the local desktop database."
                            } else {
                                "Desktop passphrase setup is still required before the shell can protect local data."
                            },
                    ),
                    DesktopStartupCheck(
                        id = "desktop_session",
                        title = "Desktop session",
                        status = if (state.sessionOpen) DesktopStartupCheckStatus.Ready else DesktopStartupCheckStatus.AttentionRequired,
                        summary =
                            if (state.sessionOpen) {
                                "Desktop startup session is unlocked for the current app run."
                            } else {
                                "Desktop startup requires passphrase unlock before the shell can continue."
                            },
                    ),
                    DesktopStartupCheck(
                        id = "desktop_database",
                        title = "Desktop database lifecycle",
                        status = if (state.desktopDatabaseReady) DesktopStartupCheckStatus.Ready else DesktopStartupCheckStatus.AttentionRequired,
                        summary =
                            if (state.desktopDatabaseReady) {
                                "Desktop database lifecycle is ready and anchored at ${paths.databaseFilePath}."
                            } else {
                                "Desktop database is present but startup still needs the first local initialization handoff."
                            },
                    ),
                ),
        )
}
