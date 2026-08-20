//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.startup

import io.payanam.shared.settings.DesktopTopLevelRoute

/**
 * DesktopStartupCheckStatus.
 */
enum class DesktopStartupCheckStatus {
    /** Ready. */
    Ready,
    /** Attention required. */
    AttentionRequired,
}

/**
 * DesktopStartupCheck.

 */
data class DesktopStartupCheck(
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Status. */
    val status: DesktopStartupCheckStatus,
    /** Summary. */
    val summary: String,
)

/**
 * DesktopStartupSnapshot.

 */
data class DesktopStartupSnapshot(
    /** Schema version. */
    val schemaVersion: Int,
    /** Launch route. */
    val launchRoute: DesktopTopLevelRoute,
    /** Checks. */
    val checks: List<DesktopStartupCheck>,
) {
    /**
     * Requires attention.
     */
    fun requiresAttention(): Boolean = checks.any { it.status == DesktopStartupCheckStatus.AttentionRequired }

    /**
     * Ready checks.
     */
    fun readyChecks(): Int = checks.count { it.status == DesktopStartupCheckStatus.Ready }
}

/**
 * DesktopStartupPaths.

 */
data class DesktopStartupPaths(
    /** Settings file path. */
    val settingsFilePath: String,
    /** App data root. */
    val appDataRoot: String,
    /** Bootstrap file path. */
    val bootstrapFilePath: String,
    /** Security file path. */
    val securityFilePath: String,
    /** Database file path. */
    val databaseFilePath: String,
)

/**
 * DesktopStartupState.

 */
data class DesktopStartupState(
    /** Passphrase configured. */
    val passphraseConfigured: Boolean,
    /** Session open. */
    val sessionOpen: Boolean,
    /** Desktop database ready. */
    val desktopDatabaseReady: Boolean = false,
)

/**
 * DesktopStartupContracts.
 */
object DesktopStartupContracts {
    /** S c h e m a  v e r s i o n. */
    const val SCHEMA_VERSION = 1

    /**
     * Snapshot.
     */
    fun snapshot(
        /** Launch route. */
        launchRoute: DesktopTopLevelRoute,
        /** Paths. */
        paths: DesktopStartupPaths,
        /** State. */
        state: DesktopStartupState,
    ): DesktopStartupSnapshot =
        /** Desktop startup snapshot. */
        DesktopStartupSnapshot(
            schemaVersion = SCHEMA_VERSION,
            launchRoute = launchRoute,
            checks =
                /** List of. */
                listOf(
                    /** Desktop startup check. */
                    DesktopStartupCheck(
                        id = "desktop_settings",
                        title = "Desktop settings",
                        status = DesktopStartupCheckStatus.Ready,
                        summary = "Desktop settings are stored in the local database at ${paths.databaseFilePath}.",
                    ),
                    /** Desktop startup check. */
                    DesktopStartupCheck(
                        id = "desktop_app_data",
                        title = "App data root",
                        status = DesktopStartupCheckStatus.Ready,
                        summary = "Desktop state is anchored at ${paths.appDataRoot}.",
                    ),
                    /** Desktop startup check. */
                    DesktopStartupCheck(
                        id = "desktop_bootstrap",
                        title = "Desktop bootstrap state",
                        status = DesktopStartupCheckStatus.Ready,
                        summary = "Startup handoff state is tracked inside the local desktop database.",
                    ),
                    /** Desktop startup check. */
                    DesktopStartupCheck(
                        id = "desktop_passphrase",
                        title = "Desktop passphrase",
                        status = if (state.passphraseConfigured) DesktopStartupCheckStatus.Ready else DesktopStartupCheckStatus.AttentionRequired,
                        summary =
                            /** If. */
                            if (state.passphraseConfigured) {
                                "Desktop passphrase verifier is persisted inside the local desktop database."
                            } else {
                                "Desktop passphrase setup is still required before the shell can protect local data."
                            },
                    ),
                    /** Desktop startup check. */
                    DesktopStartupCheck(
                        id = "desktop_session",
                        title = "Desktop session",
                        status = if (state.sessionOpen) DesktopStartupCheckStatus.Ready else DesktopStartupCheckStatus.AttentionRequired,
                        summary =
                            /** If. */
                            if (state.sessionOpen) {
                                "Desktop startup session is unlocked for the current app run."
                            } else {
                                "Desktop startup requires passphrase unlock before the shell can continue."
                            },
                    ),
                    /** Desktop startup check. */
                    DesktopStartupCheck(
                        id = "desktop_database",
                        title = "Desktop database lifecycle",
                        status = if (state.desktopDatabaseReady) DesktopStartupCheckStatus.Ready else DesktopStartupCheckStatus.AttentionRequired,
                        summary =
                            /** If. */
                            if (state.desktopDatabaseReady) {
                                "Desktop database lifecycle is ready and anchored at ${paths.databaseFilePath}."
                            } else {
                                "Desktop database is present but startup still needs the first local initialization handoff."
                            },
                    ),
                ),
        )
}
