//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseOpenOutcome
import io.payanam.shared.database.PayanamDatabaseHandle
import io.payanam.shared.database.PayanamDatabaseOpener
import io.payanam.shared.transfer.TransferOutcome
import io.payanam.shared.transfer.TransferRefusal
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the desktop's database-transfer gateway.
 *
 * Two properties carry this class, and both are asserted against the LIVE file rather than against the
 * return value, because a refusal that still damaged the live database would return the right enum:
 *
 * 1. nothing is replaced before the passphrase is verified, and
 * 2. a replacement that cannot complete leaves the previous database byte-identical.
 *
 * The opener is faked so each refusal case can be produced deliberately. Verification against a real
 * SQLCipher file is covered by the opener's own tests; what is under test here is what happens around it.
 */
class DesktopDatabaseTransferGatewayTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var live: Path

    private class FakeHandle : PayanamDatabaseHandle {
        var closed = false
            private set

        override val schemaVersion: Int = 21
        override val isOpen: Boolean get() = !closed

        override fun close() {
            closed = true
        }
    }

    /** An opener that reports a configured outcome and records whether it was consulted. */
    private class FakeOpener(
        private val outcome: DatabaseOpenOutcome,
    ) : PayanamDatabaseOpener {
        var openCalls = 0
            private set
        var lastReadOnly: Boolean? = null
            private set

        override suspend fun verifyPassphrase(
            databasePath: String,
            passphrase: CharArray,
        ): Boolean = true

        override suspend fun open(
            databasePath: String,
            passphrase: CharArray,
            readOnly: Boolean,
        ): DatabaseOpenOutcome {
            openCalls++
            lastReadOnly = readOnly
            return outcome
        }
    }

    private fun gateway(
        opener: PayanamDatabaseOpener,
        checkpoint: () -> Boolean = { true },
    ) = DesktopDatabaseTransferGateway(
        liveDatabasePath = { live },
        opener = opener,
        checkpoint = checkpoint,
    )

    private fun openedOutcome() = DatabaseOpenOutcome.Opened(FakeHandle())

    private fun refusedOutcome(type: DatabaseBootIssueType) = DatabaseOpenOutcome.Refused(DatabaseBootIssue(type = type))

    private fun writeLive(content: String) {
        live = tempFolder.newFolder().toPath().resolve("payanam.db")
        Files.write(live, content.toByteArray())
    }

    private fun incomingLeftover(): List<Path> =
        Files.list(live.parent).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".incoming") }.toList()
        }

    // ---- export ----

    @Test
    fun `export folds the log in before copying`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val order = mutableListOf<String>()
            val destination = tempFolder.root.toPath().resolve("backup.db")

            val outcome =
                gateway(
                    opener = FakeOpener(openedOutcome()),
                    checkpoint = {
                        order.add("checkpoint")
                        true
                    },
                ).exportTo(destination.toString())
            order.add("copied")

            assertThat(outcome).isInstanceOf(TransferOutcome.Succeeded::class.java)
            // The order is the point: a checkpoint after the copy would fold the log into a file already
            // copied, exporting the pre-write snapshot the checkpoint exists to avoid.
            assertThat(order).containsExactly("checkpoint", "copied").inOrder()
            assertThat(String(Files.readAllBytes(destination))).isEqualTo("live-bytes")
        }

    @Test
    fun `an incomplete fold refuses the export and copies nothing`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val destination = tempFolder.root.toPath().resolve("backup.db")

            val outcome =
                gateway(opener = FakeOpener(openedOutcome()), checkpoint = { false })
                    .exportTo(destination.toAbsolutePath().toString())

            // The copy would be a snapshot missing the user's most recent writes, so the export stops before
            // anything reaches the destination - no file is created at all.
            assertThat(outcome).isEqualTo(TransferOutcome.Refused(TransferRefusal.CHECKPOINT_INCOMPLETE))
            assertThat(Files.exists(destination)).isFalse()
        }

    @Test
    fun `export refuses when the live database cannot be read`(): Unit =
        runBlocking {
            live = tempFolder.root.toPath().resolve("not-there.db")
            val destination = tempFolder.root.toPath().resolve("backup.db")

            val outcome = gateway(FakeOpener(openedOutcome())).exportTo(destination.toString())

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.SOURCE_UNREADABLE)
            assertThat(Files.exists(destination)).isFalse()
        }

    @Test
    fun `export refuses when the destination is the live file itself`(): Unit =
        runBlocking {
            writeLive("live-bytes")

            val outcome = gateway(FakeOpener(openedOutcome())).exportTo(live.toString())

            assertThat((outcome as TransferOutcome.Refused).reason).isEqualTo(TransferRefusal.SAME_FILE)
            assertThat(String(Files.readAllBytes(live))).isEqualTo("live-bytes")
        }

    // ---- import: verification first ----

    @Test
    fun `import checks the passphrase read-only, before touching the live database`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val source = tempFolder.root.toPath().resolve("incoming.db")
            Files.write(source, "imported-bytes".toByteArray())
            val opener = FakeOpener(openedOutcome())

            val outcome = gateway(opener).importFrom(source.toString(), "Str0ng-Passphrase!".toCharArray())

            assertThat(outcome).isInstanceOf(TransferOutcome.Succeeded::class.java)
            // Read-only: the verification must not be able to change the file it is checking.
            assertThat(opener.lastReadOnly).isTrue()
            assertThat(String(Files.readAllBytes(live))).isEqualTo("imported-bytes")
            assertThat(incomingLeftover()).isEmpty()
        }

    @Test
    fun `a rejected passphrase leaves the live database byte-identical and no temp file behind`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val source = tempFolder.root.toPath().resolve("incoming.db")
            Files.write(source, "imported-bytes".toByteArray())

            val outcome =
                gateway(FakeOpener(refusedOutcome(DatabaseBootIssueType.OPEN_FAILED)))
                    .importFrom(source.toString(), "wrong".toCharArray())

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.PASSPHRASE_REJECTED)
            // The whole reason verification precedes the copy.
            assertThat(String(Files.readAllBytes(live))).isEqualTo("live-bytes")
            assertThat(incomingLeftover()).isEmpty()
        }

    @Test
    fun `a plaintext database offered as the source is refused with the live database untouched`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val source = tempFolder.root.toPath().resolve("plaintext.db")
            // The real header of an unencrypted SQLite database. The transfer path is one of the paths a
            // plaintext file can be offered to, and its refusal has to leave the live database exactly as
            // it was - the distinction between a refusal and a refusal that already half-imported.
            Files.write(
                source,
                "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(4080),
            )

            val outcome =
                gateway(FakeOpener(refusedOutcome(DatabaseBootIssueType.OPEN_FAILED)))
                    .importFrom(source.toString(), "any".toCharArray())

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.PASSPHRASE_REJECTED)
            assertThat(String(Files.readAllBytes(live))).isEqualTo("live-bytes")
            assertThat(incomingLeftover()).isEmpty()
        }

    @Test
    fun `a schema the build cannot read is reported as incompatible, not as a wrong passphrase`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val source = tempFolder.root.toPath().resolve("incoming.db")
            Files.write(source, "future-bytes".toByteArray())

            val outcome =
                gateway(FakeOpener(refusedOutcome(DatabaseBootIssueType.DB_TOO_NEW)))
                    .importFrom(source.toString(), "Str0ng-Passphrase!".toCharArray())

            // "This backup came from a newer version" is a different fix from "your passphrase is wrong",
            // so it must not be reported as one.
            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.INCOMPATIBLE_SCHEMA)
            assertThat(String(Files.readAllBytes(live))).isEqualTo("live-bytes")
        }

    @Test
    fun `a file that opens but is not one of ours is reported as such`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val source = tempFolder.root.toPath().resolve("incoming.db")
            Files.write(source, "someone-elses".toByteArray())

            val outcome =
                gateway(FakeOpener(refusedOutcome(DatabaseBootIssueType.SCHEMA_INVALID)))
                    .importFrom(source.toString(), "Str0ng-Passphrase!".toCharArray())

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.NOT_A_PAYANAM_DATABASE)
            assertThat(String(Files.readAllBytes(live))).isEqualTo("live-bytes")
        }

    @Test
    fun `import refuses an unreadable source without consulting the opener`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val opener = FakeOpener(openedOutcome())

            val outcome =
                gateway(opener)
                    .importFrom(
                        tempFolder.root
                            .toPath()
                            .resolve("absent.db")
                            .toString(),
                        "x".toCharArray(),
                    )

            assertThat((outcome as TransferOutcome.Refused).reason)
                .isEqualTo(TransferRefusal.SOURCE_UNREADABLE)
            assertThat(opener.openCalls).isEqualTo(0)
            assertThat(String(Files.readAllBytes(live))).isEqualTo("live-bytes")
        }

    @Test
    fun `import refuses when the source is the live file itself`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val opener = FakeOpener(openedOutcome())

            val outcome = gateway(opener).importFrom(live.toString(), "Str0ng-Passphrase!".toCharArray())

            assertThat((outcome as TransferOutcome.Refused).reason).isEqualTo(TransferRefusal.SAME_FILE)
            assertThat(opener.openCalls).isEqualTo(0)
            assertThat(String(Files.readAllBytes(live))).isEqualTo("live-bytes")
        }

    @Test
    fun `the verification handle is closed rather than leaked`(): Unit =
        runBlocking {
            writeLive("live-bytes")
            val source = tempFolder.root.toPath().resolve("incoming.db")
            Files.write(source, "imported-bytes".toByteArray())
            val handle = FakeHandle()

            gateway(FakeOpener(DatabaseOpenOutcome.Opened(handle)))
                .importFrom(source.toString(), "Str0ng-Passphrase!".toCharArray())

            // Verification opens the file read-only; leaving that handle open would hold a lock on a
            // database the app is about to replace.
            assertThat(handle.closed).isTrue()
        }

    @Test
    fun `a replacement that cannot complete is refused and leaves nothing half written`(): Unit =
        runBlocking {
            // The live path sits in a directory that does not exist, so the temp copy cannot be created.
            live =
                tempFolder.root
                    .toPath()
                    .resolve("missing-dir")
                    .resolve("payanam.db")
            val source = tempFolder.root.toPath().resolve("incoming.db")
            Files.write(source, "imported-bytes".toByteArray())

            val outcome =
                gateway(FakeOpener(openedOutcome()))
                    .importFrom(source.toString(), "Str0ng-Passphrase!".toCharArray())

            assertThat((outcome as TransferOutcome.Refused).reason).isEqualTo(TransferRefusal.REPLACE_FAILED)
            assertThat(Files.exists(live)).isFalse()
        }
}
