//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DatabaseHealthCheckerRegressionTest {
    private val logger: UnifiedLogger by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    private fun ensureLoggerInitialized() {
        logger.d("DatabaseHealthCheckerRegressionTest", "Logger initialized for regression test")
    }

    @Test
    fun `hasDatabaseArtifacts returns false when no database artifacts exist`() {
        ensureLoggerInitialized()
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseArtifacts(context)
        assertFalse(DatabaseHealthChecker.hasDatabaseArtifacts(context))
    }

    @Test
    fun `hasDatabaseArtifacts returns true when wal artifact exists even if main file absent`() {
        ensureLoggerInitialized()
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseArtifacts(context)
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        val walFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal")
        walFile.parentFile?.mkdirs()
        walFile.writeText("wal-placeholder")
        val hasArtifacts = DatabaseHealthChecker.hasDatabaseArtifacts(context)
        logger.i(
            "DatabaseHealthCheckerRegressionTest",
            "Evaluated WAL-only artifact state",
            mapOf(
                "hasArtifacts" to hasArtifacts.toString(),
            ),
        )
        assertTrue(hasArtifacts)
    }

    @Test
    fun `health checker current version stays in sync with room schema version`() {
        ensureLoggerInitialized()
        logger.i(
            "DatabaseHealthCheckerRegressionTest",
            "Validating schema version lock",
            mapOf(
                "healthVersion" to DatabaseHealthChecker.CURRENT_VERSION.toString(),
                "roomVersion" to PAYANAM_DATABASE_SCHEMA_VERSION.toString(),
            ),
        )
        assertEquals(PAYANAM_DATABASE_SCHEMA_VERSION, DatabaseHealthChecker.CURRENT_VERSION)
    }

    @Test
    fun `health checker minimum migratable version matches closed beta support floor`() {
        ensureLoggerInitialized()
        logger.i(
            "DatabaseHealthCheckerRegressionTest",
            "Validating minimum migratable version lock",
            mapOf("minMigratableVersion" to DatabaseHealthChecker.MIN_MIGRATABLE_VERSION.toString()),
        )
        assertEquals(16, DatabaseHealthChecker.MIN_MIGRATABLE_VERSION)
    }

    @Test
    fun `checkDatabaseHealth rejects schema below supported floor`() {
        ensureLoggerInitialized()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = createDatabaseWithRequiredTables(
            context,
            version = 15,
            fileName = PayanamDatabase.DATABASE_NAME,
        )
        val result = DatabaseHealthChecker.checkDatabaseHealth(context)
        assertFalse(result.isHealthy)
        assertTrue(result.needsRepair)
        assertEquals(15, result.currentVersion)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("too old", ignoreCase = true))

        dbFile.delete()
    }

    @Test
    fun `checkDatabaseHealth accepts current schema without migration`() {
        ensureLoggerInitialized()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = createDatabaseWithRequiredTables(
            context,
            version = PAYANAM_DATABASE_SCHEMA_VERSION,
            fileName = PayanamDatabase.DATABASE_NAME,
        )
        val result = DatabaseHealthChecker.checkDatabaseHealth(context)
        assertTrue(result.isHealthy)
        assertFalse(result.needsMigration)
        assertEquals(PAYANAM_DATABASE_SCHEMA_VERSION, result.currentVersion)

        dbFile.delete()
    }

    @Test
    fun `checkDatabaseHealth rejects schema newer than app supports`() {
        ensureLoggerInitialized()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val newerVersion = PAYANAM_DATABASE_SCHEMA_VERSION + 1
        val dbFile = createDatabaseWithRequiredTables(
            context,
            version = newerVersion,
            fileName = PayanamDatabase.DATABASE_NAME,
        )
        val result = DatabaseHealthChecker.checkDatabaseHealth(context)
        assertFalse(result.isHealthy)
        assertFalse(result.needsRepair)
        assertEquals(newerVersion, result.currentVersion)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("newer", ignoreCase = true))

        dbFile.delete()
    }

    private fun createDatabaseWithRequiredTables(context: Context, version: Int, fileName: String): File {
        deleteDatabaseArtifacts(context)
        val dbFile = context.getDatabasePath(fileName)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.version = version
            db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS time_entries (id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS day_journal_entries (id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS app_settings (`key` TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_app_settings_key ON app_settings(`key`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS day_journal_responses (
                    id TEXT PRIMARY KEY NOT NULL,
                    entryId TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    dimensionKey TEXT,
                    promptKey TEXT NOT NULL,
                    responseText TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_day_journal_responses_entryId_scope_dimensionKey_promptKey
                ON day_journal_responses(entryId, scope, dimensionKey, promptKey)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scheduled_notifications (
                    id TEXT PRIMARY KEY NOT NULL,
                    taskId TEXT NOT NULL,
                    scheduledAt TEXT NOT NULL,
                    notificationType TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    isDelivered INTEGER NOT NULL,
                    createdAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        return dbFile
    }

    private fun deleteDatabaseArtifacts(context: Context) {
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        dbDir?.listFiles()?.forEach { file ->
            if (file.name.startsWith(PayanamDatabase.DATABASE_NAME.substringBeforeLast(".")) ||
                file.name.endsWith(".db")
            ) {
                file.deleteRecursively()
            }
        }
    }
}
