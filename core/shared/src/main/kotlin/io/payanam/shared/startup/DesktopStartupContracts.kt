//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.startup

import io.payanam.shared.settings.DesktopTopLevelRoute
/**
 * Whether a desktop startup check passed or needs user attention.
 */
enum class DesktopStartupCheckStatus {
    Ready,
    AttentionRequired,
}

    /**
     * One startup readiness gate: title, status, and a human-readable summary.
     */
data class DesktopStartupCheck(
    val id: String,
    val title: String,
    val status: DesktopStartupCheckStatus,
    val summary: String,
)

/**
 * Serializable desktop startup-readiness snapshot (launch route + checks).
 */
data class DesktopStartupSnapshot(
    val schemaVersion: Int,
    val launchRoute: DesktopTopLevelRoute,
    val checks: List<DesktopStartupCheck>,
) {
    /**
     * Returns true when any startup check is still [AttentionRequired].
     */
    fun requiresAttention(): Boolean = checks.any { it.status == DesktopStartupCheckStatus.AttentionRequired }
    /**
     * Count of startup checks that are [Ready].
     */
    fun readyChecks(): Int = checks.count { it.status == DesktopStartupCheckStatus.Ready }
}

    /**
     * Resolved on-disk paths the desktop shell depends on at startup.
     */
data class DesktopStartupPaths(
    val settingsFilePath: String,
    val appDataRoot: String,
    val bootstrapFilePath: String,
    val securityFilePath: String,
    val databaseFilePath: String,
)

    /**
     * Live runtime state snapshot (passphrase set, session open, db ready).
     */
data class DesktopStartupState(
    val passphraseConfigured: Boolean,
    val sessionOpen: Boolean,
    val desktopDatabaseReady: Boolean = false,
)
object DesktopStartupContracts {
    const val SCHEMA_VERSION = 1
    /**
     * Builds the startup snapshot from [paths]/[state], evaluating each readiness gate.
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
