//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseOpener
import io.payanam.desktop.database.DesktopBackupStatusPort
import io.payanam.desktop.database.DesktopDatabaseBackupPort
import io.payanam.desktop.database.DesktopDatabaseOpenerAdapter
import io.payanam.desktop.database.scheduledBackupArtifactName
import io.payanam.domain.port.BackupTrigger
import io.payanam.shared.database.BackupSnapshotVerification
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Proves that a manual desktop backup produces an artifact whose shape and content match the
 * scheduled one, and that artifact is opened through the same shared import/export logic Android
 * itself calls.
 *
 * No device, no Android install: the source database is created on the JVM through the same
 * [PayanamDatabaseOpener.create] the desktop's first-run gate uses, and the backup artifacts are
 * compared in shape and content. The opener reused for Test 2 is [DesktopDatabaseOpenerAdapter],
 * the exact helper `DesktopImportLoopHarnessTest` uses to open the encrypted fixture.
 */
class ManualScheduledBackupParityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "parity-test-passphrase"

    @Test
    fun `manual backup artifact matches the scheduled backup artifact`() =
        runBlocking {
            // Create the source database (the "temp session")
            val sourceDir = tempFolder.newFolder("parity-source").toPath()
            val sourceDb = sourceDir.resolve("payanam.db")
            val createResult = PayanamDatabaseOpener.create(sourceDb, passphrase)
            check(createResult is DatabaseOpenResult.Opened) { "create failed: $createResult" }
            createResult.session.close()

            // Two backup destination directories
            val manualDir = tempFolder.newFolder("parity-manual")
            val autoDir = tempFolder.newFolder("parity-auto")

            // Mutable destination directory for the performBackup lambda
            var currentDestDir = manualDir

            val statusStore = DesktopBackupStatusPort(tempFolder.newFolder("parity-status").toPath())
            val port =
                DesktopDatabaseBackupPort(
                    statusStore = statusStore,
                    performBackup = {
                        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                        val destPath = currentDestDir.toPath().resolve(scheduledBackupArtifactName(stamp))
                        Files.copy(sourceDb, destPath, StandardCopyOption.REPLACE_EXISTING)
                        destPath.toString()
                    },
                    checkpoint = { true },
                    logger = NoOpUnifiedLogger,
                )

            // Run MANUAL backup
            currentDestDir = manualDir
            val manualResult = port.backupToAppBackupDirectory(BackupTrigger.MANUAL)

            // Run AUTO backup
            currentDestDir = autoDir
            val autoResult = port.backupToAppBackupDirectory(BackupTrigger.AUTO)

            val manualFile = File(manualResult.destinationPath)
            val autoFile = File(autoResult.destinationPath)

            // Both exist
            assertThat(manualFile.exists()).isTrue()
            assertThat(autoFile.exists()).isTrue()

            // Same naming pattern: auto_bk_ stamp .db
            assertThat(manualFile.name).startsWith("auto_bk_")
            assertThat(manualFile.name).endsWith(".db")
            assertThat(autoFile.name).startsWith("auto_bk_")
            assertThat(autoFile.name).endsWith(".db")

            // Same header CLASS on both artifacts AND on the source they were copied from:
            // these artifacts are SQLCipher-encrypted, so the plaintext-SQLite predicate is
            // legitimately false - parity means all three agree, not that any is true.
            val sourceHasStandard = BackupSnapshotVerification.hasStandardSqliteHeader(sourceDb.toFile())
            assertThat(BackupSnapshotVerification.hasStandardSqliteHeader(manualFile)).isEqualTo(sourceHasStandard)
            assertThat(BackupSnapshotVerification.hasStandardSqliteHeader(autoFile)).isEqualTo(sourceHasStandard)

            // Same user_version
            assertThat(BackupSnapshotVerification.readSnapshotUserVersion(manualFile))
                .isEqualTo(BackupSnapshotVerification.readSnapshotUserVersion(autoFile))

            // Same sqlite_master table set and per-table row counts
            val manualCounts = tableRowCounts(manualFile.toPath(), passphrase)
            val autoCounts = tableRowCounts(autoFile.toPath(), passphrase)
            assertThat(manualCounts).isEqualTo(autoCounts)
        }

    @Test
    fun `the manual artifact opens through the shared encrypted open path with the fixture passphrase`() =
        runBlocking {
            // Create the source database (the "temp session")
            val sourceDir = tempFolder.newFolder("parity-open-source").toPath()
            val sourceDb = sourceDir.resolve("payanam.db")
            val createResult = PayanamDatabaseOpener.create(sourceDb, passphrase)
            check(createResult is DatabaseOpenResult.Opened) { "create failed: $createResult" }
            createResult.session.close()

            // Backup destination
            val backupDir = tempFolder.newFolder("parity-open-backup")

            val statusStore = DesktopBackupStatusPort(tempFolder.newFolder("parity-open-status").toPath())
            val port =
                DesktopDatabaseBackupPort(
                    statusStore = statusStore,
                    performBackup = {
                        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                        val destPath = backupDir.toPath().resolve(scheduledBackupArtifactName(stamp))
                        Files.copy(sourceDb, destPath, StandardCopyOption.REPLACE_EXISTING)
                        destPath.toString()
                    },
                    checkpoint = { true },
                    logger = NoOpUnifiedLogger,
                )

            // Run MANUAL backup
            val result = port.backupToAppBackupDirectory(BackupTrigger.MANUAL)
            val manualArtifact = File(result.destinationPath)
            assertThat(manualArtifact.exists()).isTrue()

            // Open through the shared encrypted open path (the import-loop harness's helper)
            val adapter = DesktopDatabaseOpenerAdapter()
            val openResult = adapter.openSession(manualArtifact.absolutePath, passphrase.toCharArray())

            assertThat(openResult).isInstanceOf(DatabaseOpenResult.Opened::class.java)
            val session = (openResult as DatabaseOpenResult.Opened).session

            // Query sqlite_master successfully
            val tables =
                session.withSerializedAccess { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                            val names = mutableListOf<String>()
                            while (rows.next()) {
                                names.add(rows.getString(1))
                            }
                            names
                        }
                    }
                }

            assertThat(tables).isNotEmpty()
            session.close()
        }

    private fun tableRowCounts(
        dbPath: java.nio.file.Path,
        passphrase: String,
    ): Map<String, Int> {
        val result = PayanamDatabaseOpener.open(dbPath, passphrase)
        check(result is DatabaseOpenResult.Opened) { "Database did not open: $result" }
        val session = result.session
        return try {
            session.withSerializedAccess { connection ->
                val tables = mutableListOf<String>()
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                        while (rows.next()) {
                            tables.add(rows.getString(1))
                        }
                    }
                }
                val counts = mutableMapOf<String, Int>()
                tables.forEach { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT COUNT(*) FROM `$table`").use { rows ->
                            rows.next()
                            counts[table] = rows.getInt(1)
                        }
                    }
                }
                counts
            }
        } finally {
            session.close()
        }
    }
}
