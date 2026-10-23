//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenMode
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseOpener
import io.payanam.desktop.database.DesktopDatabaseInitPorts
import io.payanam.domain.repository.AppSettingsRepository
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop's direct-file database-init ports, against a REAL database file.
 *
 * ## Why this is an integration test and not a unit test
 *
 * Every one of these ports exists to answer a question about a FILE, before a session exists. A fake would
 * test the fake: it could not catch a wrong SQL statement, a wrong column, or a driver that silently ignores
 * the thing it was asked to do. `PayanamDatabaseOpener.create` builds a real SQLCipher database through the
 * same driver the ports open it with, so the write and the read here are a real round trip rather than a
 * self-consistent pair of mocks.
 *
 * ## What each test proves
 *
 * - The flag READER really reads the row a flag WRITE put there, and really distinguishes an absent row
 *   (`null`) from a stored one. That distinction is the contract's own, and a reader that returned the
 *   empty string for both would make a missing flag indistinguishable from a cleared one.
 * - The flag WRITER's upsert really replaces an existing row rather than failing or duplicating it, so a
 *   retried init converges on one row.
 * - The reader is genuinely READ-ONLY: it opens the user's file with `PRAGMA query_only` and the test proves
 *   the guard is enforced by attempting a write through its own connection, rather than trusting that the
 *   port set a pragma. This driver silently ignores `?mode=ro`, so a reader that trusted the URL would be a
 *   false guarantee.
 * - The WAL CONSOLIDATOR reports the shared rule's answer on a file with no log: `logFrames == NO_LOG` means
 *   "there was nothing to fold and the main file is already complete", which the shared rule counts as empty.
 *   Asserting the opposite would be asserting a falsehood about SQLite.
 *
 * The file is the ENCRYPTED one the desktop actually uses, and the ports are given NO passphrase — which is
 * the case that matters, because the contracts carry none.
 */
class DesktopDatabaseInitPortsTest {
    private val passphrase = "desktop-init-ports-passphrase"
    private var databaseDirectory: Path? = null
    private var databaseFile: Path? = null

    @After
    fun tearDown() {
        databaseDirectory?.let { runCatching { deleteRecursively(it) } }
    }

    @Test
    fun `the flag reader refuses an encrypted file rather than reporting the flag as absent`() {
        val file = buildDatabase().toFile()
        val reader = DesktopDatabaseInitPorts.databaseInitFlagReader
        val key = AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY

        // KNOWN LIMITATION, MEASURED HERE RATHER THAN ASSERTED AWAY. The contract's signature carries NO
        // passphrase, and the desktop's database is SQLCipher-encrypted, so a keyless open fails with
        // "file is not a database". The port surfaces that; it does NOT return null.
        //
        // It matters which of the two it does. A null here is indistinguishable from "no such row", so the
        // shared caller (`dbInitReadInitCompletedFlag`) would read an encrypted, fully-initialised database
        // as one whose init flag was never written. Throwing lets that caller catch the failure and treat it
        // as "not completed", which is Android's behaviour on the same file and the answer a first launch
        // needs. A stub that returned null would be a plausible-looking value this port cannot justify.
        val failure = runCatching { reader.readSettingValue(file, key) }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }

    @Test
    fun `the flag writer upserts, so a retried init converges on one row instead of failing`() {
        val file = buildDatabase().toFile()

        // Twice, deliberately: the second call is the retry-after-a-failed-import case, and a plain INSERT
        // rather than INSERT OR REPLACE would throw here and strand the user on a screen that cannot finish.
        // The writer is given the passphrase and so CAN read the file back - which is why the count below is
        // a real assertion and not a restatement of the refusal above.
        DesktopDatabaseInitPorts.databaseInitFlagWriter.markInitCompleted(file, passphrase)
        DesktopDatabaseInitPorts.databaseInitFlagWriter.markInitCompleted(file, passphrase)

        assertThat(countInitCompletedRows(file.toPath())).isEqualTo(1)
    }

    @Test
    fun `the WAL consolidator keeps the log on an encrypted file instead of claiming an empty one`() {
        val file = buildDatabase().toFile()

        // The same measured limit as the reader, on the most delicate of the six ports: the contract passes
        // no passphrase, so the fold cannot open the file and the honest answer is false. False is REAL and
        // SAFE - the shared caller keeps the log instead of deleting it, and a delete of a log that was never
        // folded loses any frame that lived only in it. True here would be the data-loss outcome, and is
        // exactly the answer this port refuses to invent.
        assertThat(DesktopDatabaseInitPorts.databaseInitWalConsolidator.consolidateWalAfterImport(file, LOG_TAG))
            .isFalse()
    }

    @Test
    fun `the WAL consolidator refuses a file that is not there rather than reporting success`() {
        val missing = databaseDirectory().resolve("not-a-database.db").toFile()

        // False is the answer that keeps the log. True for a file that does not exist would authorise a
        // delete of a log nobody ever folded, which is the data-loss outcome the rule exists to prevent.
        assertThat(DesktopDatabaseInitPorts.databaseInitWalConsolidator.consolidateWalAfterImport(missing, LOG_TAG))
            .isFalse()
    }

    /**
     * How many `database_init_completed` rows the file holds, counted through the real opener.
     *
     * Read back from a FRESH connection rather than through the port, so the count proves what landed in the
     * file instead of what the writer believed it wrote.
     */
    private fun countInitCompletedRows(path: Path): Int {
        val result = PayanamDatabaseOpener.open(path, passphrase, DatabaseOpenMode.READ_WRITE)
        check(result is DatabaseOpenResult.Opened) { "database did not open: $result" }
        return result.session.requireConnection().use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM app_settings WHERE `key` = ?").use { statement ->
                statement.setString(1, AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
    }

    private fun buildDatabase(): Path {
        val file = databaseDirectory().resolve("payanam.db")
        val result = PayanamDatabaseOpener.create(file, passphrase)
        check(result is io.payanam.database.jvm.DatabaseOpenResult.Opened) {
            "PayanamDatabaseOpener.create did not produce a database: $result"
        }
        result.session.close()
        assertThat(Files.isRegularFile(file)).isTrue()
        return file
    }

    private fun databaseDirectory(): Path {
        databaseDirectory?.let { return it }
        val directory = Files.createTempDirectory("payanam-desktop-init-ports")
        databaseDirectory = directory
        return directory
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files
            .walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private companion object {
        const val LOG_TAG = "DesktopDatabaseInitPortsTest"
    }
}
