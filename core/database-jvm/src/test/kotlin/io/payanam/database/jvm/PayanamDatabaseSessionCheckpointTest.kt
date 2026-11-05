//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.database.WalCheckpointRule.FoldReport
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * The WAL checkpoint an export depends on, with every value MEASURED on driver `0.2.13` rather than assumed.
 *
 * The export rule is "fold the log, then copy the main file". If the fold does not happen, the copy is a
 * snapshot missing the user's most recent writes — which the user experiences as data loss. So the point of
 * these tests is that the fold is observable, and that the state it leaves behind is one the desktop's own
 * opener will accept.
 */
class PayanamDatabaseSessionCheckpointTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var databasePath: Path
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    private fun walPath(): Path = databasePath.resolveSibling("${databasePath.fileName}-wal")

    private fun shmPath(): Path = databasePath.resolveSibling("${databasePath.fileName}-shm")

    private fun open(name: String = "payanam.db") {
        databasePath = tempFolder.newFolder().toPath().resolve(name)
        connection =
            DriverManager.getConnection(
                "jdbc:sqlcipher:${databasePath.toAbsolutePath()}",
                Properties().apply { setProperty("key", "checkpoint-test") },
            )
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
    }

    /**
     * Switches the journal mode AS A QUERY.
     *
     * This is not style: issuing it through `Statement.execute` silently leaves the database in `delete`
     * mode, proved by reading the mode back afterwards — see
     * [the mode only changes when the pragma is a query]. Everything below depends on WAL actually being on.
     */
    private fun enableWal(): String? = queryString("PRAGMA journal_mode=WAL")

    private fun queryString(sql: String): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet -> if (resultSet.next()) resultSet.getString(1) else null }
        }

    private fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    @After
    fun tearDown() {
        // The pure-rule test never opens a database, so the field is uninitialised there.
        if (this::session.isInitialized && session.isOpen) session.close()
    }

    @Test
    fun `a database with no write-ahead log reports an empty WAL and nothing to fold`() {
        open()
        execute("CREATE TABLE t (id TEXT PRIMARY KEY)")

        val result = session.checkpoint()

        // Measured on a `delete`-mode database: the pragma answers -1 for both frame counts, because
        // there is no log. That is EMPTY, not a failure - the main file already holds everything, which
        // is why WalCheckpointRule names the -1 as NO_LOG instead of leaving it as a magic number.
        assertThat(result).isEqualTo(FoldReport(busy = 0, logFrames = WalCheckpointRule.NO_LOG, checkpointedFrames = WalCheckpointRule.NO_LOG))
        assertThat(result.walIsEmpty).isTrue()
    }

    @Test
    fun `folding a write-ahead log leaves no frames behind`() {
        open()
        assertThat(enableWal()).isEqualTo("wal")
        execute("CREATE TABLE t (id TEXT PRIMARY KEY)")
        execute("INSERT INTO t (id) VALUES ('a')")
        // The log really does hold the write: without this the test would pass on a database that never
        // had a log, and would prove nothing about the fold.
        assertThat(Files.exists(walPath())).isTrue()
        assertThat(Files.size(walPath())).isGreaterThan(0L)

        val result = session.checkpoint()

        // Measured: a completed fold reports the tuple (0, 0, 0) - not blocked, no frames left in the log,
        // none left to fold - and TRUNCATE leaves the log at ZERO bytes rather than deleting it. The sidecar
        // disappears when the session closes, which is what the next test pins.
        assertThat(result).isEqualTo(FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 0))
        assertThat(result.walIsEmpty).isTrue()
        assertThat(Files.size(walPath())).isEqualTo(0L)
    }

    @Test
    fun `the sidecars a WAL database leaves behind are gone once the session closes`() {
        open()
        enableWal()
        execute("CREATE TABLE t (id TEXT PRIMARY KEY)")
        execute("INSERT INTO t (id) VALUES ('a')")
        session.checkpoint()
        // While the session is open the fold leaves a zero-byte log plus a shared-memory file, both of
        // which the desktop's opener REFUSES to open beside a database. That refusal is correct - it is
        // what makes a read-only verification safe - and it is why an import must close the current
        // session before replacing the file.
        assertThat(Files.exists(walPath())).isTrue()
        assertThat(Files.exists(shmPath())).isTrue()

        session.close()

        assertThat(Files.exists(walPath())).isFalse()
        assertThat(Files.exists(shmPath())).isFalse()
    }

    @Test
    fun `the journal mode only changes when the pragma is a query`() {
        open()
        // The silent no-op: `execute` does not switch the mode, and nothing reports an error.
        execute("PRAGMA journal_mode=WAL")

        assertThat(queryString("PRAGMA journal_mode")).isEqualTo("delete")

        // Issued as a query it works, and the change is durable (the mode lives in the database header).
        assertThat(enableWal()).isEqualTo("wal")
        assertThat(queryString("PRAGMA journal_mode")).isEqualTo("wal")
    }

    @Test
    fun `a closed session refuses to checkpoint rather than reporting a fold that did not happen`() {
        open()
        session.close()

        val failure = runCatching { session.checkpoint() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("closed")
    }

    @Test
    fun `the WAL reads as empty only when the fold ran and left nothing behind`() {
        // The busy case could not be reproduced through a real second reader - a completed read releases its
        // lock before the pragma runs - so the rule is asserted directly rather than through a scenario that
        // does not actually produce it. The decision itself is pinned in WalCheckpointRuleTest; what this
        // pins is that the session's own report is the one the shared rule reads.
        assertThat(FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 0).walIsEmpty).isTrue()
        assertThat(FoldReport(busy = 0, logFrames = WalCheckpointRule.NO_LOG, checkpointedFrames = WalCheckpointRule.NO_LOG).walIsEmpty).isTrue()
        assertThat(FoldReport(busy = 1, logFrames = 5, checkpointedFrames = 5).walIsEmpty).isFalse()
        // The stricter half, and the reason the rule exists: not blocked, but frames still in the log.
        assertThat(FoldReport(busy = 0, logFrames = 5, checkpointedFrames = 5).walIsEmpty).isFalse()
        // The pragma returning no row at all is reported as busy, so it can never read as an empty fold.
        assertThat(FoldReport(busy = 1, logFrames = WalCheckpointRule.NO_LOG, checkpointedFrames = WalCheckpointRule.NO_LOG).walIsEmpty).isFalse()
    }
}
