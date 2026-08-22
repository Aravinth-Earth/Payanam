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
class DatabaseModuleMigrationPolicyRegressionTest {
    private val logger: UnifiedLogger by lazy {
        val context = ApplicationProvider.getApplicationContext<Application>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Test
    /**
     * Production database module does not use destructive migration fallback.
     */
    fun production_database_module_does_not_use_destructive_migration_fallback() {
        val modulePath = resolveDatabaseModuleSourcePath()
        val sessionManagerPath = resolveDatabaseSessionManagerSourcePath()
        val moduleSource = File(modulePath).readText()
        val sessionManagerSource = File(sessionManagerPath).readText()
        val combinedSource = moduleSource + sessionManagerSource
        val hasDestructiveFallback = combinedSource.contains(".fallbackToDestructiveMigration(")
        val hasExplicitWalMode = combinedSource.contains(
            ".setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)",
        )
        val hasWalAutoCheckpointConfig =
            sessionManagerSource.contains("PRAGMA wal_autocheckpoint=")
        val hasPeriodicWalCheckpoint =
            sessionManagerSource.contains("startPeriodicCheckpointTimer()")
        logger.i(
            "DatabaseModuleMigrationPolicyRegressionTest",
            "Checked production database migration policy",
            mapOf(
                "modulePath" to modulePath,
                "sessionManagerPath" to sessionManagerPath,
                "hasDestructiveFallback" to hasDestructiveFallback.toString(),
                "hasExplicitWalMode" to hasExplicitWalMode.toString(),
                "hasWalAutoCheckpointConfig" to hasWalAutoCheckpointConfig.toString(),
                "hasPeriodicWalCheckpoint" to hasPeriodicWalCheckpoint.toString(),
            ),
        )
        assertFalse(
            "Production database module must not use fallbackToDestructiveMigration()",
            hasDestructiveFallback,
        )
        assertTrue(
            "Production database module must explicitly enable WRITE_AHEAD_LOGGING journal mode",
            hasExplicitWalMode,
        )
        assertTrue(
            "Database session manager must configure WAL auto-checkpoint for long sessions",
            hasWalAutoCheckpointConfig,
        )
        assertTrue(
            "Database session manager must run periodic WAL checkpoints during active sessions",
            hasPeriodicWalCheckpoint,
        )
    }

    private fun resolveDatabaseModuleSourcePath(): String {
        val candidates = listOf(
            "core/database/src/main/kotlin/io/payanam/database/di/DatabaseModule.kt",
            "../core/database/src/main/kotlin/io/payanam/database/di/DatabaseModule.kt",
        )
        return candidates.firstOrNull { File(it).exists() }
            ?: throw IllegalStateException("Could not locate DatabaseModule.kt from test runtime")
    }

    private fun resolveDatabaseSessionManagerSourcePath(): String {
        val candidates = listOf(
            "core/database/src/main/kotlin/io/payanam/database/session/DatabaseSessionManager.kt",
            "../core/database/src/main/kotlin/io/payanam/database/session/DatabaseSessionManager.kt",
        )
        return candidates.firstOrNull { File(it).exists() }
            ?: throw IllegalStateException("Could not locate DatabaseSessionManager.kt from test runtime")
    }
}
