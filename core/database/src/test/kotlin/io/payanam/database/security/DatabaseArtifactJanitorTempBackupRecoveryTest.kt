//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
/**
 * DatabaseArtifactJanitorTempBackupRecoveryTest.
 */
class DatabaseArtifactJanitorTempBackupRecoveryTest {
    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var dbDir: File
    private lateinit var tempBackupDir: File

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        dbDir = dbFile.parentFile ?: error("DB parent directory should exist")
        tempBackupDir = File(dbDir, "payanam_temp_backup")
        dbFile.delete()
        /** File. */
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").delete()
        /** File. */
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm").delete()
        /** File. */
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-journal").delete()
        tempBackupDir.deleteRecursively()
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        dbFile.delete()
        /** File. */
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").delete()
        /** File. */
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-shm").delete()
        /** File. */
        File(dbDir, "${PayanamDatabase.DATABASE_NAME}-journal").delete()
        tempBackupDir.deleteRecursively()
    }

    @Test
    /**
     * Cleanup stale artifacts recovers primary from temp backup when primary missing.
     */
    fun cleanupStaleArtifacts_recoversPrimaryFromTempBackupWhenPrimaryMissing() {
        tempBackupDir.mkdirs()
        /** Backup db. */
        val backupDb = File(tempBackupDir, PayanamDatabase.DATABASE_NAME)
        backupDb.writeText("backup-db-payload")
        /** Backup wal. */
        val backupWal = File(tempBackupDir, "${PayanamDatabase.DATABASE_NAME}-wal")
        backupWal.writeText("wal-payload")

        DatabaseArtifactJanitor.cleanupStaleArtifacts(context, "DatabaseArtifactJanitorTempBackupRecoveryTest")

        /** Assert that. */
        assertThat(dbFile.exists()).isTrue()
        /** Assert that. */
        assertThat(dbFile.readText()).isEqualTo("backup-db-payload")
        /** Assert that. */
        assertThat(File(dbDir, "${PayanamDatabase.DATABASE_NAME}-wal").exists()).isTrue()
        /** Assert that. */
        assertThat(tempBackupDir.exists()).isFalse()
    }

    @Test
    /**
     * Cleanup stale artifacts preserves temp backup when recovery not possible and primary missing.
     */
    fun cleanupStaleArtifacts_preservesTempBackupWhenRecoveryNotPossibleAndPrimaryMissing() {
        tempBackupDir.mkdirs()
        /** File. */
        File(tempBackupDir, "note.txt").writeText("manual-inspection")

        DatabaseArtifactJanitor.cleanupStaleArtifacts(context, "DatabaseArtifactJanitorTempBackupRecoveryTest")

        /** Assert that. */
        assertThat(dbFile.exists()).isFalse()
        /** Assert that. */
        assertThat(tempBackupDir.exists()).isTrue()
    }
}
