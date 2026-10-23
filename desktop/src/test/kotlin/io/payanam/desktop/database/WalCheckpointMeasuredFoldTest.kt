//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.WalCheckpointRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * The fold predicate, MEASURED against real SQLite instead of read off the docs.
 *
 * **Why this file exists.** `WalCheckpointRule` pins the DECISION over hand-built reports, and the JVM
 * session test pins it over a SQLCipher handle. Neither shows what the pragma actually ANSWERS, and that is
 * the part that was got wrong: the documented shape ("`busy = 0` means the log is empty") is not what the
 * row says, and the counts a blocked fold leaves behind are not the ones a reader would guess. Every value
 * below is therefore read from a database this test builds and folds itself.
 *
 * **The driver is plain SQLite, not SQLCipher.** The row is SQLite's, and SQLCipher only carries it: a
 * plain `jdbc:sqlite` connection answers it the same way and can be driven without a key, so the measured
 * answer is asserted where it can be reproduced rather than through a harness that cannot produce it.
 *
 * **The six measured cases:**
 * 1. a database not in WAL mode answers `(0, -1, -1)` and has no log to fold;
 * 2. a completed fold answers `(0, 0, 0)`;
 * 3. `TRUNCATE` leaves the log at ZERO BYTES rather than deleting it - the sidecar goes when the connection does;
 * 4. a fold blocked by a reader answers `(1, 1, 0)` and the log KEEPS its bytes (`4152` for a log this size);
 * 5. the same blocked fold makes [WalCheckpointRule.requireWalEmpty] refuse, naming the counts;
 * 6. the row's order is `(busy, log, checkpointed)` - read in any other order a completed fold looks blocked.
 *
 * The last one is the reason the read lives in the shared rule at all: a caller that guessed the order wrong
 * would report a completed fold as a blocked one, or worse, the reverse.
 */
class WalCheckpointMeasuredFoldTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var databasePath: Path
    private val connections = mutableListOf<Connection>()

    private val walPath: Path
        get() = databasePath.resolveSibling("${databasePath.fileName}-wal")

    private fun open(name: String = "wal-fold.db") {
        databasePath = temporaryFolder.newFolder().toPath().resolve(name)
    }

    private fun connect(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").also { connections += it }

    private fun execute(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { it.execute(sql) }
    }

    /** The pragma must be issued as a QUERY: as an update SQLite runs it and returns nothing. */
    private fun foldRow(connection: Connection): IntArray {
        val row =
            connection.createStatement().use { statement ->
                statement.executeQuery(WalCheckpointRule.FOLD_STATEMENT).use { resultSet ->
                    if (!resultSet.next()) {
                        emptyList()
                    } else {
                        (1..resultSet.metaData.columnCount).map { (resultSet.getObject(it) as Number).toInt() }
                    }
                }
            }
        return row.toIntArray()
    }

    private fun enableWal(connection: Connection): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA journal_mode=WAL").use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    @After
    fun tearDown() {
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    @Test
    fun `case 1 - a database not in WAL mode answers (0, -1, -1) and has no log to fold`() {
        open()
        val connection = connect()
        execute(connection, "CREATE TABLE t (id TEXT PRIMARY KEY)")

        val report = WalCheckpointRule.reportOf(foldRow(connection))

        assertThat(report)
            .isEqualTo(WalCheckpointRule.FoldReport(busy = 0, logFrames = -1, checkpointedFrames = -1))
        assertThat(report.walIsEmpty).isTrue()
        assertThat(Files.exists(walPath)).isFalse()
    }

    @Test
    fun `case 2 - a completed fold answers (0, 0, 0)`() {
        open()
        val connection = connect()
        assertThat(enableWal(connection)).isEqualTo("wal")
        execute(connection, "CREATE TABLE t (id TEXT PRIMARY KEY)")
        execute(connection, "INSERT INTO t (id) VALUES ('a')")

        val report = WalCheckpointRule.reportOf(foldRow(connection))

        assertThat(report)
            .isEqualTo(WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 0))
        assertThat(report.walIsEmpty).isTrue()
    }

    @Test
    fun `case 3 - a fold leaves the log at zero bytes, and the sidecar goes when the connection does`() {
        open()
        val connection = connect()
        enableWal(connection)
        execute(connection, "CREATE TABLE t (id TEXT PRIMARY KEY)")
        execute(connection, "INSERT INTO t (id) VALUES ('a')")
        assertThat(Files.exists(walPath)).isTrue()
        assertThat(Files.size(walPath)).isGreaterThan(0L)

        WalCheckpointRule.reportOf(foldRow(connection))

        // Measured: TRUNCATE truncates. The file survives the fold at zero bytes - it is the CLOSE that
        // removes it, which is why a caller cannot read "the log file is gone" as "the fold finished".
        assertThat(Files.exists(walPath)).isTrue()
        assertThat(Files.size(walPath)).isEqualTo(0L)

        connections.remove(connection)
        connection.close()

        assertThat(Files.exists(walPath)).isFalse()
    }

    @Test
    fun `case 4 - a reader-blocked fold answers (1, 1, 0) and the log keeps its 4152 bytes`() {
        open()
        val (writer, reader) = openBlockedFold()
        assertThat(Files.size(walPath)).isEqualTo(4152L)

        val report = WalCheckpointRule.reportOf(foldRow(writer))

        assertThat(report)
            .isEqualTo(WalCheckpointRule.FoldReport(busy = 1, logFrames = 1, checkpointedFrames = 0))
        assertThat(report.walIsEmpty).isFalse()
        // The blocked fold moved nothing: the log still holds its frame, byte for byte.
        assertThat(Files.size(walPath)).isEqualTo(4152L)
        assertThat(Files.exists(walPath)).isTrue()
        // The reader is what makes it busy; releasing it lets the same fold complete.
        reader.rollback()
        assertThat(WalCheckpointRule.reportOf(foldRow(writer)).busy).isEqualTo(0)
    }

    @Test
    fun `case 5 - a blocked fold makes requireWalEmpty refuse, naming the counts`() {
        open()
        val (writer, _) = openBlockedFold()
        val report = WalCheckpointRule.reportOf(foldRow(writer))

        val failure = runCatching { WalCheckpointRule.requireWalEmpty(report, "export") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("export")
        assertThat(failure).hasMessageThat().contains("busy=1")
        assertThat(failure).hasMessageThat().contains("logFrames=1")
    }

    @Test
    fun `case 6 - the row is read in (busy, log, checkpointed) order`() {
        open()
        val (writer, _) = openBlockedFold()

        val row = foldRow(writer)
        val report = WalCheckpointRule.reportOf(row)

        assertThat(report.busy).isEqualTo(row[0])
        assertThat(report.logFrames).isEqualTo(row[1])
        assertThat(report.checkpointedFrames).isEqualTo(row[2])
    }

    /**
     * A database whose log holds EXACTLY ONE frame that a second connection's read snapshot prevents from
     * being folded, so the pragma answers the blocked row with one frame left.
     *
     * The staged fold in the middle is not decoration: the row counts the log the fold could not empty, so a
     * log that already held frames from the setup would answer with those too - `(1, 7, 5)` on this driver for
     * the same shape - and the measurement would be of the setup rather than of the block.
     */
    private fun openBlockedFold(): Pair<Connection, Connection> {
        val writer = connect()
        assertThat(enableWal(writer)).isEqualTo("wal")
        execute(writer, "CREATE TABLE t (id TEXT PRIMARY KEY, v TEXT)")
        execute(writer, "INSERT INTO t (id, v) VALUES ('a', NULL)")
        // Fold the setup away FIRST, so what is left in the log is only the frame written under the block.
        WalCheckpointRule.reportOf(foldRow(writer))

        val reader = connect()
        reader.autoCommit = false
        reader.createStatement().use { it.executeQuery("SELECT * FROM t").use { resultSet -> resultSet.next() } }
        // An UPDATE of a non-key column on a one-row table dirties exactly ONE page, so the log holds one
        // frame (4152 bytes). A new row would dirty two, which is the same blocked fold with a different
        // count - the shape the row describes has to be the one under test.
        execute(writer, "UPDATE t SET v = 'b' WHERE id = 'a'")
        return writer to reader
    }
}
