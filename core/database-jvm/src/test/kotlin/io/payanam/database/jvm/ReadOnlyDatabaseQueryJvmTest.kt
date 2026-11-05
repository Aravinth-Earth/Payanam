//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.port.DatabaseSchemaResult
import io.payanam.domain.port.ReadOnlyQueryOutcome
import io.payanam.domain.port.ReadOnlyRefusal
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * [ReadOnlyDatabaseQueryJvm] against a real SQLCipher connection — the driver that decides whether a
 * write can land, not a mock of it.
 *
 * The behaviour under test is a REFUSAL, and a refusal that cannot fail is the failure this class
 * exists to make impossible. So the central cases here are the two ways a write can be attempted: the
 * engine must reject it on the handle this class hands out, and an opener that HANDS BACK a writable
 * handle must be refused rather than believed.
 *
 * Fixture and session follow [JdbcQueriesTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class ReadOnlyDatabaseQueryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "read-only-query-test-passphrase"
    private lateinit var file: Path
    private lateinit var connection: Connection

    /** How many times the injected open was asked for a handle; proves the cache. */
    private var openCalls = 0

    /** Every connection the injected open handed out, so teardown can close what the port did not. */
    private val portConnections = mutableListOf<Connection>()

    @Before
    fun setUp() {
        file = tempFolder.newFolder().toPath().resolve("read-only.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${file.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT, note TEXT)")
            statement.execute("CREATE TABLE app_settings (key TEXT PRIMARY KEY, value TEXT)")
            statement.execute("INSERT INTO tasks (id, title, note) VALUES (1, 'first', 'kept')")
            statement.execute("INSERT INTO tasks (id, title, note) VALUES (2, 'second', NULL)")
        }
    }

    @After
    fun tearDown() {
        // Run in reverse: the port's last handle closed first, so nothing closes twice.
        portConnections.forEach { owned ->
            runCatching { owned.close() }
        }
        connection.close()
    }

    /** A second connection to the same file, the way [PayanamDatabaseOpener] would hand one over. */
    private fun openOwnConnection(): Connection =
        DriverManager.getConnection(
            "jdbc:sqlcipher:${file.toAbsolutePath()}",
            Properties().apply { setProperty("key", passphrase) },
        )

    /**
     * The port over a genuine read-only handle: `PRAGMA query_only = ON`, read back before use exactly
     * as [PayanamDatabaseOpener] does it.
     *
     * Each open gets its OWN connection, never the fixture's: the port CLOSES the handle it opened, so
     * an injected open that handed back the fixture's connection would have [close] kill the assertions'
     * own handle. Production opens its own, and the fixture must match that or it is testing a class
     * that cannot exist.
     */
    private fun portWith(queryOnly: Boolean = true): ReadOnlyDatabaseQueryJvm =
        ReadOnlyDatabaseQueryJvm(
            databaseFile = { file },
            passphrase = { passphrase.toCharArray() },
            open = { _, _, mode ->
                openCalls++
                val owned = openOwnConnection()
                portConnections += owned
                if (queryOnly) {
                    owned.createStatement().use { it.execute("PRAGMA query_only = ON") }
                }
                DatabaseOpenResult.Opened(PayanamDatabaseSession(owned, 21, mode))
            },
        )

    private fun rows(
        sql: String,
        maxRows: Int = 10,
    ): ReadOnlyQueryOutcome = portWith().query(sql, maxRows)

    @Test
    fun `reads rows with their column labels`() {
        val outcome = rows("SELECT id, title FROM tasks ORDER BY id")

        assertThat(outcome).isInstanceOf(ReadOnlyQueryOutcome.Rows::class.java)
        val read = outcome as ReadOnlyQueryOutcome.Rows
        assertThat(read.columns).containsExactly("id", "title").inOrder()
        assertThat(read.rows).containsExactly(listOf("1", "first"), listOf("2", "second")).inOrder()
        assertThat(read.truncated).isFalse()
    }

    @Test
    fun `a NULL cell stays null rather than becoming the text null`() {
        val read = rows("SELECT note FROM tasks WHERE id = 2") as ReadOnlyQueryOutcome.Rows

        assertThat(read.rows).containsExactly(listOf(null))
    }

    @Test
    fun `the row cap truncates and says so instead of silently shortening`() {
        val read = rows("SELECT id FROM tasks ORDER BY id", maxRows = 1) as ReadOnlyQueryOutcome.Rows

        assertThat(read.rows).containsExactly(listOf("1"))
        assertThat(read.truncated).isTrue()
    }

    @Test
    fun `the row cap that is not hit does not claim truncation`() {
        val read = rows("SELECT id FROM tasks ORDER BY id", maxRows = 2) as ReadOnlyQueryOutcome.Rows

        assertThat(read.rows).hasSize(2)
        assertThat(read.truncated).isFalse()
    }

    @Test
    fun `a row cap of zero is a caller bug and throws rather than reading`() {
        val port = portWith()

        val thrown = runCatching { port.query("SELECT 1", 0) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a statement the engine refuses is reported, and the read-only handle stays usable`() {
        val port = portWith()

        val failed = port.query("SELECT * FROM table_that_does_not_exist", 5)
        val afterwards = port.query("SELECT COUNT(*) FROM tasks", 5)

        assertThat(failed).isInstanceOf(ReadOnlyQueryOutcome.Refused::class.java)
        assertThat((failed as ReadOnlyQueryOutcome.Refused).reason).isEqualTo(ReadOnlyRefusal.EXECUTION_FAILED)
        assertThat((afterwards as ReadOnlyQueryOutcome.Rows).rows).containsExactly(listOf("2"))
    }

    // ---------------------------------------------------------------------------------------------
    // The two structural claims. Everything above is behaviour; these two are the guarantee.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the engine itself refuses a write on the handle this class read through`() {
        val port = portWith()
        port.query("SELECT COUNT(*) FROM tasks", 1)

        // Deliberately bypassing the port's keyword check and going straight at a connection carrying
        // its `PRAGMA query_only`: this asks the only thing that matters — whether a write that got PAST
        // the check could still land. Asserting only the refusal would pass with the check doing all
        // the work, and the guard is exactly what this class must not rely on.
        val guarded = openOwnConnection().also { portConnections += it }
        guarded.createStatement().use { it.execute("PRAGMA query_only = ON") }
        val writeThrew =
            runCatching {
                guarded.createStatement().use { statement ->
                    statement.executeUpdate("INSERT INTO tasks (id, title) VALUES (3, 'sneaked in')")
                }
            }.exceptionOrNull()
        val count =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tasks").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }

        assertThat(writeThrew).isInstanceOf(SQLException::class.java)
        assertThat(count).isEqualTo(2)
        port.close()
        port.close()
    }

    @Test
    fun `a write statement is refused as a write, and nothing reaches the database`() {
        val port = portWith()

        val refused = port.query("DELETE FROM tasks", 5)
        val count =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tasks").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }

        assertThat(refused).isInstanceOf(ReadOnlyQueryOutcome.Refused::class.java)
        assertThat((refused as ReadOnlyQueryOutcome.Refused).reason)
            .isEqualTo(ReadOnlyRefusal.NOT_A_READ_ONLY_STATEMENT)
        assertThat(count).isEqualTo(2)
        assertThat(openCalls).isEqualTo(0)
        port.close()
    }

    @Test
    fun `WITH is refused because a CTE can be a write`() {
        val port = portWith()

        val refused = port.query("WITH gone AS (SELECT 1) DELETE FROM tasks", 5)

        assertThat((refused as ReadOnlyQueryOutcome.Refused).reason)
            .isEqualTo(ReadOnlyRefusal.NOT_A_READ_ONLY_STATEMENT)
        assertThat(refused.detail).isNotEmpty()
        port.close()
    }

    @Test
    fun `a handle that cannot be proven read-only is refused, even when the opener says it is`() {
        // The opener LIES: it reports READ_ONLY, but query_only was never set on the connection.
        val port = portWith(queryOnly = false)

        val outcome = port.query("SELECT COUNT(*) FROM tasks", 5)

        assertThat((outcome as ReadOnlyQueryOutcome.Refused).reason)
            .isEqualTo(ReadOnlyRefusal.READ_ONLY_NOT_ENFORCED)
        assertThat(openCalls).isEqualTo(1)
        port.close()
    }

    @Test
    fun `an open that throws is a refusal and never a read`() {
        val port =
            ReadOnlyDatabaseQueryJvm(
                databaseFile = { file },
                passphrase = { passphrase.toCharArray() },
                open = { _, _, _ -> throw SQLException("no native payload") },
            )

        val outcome = port.query("SELECT 1", 5)

        assertThat((outcome as ReadOnlyQueryOutcome.Refused).reason).isEqualTo(ReadOnlyRefusal.DATABASE_NOT_OPEN)
    }

    @Test
    fun `a refused open is a refusal and never a read`() {
        val port =
            ReadOnlyDatabaseQueryJvm(
                databaseFile = { file },
                passphrase = { passphrase.toCharArray() },
                open = { _, _, _ ->
                    DatabaseOpenResult.Refused(DatabaseBootIssue(DatabaseBootIssueType.OPEN_FAILED))
                },
            )

        val outcome = port.query("SELECT 1", 5)

        assertThat((outcome as ReadOnlyQueryOutcome.Refused).reason).isEqualTo(ReadOnlyRefusal.DATABASE_NOT_OPEN)
        assertThat(outcome.detail).contains("OPEN_FAILED")
    }

    @Test
    fun `no passphrase for an open session is a refusal, not an empty table`() {
        val port =
            ReadOnlyDatabaseQueryJvm(
                databaseFile = { file },
                passphrase = { null },
                open = { _, _, _ -> throw AssertionError("the open must not be reached") },
            )

        val outcome = port.query("SELECT 1", 5)

        assertThat((outcome as ReadOnlyQueryOutcome.Refused).reason).isEqualTo(ReadOnlyRefusal.DATABASE_NOT_OPEN)
    }

    @Test
    fun `the handle is opened once and reused, so the key derivation is paid once`() {
        val port = portWith()

        port.query("SELECT 1", 1)
        port.query("SELECT 2", 1)
        port.query("SELECT 3", 1)

        assertThat(openCalls).isEqualTo(1)
        port.close()
    }

    @Test
    fun `a closed handle is reopened rather than read through`() {
        val port = portWith()
        port.query("SELECT 1", 1)
        port.close()
        port.close()

        val second = port.query("SELECT COUNT(*) FROM tasks", 1)

        assertThat((second as ReadOnlyQueryOutcome.Rows).rows).containsExactly(listOf("2"))
        assertThat(openCalls).isEqualTo(2)
    }

    // ---------------------------------------------------------------------------------------------
    // The schema reader: one question, no SQL argument, and no key-holding table in the answer.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the schema lists the user tables and omits the settings table that holds the key`() {
        val result = portWith().readSchema()

        val tables = (result as DatabaseSchemaResult.Tables).tables
        assertThat(tables.map { it.name }).containsExactly("tasks")
        assertThat(tables.single().ddl).contains("CREATE TABLE tasks")
        assertThat(tables.single().name).isEqualTo("tasks")
    }

    @Test
    fun `the schema of an unreadable database is a refusal, not an empty schema`() {
        val port =
            ReadOnlyDatabaseQueryJvm(
                databaseFile = { file },
                passphrase = { null },
                open = { _, _, _ -> throw AssertionError("the open must not be reached") },
            )

        val result = port.readSchema()

        assertThat((result as DatabaseSchemaResult.Refused).reason).isEqualTo(ReadOnlyRefusal.DATABASE_NOT_OPEN)
    }
}
