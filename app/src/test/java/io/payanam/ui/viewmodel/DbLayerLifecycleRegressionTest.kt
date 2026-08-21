//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.PayanamDatabase
import io.payanam.feature.settings.deleteAllDatabaseArtifactFiles
import io.payanam.ui.NavRoutePolicy
import io.payanam.ui.Routes
import io.payanam.ui.resolveConcreteRoute
import io.payanam.ui.shouldCaptureReturnRouteForUnlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
/**
 * DbLayerLifecycleRegressionTest.
 */
class DbLayerLifecycleRegressionTest {

    private lateinit var context: Context

    @Before
    /**
     * Setup.
     */
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
        // Clean DB dir before each test
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        dbDir?.listFiles()?.forEach { it.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // NavRoutePolicy tests (Items 2 + 4)
    // -------------------------------------------------------------------------

    @Test
    fun `navRoutePolicy startupGateRoutes always allowed in minimal mode`() {
        val gates = listOf(
            "database_init",
            "passphrase_setup",
            "passphrase_unlock",
            "passphrase_change",
            "focus_mode_selection",
        )
        gates.forEach { route ->
            assertTrue(
                "Startup gate '$route' must be allowed in minimal mode",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
            assertTrue(
                "Startup gate '$route' must be allowed when minimal mode off",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = false),
            )
        }
    }

    @Test
    fun `navRoutePolicy secondaryRoutes always allowed in minimal mode`() {
        val secondaries = listOf(
            "add_task",
            "scoring_config",
            "task_detail/some-uuid",
            "edit_task/some-uuid",
        )
        secondaries.forEach { route ->
            assertTrue(
                "Secondary route '$route' must be allowed in minimal mode",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
        }
    }

    @Test
    fun `navRoutePolicy allowed tabs pass in minimal mode`() {
        val allowedTabs = listOf("time", "tasks", "journal", "notes", "lenses", "settings")
        allowedTabs.forEach { route ->
            assertTrue(
                "Allowed tab '$route' must pass in minimal mode",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
        }
    }

    @Test
    fun `navRoutePolicy non-allowed tabs blocked in minimal mode`() {
        val blockedTabs = listOf("habits")
        blockedTabs.forEach { route ->
            assertFalse(
                "Tab '$route' must be blocked in minimal mode",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
        }
    }

    @Test
    fun `navRoutePolicy all routes pass when minimal mode disabled`() {
        val allRoutes = listOf("notes", "habits", "journal", "add_task", "task_detail/x", "edit_task/x")
        allRoutes.forEach { route ->
            assertTrue(
                "Route '$route' must be allowed when minimal mode is off",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = false),
            )
        }
    }

    @Test
    fun `shouldCaptureReturnRouteForUnlock only captures non-gate routes`() {
        assertTrue(shouldCaptureReturnRouteForUnlock("time"))
        assertTrue(shouldCaptureReturnRouteForUnlock(Routes.taskDetail("abc")))
        assertFalse(shouldCaptureReturnRouteForUnlock(Routes.PASSPHRASE_UNLOCK))
        assertFalse(shouldCaptureReturnRouteForUnlock(Routes.DATABASE_INIT))
        assertFalse(shouldCaptureReturnRouteForUnlock(Routes.FOCUS_MODE_SELECTION))
    }

    @Test
    fun `resolveConcreteRoute expands task detail route with task id argument`() {
        assertEquals(Routes.taskDetail("task-42"), resolveConcreteRoute(Routes.TASK_DETAIL, "task-42"))
        assertEquals(Routes.editTask("task-42"), resolveConcreteRoute(Routes.EDIT_TASK, "task-42"))
        assertEquals("time", resolveConcreteRoute("time"))
    }

    // -------------------------------------------------------------------------
    // deleteAllDatabaseArtifactFiles tests (Item 4)
    // -------------------------------------------------------------------------

    @Test
    fun `deleteAllDatabaseArtifactFiles wipes all files and payanam_temp_backup dir`() {
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()

        // Create mock DB artifacts
        File(dbDir, PayanamDatabase.DATABASE_NAME).writeText("db")
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").writeText("wal")
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm").writeText("shm")
        File(dbDir, "payanam.db.bak").writeText("bak")

        // Create temp backup subdir with a file inside
        val tempBackupDir = File(dbDir, "payanam_temp_backup")
        tempBackupDir.mkdirs()
        File(tempBackupDir, PayanamDatabase.DATABASE_NAME).writeText("backup-db")
        val deletedCount = deleteAllDatabaseArtifactFiles(context)
        assertTrue("Should have deleted multiple items", deletedCount > 0)
        assertFalse("Temp backup dir must be gone", tempBackupDir.exists())
        assertFalse(".db must be gone", File(dbDir, PayanamDatabase.DATABASE_NAME).exists())
        assertFalse("-wal must be gone", File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").exists())
        assertFalse("-shm must be gone", File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm").exists())
        assertFalse(".bak must be gone", File(dbDir, "payanam.db.bak").exists())
    }

    @Test
    fun `dbInitDeleteAllFiles wipes active db artifacts but preserves payanam_temp_backup subdir`() {
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()
        File(dbDir, PayanamDatabase.DATABASE_NAME).writeText("db")
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").writeText("wal")
        val tempBackupDir = File(dbDir, "payanam_temp_backup")
        tempBackupDir.mkdirs()
        File(tempBackupDir, PayanamDatabase.DATABASE_NAME).writeText("backup-db")
        dbInitDeleteAllFiles(context)
        assertTrue("Temp backup dir must be preserved", tempBackupDir.exists())
        assertFalse(".db must be gone", File(dbDir, PayanamDatabase.DATABASE_NAME).exists())
        assertFalse("-wal must be gone", File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").exists())
    }

    @Test
    fun `consolidateWalAfterImport preserves sidecars for non-standard database header`() {
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()
        val dbFile = File(dbDir, PayanamDatabase.DATABASE_NAME)
        val walFile = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal")
        val shmFile = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm")
        val nonSqliteHeader = byteArrayOf(
            0x90.toByte(), 0x4D, 0x00, 0x7D, 0x5E, 0xBC.toByte(), 0x0F, 0xED.toByte(),
            0x0F, 0x5F, 0xD7.toByte(), 0xD0.toByte(), 0x2B, 0x90.toByte(), 0x86.toByte(), 0x7C,
        )
        dbFile.writeBytes(nonSqliteHeader + ByteArray(1024))
        walFile.writeBytes(ByteArray(4096) { 0x2A.toByte() })
        shmFile.writeBytes(ByteArray(1024) { 0x1F.toByte() })
        val walSizeBefore = walFile.length()
        val shmSizeBefore = shmFile.length()
        val consolidated = DatabaseImportSupport.consolidateWalAfterImport(
            dbFile = dbFile,
            logTag = "DbLayerLifecycleRegressionTest.nonStandardHeader",
        )
        assertFalse("Non-standard header should skip framework WAL checkpoint", consolidated)
        assertTrue("WAL must be preserved", walFile.exists())
        assertEquals("WAL size must remain unchanged", walSizeBefore, walFile.length())
        assertTrue("SHM must be preserved", shmFile.exists())
        assertEquals("SHM size must remain unchanged", shmSizeBefore, shmFile.length())
    }

    @Test
    fun `consolidateWalAfterImport keeps wal when temp checkpoint fails`() {
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()
        val dbFile = File(dbDir, PayanamDatabase.DATABASE_NAME)
        val walFile = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal")
        val shmFile = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm")
        val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        dbFile.writeBytes(sqliteMagic + ByteArray(1024))
        walFile.writeBytes(ByteArray(2048) { 0x5A.toByte() })
        shmFile.writeBytes(ByteArray(512) { 0x3C.toByte() })
        val walSizeBefore = walFile.length()
        val consolidated = DatabaseImportSupport.consolidateWalAfterImport(
            dbFile = dbFile,
            logTag = "DbLayerLifecycleRegressionTest.tempCheckpointFailure",
        )
        assertFalse("Invalid SQLite payload should fail checkpoint path", consolidated)
        assertTrue("WAL must be retained to avoid silent data loss", walFile.exists())
        assertEquals("WAL size must remain unchanged", walSizeBefore, walFile.length())
    }

    @Test
    fun `validateSupportedPlaintextImportSchema rejects schema below support floor`() {
        val dbFile = createPlaintextImportDatabase(version = 15, fileName = "legacy-import.db")
        val error = runCatching {
            DatabaseImportSupport.validateSupportedPlaintextImportSchema(
                context = context,
                databaseFile = dbFile,
                logTag = "DbLayerLifecycleRegressionTest.validateSupportedPlaintextImportSchema",
            )
        }.exceptionOrNull()
        assertNotNull("Schema below support floor must fail", error)
        assertTrue("Schema below support floor must surface a non-blank error", !error!!.message.isNullOrBlank())
    }

    @Test
    fun `validateSupportedPlaintextImportSchema accepts current schema`() {
        val dbFile = createPlaintextImportDatabase(
            version = DatabaseHealthChecker.CURRENT_VERSION,
            fileName = "supported-import.db",
        )
        val version = DatabaseImportSupport.validateSupportedPlaintextImportSchema(
            context = context,
            databaseFile = dbFile,
            logTag = "DbLayerLifecycleRegressionTest.validateSupportedPlaintextImportSchema",
        )
        assertEquals(DatabaseHealthChecker.CURRENT_VERSION, version)
    }

    // -------------------------------------------------------------------------
    // dbInitClassifyBootIssue tests (Item 4)
    // -------------------------------------------------------------------------

    @Test
    fun `dbInitClassifyBootIssue returns null when no artifacts exist`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = false,
            healthResult = DatabaseHealthChecker.HealthCheckResult(isHealthy = false, needsRepair = false),
        )
        assertNull("Should return null when no artifacts", result)
    }

    @Test
    fun `dbInitClassifyBootIssue returns null when healthy`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(isHealthy = true, needsRepair = false),
        )
        assertNull("Should return null when healthy", result)
    }

    @Test
    fun `dbInitClassifyBootIssue maps sidecar primary missing error`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Sidecar exists but primary db missing",
            ),
        )
        assertNotNull(result)
        assertEquals(DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps db too old error`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Database version is too old to migrate",
            ),
        )
        assertNotNull(result)
        assertEquals(DatabaseBootIssueType.DB_TOO_OLD, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps db too new error`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Database is newer than app supports. Please update the app.",
            ),
        )
        assertNotNull(result)
        assertEquals(DatabaseBootIssueType.DB_TOO_NEW, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps schema invalid error`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Missing tables in schema",
            ),
        )
        assertNotNull(result)
        assertEquals(DatabaseBootIssueType.SCHEMA_INVALID, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps open failed error`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Cannot open database: file is locked",
            ),
        )
        assertNotNull(result)
        assertEquals(DatabaseBootIssueType.OPEN_FAILED, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps repairable generic when needsRepair true`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = true,
                errorMessage = "Some unrecognized error",
            ),
        )
        assertNotNull(result)
        assertEquals(DatabaseBootIssueType.REPAIRABLE_GENERIC, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps non-repairable generic as fallback`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Some other unrecognized error",
            ),
        )
        assertNotNull(result)
        assertEquals(DatabaseBootIssueType.NON_REPAIRABLE_GENERIC, result!!.type)
    }

    // -------------------------------------------------------------------------
    // resolveShouldShowDatabaseInit tests (regression for blank-DB-on-missing-artifacts bug)
    // -------------------------------------------------------------------------

    @Test
    fun `resolveShouldShowDatabaseInit shows init when no artifacts even if passphrase configured`() {
        // Key regression: missing DB + passphrase configured must show DatabaseInit,
        // NOT passphrase unlock (which would silently create a blank encrypted DB).
        assertTrue(
            "Missing artifacts must always show DatabaseInit regardless of passphrase state",
            io.payanam.resolveShouldShowDatabaseInit(
                hasDatabaseArtifacts = false,
                shouldShowPassphraseUnlock = true,
                isHealthy = false,
                databaseInitCompleted = false,
            ),
        )
    }

    @Test
    fun `resolveShouldShowDatabaseInit shows passphrase unlock when artifacts exist and healthy`() {
        assertFalse(
            "Existing healthy DB with passphrase must show unlock not init",
            io.payanam.resolveShouldShowDatabaseInit(
                hasDatabaseArtifacts = true,
                shouldShowPassphraseUnlock = true,
                isHealthy = true,
                databaseInitCompleted = true,
            ),
        )
    }

    @Test
    fun `resolveShouldShowDatabaseInit shows init when unhealthy and no passphrase unlock needed`() {
        assertTrue(
            "Unhealthy DB without passphrase must show init",
            io.payanam.resolveShouldShowDatabaseInit(
                hasDatabaseArtifacts = true,
                shouldShowPassphraseUnlock = false,
                isHealthy = false,
                databaseInitCompleted = false,
            ),
        )
    }

    @Test
    fun `resolveShouldShowDatabaseInit shows init when init not completed`() {
        assertTrue(
            "Healthy DB but init not completed must show init",
            io.payanam.resolveShouldShowDatabaseInit(
                hasDatabaseArtifacts = true,
                shouldShowPassphraseUnlock = false,
                isHealthy = true,
                databaseInitCompleted = false,
            ),
        )
    }

    @Test
    fun `resolveShouldShowDatabaseInit does not show init when all conditions normal`() {
        assertFalse(
            "Healthy DB with init completed and no passphrase needed must not show init",
            io.payanam.resolveShouldShowDatabaseInit(
                hasDatabaseArtifacts = true,
                shouldShowPassphraseUnlock = false,
                isHealthy = true,
                databaseInitCompleted = true,
            ),
        )
    }

    @Test
    fun `resolveStartupHealthLogSummary defers verdict until unlock when passphrase gate is active`() {
        val result = io.payanam.resolveStartupHealthLogSummary(
            hasDatabaseArtifacts = true,
            shouldShowPassphraseUnlock = true,
            healthResult = null,
        )
        assertEquals("deferred_until_unlock", result.status)
        assertNull(result.isHealthy)
        assertNull(result.needsRepair)
        assertEquals("Deferred until unlock", result.errorMessage)
    }

    @Test
    fun `resolveStartupHealthLogSummary preserves real unhealthy verdict when check ran`() {
        val result = io.payanam.resolveStartupHealthLogSummary(
            hasDatabaseArtifacts = true,
            shouldShowPassphraseUnlock = false,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = true,
                errorMessage = "Cipher mismatch",
            ),
        )
        assertEquals("checked_unhealthy", result.status)
        assertEquals(false, result.isHealthy)
        assertEquals(true, result.needsRepair)
        assertEquals("Cipher mismatch", result.errorMessage)
    }

    private fun createPlaintextImportDatabase(version: Int, fileName: String): File {
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()
        val dbFile = File(dbDir, fileName)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.version = version
            db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY)")
        }
        return dbFile
    }
}
