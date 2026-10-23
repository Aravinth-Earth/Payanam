//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * [DatabaseTableCountReader] against real SQLCipher files — encrypted, plaintext, and neither.
 *
 * This reader exists so the import integrity check can compare a file's row counts before and after a
 * conversion. The failure that matters is a WRONG BUT PLAUSIBLE count: a silent 0 would read as "the
 * conversion destroyed your data", so every unreadable path here is asserted to be zero AND to leave the
 * file untouched, since the check runs while a conversion may be about to rewrite it.
 */
class DatabaseTableCountReaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "table-count-reader-test-passphrase"
    private val tables = listOf("tasks", "time_entries", "notes")

    private fun path(name: String): Path = tempFolder.newFolder().toPath().resolve(name)

    /** Creates a plaintext SQLCipher-driver file with [rowCounts] rows in each tracked table. */
    private fun createFile(
        file: Path,
        rowCounts: Map<String, Int>,
        key: String?,
    ) {
        val properties = Properties().apply { key?.let { setProperty("key", it) } }
        DriverManager.getConnection("jdbc:sqlcipher:${file.toAbsolutePath()}", properties).use { connection ->
            connection.createStatement().use { statement ->
                rowCounts.keys.forEach { table ->
                    statement.execute("CREATE TABLE $table (id INTEGER PRIMARY KEY AUTOINCREMENT)")
                }
                rowCounts.forEach { (table, count) ->
                    repeat(count) { statement.execute("INSERT INTO $table DEFAULT VALUES") }
                }
            }
        }
    }

    @Test
    fun `counts rows in an encrypted file with the right passphrase`() {
        val file = path("encrypted.db")
        createFile(file, mapOf("tasks" to 3, "time_entries" to 7, "notes" to 1), passphrase)

        val counts = DatabaseTableCountReader.readTableCounts(file, passphrase, tables)

        assertThat(counts).containsExactly("tasks", 3, "time_entries", 7, "notes", 1)
    }

    @Test
    fun `counts a plaintext file when no passphrase is supplied`() {
        // The import integrity check runs against BOTH shapes: a plaintext source file, and the encrypted
        // result. A reader that only handled the encrypted case would report zeros and look like data loss.
        val file = path("plaintext.db")
        createFile(file, mapOf("tasks" to 2, "time_entries" to 0, "notes" to 5), key = null)

        val counts = DatabaseTableCountReader.readTableCounts(file, null, tables)

        assertThat(counts).containsExactly("tasks", 2, "time_entries", 0, "notes", 5)
    }

    @Test
    fun `reports zeros rather than throwing when the passphrase is wrong`() {
        // A wrong passphrase is an ordinary user outcome, not an exceptional one: the check must still get
        // an answer for all three tables so the caller never sees a missing key.
        val file = path("wrong-key.db")
        createFile(file, mapOf("tasks" to 4, "time_entries" to 1, "notes" to 1), passphrase)

        val counts = DatabaseTableCountReader.readTableCounts(file, "not-the-passphrase", tables)

        assertThat(counts).containsExactly("tasks", 0, "time_entries", 0, "notes", 0)
    }

    @Test
    fun `reports zeros for a file that does not exist, and reports every requested table`() {
        val counts = DatabaseTableCountReader.readTableCounts(path("absent.db"), passphrase, tables)

        assertThat(counts.keys).containsExactlyElementsIn(tables)
        assertThat(counts.values.toSet()).containsExactly(0)
    }

    @Test
    fun `every zero-reporting path logs why it could not read`() {
        // The reader's own contract says every path that yields zeros logs why - a silent 0 would read as
        // "the conversion destroyed your data" to the integrity check. This asserts that contract at the
        // only place it can be observed: the logger the reader actually writes through.
        val recorder = RecordingLogger()
        AppLogger.install(recorder)
        try {
            DatabaseTableCountReader.readTableCounts(path("absent.db"), passphrase, tables)
            DatabaseTableCountReader.readTableCounts(
                path("wrong-key.db").also { createFile(it, mapOf("tasks" to 4), passphrase) },
                "not-the-passphrase",
                tables,
            )
        } finally {
            AppLogger.resetForTests()
        }

        val warnings = recorder.warnings.filter { it.source.startsWith("DatabaseTableCountReader") }
        // Two distinct zero-reporting paths, asserted by their own words: the absent file, and the
        // wrong-passphrase read - whose file DOES open (SQLCipher accepts the handle) but yields no
        // readable table, so its warning comes once per requested table from the counting path.
        assertThat(warnings.map { it.message }.toSet())
            .containsExactly(
                "No database file to count; reporting zeros",
                "Could not count a table; reporting zero for it",
            )
        assertThat(warnings.filter { it.message.contains("No database file") }).hasSize(1)
        assertThat(warnings.filter { it.message.contains("Could not count a table") })
            .hasSize(tables.size)
    }

    @Test
    fun `reading never modifies the file`() {
        // The check runs while a conversion may be about to rewrite this exact file, so a read that touched
        // it — even by creating a sidecar or bumping the size — would be a real defect. This is also the
        // property that makes `?mode=ro` unsafe to rely on: the driver ignores it.
        val file = path("untouched.db")
        createFile(file, mapOf("tasks" to 2, "time_entries" to 2, "notes" to 2), passphrase)
        val sizeBefore = file.toFile().length()
        val modifiedBefore = file.toFile().lastModified()

        DatabaseTableCountReader.readTableCounts(file, passphrase, tables)

        assertThat(file.toFile().length()).isEqualTo(sizeBefore)
        assertThat(file.toFile().lastModified()).isEqualTo(modifiedBefore)
        val siblings =
            file
                .toFile()
                .parentFile
                .listFiles()!!
                .map { it.name }
        assertThat(siblings).containsExactly("untouched.db")
    }

    @Test
    fun `a wrong passphrase does not modify the file either`() {
        val file = path("untouched-on-failure.db")
        createFile(file, mapOf("tasks" to 1, "time_entries" to 1, "notes" to 1), passphrase)
        val sizeBefore = file.toFile().length()

        DatabaseTableCountReader.readTableCounts(file, "wrong", tables)

        assertThat(file.toFile().length()).isEqualTo(sizeBefore)
    }

    /** Captures log entries so the reader's zero-reporting paths can be asserted rather than assumed. */
    private class RecordingLogger : UnifiedLogger {
        data class Entry(
            val level: String,
            val source: String,
            val message: String,
            val data: Map<String, Any?>,
        )

        private val entries = mutableListOf<Entry>()

        val warnings: List<Entry> get() = entries.filter { it.level == "W" }

        val all: List<Entry> get() = entries

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            entries += Entry("D", source, message, data)
        }

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            entries += Entry("I", source, message, data)
        }

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            entries += Entry("W", source, message, data)
        }

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            entries += Entry("E", source, message, data)
        }

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            entries += Entry("E", source, message, data)
        }
    }

}
