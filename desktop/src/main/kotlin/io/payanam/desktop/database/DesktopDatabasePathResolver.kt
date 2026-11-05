//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.desktop.DesktopAppPaths
import io.payanam.domain.port.DatabasePathResolver
import java.nio.file.Paths

/**
 * The desktop's [DatabasePathResolver], delegating to [DesktopAppPaths] so the data layout stays
 * defined in exactly one place: this class knows how to ask, not where things go.
 *
 * `payanam.db` is the same name Android uses because the two platforms read the SAME file: export from
 * one is import into the other (see [DesktopAppPaths.DATABASE_FILE_NAME]), so a per-platform name would
 * be a second convention to reconcile for no gain. The directory is a lambda rather than a call made
 * inside the class so tests can pin it — the desktop convention for anything that would otherwise read
 * the host's real environment.
 */
internal class DesktopDatabasePathResolver(
    private val databaseDirectory: () -> String = { DesktopAppPaths.resolveDatabaseDirectory().toString() },
) : DatabasePathResolver {
    /**
     * The injected directory supplier's value. `.invoke()` rather than `databaseDirectory()` so the
     * expression can only mean the property, never this function calling itself.
     */
    override fun databaseDirectory(): String = databaseDirectory.invoke()

    /**
     * The directory joined with [DesktopAppPaths.DATABASE_FILE_NAME] through [Paths.get], so the
     * separator is the host platform's and the file name stays single-sourced.
     */
    override fun databaseFilePath(): String = Paths.get(databaseDirectory.invoke(), DesktopAppPaths.DATABASE_FILE_NAME).toString()
}
