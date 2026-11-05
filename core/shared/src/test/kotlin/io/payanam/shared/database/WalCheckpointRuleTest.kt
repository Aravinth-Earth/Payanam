//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the DECISION of the shared fold rule: when a caller may treat the main database file as holding
 * every write.
 *
 * ## What is asserted, and what deliberately is not
 *
 * The SQL text is NOT asserted, for the same reason the rekey test does not assert it: `PRAGMA
 * wal_checkpoint(TRUNCATE)` is already known to work on both drivers, and pinning the string would only
 * assert it against a rewrite SQLite could legitimately make. What is asserted is the DECISION, because
 * that is the part that was duplicated per call site and drifted: three of them read the row and none of
 * them read it the same way.
 *
 * ## The failure this class exists to catch
 *
 * The dangerous shape is a fold that RUNS and is reported as fine while the log still holds frames — a
 * checkpoint on a lifecycle boundary whose return value is thrown away, and an export whose copy therefore
 * omits whatever the user did in the last few seconds. `busy == 0` on its own is what allowed that, and
 * these assertions exist to make it impossible to reintroduce.
 */
class WalCheckpointRuleTest {
    private fun folded(logFrames: Int) = WalCheckpointRule.FoldReport(busy = 0, logFrames = logFrames, checkpointedFrames = 0)

    private fun blocked(logFrames: Int) = WalCheckpointRule.FoldReport(busy = 1, logFrames = logFrames, checkpointedFrames = 0)

    // ---- the empty case ----

    @Test
    fun `a fold that emptied the log reports the WAL empty`() {
        assertThat(folded(logFrames = 0).walIsEmpty).isTrue()
        assertThat(WalCheckpointRule.walIsEmpty(folded(logFrames = 0))).isTrue()
    }

    @Test
    fun `a database with no write-ahead log at all reports the WAL empty`() {
        // -1 is NOT a large frame count. It is the answer for "the pragma returned no row, or this
        // database is not in WAL mode" — there was nothing to fold, so the main file is already complete.
        // Reading it as non-empty would refuse a database that is, in fact, complete.
        assertThat(folded(logFrames = WalCheckpointRule.NO_LOG).walIsEmpty).isTrue()
    }

    @Test
    fun `requireWalEmpty returns the report for a fold that emptied the log`() {
        val report = folded(logFrames = 0)
        assertThat(WalCheckpointRule.requireWalEmpty(report, "export")).isEqualTo(report)
    }

    // ---- the non-empty case ----

    @Test
    fun `a fold blocked by another connection reports the WAL non-empty`() {
        assertThat(blocked(logFrames = 0).walIsEmpty).isFalse()
        assertThat(WalCheckpointRule.walIsEmpty(blocked(logFrames = 0))).isFalse()
    }

    @Test
    fun `a fold that was not blocked but left frames behind reports the WAL NON-empty`() {
        // The case the old rule missed. busy == 0 means nothing HELD the database; it does not mean the
        // log is empty. A backup taken from this state omits the frames still sitting in the log.
        assertThat(blocked(logFrames = 0).busy).isEqualTo(1)
        assertThat(folded(logFrames = 12).walIsEmpty).isFalse()
        assertThat(WalCheckpointRule.walIsEmpty(folded(logFrames = 12))).isFalse()
    }

    @Test
    fun `no report at all is a NON-empty answer, never an empty one`() {
        // A caller with no session, no cursor, or an unread pragma must not be able to claim an empty log
        // out of silence — and a null must not read as "nothing to do".
        assertThat(WalCheckpointRule.walIsEmpty(null)).isFalse()
        assertThat(WalCheckpointRule.NO_ROW.walIsEmpty).isFalse()
    }

    // ---- the refusing path ----

    @Test
    fun `requireWalEmpty throws for a fold that was blocked`() {
        val failure =
            runCatching { WalCheckpointRule.requireWalEmpty(blocked(logFrames = 3), "export") }
                .exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        // The counts are in the message: a refusal nobody can diagnose is a refusal nobody will fix.
        assertThat(failure).hasMessageThat().contains("export")
        assertThat(failure).hasMessageThat().contains("busy=1")
        assertThat(failure).hasMessageThat().contains("logFrames=3")
    }

    @Test
    fun `requireWalEmpty throws for a fold that left frames behind`() {
        val failure =
            runCatching { WalCheckpointRule.requireWalEmpty(folded(logFrames = 7), "backup snapshot") }
                .exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("backup snapshot")
        assertThat(failure).hasMessageThat().contains("logFrames=7")
    }

    @Test
    fun `requireWalEmpty throws when nothing was folded, and says so`() {
        val failure =
            runCatching { WalCheckpointRule.requireWalEmpty(null, "import replace") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("import replace")
        assertThat(failure).hasMessageThat().contains("no fold was reported")
    }

    // ---- reading the row ----

    @Test
    fun `reportOf reads the pragma row in the order SQLite returns it`() {
        // (busy, log, checkpointed). A caller that read these in the wrong order would report a blocked
        // fold as a clean one, so the order is pinned here rather than trusted at four call sites.
        assertThat(WalCheckpointRule.reportOf(intArrayOf(0, 4, 4)))
            .isEqualTo(WalCheckpointRule.FoldReport(busy = 0, logFrames = 4, checkpointedFrames = 4))
        assertThat(WalCheckpointRule.reportOf(intArrayOf(1, 4, 4)))
            .isEqualTo(WalCheckpointRule.FoldReport(busy = 1, logFrames = 4, checkpointedFrames = 4))
    }

    @Test
    fun `a missing or short row becomes NO_ROW, which is never empty`() {
        // A cursor with no row, or one whose columns moved, must not read as a completed fold. NO_ROW is
        // reported as busy precisely so it can never pass the gate.
        assertThat(WalCheckpointRule.reportOf(null)).isEqualTo(WalCheckpointRule.NO_ROW)
        assertThat(WalCheckpointRule.reportOf(intArrayOf(0))).isEqualTo(WalCheckpointRule.NO_ROW)
        assertThat(WalCheckpointRule.reportOf(intArrayOf(0, 0))).isEqualTo(WalCheckpointRule.NO_ROW)
        assertThat(WalCheckpointRule.reportOf(null).walIsEmpty).isFalse()
    }
}
