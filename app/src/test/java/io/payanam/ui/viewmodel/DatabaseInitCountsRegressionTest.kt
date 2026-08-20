//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
/**
 * DatabaseInitCountsRegressionTest.
 */
class DatabaseInitCountsRegressionTest {
    private val logger: UnifiedLogger by lazy {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Test
    fun `readDatabaseTableCounts returns persisted table counts`() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** Db file. */
        val dbFile = context.getDatabasePath("regression_counts_test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        /** Database. */
        val database = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        database.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY)")
        database.execSQL("CREATE TABLE time_entries (id TEXT PRIMARY KEY)")
        database.execSQL("CREATE TABLE day_journal_entries (id TEXT PRIMARY KEY)")
        database.execSQL("CREATE TABLE journal_notes (id TEXT PRIMARY KEY)")
        database.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY)")
        database.execSQL("INSERT INTO tasks(id) VALUES ('t1'), ('t2'), ('t3')")
        database.execSQL("INSERT INTO time_entries(id) VALUES ('te1'), ('te2')")
        database.execSQL("INSERT INTO day_journal_entries(id) VALUES ('j1')")
        database.execSQL("INSERT INTO journal_notes(id) VALUES ('jn1'), ('jn2')")
        database.execSQL("INSERT INTO notes(id) VALUES ('n1')")
        database.close()

        /** Counts. */
        val counts = readDatabaseTableCounts(dbFile, logger)

        /** Assert equals. */
        assertEquals(3, counts.taskCount)
        /** Assert equals. */
        assertEquals(2, counts.timeEntryCount)
        /** Assert equals. */
        assertEquals(3, counts.journalEntryCount)
        /** Assert equals. */
        assertEquals(1, counts.noteCount)
        dbFile.delete()
        /** File. */
        File(dbFile.parent, "regression_counts_test.db-wal").delete()
        /** File. */
        File(dbFile.parent, "regression_counts_test.db-shm").delete()
    }
}
