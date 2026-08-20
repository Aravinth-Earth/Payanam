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
/**
 * DatabaseHealthCheckerRegressionTest.
 */
class DatabaseHealthCheckerRegressionTest {
    private val logger: UnifiedLogger by lazy {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    private fun ensureLoggerInitialized() {
        logger.d("DatabaseHealthCheckerRegressionTest", "Logger initialized for regression test")
    }

    @Test
    fun `hasDatabaseArtifacts returns false when no database artifacts exist`() {
        /** Ensure logger initialized. */
        ensureLoggerInitialized()
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** Delete database artifacts. */
        deleteDatabaseArtifacts(context)

        /** Assert false. */
        assertFalse(DatabaseHealthChecker.hasDatabaseArtifacts(context))
    }

    @Test
    fun `hasDatabaseArtifacts returns true when wal artifact exists even if main file absent`() {
        /** Ensure logger initialized. */
        ensureLoggerInitialized()
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** Delete database artifacts. */
        deleteDatabaseArtifacts(context)
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        /** Wal file. */
        val walFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal")
        walFile.parentFile?.mkdirs()
        walFile.writeText("wal-placeholder")

        /** Has artifacts. */
        val hasArtifacts = DatabaseHealthChecker.hasDatabaseArtifacts(context)
        logger.i(
            "DatabaseHealthCheckerRegressionTest",
            "Evaluated WAL-only artifact state",
            /** Map of. */
            mapOf(
                "hasArtifacts" to hasArtifacts.toString(),
            ),
        )
        /** Assert true. */
        assertTrue(hasArtifacts)
    }

    @Test
    fun `health checker current version stays in sync with room schema version`() {
        /** Ensure logger initialized. */
        ensureLoggerInitialized()
        logger.i(
            "DatabaseHealthCheckerRegressionTest",
            "Validating schema version lock",
            /** Map of. */
            mapOf(
                "healthVersion" to DatabaseHealthChecker.CURRENT_VERSION.toString(),
                "roomVersion" to PAYANAM_DATABASE_SCHEMA_VERSION.toString(),
            ),
        )
        /** Assert equals. */
        assertEquals(PAYANAM_DATABASE_SCHEMA_VERSION, DatabaseHealthChecker.CURRENT_VERSION)
    }

    @Test
    fun `health checker minimum migratable version matches closed beta support floor`() {
        /** Ensure logger initialized. */
        ensureLoggerInitialized()
        logger.i(
            "DatabaseHealthCheckerRegressionTest",
            "Validating minimum migratable version lock",
            /** Map of. */
            mapOf("minMigratableVersion" to DatabaseHealthChecker.MIN_MIGRATABLE_VERSION.toString()),
        )
        /** Assert equals. */
        assertEquals(16, DatabaseHealthChecker.MIN_MIGRATABLE_VERSION)
    }

    @Test
    fun `checkDatabaseHealth rejects schema below supported floor`() {
        /** Ensure logger initialized. */
        ensureLoggerInitialized()
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** Db file. */
        val dbFile = createDatabaseWithRequiredTables(
            /** Context. */
            context,
            version = 15,
            fileName = PayanamDatabase.DATABASE_NAME,
        )

        /** Result. */
        val result = DatabaseHealthChecker.checkDatabaseHealth(context)

        /** Assert false. */
        assertFalse(result.isHealthy)
        /** Assert true. */
        assertTrue(result.needsRepair)
        /** Assert equals. */
        assertEquals(15, result.currentVersion)
        /** Assert not null. */
        assertNotNull(result.errorMessage)
        /** Assert true. */
        assertTrue(result.errorMessage!!.contains("too old", ignoreCase = true))

        dbFile.delete()
    }

    @Test
    fun `checkDatabaseHealth accepts current schema without migration`() {
        /** Ensure logger initialized. */
        ensureLoggerInitialized()
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** Db file. */
        val dbFile = createDatabaseWithRequiredTables(
            /** Context. */
            context,
            version = PAYANAM_DATABASE_SCHEMA_VERSION,
            fileName = PayanamDatabase.DATABASE_NAME,
        )

        /** Result. */
        val result = DatabaseHealthChecker.checkDatabaseHealth(context)

        /** Assert true. */
        assertTrue(result.isHealthy)
        /** Assert false. */
        assertFalse(result.needsMigration)
        /** Assert equals. */
        assertEquals(PAYANAM_DATABASE_SCHEMA_VERSION, result.currentVersion)

        dbFile.delete()
    }

    @Test
    fun `checkDatabaseHealth rejects schema newer than app supports`() {
        /** Ensure logger initialized. */
        ensureLoggerInitialized()
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** Newer version. */
        val newerVersion = PAYANAM_DATABASE_SCHEMA_VERSION + 1
        /** Db file. */
        val dbFile = createDatabaseWithRequiredTables(
            /** Context. */
            context,
            version = newerVersion,
            fileName = PayanamDatabase.DATABASE_NAME,
        )

        /** Result. */
        val result = DatabaseHealthChecker.checkDatabaseHealth(context)

        /** Assert false. */
        assertFalse(result.isHealthy)
        /** Assert false. */
        assertFalse(result.needsRepair)
        /** Assert equals. */
        assertEquals(newerVersion, result.currentVersion)
        /** Assert not null. */
        assertNotNull(result.errorMessage)
        /** Assert true. */
        assertTrue(result.errorMessage!!.contains("newer", ignoreCase = true))

        dbFile.delete()
    }

    private fun createDatabaseWithRequiredTables(context: Context, version: Int, fileName: String): File {
        /** Delete database artifacts. */
        deleteDatabaseArtifacts(context)
        /** Db file. */
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
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile
        dbDir?.listFiles()?.forEach { file ->
            /** If. */
            if (file.name.startsWith(PayanamDatabase.DATABASE_NAME.substringBeforeLast(".")) ||
                file.name.endsWith(".db")
            ) {
                file.deleteRecursively()
            }
        }
    }
}
