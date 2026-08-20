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
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        dbDir?.listFiles()?.forEach { it.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // NavRoutePolicy tests (Items 2 + 4)
    // -------------------------------------------------------------------------

    @Test
    fun `navRoutePolicy startupGateRoutes always allowed in minimal mode`() {
        /** Gates. */
        val gates = listOf(
            "database_init",
            "passphrase_setup",
            "passphrase_unlock",
            "passphrase_change",
            "focus_mode_selection",
        )
        gates.forEach { route ->
            /** Assert true. */
            assertTrue(
                "Startup gate '$route' must be allowed in minimal mode",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
            /** Assert true. */
            assertTrue(
                "Startup gate '$route' must be allowed when minimal mode off",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = false),
            )
        }
    }

    @Test
    fun `navRoutePolicy secondaryRoutes always allowed in minimal mode`() {
        /** Secondaries. */
        val secondaries = listOf(
            "add_task",
            "scoring_config",
            "task_detail/some-uuid",
            "edit_task/some-uuid",
        )
        secondaries.forEach { route ->
            /** Assert true. */
            assertTrue(
                "Secondary route '$route' must be allowed in minimal mode",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
        }
    }

    @Test
    fun `navRoutePolicy allowed tabs pass in minimal mode`() {
        /** Allowed tabs. */
        val allowedTabs = listOf("time", "tasks", "journal", "notes", "lenses", "settings")
        allowedTabs.forEach { route ->
            /** Assert true. */
            assertTrue(
                "Allowed tab '$route' must pass in minimal mode",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
        }
    }

    @Test
    fun `navRoutePolicy non-allowed tabs blocked in minimal mode`() {
        /** Blocked tabs. */
        val blockedTabs = listOf("habits")
        blockedTabs.forEach { route ->
            /** Assert false. */
            assertFalse(
                "Tab '$route' must be blocked in minimal mode",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
        }
    }

    @Test
    fun `navRoutePolicy all routes pass when minimal mode disabled`() {
        /** All routes. */
        val allRoutes = listOf("notes", "habits", "journal", "add_task", "task_detail/x", "edit_task/x")
        allRoutes.forEach { route ->
            /** Assert true. */
            assertTrue(
                "Route '$route' must be allowed when minimal mode is off",
                NavRoutePolicy.isAllowed(route, minimalModeEnabled = false),
            )
        }
    }

    @Test
    fun `shouldCaptureReturnRouteForUnlock only captures non-gate routes`() {
        /** Assert true. */
        assertTrue(shouldCaptureReturnRouteForUnlock("time"))
        /** Assert true. */
        assertTrue(shouldCaptureReturnRouteForUnlock(Routes.taskDetail("abc")))
        /** Assert false. */
        assertFalse(shouldCaptureReturnRouteForUnlock(Routes.PASSPHRASE_UNLOCK))
        /** Assert false. */
        assertFalse(shouldCaptureReturnRouteForUnlock(Routes.DATABASE_INIT))
        /** Assert false. */
        assertFalse(shouldCaptureReturnRouteForUnlock(Routes.FOCUS_MODE_SELECTION))
    }

    @Test
    fun `resolveConcreteRoute expands task detail route with task id argument`() {
        /** Assert equals. */
        assertEquals(Routes.taskDetail("task-42"), resolveConcreteRoute(Routes.TASK_DETAIL, "task-42"))
        /** Assert equals. */
        assertEquals(Routes.editTask("task-42"), resolveConcreteRoute(Routes.EDIT_TASK, "task-42"))
        /** Assert equals. */
        assertEquals("time", resolveConcreteRoute("time"))
    }

    // -------------------------------------------------------------------------
    // deleteAllDatabaseArtifactFiles tests (Item 4)
    // -------------------------------------------------------------------------

    @Test
    fun `deleteAllDatabaseArtifactFiles wipes all files and payanam_temp_backup dir`() {
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()

        // Create mock DB artifacts
        /** File. */
        File(dbDir, PayanamDatabase.DATABASE_NAME).writeText("db")
        /** File. */
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").writeText("wal")
        /** File. */
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm").writeText("shm")
        /** File. */
        File(dbDir, "payanam.db.bak").writeText("bak")

        // Create temp backup subdir with a file inside
        /** Temp backup dir. */
        val tempBackupDir = File(dbDir, "payanam_temp_backup")
        tempBackupDir.mkdirs()
        /** File. */
        File(tempBackupDir, PayanamDatabase.DATABASE_NAME).writeText("backup-db")

        /** Deleted count. */
        val deletedCount = deleteAllDatabaseArtifactFiles(context)

        /** Assert true. */
        assertTrue("Should have deleted multiple items", deletedCount > 0)
        /** Assert false. */
        assertFalse("Temp backup dir must be gone", tempBackupDir.exists())
        /** Assert false. */
        assertFalse(".db must be gone", File(dbDir, PayanamDatabase.DATABASE_NAME).exists())
        /** Assert false. */
        assertFalse("-wal must be gone", File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").exists())
        /** Assert false. */
        assertFalse("-shm must be gone", File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm").exists())
        /** Assert false. */
        assertFalse(".bak must be gone", File(dbDir, "payanam.db.bak").exists())
    }

    @Test
    fun `dbInitDeleteAllFiles wipes active db artifacts but preserves payanam_temp_backup subdir`() {
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()

        /** File. */
        File(dbDir, PayanamDatabase.DATABASE_NAME).writeText("db")
        /** File. */
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").writeText("wal")

        /** Temp backup dir. */
        val tempBackupDir = File(dbDir, "payanam_temp_backup")
        tempBackupDir.mkdirs()
        /** File. */
        File(tempBackupDir, PayanamDatabase.DATABASE_NAME).writeText("backup-db")

        /** Db init delete all files. */
        dbInitDeleteAllFiles(context)

        /** Assert true. */
        assertTrue("Temp backup dir must be preserved", tempBackupDir.exists())
        /** Assert false. */
        assertFalse(".db must be gone", File(dbDir, PayanamDatabase.DATABASE_NAME).exists())
        /** Assert false. */
        assertFalse("-wal must be gone", File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").exists())
    }

    @Test
    fun `consolidateWalAfterImport preserves sidecars for non-standard database header`() {
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()

        /** Db file. */
        val dbFile = File(dbDir, PayanamDatabase.DATABASE_NAME)
        /** Wal file. */
        val walFile = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal")
        /** Shm file. */
        val shmFile = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm")

        /** Non sqlite header. */
        val nonSqliteHeader = byteArrayOf(
            0x90.toByte(), 0x4D, 0x00, 0x7D, 0x5E, 0xBC.toByte(), 0x0F, 0xED.toByte(),
            0x0F, 0x5F, 0xD7.toByte(), 0xD0.toByte(), 0x2B, 0x90.toByte(), 0x86.toByte(), 0x7C,
        )
        dbFile.writeBytes(nonSqliteHeader + ByteArray(1024))
        walFile.writeBytes(ByteArray(4096) { 0x2A.toByte() })
        shmFile.writeBytes(ByteArray(1024) { 0x1F.toByte() })

        /** Wal size before. */
        val walSizeBefore = walFile.length()
        /** Shm size before. */
        val shmSizeBefore = shmFile.length()

        /** Consolidated. */
        val consolidated = DatabaseImportSupport.consolidateWalAfterImport(
            dbFile = dbFile,
            logTag = "DbLayerLifecycleRegressionTest.nonStandardHeader",
        )

        /** Assert false. */
        assertFalse("Non-standard header should skip framework WAL checkpoint", consolidated)
        /** Assert true. */
        assertTrue("WAL must be preserved", walFile.exists())
        /** Assert equals. */
        assertEquals("WAL size must remain unchanged", walSizeBefore, walFile.length())
        /** Assert true. */
        assertTrue("SHM must be preserved", shmFile.exists())
        /** Assert equals. */
        assertEquals("SHM size must remain unchanged", shmSizeBefore, shmFile.length())
    }

    @Test
    fun `consolidateWalAfterImport keeps wal when temp checkpoint fails`() {
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()

        /** Db file. */
        val dbFile = File(dbDir, PayanamDatabase.DATABASE_NAME)
        /** Wal file. */
        val walFile = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal")
        /** Shm file. */
        val shmFile = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm")

        /** Sqlite magic. */
        val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        dbFile.writeBytes(sqliteMagic + ByteArray(1024))
        walFile.writeBytes(ByteArray(2048) { 0x5A.toByte() })
        shmFile.writeBytes(ByteArray(512) { 0x3C.toByte() })

        /** Wal size before. */
        val walSizeBefore = walFile.length()

        /** Consolidated. */
        val consolidated = DatabaseImportSupport.consolidateWalAfterImport(
            dbFile = dbFile,
            logTag = "DbLayerLifecycleRegressionTest.tempCheckpointFailure",
        )

        /** Assert false. */
        assertFalse("Invalid SQLite payload should fail checkpoint path", consolidated)
        /** Assert true. */
        assertTrue("WAL must be retained to avoid silent data loss", walFile.exists())
        /** Assert equals. */
        assertEquals("WAL size must remain unchanged", walSizeBefore, walFile.length())
    }

    @Test
    fun `validateSupportedPlaintextImportSchema rejects schema below support floor`() {
        /** Db file. */
        val dbFile = createPlaintextImportDatabase(version = 15, fileName = "legacy-import.db")

        /** Error. */
        val error = runCatching {
            DatabaseImportSupport.validateSupportedPlaintextImportSchema(
                context = context,
                databaseFile = dbFile,
                logTag = "DbLayerLifecycleRegressionTest.validateSupportedPlaintextImportSchema",
            )
        }.exceptionOrNull()

        /** Assert not null. */
        assertNotNull("Schema below support floor must fail", error)
        /** Assert true. */
        assertTrue("Schema below support floor must surface a non-blank error", !error!!.message.isNullOrBlank())
    }

    @Test
    fun `validateSupportedPlaintextImportSchema accepts current schema`() {
        /** Db file. */
        val dbFile = createPlaintextImportDatabase(
            version = DatabaseHealthChecker.CURRENT_VERSION,
            fileName = "supported-import.db",
        )

        /** Version. */
        val version = DatabaseImportSupport.validateSupportedPlaintextImportSchema(
            context = context,
            databaseFile = dbFile,
            logTag = "DbLayerLifecycleRegressionTest.validateSupportedPlaintextImportSchema",
        )

        /** Assert equals. */
        assertEquals(DatabaseHealthChecker.CURRENT_VERSION, version)
    }

    // -------------------------------------------------------------------------
    // dbInitClassifyBootIssue tests (Item 4)
    // -------------------------------------------------------------------------

    @Test
    fun `dbInitClassifyBootIssue returns null when no artifacts exist`() {
        /** Result. */
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = false,
            healthResult = DatabaseHealthChecker.HealthCheckResult(isHealthy = false, needsRepair = false),
        )
        /** Assert null. */
        assertNull("Should return null when no artifacts", result)
    }

    @Test
    fun `dbInitClassifyBootIssue returns null when healthy`() {
        /** Result. */
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(isHealthy = true, needsRepair = false),
        )
        /** Assert null. */
        assertNull("Should return null when healthy", result)
    }

    @Test
    fun `dbInitClassifyBootIssue maps sidecar primary missing error`() {
        /** Result. */
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Sidecar exists but primary db missing",
            ),
        )
        /** Assert not null. */
        assertNotNull(result)
        /** Assert equals. */
        assertEquals(DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps db too old error`() {
        /** Result. */
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Database version is too old to migrate",
            ),
        )
        /** Assert not null. */
        assertNotNull(result)
        /** Assert equals. */
        assertEquals(DatabaseBootIssueType.DB_TOO_OLD, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps db too new error`() {
        /** Result. */
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Database is newer than app supports. Please update the app.",
            ),
        )
        /** Assert not null. */
        assertNotNull(result)
        /** Assert equals. */
        assertEquals(DatabaseBootIssueType.DB_TOO_NEW, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps schema invalid error`() {
        /** Result. */
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Missing tables in schema",
            ),
        )
        /** Assert not null. */
        assertNotNull(result)
        /** Assert equals. */
        assertEquals(DatabaseBootIssueType.SCHEMA_INVALID, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps open failed error`() {
        /** Result. */
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Cannot open database: file is locked",
            ),
        )
        /** Assert not null. */
        assertNotNull(result)
        /** Assert equals. */
        assertEquals(DatabaseBootIssueType.OPEN_FAILED, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps repairable generic when needsRepair true`() {
        /** Result. */
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = true,
                errorMessage = "Some unrecognized error",
            ),
        )
        /** Assert not null. */
        assertNotNull(result)
        /** Assert equals. */
        assertEquals(DatabaseBootIssueType.REPAIRABLE_GENERIC, result!!.type)
    }

    @Test
    fun `dbInitClassifyBootIssue maps non-repairable generic as fallback`() {
        /** Result. */
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = false,
                errorMessage = "Some other unrecognized error",
            ),
        )
        /** Assert not null. */
        assertNotNull(result)
        /** Assert equals. */
        assertEquals(DatabaseBootIssueType.NON_REPAIRABLE_GENERIC, result!!.type)
    }

    // -------------------------------------------------------------------------
    // resolveShouldShowDatabaseInit tests (regression for blank-DB-on-missing-artifacts bug)
    // -------------------------------------------------------------------------

    @Test
    fun `resolveShouldShowDatabaseInit shows init when no artifacts even if passphrase configured`() {
        // Key regression: missing DB + passphrase configured must show DatabaseInit,
        // NOT passphrase unlock (which would silently create a blank encrypted DB).
        /** Assert true. */
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
        /** Assert false. */
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
        /** Assert true. */
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
        /** Assert true. */
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
        /** Assert false. */
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
        /** Result. */
        val result = io.payanam.resolveStartupHealthLogSummary(
            hasDatabaseArtifacts = true,
            shouldShowPassphraseUnlock = true,
            healthResult = null,
        )

        /** Assert equals. */
        assertEquals("deferred_until_unlock", result.status)
        /** Assert null. */
        assertNull(result.isHealthy)
        /** Assert null. */
        assertNull(result.needsRepair)
        /** Assert equals. */
        assertEquals("Deferred until unlock", result.errorMessage)
    }

    @Test
    fun `resolveStartupHealthLogSummary preserves real unhealthy verdict when check ran`() {
        /** Result. */
        val result = io.payanam.resolveStartupHealthLogSummary(
            hasDatabaseArtifacts = true,
            shouldShowPassphraseUnlock = false,
            healthResult = DatabaseHealthChecker.HealthCheckResult(
                isHealthy = false,
                needsRepair = true,
                errorMessage = "Cipher mismatch",
            ),
        )

        /** Assert equals. */
        assertEquals("checked_unhealthy", result.status)
        /** Assert equals. */
        assertEquals(false, result.isHealthy)
        /** Assert equals. */
        assertEquals(true, result.needsRepair)
        /** Assert equals. */
        assertEquals("Cipher mismatch", result.errorMessage)
    }

    private fun createPlaintextImportDatabase(version: Int, fileName: String): File {
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()
        /** Db file. */
        val dbFile = File(dbDir, fileName)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.version = version
            db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY)")
        }
        return dbFile
    }
}
