//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/** Long enough for a real dispatcher hop on a loaded CI box, short enough to fail rather than hang. */
private const val FLOW_TIMEOUT_MS = 10_000L

/**
 * A closed session ENDS an observation stream instead of failing it, and only a closed session does.
 *
 * Before this, the stream kept polling and the next read threw `IllegalStateException` out of the flow. The
 * collectors that own these streams run on a bare `Dispatchers.Default` scope with no exception handler, so
 * the failure reached the JVM-global collector and was re-thrown as `UncaughtExceptionsBeforeTest` by
 * whichever LATER test opened a TestScope - a flake that moved from run to run and blocked every branch.
 *
 * Fixture follows [JdbcQueriesTest]: a generated SQLCipher file in a per-class temp directory.
 */
class JdbcFlowsClosedSessionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var session: PayanamDatabaseSession

    private fun open() {
        val path: Path = tempFolder.newFolder().toPath().resolve("flows.db")
        val properties = Properties().apply { setProperty("key", "jdbc-flows-closed-session-passphrase") }
        val connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE t (id TEXT PRIMARY KEY NOT NULL)")
        }
        // The session owns the connection: closing it closes the connection, so no second handle is kept.
        session = PayanamDatabaseSession.forGeneratedFixture(connection, schemaVersion = 21)
    }

    @After
    fun tearDown() {
        // The unchecked-session cases close the session themselves; close() is idempotent.
        if (this::session.isInitialized && session.isOpen) session.close()
    }

    /** One poll of the fixture table, through the same read seam every observing repository uses. */
    private fun readIds(): List<String> =
        JdbcQueries.read(session, "SELECT id FROM t", emptyArray<String>()) { resultSet ->
            buildList { while (resultSet.next()) add(resultSet.getString(1)) }
        }

    @Test
    fun `closing the session completes the observation stream instead of throwing`() = runBlocking {
        open()
        // Completed once the first value has been EMITTED, so the close below cannot land before the stream
        // has proven it runs.
        val firstEmission = CompletableDeferred<Unit>()
        val collector =
            async {
                JdbcFlows
                    .observe(pollIntervalMs = 1L) { readIds() }
                    .onEach { firstEmission.complete(Unit) }
                    // Returns only when the flow COMPLETES: an infinite poll ends by cancelling or by
                    // completing, and a throw here would surface as this test's own failure.
                    .toList()
            }

        withTimeout(FLOW_TIMEOUT_MS) { firstEmission.await() }
        session.close()

        val values = withTimeout(FLOW_TIMEOUT_MS) { collector.await() }

        // The one emission the unchanged empty fixture produces, then the stream ended: not cancelled, no throw.
        assertThat(values).hasSize(1)
        assertThat(values.single()).isEmpty()
        assertThat(collector.isCompleted).isTrue()
        assertThat(collector.isCancelled).isFalse()
    }

    @Test
    fun `a write on a closed session still fails loudly`() {
        open()
        session.close()

        val failure =
            runCatching {
                JdbcQueries.write(session, "INSERT INTO t (id) VALUES (?)", arrayOf("one"))
            }.exceptionOrNull()

        // The typed failure, so the observation path can recognise it - and still an IllegalStateException,
        // which is what every existing caller of a closed session was already promised.
        assertThat(failure).isInstanceOf(PayanamDatabaseSessionClosedException::class.java)
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo("PayanamDatabaseSession is closed")
    }

    @Test
    fun `an unrelated IllegalStateException from the read still fails the stream`() = runBlocking {
        open()

        val failure =
            runCatching {
                JdbcFlows
                    .observe(pollIntervalMs = 1L) {
                        // The session is OPEN here, so this is a real defect rather than an end of stream.
                        throw IllegalStateException("a read defect, not a closed session")
                    }.first()
            }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).isNotInstanceOf(PayanamDatabaseSessionClosedException::class.java)
        assertThat(failure?.message).isEqualTo("a read defect, not a closed session")
    }
}
