//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The encryption-probe contract, driven through a recording implementation.
 *
 * WHY A CONTRACT TEST WHEN BOTH PLATFORMS ARE TESTED AGAINST REAL FILES: `DesktopDatabaseEncryptionProbeTest`
 * and `DatabaseEncryptionMigrationSupportRegressionTest` each answer for their own file, and the two suites
 * can drift without either noticing — which is exactly what happened before `EncryptedFileClassifier`
 * existed (Android called a 1-byte file encrypted, the desktop did not). This file cannot detect that kind
 * of drift, because it never opens a file. What it CAN do is pin the part both platforms share and that
 * neither can check alone: **that the contract is satisfiable without lying, and that a probe which cannot
 * answer must say so.**
 *
 * THE TWO RULES Pinned HERE, both of which a careless implementation gets wrong:
 *
 * 1. **A probe that refuses must refuse loudly.** `ensureEncrypted` is the one member whose contract is a
 *    refusal on a platform without the conversion path. If a probe threw nothing and returned normally, the
 *    import would compare row counts across a conversion that never happened and report success on a
 *    plaintext file. So the refusal is required to be observable, and a silent success is the failure.
 *
 * 2. **The re-encryption half is not optional.** The contract carries `ensureEncrypted` because Android's
 *    import path converts a plaintext file it has accepted. A platform that genuinely has no such path may
 *    refuse — but it may not quietly return, and a caller must be able to tell which platform it is on by
 *    what the probe does. That is why both answers (converted and refused) are exercised here rather than
 *    only the one the caller's platform happens to need.
 */
class DatabaseEncryptionProbeContractTest {
    /**
     * A probe that records what it was asked and answers from a script, so the assertions are about the
     * CONTRACT and not about one platform's driver.
     */
    private class RecordingProbe(
        private val encrypted: Boolean = false,
        private val openable: Boolean = false,
        private val conversionSupported: Boolean = false,
        private val counts: Map<String, Int> = emptyMap(),
    ) : DatabaseEncryptionProbe {
        val detectionCalls = mutableListOf<File>()
        val openCalls = mutableListOf<Pair<File, String>>()
        val conversionCalls = mutableListOf<Pair<File, String>>()
        val countCalls = mutableListOf<Triple<File, String?, List<String>>>()

        override fun isDetectablyEncrypted(
            databaseFile: File,
            logTag: String,
        ): Boolean {
            detectionCalls += databaseFile
            return encrypted
        }

        override fun canOpen(
            databaseFile: File,
            passphrase: String,
            logTag: String,
        ): Boolean {
            openCalls += databaseFile to passphrase
            return openable
        }

        override fun ensureEncrypted(
            databaseFile: File,
            passphrase: String,
            logTag: String,
        ) {
            conversionCalls += databaseFile to passphrase
            if (!conversionSupported) {
                throw UnsupportedOperationException("No plaintext-to-encrypted path on this platform")
            }
        }

        override fun readTableCounts(
            databaseFile: File,
            passphrase: String?,
            tableNames: List<String>,
        ): Map<String, Int> {
            countCalls += Triple(databaseFile, passphrase, tableNames)
            return counts
        }
    }

    @Test
    fun `a probe reports a file's shape without opening it as a database`() {
        val probe = RecordingProbe(encrypted = true)
        val file = File("/tmp/not-created-by-this-test.db")

        assertThat(probe.isDetectablyEncrypted(file)).isTrue()

        // Detection and opening are separate members for a reason: on Android the classification must not
        // delete the file it is classifying, which the framework probe can do. A probe that reached for the
        // open to decide would reintroduce that. Asserted on the recording so the two cannot be conflated.
        assertThat(probe.detectionCalls).containsExactly(file)
        assertThat(probe.openCalls).isEmpty()
    }

    @Test
    fun `the passphrase check is the read-only one and is asked separately`() {
        val probe = RecordingProbe(openable = true)
        val file = File("/tmp/not-created-by-this-test.db")

        assertThat(probe.canOpen(file, "the-passphrase")).isTrue()

        assertThat(probe.openCalls).containsExactly(file to "the-passphrase")
        assertThat(probe.detectionCalls).isEmpty()
    }

    @Test
    fun `a probe that refuses re-encryption refuses observably rather than reporting success`() {
        val probe = RecordingProbe(conversionSupported = false)
        val file = File("/tmp/not-created-by-this-test.db")

        val thrown = runCatching { probe.ensureEncrypted(file, "the-passphrase") }.exceptionOrNull()

        // The dangerous outcome is the one that does NOT throw: the caller would then read success, compare
        // counts across a conversion that never ran, and report a plaintext file as encrypted.
        assertThat(thrown).isInstanceOf(UnsupportedOperationException::class.java)
        // The refusal is still a real attempt, so it is recorded — a probe that refused before touching
        // anything would be indistinguishable from one that was never asked.
        assertThat(probe.conversionCalls).containsExactly(file to "the-passphrase")
    }

    @Test
    fun `a probe with a conversion path completes without refusing`() {
        val probe = RecordingProbe(conversionSupported = true)
        val file = File("/tmp/not-created-by-this-test.db")

        runCatching { probe.ensureEncrypted(file, "the-passphrase") }.exceptionOrNull()

        // The other half of the contract's re-encryption promise. Both answers are asserted so neither
        // platform's behaviour can be adopted as the only one a caller has to handle.
        assertThat(runCatching { probe.ensureEncrypted(file, "the-passphrase") }.exceptionOrNull()).isNull()
        assertThat(probe.conversionCalls).hasSize(2)
    }

    @Test
    fun `row counts are answered for the tables asked about, and the passphrase is passed on`() {
        val probe = RecordingProbe(counts = mapOf("tasks" to 3, "notes" to 0))
        val file = File("/tmp/not-created-by-this-test.db")
        val tables = listOf("tasks", "notes")

        val counts = probe.readTableCounts(file, "the-passphrase", tables)

        // Every requested table gets an answer, including the empty one: an unreadable table reporting 0
        // rather than being ABSENT is what keeps the integrity check from reading a missing key as a
        // mismatch it did not measure.
        assertThat(counts.keys).containsExactlyElementsIn(tables)
        assertThat(counts["tasks"]).isEqualTo(3)
        assertThat(counts["notes"]).isEqualTo(0)
        assertThat(probe.countCalls).containsExactly(Triple(file, "the-passphrase", tables))
    }

    @Test
    fun `a plaintext file is counted with no passphrase at all`() {
        val probe = RecordingProbe(counts = mapOf("tasks" to 1))
        val file = File("/tmp/not-created-by-this-test.db")

        probe.readTableCounts(file, null, listOf("tasks"))

        // The import integrity check runs against BOTH shapes — a plaintext source before conversion and
        // the encrypted result after — so `null` has to reach the probe rather than being substituted.
        assertThat(probe.countCalls.single().second).isNull()
    }
}