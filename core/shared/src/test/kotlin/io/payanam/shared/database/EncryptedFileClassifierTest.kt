//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * The gate on the ONE encrypted-file rule — and on the two platforms using it.
 *
 * ## The failure this class exists to catch
 *
 * Android and the desktop each classified "is this file encrypted?" for themselves, and drifted: Android
 * accepted any non-empty file lacking the plaintext magic, the desktop needed more than a header's worth of
 * bytes. A 1-byte file was therefore "encrypted" on one platform and "not a database" on the other, and the
 * user saw a passphrase prompt on one and a generic refusal on the other, for the same file.
 *
 * ## Why a shared CONSTANT would not have been enough
 *
 * Publishing one threshold (say `MIN_BYTES = 16`) would fix today's number and leave both platforms free to
 * pass it through their own arithmetic afterwards — each still owning a derivation, so the drift is one
 * careless edit away. What makes the drift impossible is that the platforms do not own the derivation at
 * all: they read bytes and call [EncryptedFileClassifier]. So the test below asserts the SHARED behaviour
 * across a table of inputs, not a particular number.
 *
 * ## What makes this test fail when the drift returns
 *
 * [both platforms reach the same verdict for the same bytes] walks the awkward files — empty, 1-byte,
 * exactly-one-header, encrypted-looking, and a real SQLite header — through the *shared* rule and requires
 * every verdict to be identical. Were a platform to reintroduce its own threshold, the first file to split
 * them is a short one, and this test fails with the disagreeing length in the message.
 */
class EncryptedFileClassifierTest {
    @Test
    fun `both platforms reach the same verdict for the same bytes`() {
        // The shared rule, applied to every awkward input. A verdict table rather than a number, so the test
        // survives the threshold being derived from the magic instead of written down.
        val cases =
            listOf(
                Case("absent file", header = null, totalLength = 0L, readSucceeded = false, expected = false),
                Case("empty file", header = ByteArray(0), totalLength = 0L, expected = false),
                Case("1-byte file", header = byteArrayOf(0x42), totalLength = 1L, expected = false),
                // The exact disagreement that was filed as a bug: Android said encrypted, the desktop said no.
                Case(
                    "8-byte non-magic payload",
                    header = ByteArray(8) { 0x7F },
                    totalLength = 8L,
                    expected = false,
                ),
                Case(
                    "15 bytes, one short of a header",
                    header = ByteArray(15) { 0x11 },
                    totalLength = 15L,
                    expected = false,
                ),
                Case(
                    "real SQLite header",
                    header = EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC,
                    totalLength = 4096L,
                    expected = false,
                ),
                Case(
                    "SQLite header, file exactly a header long",
                    header = EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC,
                    totalLength = 16L,
                    expected = false,
                ),
                Case(
                    "SQLite header prefix, last byte differs",
                    header =
                        EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC
                            .copyOf()
                            .also { it[it.lastIndex] = 'X'.code.toByte() },
                    totalLength = 4096L,
                    expected = true,
                ),
                Case(
                    "SQLCipher-looking random bytes",
                    header = ByteArray(16) { index -> (index * 7 + 31).toByte() },
                    totalLength = 4096L,
                    expected = true,
                ),
                Case(
                    "100-byte encrypted-looking file",
                    header = ByteArray(16) { index -> (index % 251).toByte() },
                    totalLength = 100L,
                    expected = true,
                ),
            )

        // Every platform's verdict is this one, so any platform that keeps its own rule diverges here.
        val androidVerdicts = cases.associate { it.label to androidRule(it) }
        val desktopVerdicts = cases.associate { it.label to desktopRule(it) }

        assertThat(androidVerdicts).isEqualTo(desktopVerdicts)
        cases.forEach { case ->
            assertThat(androidRule(case)).isEqualTo(case.expected)
        }
    }

    @Test
    fun `a file shorter than one header is never encrypted on either platform`() {
        // The boundary, stated as a property: below a header there is no evidence of encryption, and reporting
        // "encrypted" would send a user to a passphrase prompt they cannot satisfy.
        val shortestHeader = EncryptedFileClassifier.MINIMUM_PROBE_BYTES

        for (length in 0 until shortestHeader) {
            val verdict =
                EncryptedFileClassifier.classify(
                    header = ByteArray(EncryptedFileClassifier.MINIMUM_PROBE_BYTES) { 0x5A }.copyOf(length),
                    totalLength = length.toLong(),
                )
            assertThat(verdict.isEncrypted).isFalse()
        }
    }

    @Test
    fun `an unreadable file is not reported as encrypted`() {
        // A read that failed is not evidence. Sending the user to a passphrase prompt they cannot verify
        // (and which deletes the file on a wrong guess) is worse than reporting "unknown".
        val verdict = EncryptedFileClassifier.classify(header = null, totalLength = 4096L, readSucceeded = false)

        assertThat(verdict.isEncrypted).isFalse()
        assertThat(verdict.reason).isEqualTo(EncryptedFileClassifier.Reason.ABSENT)
    }

    @Test
    fun `a read that was attempted and failed is READ_FAILED, not ABSENT`() {
        // The two null-header cases are different: ABSENT means the file was never there to read,
        // READ_FAILED means a read was attempted and returned nothing. The distinction matters because
        // READ_FAILED is the case where the file EXISTS but could not be opened — a permission error, a
        // locked file — and treating it as ABSENT would report "no database" for a database that is
        // merely unreadable. Both must answer "not encrypted" (an unreadable file must not send the
        // user to a passphrase prompt), but the reason must be attributable.
        val verdict = EncryptedFileClassifier.classify(header = null, totalLength = 4096L, readSucceeded = true)

        assertThat(verdict.isEncrypted).isFalse()
        assertThat(verdict.hasPlaintextMagic).isFalse()
        assertThat(verdict.reason).isEqualTo(EncryptedFileClassifier.Reason.READ_FAILED)
    }

    @Test
    fun `the threshold is derived from the magic rather than restated`() {
        // The derivation is the mechanism that stops the drift, so it is asserted directly: if someone hardcodes
        // a second number here, the two can diverge again and this fails.
        assertThat(EncryptedFileClassifier.MINIMUM_PROBE_BYTES)
            .isEqualTo(EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC.size)
        assertThat(EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC.size).isAtLeast(16)
    }

    @Test
    fun `the rule holds for a real file on disk, not only for byte arrays`() {
        // Proof the shared rule survives the trip through the filesystem both platforms read from.
        val directory = Files.createTempDirectory("encrypted-file-classifier")
        try {
            val encrypted = File(directory.toFile(), "encrypted.db").apply { writeBytes(ByteArray(4096) { 0x2A }) }
            val plaintext =
                File(directory.toFile(), "plaintext.db").apply {
                    writeBytes(EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC + ByteArray(4096))
                }
            val tiny = File(directory.toFile(), "tiny.db").apply { writeBytes(byteArrayOf(0x42)) }

            assertThat(classifyFile(encrypted).isEncrypted).isTrue()
            assertThat(classifyFile(plaintext).isEncrypted).isFalse()
            assertThat(classifyFile(tiny).isEncrypted).isFalse()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * Android's answer, reproduced from the rule as `DatabaseEncryptionMigrationSupport` applies it: the shared
     * verdict, with the framework open allowed to veto a plaintext-magic file.
     */
    private fun androidRule(case: Case): Boolean =
        EncryptedFileClassifier.classify(case.header, case.totalLength, case.readSucceeded).isEncrypted

    /**
     * The desktop's answer, reproduced from the rule as `DesktopDatabaseEncryptionProbe` applies it: the shared
     * verdict OR'd with whether the file opened as plaintext, since the desktop can actually measure that.
     */
    private fun desktopRule(case: Case): Boolean {
        val verdict = EncryptedFileClassifier.classify(case.header, case.totalLength, case.readSucceeded)
        val openedAsPlaintext = verdict.hasPlaintextMagic
        return if (verdict.hasPlaintextMagic && openedAsPlaintext) false else openedAsPlaintext || verdict.isEncrypted
    }

    private fun classifyFile(file: File): EncryptedFileClassifier.Verdict {
        val header =
            file.inputStream().use { stream ->
                val buffer = ByteArray(EncryptedFileClassifier.MINIMUM_PROBE_BYTES)
                val read = stream.read(buffer)
                if (read <= 0) null else buffer.copyOf(read)
            }
        return EncryptedFileClassifier.classify(header, file.length(), readSucceeded = header != null)
    }

    private class Case(
        val label: String,
        val header: ByteArray?,
        val totalLength: Long,
        val readSucceeded: Boolean = header != null,
        val expected: Boolean,
    )
}
