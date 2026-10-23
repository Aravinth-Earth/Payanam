//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.shared.database.PayanamDatabaseSchema
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * REGRESSION GUARD for foreign-key enforcement on the Android Room + SQLCipher open path.
 *
 * WHAT IT LOCKS IN (measured on 2026-10-22): the app's production open path leaves
 * `PRAGMA foreign_keys` ON, and the declared `ON DELETE CASCADE` actions actually fire. Both facts
 * are asserted, and both must hold.
 *
 * WHY THE PRAGMA IS NOT A PROPERTY OF THIS REPOSITORY: `PRAGMA foreign_keys = ON` is issued by
 * ROOM'S GENERATED CODE, not by any hand-written call here. The open delegate inside
 * `PayanamDatabase_Impl` runs `execSQL("PRAGMA foreign_keys = ON")` from its `onOpen`, on every
 * connection it opens. Nothing in `core/database` issues it, and the SQLCipher
 * `SupportOpenHelperFactory` does not either — which is why the desktop's JDBC opener has to
 * issue the pragma for itself, because the JDBC driver does not.
 *
 * That is exactly what makes this file worth keeping. The pragma is a property of the ROOM
 * VERSION, the KSP OUTPUT, and the open path Room is wired into — none of which this repository
 * owns. If any of them ever stop issuing it, every `ON DELETE CASCADE` in the schema becomes
 * inert and every dependent row silently survives its parent's deletion, with no error raised
 * anywhere. This guard is what turns that silent failure into a failed build.
 *
 * WHY BOTH THE PRAGMA AND THE CASCADE CONSEQUENCE ARE ASSERTED: `PRAGMA foreign_keys` is a
 * PER-CONNECTION setting, so it can read 1 on one pooled connection while the connection that
 * performs the delete behaves as if it were off. The pragma proves the setting is applied; the
 * cascade proves it is applied to the connection that actually performs the delete. Neither
 * alone is enough.
 *
 * The database is always opened through [DatabaseSessionManager.openDatabase] — the same entry
 * point the app uses — and never through a hand-built Room instance, because a hand-built
 * instance would measure the test harness rather than the shipped open path.
 *
 * The parent/child pair is `tasks` -> `task_occurrences`. The child declares
 * `ON DELETE CASCADE` (see `TaskOccurrenceEntity`), so deleting the parent row must remove the
 * dependent row.
 */
@RunWith(AndroidJUnit4::class)
class ForeignKeyEnforcementTest {
    private lateinit var context: Context
    private lateinit var logger: UnifiedLogger
    private lateinit var encryptionManager: DatabaseEncryptionManager
    private lateinit var sessionManager: DatabaseSessionManager

    private val testPassphrase = "ForeignKeyTest123!"

    private val parentTaskId = "fk-proof-parent-task"
    private val childOccurrenceId = "fk-proof-child-occurrence"
    private val fixedTimestamp = "2026-10-22T00:00:00"

    /**
     * Updates the set up.
     */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        logger = UnifiedLogger.getInstance()

        encryptionManager = DatabaseEncryptionManager(context)
        sessionManager = DatabaseSessionManager(context, encryptionManager)

        // Start from a FRESH database file so Room creates the current schema (whose exported DDL
        // carries the ON DELETE CASCADE foreign key) instead of inheriting whatever file a
        // previous test class left behind on the device.
        deleteDatabaseFiles()

        logger.d(
            "ForeignKeyEnforcementTest.setUp",
            "Test setup complete",
            mapOf("testPassphraseLength" to testPassphrase.length),
        )
    }

    /**
     * Performs the tear down.
     */
    @After
    fun tearDown() {
        sessionManager.closeDatabase()
        encryptionManager.resetEncryptionState()
        deleteDatabaseFiles()
        logger.d("ForeignKeyEnforcementTest.tearDown", "Database and encryption state cleaned up")
    }

    /**
     * Shows the production open path leaves foreign key enforcement turned on.
     */
    @Test
    fun foreignKeysPragma_isEnabledOnProductionOpenPath() =
        runTest {
            // Arrange: open the database the way the app opens it
            openProductionSession()

            // Act: read the effective setting from the live handle
            val pragmaValue = readForeignKeysPragma()

            // Assert: SQLite reports 1 for enforcement ON and 0 for OFF
            logger.i(
                "ForeignKeyEnforcementTest.foreignKeysPragma_isEnabledOnProductionOpenPath",
                "Read PRAGMA foreign_keys from the production open path",
                mapOf("foreignKeysPragma" to pragmaValue),
            )
            assertThat(pragmaValue).isEqualTo(PRAGMA_FOREIGN_KEYS_ON)
        }

    /**
     * Shows the live child table really declares the cascade that the consequence test relies on.
     */
    @Test
    fun childTableSchema_declaresOnDeleteCascade() =
        runTest {
            // Arrange: open the database the way the app opens it
            openProductionSession()

            // Act: read the table's own DDL back out of the live schema
            val ddl = readChildTableDdl()
            val normalised = normaliseDdl(ddl)

            // Assert: the declared foreign key really is the cascade, so a cascade miss is
            // attributable to enforcement and not to a missing or changed FK declaration
            logger.i(
                "ForeignKeyEnforcementTest.childTableSchema_declaresOnDeleteCascade",
                "Read the live DDL of the child table",
                mapOf("ddl" to ddl),
            )
            assertThat(normalised).contains(CASCADE_ACTION_TEXT)
            assertThat(normalised).contains("references $PARENT_TABLE_NAME")
            assertThat(normalised).contains(CHILD_COLUMN_NAME.lowercase())
        }

    /**
     * Shows deleting the parent row removes the dependent child row.
     */
    @Test
    fun deletingParentTask_cascadesDeleteToChildOccurrenceRow() =
        runTest {
            // Arrange: open the production session and seed one parent row plus one child row
            openProductionSession()
            val database = sessionManager.requireDatabase()
            val taskDao = database.taskDao()
            val occurrenceDao = database.taskOccurrenceDao()

            taskDao.insert(
                TaskEntity(
                    id = parentTaskId,
                    title = "Foreign key proof parent",
                    createdAt = fixedTimestamp,
                    updatedAt = fixedTimestamp,
                ),
            )
            occurrenceDao.insert(
                TaskOccurrenceEntity(
                    id = childOccurrenceId,
                    taskId = parentTaskId,
                    dueDate = "2026-10-22",
                    status = "pending",
                    createdAt = fixedTimestamp,
                ),
            )

            // Precondition: the dependent row really exists before the parent is deleted, so a
            // later "child is gone" cannot be a pass produced by a silently failed insert
            val before = occurrenceDao.getOccurrencesForTaskForBackfill(parentTaskId)
            assertThat(before).hasSize(1)
            assertThat(countChildRowsOnHandle()).isEqualTo(1)

            // Act: delete the parent through the production DAO write path
            val pragmaValue = readForeignKeysPragma()
            taskDao.deleteById(parentTaskId)

            // Assert: the ON DELETE CASCADE must have removed the dependent row
            val survivors = occurrenceDao.getOccurrencesForTaskForBackfill(parentTaskId)
            val rawSurvivors = countChildRowsOnHandle()
            logger.i(
                "ForeignKeyEnforcementTest.deletingParentTask_cascadesDeleteToChildOccurrenceRow",
                "Deleted the parent row and checked the dependent row",
                mapOf(
                    "foreignKeysPragma" to pragmaValue,
                    "childRowsBefore" to before.size,
                    "childRowsAfterViaDao" to survivors.size,
                    "childRowsAfterViaRawSql" to rawSurvivors,
                ),
            )
            assertThat(survivors).isEmpty()
            assertThat(rawSurvivors).isEqualTo(0)
        }

    /**
     * Opens the database through the PRODUCTION entry point, exactly as the app does.
     */
    private suspend fun openProductionSession() {
        val configured = encryptionManager.configurePassphrase(testPassphrase)
        assertThat(configured).isTrue()
        val openResult = sessionManager.openDatabase(testPassphrase)
        logger.i(
            "ForeignKeyEnforcementTest.openProductionSession",
            "Production open path invoked",
            mapOf("openSucceeded" to openResult.isSuccess),
        )
        assertThat(openResult.isSuccess).isTrue()
    }

    /**
     * Reads the effective `PRAGMA foreign_keys` value from the live production handle.
     *
     * @return 1 when enforcement is ON, 0 when it is OFF, [PRAGMA_NO_ROW] when the pragma
     * returned no row at all.
     */
    private fun readForeignKeysPragma(): Int {
        val handle = sessionManager.requireDatabase().openHelper.writableDatabase
        return handle.query(PRAGMA_FOREIGN_KEYS).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else PRAGMA_NO_ROW
        }
    }

    /**
     * Reads the live DDL of the child table out of `sqlite_master`.
     *
     * The DDL is the declaration itself, so it is read as TEXT and matched as text. Reading the
     * action out of `PRAGMA foreign_key_list` was MEASURED to report NO ACTION for a genuine
     * `ON DELETE CASCADE` on this SQLCipher build — by ordinal and by column name alike — so the
     * pragma's action column is not trusted here and the schema text is.
     */
    private fun readChildTableDdl(): String {
        val handle = sessionManager.requireDatabase().openHelper.writableDatabase
        val sql = "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?"
        return handle.query(sql, arrayOf(CHILD_TABLE_NAME)).use { cursor ->
            assertWithMessage("sqlite_master has no row for table '$CHILD_TABLE_NAME'")
                .that(cursor.moveToFirst())
                .isTrue()
            assertWithMessage("sqlite_master has a NULL DDL for table '$CHILD_TABLE_NAME'")
                .that(cursor.isNull(0))
                .isFalse()
            cursor.getString(0)
        }
    }

    /**
     * Normalises a DDL string for matching: lower-cased, unquoted, and whitespace-collapsed, so
     * that formatting differences between Room versions cannot make the match brittle.
     */
    private fun normaliseDdl(ddl: String): String =
        ddl
            .lowercase()
            .replace("`", "")
            .replace("\"", "")
            .replace("[", "")
            .replace("]", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Counts the dependent rows on the SAME handle the pragma was read from, so the two
     * observations are not taken on different pooled connections.
     */
    private fun countChildRowsOnHandle(): Int {
        val handle = sessionManager.requireDatabase().openHelper.writableDatabase
        val sql = "SELECT COUNT(*) FROM $CHILD_TABLE_NAME WHERE taskId = ?"
        return handle.query(sql, arrayOf(parentTaskId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else PRAGMA_NO_ROW
        }
    }

    /**
     * Removes the database file and its sidecars so each test starts from a fresh file.
     */
    private fun deleteDatabaseFiles() {
        val dbName = PayanamDatabaseSchema.DATABASE_NAME
        val dbDir = context.getDatabasePath(dbName).parentFile
        if (dbDir != null) {
            File(dbDir, dbName).delete()
            File(dbDir, "$dbName-wal").delete()
            File(dbDir, "$dbName-shm").delete()
            File(dbDir, "$dbName-journal").delete()
        }
    }

    private companion object {
        private const val PRAGMA_FOREIGN_KEYS = "PRAGMA foreign_keys"
        private const val PRAGMA_FOREIGN_KEYS_ON = 1
        private const val PRAGMA_NO_ROW = -1
        private const val PARENT_TABLE_NAME = "tasks"
        private const val CHILD_TABLE_NAME = "task_occurrences"
        private const val CHILD_COLUMN_NAME = "taskId"

        // The cascade action as the schema text spells it, after normalisation.
        private const val CASCADE_ACTION_TEXT = "on delete cascade"
    }
}
