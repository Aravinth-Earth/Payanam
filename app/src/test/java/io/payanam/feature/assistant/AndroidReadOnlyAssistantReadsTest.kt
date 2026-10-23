//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.AppSettingEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.port.DatabaseSchemaResult
import io.payanam.domain.port.ReadOnlyQueryOutcome
import io.payanam.domain.port.ReadOnlyRefusal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [AndroidReadOnlyAssistantReads] against a REAL Room database (in memory).
 *
 * ## WHY A REAL DATABASE AND NOT A MOCK CURSOR
 *
 * The class under test makes its read-only guarantee structurally: it holds no writable handle, exposes no
 * method that returns one, and reaches the database only through `openHelper.readableDatabase`. Every claim
 * it makes therefore depends on what Room's own handle actually returns, and a mock
 * `SupportSQLiteDatabase` would assert only that the code calls the methods the test told it to call -
 * which is the assumption under test, not a fact. The desktop half of the same guarantee is proven against
 * a real engine in `ReadOnlyDatabaseQueryJvm`; an untested read path underneath a security port is exactly
 * the gap that lets two halves drift apart while both still read plausibly.
 *
 * ## WHAT EACH CASE PINS
 *
 * The cursor handling is the part that fails quietly rather than loudly. A collection loop that stops at
 * `maxRows` must say that it stopped, or a model is handed an answer described as complete when a tenth of
 * it was dropped. A NULL cell must arrive as null, not as the four characters "null" inside the context a
 * model reasons over. And a value must be paired with the column name the CURSOR reports rather than with
 * an order the caller assumed, because a model reading a row count under a date header produces a
 * confidently wrong answer rather than an error.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReadOnlyAssistantReadsTest {
    private lateinit var db: PayanamDatabase
    private lateinit var reads: AndroidReadOnlyAssistantReads

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
        db =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        reads = AndroidReadOnlyAssistantReads(sessionManagerReturning(db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A session manager that hands back [database], standing in for an opened session. */
    private fun sessionManagerReturning(database: PayanamDatabase): DatabaseSessionManager = mock<DatabaseSessionManager>().also { whenever(it.requireDatabase()).thenReturn(database) }

    /** A session manager for the closed case: the throw is the contract, not an accident. */
    private fun closedSessionManager(): DatabaseSessionManager = mock<DatabaseSessionManager>().also {
        whenever(it.requireDatabase()).thenThrow(IllegalStateException("DatabaseSessionManager: DB not open"))
    }

    /**
     * Puts a known row in the settings table, which is a real table in the shipped schema. [value] is
     * nullable because the column is, and a genuine stored NULL is one of the cases under test.
     */
    private fun seedSettingsRow(
        key: String,
        value: String?,
    ) = runBlocking {
        db.appSettingsDao().insertSetting(AppSettingEntity(key = key, value = value, updatedAt = FIXED_TIME))
    }

    @Test
    fun `query returns the requested columns and rows`() = runBlocking {
        seedSettingsRow("assistant.model", "m1")
        seedSettingsRow("assistant.length", "BALANCED")

        val outcome = reads.query("SELECT key, value FROM app_settings ORDER BY key", maxRows = 10)

        val rows = outcome as ReadOnlyQueryOutcome.Rows
        assertEquals(listOf("key", "value"), rows.columns)
        assertEquals(2, rows.rows.size)
        assertEquals(listOf("assistant.length", "BALANCED"), rows.rows[0])
        assertEquals(listOf("assistant.model", "m1"), rows.rows[1])
        assertFalse("a read that returned every row must not claim truncation", rows.truncated)
    }

    @Test
    fun `query stopped at maxRows reports truncation`() = runBlocking {
        seedSettingsRow("k1", "v1")
        seedSettingsRow("k2", "v2")
        seedSettingsRow("k3", "v3")

        val outcome = reads.query("SELECT key FROM app_settings ORDER BY key", maxRows = 2)

        val rows = outcome as ReadOnlyQueryOutcome.Rows
        assertEquals(2, rows.rows.size)
        assertTrue("a read stopped at maxRows must say so", rows.truncated)
    }

    @Test
    fun `a read that fits entirely is not reported as truncated`() = runBlocking {
        seedSettingsRow("only", "v")

        val outcome = reads.query("SELECT key FROM app_settings", maxRows = 1)

        val rows = outcome as ReadOnlyQueryOutcome.Rows
        assertEquals(1, rows.rows.size)
        assertFalse("a read that returned every row must not claim truncation", rows.truncated)
    }

    @Test
    fun `a null cell arrives as null rather than a rendered string`() = runBlocking {
        // The case a reader that calls getString unconditionally turns into the literal four characters
        // "null" inside the context a model reasons over.
        seedSettingsRow("present", "value")
        seedSettingsRow("void", null)

        val outcome = reads.query("SELECT key, value FROM app_settings ORDER BY key", maxRows = 5)

        val rows = outcome as ReadOnlyQueryOutcome.Rows
        assertEquals(2, rows.rows.size)
        // ORDER BY key puts "present" before "void", so the stored NULL is the SECOND row.
        assertEquals("present", rows.rows[0][0])
        assertEquals("value", rows.rows[0][1])
        assertEquals("void", rows.rows[1][0])
        assertNull("a NULL cell must stay null, not become \"null\"", rows.rows[1][1])
    }

    @Test
    fun `column order comes from the cursor rather than from the SQL text`() = runBlocking {
        seedSettingsRow("only-key", "v")
        // The statement names its columns in the opposite order to the alphabetical one. A reader that
        // paired a value with a hard-coded column list - or with the order the SELECT was written in -
        // would swap them and hand a model a value under the wrong header.
        val outcome = reads.query("SELECT value, key FROM app_settings", maxRows = 5)

        val rows = outcome as ReadOnlyQueryOutcome.Rows
        assertEquals(listOf("value", "key"), rows.columns)
        assertEquals(listOf("v", "only-key"), rows.rows[0])
    }

    @Test
    fun `a query matching nothing is an empty row set and not a refusal`() = runBlocking {
        seedSettingsRow("present", "v")

        val outcome = reads.query("SELECT key FROM app_settings WHERE key = 'absent'", maxRows = 5)

        val rows = outcome as ReadOnlyQueryOutcome.Rows
        assertTrue(rows.rows.isEmpty())
        assertFalse(rows.truncated)
    }

    @Test
    fun `a statement against a missing table is a refusal and not a crash`() {
        val outcome = reads.query("SELECT * FROM no_such_table", maxRows = 5)

        val refused = outcome as ReadOnlyQueryOutcome.Refused
        assertEquals(ReadOnlyRefusal.EXECUTION_FAILED, refused.reason)
        assertTrue("the refusal must name the failure: ${refused.detail}", refused.detail.isNotBlank())
    }

    @Test
    fun `a malformed statement is a refusal and not a crash`() {
        val outcome = reads.query("SELECT FROM WHERE", maxRows = 5)

        val refused = outcome as ReadOnlyQueryOutcome.Refused
        assertEquals(ReadOnlyRefusal.EXECUTION_FAILED, refused.reason)
        assertTrue("the refusal must name the failure: ${refused.detail}", refused.detail.isNotBlank())
    }

    @Test
    fun `a non-positive maxRows is refused before any statement runs`() {
        // A cap of zero is a caller bug, and answering it with an empty row set would be indistinguishable
        // from a database that genuinely holds no rows - the model would report "you have no habits" over
        // a database full of them.
        val zero = runCatching { reads.query("SELECT 1", maxRows = 0) }.exceptionOrNull()
        val negative = runCatching { reads.query("SELECT 1", maxRows = -1) }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $zero", zero is IllegalArgumentException)
        assertTrue("expected IllegalArgumentException, got $negative", negative is IllegalArgumentException)
    }

    @Test
    fun `a closed session escapes a query as a throw rather than a refusal`() {
        val closedReads = AndroidReadOnlyAssistantReads(closedSessionManager())

        val thrown = runCatching { closedReads.query("SELECT 1", maxRows = 1) }.exceptionOrNull()

        // A model told DATABASE_NOT_OPEN would answer a question about a database nobody can open, so the
        // lock has to reach the screen - which renders "data locked" - rather than be converted into a
        // refusal the model reads as an answer.
        assertTrue("expected IllegalStateException, got $thrown", thrown is IllegalStateException)
    }

    @Test
    fun `a closed session escapes a schema read as a throw rather than a refusal`() {
        val closedReads = AndroidReadOnlyAssistantReads(closedSessionManager())

        val thrown = runCatching { closedReads.readSchema() }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $thrown", thrown is IllegalStateException)
    }

    @Test
    fun `readSchema hides the table holding the provider key`() = runBlocking {
        seedSettingsRow("assistant.api_key", SECRET)

        val result = reads.readSchema() as DatabaseSchemaResult.Tables

        val names = result.tables.map { it.name }
        assertTrue("the schema must describe real tables: $names", names.isNotEmpty())
        assertFalse(
            "the table holding the key must never reach a model: $names",
            "app_settings" in names,
        )
        assertTrue(
            "Room's own tables must be filtered out: $names",
            names.none { it.startsWith("room_") || it.startsWith("sqlite_") },
        )
        assertTrue("no schema text may carry a stored value", result.tables.none { it.ddl.contains(SECRET) })
    }

    @Test
    fun `readSchema returns a DDL for each table it lists`() = runBlocking {
        seedSettingsRow("present", "v")

        val result = reads.readSchema() as DatabaseSchemaResult.Tables

        assertTrue(result.tables.isNotEmpty())
        result.tables.forEach { table ->
            assertTrue("a listed table must carry its DDL: ${table.name}", table.ddl.isNotBlank())
        }
    }

    private companion object {
        const val FIXED_TIME = "2026-01-01T00:00:00Z"

        /** A stand-in for a stored key: the schema must describe no table holding it. */
        const val SECRET = "secret-value"
    }
}
