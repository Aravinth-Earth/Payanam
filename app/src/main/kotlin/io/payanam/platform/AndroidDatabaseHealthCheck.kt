//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import io.payanam.database.DatabaseHealthChecker
import io.payanam.shared.database.DatabaseHealthCheck
import io.payanam.shared.database.DatabaseHealthReport
import java.io.File

/**
 * Android's answer to [DatabaseHealthCheck], over Room.
 *
 * A thin adapter, not a second implementation: every rule — the critical-table list, the schema
 * thresholds, what counts as repairable — stays in [DatabaseHealthChecker], which the desktop cannot see
 * and this class does not duplicate. Only the HANDLE differs, a Room `SQLiteDatabase` where the desktop has
 * a JDBC connection.
 *
 * The two facts Android's checker reports that this does not carry (currentVersion, targetVersion) are
 * migration vocabulary read only by Android's own migration path, so they stay there.
 */
class AndroidDatabaseHealthCheck(
    private val context: Context,
) : DatabaseHealthCheck {
    override suspend fun check(
        databaseFile: File,
        passphrase: String?,
    ): DatabaseHealthReport {
        val result = DatabaseHealthChecker.checkDatabaseHealth(databaseFile, passphrase)
        return DatabaseHealthReport(
            isHealthy = result.isHealthy,
            errorMessage = result.errorMessage,
            needsRepair = result.needsRepair,
            needsMigration = result.needsMigration,
            // The port's contract is explicit that "nothing was read" is null rather than 0, because 0
            // is a real (too old) version and would be a claim nobody measured. DatabaseHealthChecker
            // reports 0 both for a genuine version-0 file and for one that never opened, so the only
            // question this adapter can answer is the one it can actually measure: was the file there
            // and openable at all. A file with no artifacts was never read, so it reports nothing.
            detectedVersion = result.currentVersion.takeIf { DatabaseHealthChecker.hasDatabaseArtifacts(databaseFile) },
        )
    }

    override fun hasArtifacts(databaseFile: File): Boolean = DatabaseHealthChecker.hasDatabaseArtifacts(databaseFile)
}
