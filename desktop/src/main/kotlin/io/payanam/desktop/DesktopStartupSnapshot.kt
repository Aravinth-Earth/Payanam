//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.startup.DesktopStartupContracts
import io.payanam.shared.startup.DesktopStartupPaths
import io.payanam.shared.startup.DesktopStartupRuntimeState
import io.payanam.shared.startup.DesktopStartupSnapshot
import io.payanam.shared.startup.DesktopStartupState

internal fun desktopStartupSnapshot(
    launchRoute: DesktopTopLevelRoute,
    appDataRoot: String,
    bootstrapFilePath: String,
    securityFilePath: String,
    databaseFilePath: String,
    runtimeState: DesktopStartupRuntimeState,
): DesktopStartupSnapshot =
    DesktopStartupContracts.snapshot(
        launchRoute = launchRoute,
        paths =
            DesktopStartupPaths(
                appDataRoot = appDataRoot,
                bootstrapFilePath = bootstrapFilePath,
                securityFilePath = securityFilePath,
                databaseFilePath = databaseFilePath,
            ),
        state =
            DesktopStartupState(
                passphraseConfigured = runtimeState.hasPassphraseConfigured,
                sessionOpen = runtimeState.sessionOpen,
                desktopDatabaseReady = runtimeState.databaseLifecycleReady,
            ),
    )
