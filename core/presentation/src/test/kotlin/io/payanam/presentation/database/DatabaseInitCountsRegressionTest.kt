//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.NoOpUnifiedLogger
import org.junit.After
import org.junit.Test
import java.io.File

/**
 * The regression the import path must not lose.
 *
 * A count that is wrong but plausible is the dangerous failure here, and a count that is silently 0 is the
 * one a user reads as "the conversion destroyed your data". So these pin the two halves of that: an
 * unreadable table reports 0 rather than throwing (a refusal the caller reports, not a crash mid-import),
 * and a REAL loss is still caught as a mismatch.
 *
 * The counts arrive through [DatabaseEncryptionProbe], so this needs no SQLite and no Android runtime — it
 * is the port's contract, not the driver's.
 */
class DatabaseInitCountsRegressionTest {
    @After
    fun resetLogger() {
        AppLogger.resetForTests()
    }

    /** A probe that answers for the tables it is given and reports 0 for the rest, as the contract says. */
    private class FakeProbe(
        private val counts: Map<String, Int>,
    ) : DatabaseEncryptionProbe {
        override fun isDetectablyEncrypted(
            databaseFile: File,
            logTag: String,
        ) = false

        override fun canOpen(
            databaseFile: File,
            passphrase: String,
            logTag: String,
        ) = true

        override fun ensureEncrypted(
            databaseFile: File,
            passphrase: String,
            logTag: String,
        ) = Unit

        override fun readTableCounts(
            databaseFile: File,
            passphrase: String?,
            tableNames: List<String>,
        ): Map<String, Int> = tableNames.associateWith { counts[it] ?: 0 }
    }

    @Test
    fun `readCoreCounts reports zero for a table that could not be counted`() {
        val counts =
            DatabaseImportIntegritySupport.readCoreCounts(
                databaseFile = File("/tmp/payanam-unreadable.db"),
                passphrase = "passphrase-that-cannot-open-it",
                encryptionProbe = FakeProbe(counts = mapOf("tasks" to 3)),
            )
        // 0, not a throw: the file that cannot be counted is a refusal the CALLER reports.
        assertThat(counts["time_entries"]).isEqualTo(0)
        assertThat(counts["notes"]).isEqualTo(0)
        // The table that WAS readable is unaffected: a bad read must not zero the whole map.
        assertThat(counts["tasks"]).isEqualTo(3)
    }

    @Test
    fun `an unreadable read logs why it could not be counted, not just a zero`() {
        // The other half of the same regression: a silent 0 reads as "the conversion destroyed your data",
        // so the read that produced it must say what it was reading and that the file could not answer.
        // The assertion is on the log's own content - the source, the message and the facts a reader needs -
        // rather than on the mere fact that something was logged.
        val recorder = RecordingLogger()
        AppLogger.install(recorder)
        DatabaseImportIntegritySupport.readCoreCounts(
            databaseFile = File("/tmp/payanam-unreadable-logged.db"),
            passphrase = "passphrase-that-cannot-open-it",
            encryptionProbe = FakeProbe(counts = mapOf("tasks" to 3)),
        )

        val read = recorder.entries.single { it.message == "Reading import integrity core counts" }
        assertThat(read.source).isEqualTo("DatabaseImportIntegritySupport.readCoreCounts")
        assertThat(read.data["dbFile"]).isEqualTo("/tmp/payanam-unreadable-logged.db")
        assertThat(read.data["hasPassphrase"]).isEqualTo(true)
        // And the result is logged too, with the zero visible in the entry - the count that the caller
        // compares is never reported without its own record.
        val result = recorder.entries.single { it.message == "Read import integrity core counts" }
        assertThat(result.data["timeEntries"]).isEqualTo(0)
        assertThat(result.data["tasks"]).isEqualTo(3)
    }

    @Test
    fun `readCoreCounts reads a fully readable file without the no-op logger installed`() {
        // No logger installed on purpose: the promoted object logs through AppLogger.current, and reading
        // counts must not depend on a platform having installed one.
        AppLogger.current.let { }
        val counts =
            DatabaseImportIntegritySupport.readCoreCounts(
                databaseFile = File("/tmp/payanam-readable.db"),
                passphrase = null,
                encryptionProbe =
                    FakeProbe(
                        counts = mapOf("tasks" to 3, "time_entries" to 2, "notes" to 1),
                    ),
            )
        assertThat(counts).containsExactly("tasks", 3, "time_entries", 2, "notes", 1)
    }

    @Test
    fun `validateCountsPreserved throws when a conversion actually lost rows`() {
        val failure =
            runCatching {
                DatabaseImportIntegritySupport.validateCountsPreserved(
                    beforeCounts = mapOf("tasks" to 5, "time_entries" to 2, "notes" to 1),
                    afterCounts = mapOf("tasks" to 4, "time_entries" to 2, "notes" to 1),
                    logTag = "Test.validateCountsPreserved",
                )
            }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("tasks")
    }

    @Test
    fun `validateCountsPreserved passes on equal counts and logs the pass`() {
        AppLogger.install(RecordingLogger())
        DatabaseImportIntegritySupport.validateCountsPreserved(
            beforeCounts = mapOf("tasks" to 5, "time_entries" to 2, "notes" to 1),
            afterCounts = mapOf("tasks" to 5, "time_entries" to 2, "notes" to 1),
            logTag = "Test.validateCountsPreserved",
        )
        val logger = AppLogger.current
        assertThat(logger).isInstanceOf(RecordingLogger::class.java)
        assertThat((logger as RecordingLogger).sources).contains("Test.validateCountsPreserved")
    }

    /** A logger that records what it was told, so "logs why" is an assertion and not a hope. */
    private class RecordingLogger : io.payanam.shared.logging.UnifiedLogger {
        data class Entry(
            val source: String,
            val message: String,
            val data: Map<String, Any?>,
        )

        val sources = mutableListOf<String>()
        val entries = mutableListOf<Entry>()

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            sources.add(source)
            entries.add(Entry(source, message, data))
        }

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            sources.add(source)
            entries.add(Entry(source, message, data))
        }

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            sources.add(source)
            entries.add(Entry(source, message, data))
        }

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            sources.add(source)
            entries.add(Entry(source, message, data))
        }

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) {
            sources.add(source)
            entries.add(Entry(source, message, data))
        }
    }

    @Test
    fun `the default logger is a no-op rather than a crash`() {
        AppLogger.resetForTests()
        assertThat(AppLogger.current).isSameInstanceAs(NoOpUnifiedLogger)
    }
}
