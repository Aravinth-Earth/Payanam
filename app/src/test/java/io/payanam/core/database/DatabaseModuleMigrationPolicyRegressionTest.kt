//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
/**
 * DatabaseModuleMigrationPolicyRegressionTest.
 */
class DatabaseModuleMigrationPolicyRegressionTest {
    private val logger: UnifiedLogger by lazy {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Application>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Test
    /**
     * Production database module does not use destructive migration fallback.
     */
    fun production_database_module_does_not_use_destructive_migration_fallback() {
        /** Module path. */
        val modulePath = resolveDatabaseModuleSourcePath()
        /** Session manager path. */
        val sessionManagerPath = resolveDatabaseSessionManagerSourcePath()
        /** Module source. */
        val moduleSource = File(modulePath).readText()
        /** Session manager source. */
        val sessionManagerSource = File(sessionManagerPath).readText()
        /** Combined source. */
        val combinedSource = moduleSource + sessionManagerSource

        /** Has destructive fallback. */
        val hasDestructiveFallback = combinedSource.contains(".fallbackToDestructiveMigration(")
        /** Has explicit wal mode. */
        val hasExplicitWalMode = combinedSource.contains(
            ".setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)",
        )
        /** Has wal auto checkpoint config. */
        val hasWalAutoCheckpointConfig =
            sessionManagerSource.contains("PRAGMA wal_autocheckpoint=")
        /** Has periodic wal checkpoint. */
        val hasPeriodicWalCheckpoint =
            sessionManagerSource.contains("startPeriodicCheckpointTimer()")
        logger.i(
            "DatabaseModuleMigrationPolicyRegressionTest",
            "Checked production database migration policy",
            /** Map of. */
            mapOf(
                "modulePath" to modulePath,
                "sessionManagerPath" to sessionManagerPath,
                "hasDestructiveFallback" to hasDestructiveFallback.toString(),
                "hasExplicitWalMode" to hasExplicitWalMode.toString(),
                "hasWalAutoCheckpointConfig" to hasWalAutoCheckpointConfig.toString(),
                "hasPeriodicWalCheckpoint" to hasPeriodicWalCheckpoint.toString(),
            ),
        )

        /** Assert false. */
        assertFalse(
            "Production database module must not use fallbackToDestructiveMigration()",
            /** Has destructive fallback. */
            hasDestructiveFallback,
        )
        /** Assert true. */
        assertTrue(
            "Production database module must explicitly enable WRITE_AHEAD_LOGGING journal mode",
            /** Has explicit wal mode. */
            hasExplicitWalMode,
        )
        /** Assert true. */
        assertTrue(
            "Database session manager must configure WAL auto-checkpoint for long sessions",
            /** Has wal auto checkpoint config. */
            hasWalAutoCheckpointConfig,
        )
        /** Assert true. */
        assertTrue(
            "Database session manager must run periodic WAL checkpoints during active sessions",
            /** Has periodic wal checkpoint. */
            hasPeriodicWalCheckpoint,
        )
    }

    private fun resolveDatabaseModuleSourcePath(): String {
        /** Candidates. */
        val candidates = listOf(
            "core/database/src/main/kotlin/io/payanam/database/di/DatabaseModule.kt",
            "../core/database/src/main/kotlin/io/payanam/database/di/DatabaseModule.kt",
        )
        return candidates.firstOrNull { File(it).exists() }
            ?: throw IllegalStateException("Could not locate DatabaseModule.kt from test runtime")
    }

    private fun resolveDatabaseSessionManagerSourcePath(): String {
        /** Candidates. */
        val candidates = listOf(
            "core/database/src/main/kotlin/io/payanam/database/session/DatabaseSessionManager.kt",
            "../core/database/src/main/kotlin/io/payanam/database/session/DatabaseSessionManager.kt",
        )
        return candidates.firstOrNull { File(it).exists() }
            ?: throw IllegalStateException("Could not locate DatabaseSessionManager.kt from test runtime")
    }
}
