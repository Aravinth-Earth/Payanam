//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * Tests for [JdbcQueries], focused on the transaction helper's nesting contract.
 *
 * This is the behaviour every multi-table write in the layer depends on, and the one that fails silently:
 * a nested `BEGIN`/`COMMIT` pair commits the OUTER transaction's work early, after which the outer rollback
 * has nothing left to undo. Every assertion here would still pass if the nesting guard were removed and
 * nothing threw, which is why the rollback case is asserted explicitly.
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class JdbcQueriesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "jdbc-queries-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("queries.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            // `value` is nullable so the NULL-binding test can prove `setString(i, null)` writes SQL NULL
            // rather than the four-character string "null".
            statement.execute("CREATE TABLE probe (id TEXT PRIMARY KEY NOT NULL, value TEXT)")
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun insert(
        id: String,
        value: String,
    ) {
        JdbcQueries.write(session, "INSERT INTO probe (id, value) VALUES (?, ?)", arrayOf(id, value))
    }

    private fun ids(): Set<String> =
        JdbcQueries.read(session, "SELECT id FROM probe", emptyArray<String?>()) { resultSet ->
            buildSet {
                while (resultSet.next()) add(resultSet.getString(1))
            }
        }

    @Test
    fun `a nested transaction joins the outer one so the outer rollback undoes both writes`() {
        val failure =
            runCatching {
                JdbcQueries.transaction(session) {
                    insert("outer", "1")
                    // The nested call must NOT commit; if it does, the rollback below cannot remove
                    // "outer" and this test fails on the final assertion.
                    JdbcQueries.transaction(session) {
                        insert("inner", "2")
                    }
                    throw IllegalStateException("outer failed after the inner write")
                }
            }

        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(ids()).isEmpty()
    }

    @Test
    fun `a nested transaction that throws propagates and rolls the outer transaction back`() {
        val failure =
            runCatching {
                JdbcQueries.transaction(session) {
                    insert("outer", "1")
                    JdbcQueries.transaction(session) {
                        insert("inner", "2")
                        throw IllegalStateException("inner failed")
                    }
                }
            }

        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(ids()).isEmpty()
    }

    @Test
    fun `a committed transaction keeps its work and restores autoCommit`() {
        JdbcQueries.transaction(session) {
            insert("committed", "1")
        }

        assertThat(ids()).containsExactly("committed")
        assertThat(connection.autoCommit).isTrue()
    }

    @Test
    fun `a failed transaction leaves autoCommit as it found it`() {
        runCatching {
            JdbcQueries.transaction(session) {
                insert("doomed", "1")
                throw SQLException("failure")
            }
        }

        assertThat(connection.autoCommit).isTrue()
        assertThat(ids()).isEmpty()
    }

    @Test
    fun `an Error still rolls the transaction back`() {
        val failure =
            runCatching {
                JdbcQueries.transaction(session) {
                    insert("doomed", "1")
                    throw StackOverflowError("not an Exception")
                }
            }

        // The catch is Throwable, so an Error cannot leave a half-applied write behind.
        assertThat(failure.exceptionOrNull()).isInstanceOf(StackOverflowError::class.java)
        assertThat(ids()).isEmpty()
        assertThat(connection.autoCommit).isTrue()
    }

    @Test
    fun `readScalar returns null for an empty table and for a null aggregate`(): Unit =
        runBlocking {
            assertThat(JdbcQueries.readScalar(session, "SELECT id FROM probe", emptyArray<String?>())).isNull()
            assertThat(JdbcQueries.readScalar(session, "SELECT MIN(id) FROM probe", emptyArray<String?>())).isNull()

            insert("only", "1")

            assertThat(JdbcQueries.readScalar(session, "SELECT id FROM probe", emptyArray<String?>()))
                .isEqualTo("only")
        }

    @Test
    fun `a null parameter binds SQL NULL rather than the string null`() {
        JdbcQueries.write(session, "INSERT INTO probe (id, value) VALUES (?, ?)", arrayOf("nulled", null))

        val isNull =
            JdbcQueries.read(
                session,
                "SELECT value IS NULL FROM probe WHERE id = ?",
                arrayOf("nulled"),
            ) { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }

        assertThat(isNull).isEqualTo(1)
    }
}
