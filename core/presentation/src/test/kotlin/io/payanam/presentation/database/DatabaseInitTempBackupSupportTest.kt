//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseHealthReport
import io.payanam.shared.database.PayanamDatabaseSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The promoted DB-init file helpers, exercised WITHOUT Android.
 *
 * This is the test the promotion is for: on the JVM the same functions run, because they take a
 * `File` and a platform handle rather than a `Context` and a `SQLiteDatabase`. Before the move the
 * only coverage of `dbInitDeleteAllFiles` and `dbInitClassifyBootIssue` was
 * `app/src/test/java/io/payanam/ui/viewmodel/DbLayerLifecycleRegressionTest.kt`, which can only run
 * under Robolectric on a device-less-but-Android JVM — so the desktop build had no way to reach them
 * at all.
 */
class DatabaseInitTempBackupSupportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun dbFile(): File = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME)

    // -------------------------------------------------------------------------
    // dbInitGetArtifactFiles
    // -------------------------------------------------------------------------

    @Test
    fun `artifact files follow the file given, not a database name`() {
        val relocated = File(temporaryFolder.root, "imported-copy.db")
        val files = dbInitGetArtifactFiles(relocated)
        assertEquals(DatabaseArtifacts.artifactSuffixes.size, files.size)
        assertEquals("imported-copy.db", files.first().name)
        // Every member is a SIBLING of the file given, not a member of the canonical db dir.
        assertTrue(files.all { it.parentFile == temporaryFolder.root })
    }

    @Test
    fun `artifact files are returned whether or not they exist`() {
        val absent = dbInitGetArtifactFiles(dbFile())
        assertEquals(DatabaseArtifacts.artifactSuffixes.size, absent.size)
        assertTrue("A file that does not exist must still be listed", absent.none { it.exists() })
    }

    // -------------------------------------------------------------------------
    // dbInitDeleteAllFiles
    // -------------------------------------------------------------------------

    @Test
    fun `deleteAllFiles removes the live artifacts but preserves the temp backup dir`() {
        val db = dbFile()
        val backupDir = File(temporaryFolder.root, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
        backupDir.mkdirs()
        File(backupDir, PayanamDatabaseSchema.DATABASE_NAME).writeText("good-rows")
        db.writeText("live")
        File(temporaryFolder.root, "${db.name}-wal").writeText("wal")

        dbInitDeleteAllFiles(db)

        assertFalse("The .db must be gone", db.exists())
        assertFalse("The -wal must be gone", File(temporaryFolder.root, "${db.name}-wal").exists())
        assertTrue("The temp backup dir is the only good copy and must survive", backupDir.exists())
        assertEquals("good-rows", File(backupDir, PayanamDatabaseSchema.DATABASE_NAME).readText())
    }

    @Test
    fun `deleteAllFiles sweeps the transient import and migration names`() {
        val db = dbFile()
        val prefix = DatabaseArtifacts.transientTempNamePrefix
        val transient =
            DatabaseArtifacts.transientImportTempSuffixes.map { File(temporaryFolder.root, prefix + it) } +
                // The variable-tail form: the sweep matches by prefix, not by an exact list.
                File(temporaryFolder.root, prefix + DatabaseArtifacts.ENCRYPTED_RENAME_TEMP_TYPED_SUFFIX + "1739")
        val bystander = File(temporaryFolder.root, "unrelated.tmp")
        transient.forEach { it.writeText("x") }
        bystander.writeText("x")

        dbInitDeleteAllFiles(db)

        transient.forEach {
            assertFalse("Transient leftover must be swept: ${it.name}", it.exists())
        }
        assertTrue("A name outside the family must not be swept", bystander.exists())
    }

    // -------------------------------------------------------------------------
    // dbInitCreateSidecarSafeTempBackup / dbInitRestoreFromTempBackup
    // -------------------------------------------------------------------------

    @Test
    fun `createTempBackup checkpoints through the supplied consolidator and copies the family`() {
        val db = dbFile()
        db.writeText("main")
        File(temporaryFolder.root, "${db.name}-wal").writeText("wal")
        var consolidatedFile: File? = null
        var consolidations = 0

        val dir =
            dbInitCreateSidecarSafeTempBackup(
                databaseFile = db,
                walConsolidator =
                    DatabaseInitWalConsolidator { file, _ ->
                        consolidatedFile = file
                        consolidations++
                        true
                    },
            )

        assertNotNull(dir)
        assertEquals(1, consolidations)
        assertEquals(db, consolidatedFile)
        assertEquals(DatabaseArtifacts.TEMP_BACKUP_DIR_NAME, dir!!.name)
        assertTrue(File(dir, db.name).exists())
        assertTrue(File(dir, "${db.name}-wal").exists())
    }

    @Test
    fun `createTempBackup returns null and leaves no directory when the main db is missing`() {
        val db = dbFile() // never created
        val dir = dbInitCreateSidecarSafeTempBackup(db, DatabaseInitWalConsolidator { _, _ -> true })
        assertNull(dir)
        assertFalse(File(temporaryFolder.root, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME).exists())
    }

    @Test
    fun `restoreFromTempBackup replaces the live files and consumes the backup dir`() {
        val db = dbFile()
        db.writeText("corrupt")
        val backupDir = File(temporaryFolder.root, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
        backupDir.mkdirs()
        File(backupDir, db.name).writeText("restored")

        assertTrue(dbInitRestoreFromTempBackup(db, backupDir))

        assertEquals("restored", db.readText())
        assertFalse("The backup dir is consumed by a successful restore", backupDir.exists())
    }

    @Test
    fun `restoreFromTempBackup reports false and keeps the live file when the dir is missing`() {
        val db = dbFile()
        db.writeText("live")
        val missing = File(temporaryFolder.root, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)

        assertFalse(dbInitRestoreFromTempBackup(db, missing))
        assertEquals("live", db.readText())
    }

    // -------------------------------------------------------------------------
    // dbInitMarkInitCompletedDirect / dbInitReadInitCompletedFlag
    // -------------------------------------------------------------------------

    @Test
    fun `markInitCompleted hands the platform the file and the passphrase verbatim`() {
        val db = dbFile()
        var seenFile: File? = null
        var seenPassphrase: String? = "unset"

        dbInitMarkInitCompletedDirect(
            databaseFile = db,
            passphrase = "s3cret",
            flagWriter =
                DatabaseInitFlagWriter { file, passphrase ->
                    seenFile = file
                    seenPassphrase = passphrase
                },
        )

        assertEquals(db, seenFile)
        assertEquals("s3cret", seenPassphrase)
    }

    @Test
    fun `readInitCompletedFlag reads the contract key and parses the stored value`() {
        val db = dbFile()
        val asked = mutableListOf<String>()

        val result =
            dbInitReadInitCompletedFlag(
                db,
                DatabaseInitFlagReader { _, key ->
                    asked += key
                    "true"
                },
            )

        assertTrue(result)
        assertEquals(
            listOf(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY),
            asked,
        )
    }

    @Test
    fun `readInitCompletedFlag reads an absent row as not completed`() {
        val db = dbFile()
        val absent = dbInitReadInitCompletedFlag(db, DatabaseInitFlagReader { _, _ -> null })
        val unparseable = dbInitReadInitCompletedFlag(db, DatabaseInitFlagReader { _, _ -> "yes-please" })
        val explicitFalse = dbInitReadInitCompletedFlag(db, DatabaseInitFlagReader { _, _ -> "false" })
        assertFalse(absent)
        assertFalse(unparseable)
        assertFalse(explicitFalse)
    }

    @Test
    fun `readInitCompletedFlag defaults to false when the platform read throws`() {
        val db = dbFile()
        val result =
            dbInitReadInitCompletedFlag(
                db,
                DatabaseInitFlagReader { _, _ -> throw IllegalStateException("no such table") },
            )
        assertFalse("A first launch with no table must read as not completed", result)
    }

    // -------------------------------------------------------------------------
    // dbInitClassifyBootIssue
    // -------------------------------------------------------------------------

    @Test
    fun `classifyBootIssue returns null when artifacts are absent or the db is healthy`() {
        val unhealthy = DatabaseHealthReport(isHealthy = false, needsRepair = false)
        assertNull(dbInitClassifyBootIssue(false, unhealthy))
        assertNull(
            dbInitClassifyBootIssue(
                true,
                DatabaseHealthReport(isHealthy = true, needsRepair = false),
            ),
        )
    }

    @Test
    fun `classifyBootIssue maps every message rule`() {
        val cases =
            listOf(
                Triple("Sidecar exists but primary db missing", false, DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING),
                Triple("Database version is too old to migrate", false, DatabaseBootIssueType.DB_TOO_OLD),
                Triple(
                    "Database is newer than app supports. Please update the app.",
                    false,
                    DatabaseBootIssueType.DB_TOO_NEW,
                ),
                Triple("Missing tables in schema", false, DatabaseBootIssueType.SCHEMA_INVALID),
                Triple("Cannot open database: file is locked", false, DatabaseBootIssueType.OPEN_FAILED),
                Triple("Some unrecognized error", true, DatabaseBootIssueType.REPAIRABLE_GENERIC),
                Triple("Some other unrecognized error", false, DatabaseBootIssueType.NON_REPAIRABLE_GENERIC),
            )
        cases.forEach { (message, needsRepair, expected) ->
            val issue =
                dbInitClassifyBootIssue(
                    databaseArtifactsExist = true,
                    healthResult =
                        DatabaseHealthReport(
                            isHealthy = false,
                            needsRepair = needsRepair,
                            errorMessage = message,
                        ),
                )
            assertNotNull("No issue classified for: $message", issue)
            assertEquals("Wrong type for: $message", expected, issue!!.type)
        }
    }

    @Test
    fun `classifyBootIssue reports an unmeasured version as zero`() {
        val issue =
            dbInitClassifyBootIssue(
                databaseArtifactsExist = true,
                healthResult =
                    DatabaseHealthReport(
                        isHealthy = false,
                        needsRepair = false,
                        detectedVersion = null,
                        errorMessage = "Database version is too old to migrate",
                    ),
            )
        assertEquals(0, issue!!.detectedVersion)
    }

    // -------------------------------------------------------------------------
    // dbInitDeleteTempBackup
    // -------------------------------------------------------------------------

    @Test
    fun `deleteTempBackup removes the directory and tolerates an absent one`() {
        val dir = File(temporaryFolder.root, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
        dir.mkdirs()
        File(dir, dbFile().name).writeText("x")

        dbInitDeleteTempBackup(dir)
        assertFalse(dir.exists())

        dbInitDeleteTempBackup(dir) // no throw on the second call
        assertFalse(dir.exists())
    }
}
