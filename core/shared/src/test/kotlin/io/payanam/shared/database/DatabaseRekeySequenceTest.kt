//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pins the DECISIONS of the shared rekey sequence, for both platforms.
 *
 * ## What is asserted, and what deliberately is not
 *
 * The SQL text is NOT asserted. `PRAGMA rekey = '…'` is already known to be correct on Android — it runs
 * there today — and asserting a string would only pin it against a rewrite that a driver upgrade could
 * legitimately require. What is asserted is every DECISION the sequence makes, because those are the parts
 * that were duplicated per platform and could drift: when a handle is opened, when a statement is issued,
 * that the handle is closed before verification, and that success requires BOTH halves of the gate.
 *
 * ## The failure this class exists to catch
 *
 * The two platforms each grew their own rekey, and the desktop's was a refusal with a comment saying the
 * driver probably could not rekey. Nobody had asked it. When the driver was asked, it rekeyed fine. A
 * refusal left in place is therefore not a safe default — it is an unmeasured claim about another platform's
 * capability, and this test is the thing that keeps such a claim from being written as if it were a fact.
 *
 * The handle is a fake supplied by the test, so these assertions are about the sequence, not about either
 * driver. That split is the point: the driver measurement is a probe's job, and asserting SQLCipher here
 * would make a test depend on a native payload `:core:shared` deliberately does not carry.
 */
class DatabaseRekeySequenceTest {
    private val passphrase = "current-passphrase"
    private val targetPassphrase = "target-passphrase"

    @Test
    fun `a verified rekey reports REKEYED and the handle is opened with the CURRENT passphrase`() {
        val file = databaseFile("rekey.db")
        val keys = mutableListOf<String>()
        val statements = mutableListOf<String>()

        val outcome =
            DatabaseRekeySequence.rekeyToNewPassphrase(
                databaseFile = file,
                currentPassphrase = passphrase,
                newPassphrase = targetPassphrase,
                openForRekey = { _, key ->
                    keys += key
                    recordingHandle(statements)
                },
                canOpenWith = { _, key -> key == targetPassphrase },
            )

        assertThat(outcome).isEqualTo(DatabaseRekeySequence.Outcome.REKEYED)
        // The handle is opened under the passphrase the file currently HAS. Opening under the new one is
        // the mistake this asserts against: it would fail on a real file and, worse, could "succeed" on
        // one that is not encrypted at all.
        assertThat(keys).containsExactly(passphrase)
        assertThat(statements).hasSize(1)
    }

    @Test
    fun `the handle is closed BEFORE the result is verified`() {
        // Ordering, not just "closed". A rekey whose handle is still open when the verification opens the
        // file can read a stale page-1 through the old connection and report a rekey that did not land, and
        // on the desktop a leaked connection also pins the file open against a later delete.
        val file = databaseFile("close-order.db")
        val events = mutableListOf<String>()

        DatabaseRekeySequence.rekeyToNewPassphrase(
            databaseFile = file,
            currentPassphrase = passphrase,
            newPassphrase = targetPassphrase,
            openForRekey = { _, _ ->
                object : DatabaseRekeySequence.RekeyableDatabase {
                    override fun applyRekey(statement: String) {
                        events += "rekey"
                    }

                    override fun close() {
                        events += "close"
                    }
                }
            },
            canOpenWith = { _, key ->
                events += "verify"
                // The gate's two halves both run, so the ordering below records the SECOND verification
                // too. Returning true for everything would make the "still opens under the old passphrase"
                // check fire — which is correct behaviour, and asserted on its own below.
                key == targetPassphrase
            },
        )

        assertThat(events).containsExactly("rekey", "close", "verify", "verify").inOrder()
    }

    @Test
    fun `the handle is closed even when the driver refuses the statement`() {
        // A leaked handle is how a failed rekey becomes a locked file the user cannot recover. The close is
        // in a `finally` in the sequence, and this is what holds it there.
        val file = databaseFile("refused.db")
        var closed = false

        val failure =
            runCatching {
                DatabaseRekeySequence.rekeyToNewPassphrase(
                    databaseFile = file,
                    currentPassphrase = passphrase,
                    newPassphrase = targetPassphrase,
                    openForRekey = { _, _ ->
                        object : DatabaseRekeySequence.RekeyableDatabase {
                            override fun applyRekey(statement: String) {
                                throw IllegalStateException("driver refused")
                            }

                            override fun close() {
                                closed = true
                            }
                        }
                    },
                    canOpenWith = { _, _ -> true },
                )
            }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure).hasMessageThat().contains("driver refused")
        assertThat(closed).isTrue()
    }

    @Test
    fun `a passphrase that already matches is a no-op and opens nothing`() {
        // The short-circuit is a DECISION, so it is pinned: a same-passphrase "rekey" would rewrite the
        // file and risk it for no benefit, and on the desktop an unnecessary open can fail on a file that
        // is perfectly fine.
        val file = databaseFile("same.db")
        var opened = false

        val outcome =
            DatabaseRekeySequence.rekeyToNewPassphrase(
                databaseFile = file,
                currentPassphrase = passphrase,
                newPassphrase = passphrase,
                openForRekey = { _, _ ->
                    opened = true
                    recordingHandle(mutableListOf())
                },
                canOpenWith = { _, _ -> true },
            )

        assertThat(outcome).isEqualTo(DatabaseRekeySequence.Outcome.ALREADY_ON_TARGET_PASSPHRASE)
        assertThat(opened).isFalse()
    }

    @Test
    fun `a missing file is refused before any handle is opened`() {
        // "Succeeded" against a file that is not there is the silent outcome the gate exists to prevent, so
        // this is the same message both platforms raise.
        val absent = File(Files.createTempDirectory("w0-rekey-absent").toFile(), "absent.db")
        var opened = false

        val failure =
            runCatching {
                DatabaseRekeySequence.rekeyToNewPassphrase(
                    databaseFile = absent,
                    currentPassphrase = passphrase,
                    newPassphrase = targetPassphrase,
                    openForRekey = { _, _ ->
                        opened = true
                        recordingHandle(mutableListOf())
                    },
                    canOpenWith = { _, _ -> true },
                )
            }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("does not exist")
        assertThat(opened).isFalse()
    }

    @Test
    fun `a result that does not open under the new passphrase is a failure, not a success`() {
        // The first half of the gate. A driver that accepted the statement and wrote nothing must not be
        // reported as done: the caller would then tell a user their passphrase changed.
        val file = databaseFile("no-open.db")

        val failure =
            runCatching {
                DatabaseRekeySequence.rekeyToNewPassphrase(
                    databaseFile = file,
                    currentPassphrase = passphrase,
                    newPassphrase = targetPassphrase,
                    openForRekey = { _, _ -> recordingHandle(mutableListOf()) },
                    // Opens under NEITHER passphrase, as a half-written file does.
                    canOpenWith = { _, _ -> false },
                )
            }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("does not open under the new passphrase")
    }

    @Test
    fun `a result that STILL opens under the old passphrase is a failure`() {
        // The second half of the gate, and the one that catches a no-op rekey. A file that opens with the
        // old key was not rekeyed, so the sequence must fail even though the new passphrase "worked".
        val file = databaseFile("still-old.db")

        val failure =
            runCatching {
                DatabaseRekeySequence.rekeyToNewPassphrase(
                    databaseFile = file,
                    currentPassphrase = passphrase,
                    newPassphrase = targetPassphrase,
                    openForRekey = { _, _ -> recordingHandle(mutableListOf()) },
                    canOpenWith = { _, key -> true }, // opens under everything, old and new
                )
            }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("still opens under the old passphrase")
    }

    @Test
    fun `the escape is the one both platforms embed a passphrase with`() {
        // A passphrase containing a quote is a real case (`it's mine`). Unescaped, it truncates the
        // statement into a syntactically valid rekey to a DIFFERENT key — the kind of bug that only shows
        // up as "my passphrase does not work" weeks later.
        assertThat(DatabaseRekeySequence.escapeSqlLiteral("it's mine")).isEqualTo("it''s mine")
        assertThat(DatabaseRekeySequence.escapeSqlLiteral("plain")).isEqualTo("plain")
        // Doubling is the whole rule: a second quote in the escaped value must not be re-escaped.
        assertThat(DatabaseRekeySequence.escapeSqlLiteral("''")).isEqualTo("''''")
    }

    @Test
    fun `the rekey statement carries the escaped passphrase and nothing platform-specific`() {
        // The one place the SQL itself is checked, and only for the property that matters: both platforms
        // build the identical statement, and the new passphrase appears in it escaped. The statement is
        // built here rather than asserted as a literal constant, so a platform cannot quietly pass a
        // different one to its driver.
        val file = databaseFile("statement.db")
        val statements = mutableListOf<String>()

        DatabaseRekeySequence.rekeyToNewPassphrase(
            databaseFile = file,
            currentPassphrase = passphrase,
            newPassphrase = "it's mine",
            openForRekey = { _, _ -> recordingHandle(statements) },
            canOpenWith = { _, key -> key == "it's mine" },
        )

        assertThat(statements).hasSize(1)
        assertThat(statements.single())
            .isEqualTo("PRAGMA rekey = 'it''s mine';")
    }

    private fun recordingHandle(statements: MutableList<String>) =
        object : DatabaseRekeySequence.RekeyableDatabase {
            override fun applyRekey(statement: String) {
                statements += statement
            }

            override fun close() = Unit
        }

    private fun databaseFile(name: String): File =
        Files.createTempDirectory("w0-rekey")
            .toFile()
            .resolve(name)
            .apply { writeBytes(ByteArray(8192) { 0x2A }) }
}
