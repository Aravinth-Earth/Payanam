//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the shared database-session contract.
 *
 * The contract itself is mostly types, so what is worth asserting is the two things that carry behaviour:
 * the accessors that both platforms' callers use to read an outcome, and the closing rule — a handle must
 * report itself closed afterwards rather than throwing, because a shutdown path and a failure path
 * routinely both close the same handle.
 */
class PayanamDatabaseSessionContractTest {
    private class FakeHandle(
        override val schemaVersion: Int = PayanamDatabaseSchema.VERSION,
    ) : PayanamDatabaseHandle {
        private var closed = false
        var closeCount = 0
            private set

        override val isOpen: Boolean get() = !closed

        override fun close() {
            closed = true
            closeCount++
        }
    }

    private fun issue(type: DatabaseBootIssueType = DatabaseBootIssueType.DB_TOO_OLD) =
        DatabaseBootIssue(type = type, detailMessage = "detail", detectedVersion = 16)

    @Test
    fun `an opened outcome exposes its handle and no issue`() {
        val handle = FakeHandle()
        val outcome: DatabaseOpenOutcome = DatabaseOpenOutcome.Opened(handle)

        assertThat(outcome.handleOrNull()).isSameInstanceAs(handle)
        assertThat(outcome.refusalIssue()).isNull()
    }

    @Test
    fun `a refused outcome exposes its issue and no handle`() {
        val bootIssue = issue()
        val outcome: DatabaseOpenOutcome = DatabaseOpenOutcome.Refused(bootIssue)

        assertThat(outcome.refusalIssue()).isSameInstanceAs(bootIssue)
        assertThat(outcome.handleOrNull()).isNull()
    }

    @Test
    fun `a handle reports itself closed after closing and does not throw on a second close`() {
        val handle = FakeHandle()
        assertThat(handle.isOpen).isTrue()

        handle.close()

        assertThat(handle.isOpen).isFalse()
        assertThat(handle.schemaVersion).isEqualTo(PayanamDatabaseSchema.VERSION)

        // A shutdown path and a failure path both closing is routine; the second close must be harmless.
        handle.close()
        assertThat(handle.closeCount).isEqualTo(2)
        assertThat(handle.isOpen).isFalse()
    }

    @Test
    fun `every boot issue type can be carried by a refusal`() {
        // Exercised through the enum rather than listed: a new case is covered the moment it is added, and
        // the assertion is that the contract can carry ANY of them rather than a chosen few.
        val carried = DatabaseBootIssueType.entries.map { DatabaseOpenOutcome.Refused(issue(it)) }
        assertThat(carried.map { it.refusalIssue()?.type }).containsExactlyElementsIn(DatabaseBootIssueType.entries)
    }
}
