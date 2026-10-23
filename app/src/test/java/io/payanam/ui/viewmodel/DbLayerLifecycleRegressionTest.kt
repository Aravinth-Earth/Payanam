//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.navigation.PayanamNavRoutePolicy
import io.payanam.core.ui.navigation.PayanamRoutes
import io.payanam.core.ui.navigation.resolveConcreteRoute
import io.payanam.core.ui.navigation.shouldCaptureReturnRouteForUnlock
import io.payanam.feature.settings.deleteAllDatabaseArtifactFiles
import io.payanam.presentation.database.DatabaseImportSupport
import io.payanam.presentation.database.DatabaseInitWalConsolidator
import io.payanam.presentation.database.dbInitClassifyBootIssue
import io.payanam.presentation.database.dbInitDeleteAllFiles
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseHealthReport
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.database.WalCheckpointRule
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
class DbLayerLifecycleRegressionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
        // Clean DB dir before each test
        val dbDir = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).parentFile
        dbDir?.listFiles()?.forEach { it.deleteRecursively() }
    }

    // -------------------------------------------------------------------------
    // PayanamNavRoutePolicy tests (Items 2 + 4)
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
                PayanamNavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
            assertTrue(
                "Startup gate '$route' must be allowed when minimal mode off",
                PayanamNavRoutePolicy.isAllowed(route, minimalModeEnabled = false),
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
                PayanamNavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
        }
    }

    @Test
    fun `navRoutePolicy allowed tabs pass in minimal mode`() {
        val allowedTabs = listOf("time", "tasks", "journal", "notes", "lenses", "settings")
        allowedTabs.forEach { route ->
            assertTrue(
                "Allowed tab '$route' must pass in minimal mode",
                PayanamNavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
        }
    }

    @Test
    fun `navRoutePolicy non-allowed tabs blocked in minimal mode`() {
        val blockedTabs = listOf("habits")
        blockedTabs.forEach { route ->
            assertFalse(
                "Tab '$route' must be blocked in minimal mode",
                PayanamNavRoutePolicy.isAllowed(route, minimalModeEnabled = true),
            )
        }
    }

    @Test
    fun `navRoutePolicy all routes pass when minimal mode disabled`() {
        val allRoutes = listOf("notes", "habits", "journal", "add_task", "task_detail/x", "edit_task/x")
        allRoutes.forEach { route ->
            assertTrue(
                "Route '$route' must be allowed when minimal mode is off",
                PayanamNavRoutePolicy.isAllowed(route, minimalModeEnabled = false),
            )
        }
    }

    @Test
    fun `shouldCaptureReturnRouteForUnlock only captures non-gate routes`() {
        assertTrue(shouldCaptureReturnRouteForUnlock("time"))
        assertTrue(shouldCaptureReturnRouteForUnlock(PayanamRoutes.taskDetail("abc")))
        assertFalse(shouldCaptureReturnRouteForUnlock(PayanamRoutes.PASSPHRASE_UNLOCK))
        assertFalse(shouldCaptureReturnRouteForUnlock(PayanamRoutes.DATABASE_INIT))
        assertFalse(shouldCaptureReturnRouteForUnlock(PayanamRoutes.FOCUS_MODE_SELECTION))
    }

    @Test
    fun `resolveConcreteRoute expands task detail route with task id argument`() {
        assertEquals(PayanamRoutes.taskDetail("task-42"), resolveConcreteRoute(PayanamRoutes.TASK_DETAIL, "task-42"))
        assertEquals(PayanamRoutes.editTask("task-42"), resolveConcreteRoute(PayanamRoutes.EDIT_TASK, "task-42"))
        assertEquals("time", resolveConcreteRoute("time"))
    }

    // -------------------------------------------------------------------------
    // deleteAllDatabaseArtifactFiles tests (Item 4)
    // -------------------------------------------------------------------------

    @Test
    fun `deleteAllDatabaseArtifactFiles wipes all files and payanam_temp_backup dir`() {
        val dbDir = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()

        // Create mock DB artifacts
        File(dbDir, PayanamDatabaseSchema.DATABASE_NAME).writeText("db")
        File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-wal").writeText("wal")
        File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-shm").writeText("shm")
        File(dbDir, "payanam.db.bak").writeText("bak")

        // Create temp backup subdir with a file inside
        val tempBackupDir = File(dbDir, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
        tempBackupDir.mkdirs()
        File(tempBackupDir, PayanamDatabaseSchema.DATABASE_NAME).writeText("backup-db")
        val deletedCount = deleteAllDatabaseArtifactFiles(context)
        assertTrue("Should have deleted multiple items", deletedCount > 0)
        assertFalse("Temp backup dir must be gone", tempBackupDir.exists())
        assertFalse(".db must be gone", File(dbDir, PayanamDatabaseSchema.DATABASE_NAME).exists())
        assertFalse("-wal must be gone", File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-wal").exists())
        assertFalse("-shm must be gone", File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-shm").exists())
        assertFalse(".bak must be gone", File(dbDir, "payanam.db.bak").exists())
    }

    @Test
    fun `dbInitDeleteAllFiles wipes active db artifacts but preserves payanam_temp_backup subdir`() {
        val dbDir = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()
        File(dbDir, PayanamDatabaseSchema.DATABASE_NAME).writeText("db")
        File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-wal").writeText("wal")
        val tempBackupDir = File(dbDir, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
        tempBackupDir.mkdirs()
        File(tempBackupDir, PayanamDatabaseSchema.DATABASE_NAME).writeText("backup-db")
        dbInitDeleteAllFiles(context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME))
        assertTrue("Temp backup dir must be preserved", tempBackupDir.exists())
        assertFalse(".db must be gone", File(dbDir, PayanamDatabaseSchema.DATABASE_NAME).exists())
        assertFalse("-wal must be gone", File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-wal").exists())
    }

    @Test
    fun `consolidateWalAfterImport preserves sidecars for non-standard database header`() {
        val dbDir = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()
        val dbFile = File(dbDir, PayanamDatabaseSchema.DATABASE_NAME)
        val walFile = File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-wal")
        val shmFile = File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-shm")
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
            walConsolidator = frameworkWalConsolidator(),
        )
        assertFalse("Non-standard header should skip framework WAL checkpoint", consolidated)
        assertTrue("WAL must be preserved", walFile.exists())
        assertEquals("WAL size must remain unchanged", walSizeBefore, walFile.length())
        assertTrue("SHM must be preserved", shmFile.exists())
        assertEquals("SHM size must remain unchanged", shmSizeBefore, shmFile.length())
    }

    @Test
    fun `consolidateWalAfterImport keeps wal when temp checkpoint fails`() {
        val dbDir = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).parentFile!!
        dbDir.mkdirs()
        val dbFile = File(dbDir, PayanamDatabaseSchema.DATABASE_NAME)
        val walFile = File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-wal")
        val shmFile = File(dbDir, "${PayanamDatabaseSchema.DATABASE_NAME}-shm")
        val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        dbFile.writeBytes(sqliteMagic + ByteArray(1024))
        walFile.writeBytes(ByteArray(2048) { 0x5A.toByte() })
        shmFile.writeBytes(ByteArray(512) { 0x3C.toByte() })
        val walSizeBefore = walFile.length()
        val consolidated = DatabaseImportSupport.consolidateWalAfterImport(
            dbFile = dbFile,
            logTag = "DbLayerLifecycleRegressionTest.tempCheckpointFailure",
            walConsolidator = frameworkWalConsolidator(),
        )
        assertFalse("Invalid SQLite payload should fail checkpoint path", consolidated)
        assertTrue("WAL must be retained to avoid silent data loss", walFile.exists())
        assertEquals("WAL size must remain unchanged", walSizeBefore, walFile.length())
    }

    /**
     * The platform's half of the promoted WAL fold, kept here so these tests still exercise a REAL
     * checkpoint attempt against the temp copy rather than a stub that always answers one way: the
     * failure case is only meaningful if the framework genuinely refuses the invalid payload.
     */
    private fun frameworkWalConsolidator(): DatabaseInitWalConsolidator = DatabaseInitWalConsolidator { file, _ ->
        try {
            var report: WalCheckpointRule.FoldReport = WalCheckpointRule.NO_ROW
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                android.database.DatabaseErrorHandler { },
            ).use { db ->
                db.rawQuery(WalCheckpointRule.FOLD_STATEMENT, null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        report = WalCheckpointRule.reportOf(intArrayOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)))
                    } else {
                        report = WalCheckpointRule.NO_ROW
                    }
                }
            }
            file.exists() && file.length() > 0 && WalCheckpointRule.walIsEmpty(report)
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // dbInitClassifyBootIssue tests (Item 4)
    // -------------------------------------------------------------------------

    @Test
    fun `dbInitClassifyBootIssue returns null when no artifacts exist`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = false,
            healthResult = DatabaseHealthReport(isHealthy = false, needsRepair = false),
        )
        assertNull("Should return null when no artifacts", result)
    }

    @Test
    fun `dbInitClassifyBootIssue returns null when healthy`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthReport(isHealthy = true, needsRepair = false),
        )
        assertNull("Should return null when healthy", result)
    }

    @Test
    fun `dbInitClassifyBootIssue maps sidecar primary missing error`() {
        val result = dbInitClassifyBootIssue(
            databaseArtifactsExist = true,
            healthResult = DatabaseHealthReport(
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
            healthResult = DatabaseHealthReport(
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
            healthResult = DatabaseHealthReport(
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
            healthResult = DatabaseHealthReport(
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
            healthResult = DatabaseHealthReport(
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
            healthResult = DatabaseHealthReport(
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
            healthResult = DatabaseHealthReport(
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
            healthResult = DatabaseHealthReport(
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
}
