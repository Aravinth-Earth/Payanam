//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.transfer

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.transfer.TransferOutcome
import io.payanam.shared.transfer.TransferRefusal
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tests for the Android SAF transfer gateway.
 *
 * Plain Kotlin on purpose: every platform touch is injected, so this exercises the staging order and the
 * refusal rules without a device, a resolver or SQLCipher. The one thing that must hold on Android —
 * because a content URI cannot be verified where it lives — is that the LIVE database is untouched until
 * the staged copy has been verified, and each refusal here asserts the live bytes are unchanged.
 */
class AndroidDatabaseTransferGatewayTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var liveDirectory: File
    private lateinit var live: File

    private class Calls {
        val order = mutableListOf<String>()
        var verifiedFile: File? = null
        var verifiedPassphrase: String? = null

        // Captured INSIDE the verifier: after a successful import the staged file has been renamed into
        // place, so reading it afterwards would fail for the right reason and prove nothing.
        var verifiedContent: String? = null
    }

    private fun liveBytes(): String = live.readText()

    private fun stagingLeftover(): List<String> =
        (liveDirectory.listFiles() ?: emptyArray()).map { it.name }.filter { it.endsWith(".incoming") }

    private fun setUpLive(content: String) {
        liveDirectory = tempFolder.newFolder("app-db")
        live = File(liveDirectory, "payanam.db")
        live.writeText(content)
    }

    private fun gateway(
        sourceBytes: ByteArray? = null,
        destination: ByteArrayOutputStream? = ByteArrayOutputStream(),
        verify: (File, String) -> Boolean = { _, _ -> true },
        checkpoint: () -> Boolean = { true },
        calls: Calls = Calls(),
    ) = AndroidDatabaseTransferGateway(
        liveDatabaseFile = { live },
        openSourceStream = { sourceBytes?.let { bytes -> ByteArrayInputStream(bytes) } },
        openDestinationStream = { destination },
        verifyFile = { file, passphrase ->
            calls.order.add("verify")
            calls.verifiedFile = file
            calls.verifiedPassphrase = passphrase
            calls.verifiedContent = file.readText()
            verify(file, passphrase)
        },
        checkpoint = {
            calls.order.add("checkpoint")
            checkpoint()
        },
    )

    @Test
    fun `export folds the log in before copying`(): Unit =
        runBlocking {
            setUpLive("live-bytes")
            val destination = ByteArrayOutputStream()
            val calls = Calls()

            val outcome = gateway(destination = destination, calls = calls).exportTo("content://target")

            assertThat(outcome).isInstanceOf(TransferOutcome.Succeeded::class.java)
            // Order asserted, not presence: a checkpoint after the copy would fold the log into a file that
            // had already been copied.
            assertThat(calls.order).containsExactly("checkpoint")
            assertThat(destination.toString(Charsets.UTF_8.name())).isEqualTo("live-bytes")
        }

    @Test
    fun `export refuses a live database that is not readable`(): Unit =
        runBlocking {
            setUpLive("live-bytes")
            live.delete()

            val outcome = gateway().exportTo("content://target")

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.SOURCE_UNREADABLE)
        }

    @Test
    fun `export refuses when the destination cannot be opened`(): Unit =
        runBlocking {
            setUpLive("live-bytes")

            val outcome = gateway(destination = null).exportTo("content://target")

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.DESTINATION_UNWRITABLE)
        }

    @Test
    fun `import stages the source, verifies the STAGED file, then swaps it in`(): Unit =
        runBlocking {
            setUpLive("live-bytes")
            val calls = Calls()

            val outcome =
                gateway(sourceBytes = "imported-bytes".toByteArray(), calls = calls)
                    .importFrom("content://source", "Str0ng-Passphrase!".toCharArray())

            assertThat(outcome).isInstanceOf(TransferOutcome.Succeeded::class.java)
            assertThat(liveBytes()).isEqualTo("imported-bytes")
            // The verifier must have been handed the staged copy, never the live database: checking the live
            // file would be checking the thing about to be replaced.
            assertThat(calls.verifiedFile).isNotNull()
            assertThat(calls.verifiedFile!!.absolutePath).isNotEqualTo(live.absolutePath)
            assertThat(calls.verifiedContent).isEqualTo("imported-bytes")
            assertThat(calls.verifiedPassphrase).isEqualTo("Str0ng-Passphrase!")
            assertThat(stagingLeftover()).isEmpty()
        }

    @Test
    fun `a rejected passphrase leaves the live database byte-identical and nothing staged`(): Unit =
        runBlocking {
            setUpLive("live-bytes")

            val outcome =
                gateway(
                    sourceBytes = "imported-bytes".toByteArray(),
                    verify = { _, _ -> false },
                ).importFrom("content://source", "wrong".toCharArray())

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.PASSPHRASE_REJECTED)
            assertThat(liveBytes()).isEqualTo("live-bytes")
            assertThat(stagingLeftover()).isEmpty()
        }

    @Test
    fun `an unreadable source is refused without staging or verifying`(): Unit =
        runBlocking {
            setUpLive("live-bytes")
            val calls = Calls()

            val outcome = gateway(sourceBytes = null, calls = calls).importFrom("content://gone", "x".toCharArray())

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.SOURCE_UNREADABLE)
            assertThat(calls.order).isEmpty()
            assertThat(liveBytes()).isEqualTo("live-bytes")
            assertThat(stagingLeftover()).isEmpty()
        }

    @Test
    fun `a swap that cannot complete is refused and leaves nothing half written`(): Unit =
        runBlocking {
            // A directory where the database file should be: the rename can neither replace nor remove it.
            liveDirectory = tempFolder.newFolder("app-db-broken")
            live = File(liveDirectory, "payanam.db")
            live.mkdirs()
            File(live, "occupied").writeText("in the way")

            val outcome =
                gateway(sourceBytes = "imported-bytes".toByteArray())
                    .importFrom("content://source", "Str0ng-Passphrase!".toCharArray())

            assertThat((outcome as TransferOutcome.Refused).reason).isEqualTo(TransferRefusal.REPLACE_FAILED)
            assertThat(stagingLeftover()).isEmpty()
        }
}
