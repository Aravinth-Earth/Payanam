//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The shape of a read answer, asserted apart from any implementation.
 *
 * The distinction these carry is the whole reason they are sealed: a refusal rendered as an empty table
 * tells the user "nothing here" when the truth is "I would not look".
 */
class ReadOnlyQueryOutcomeTest {
    @Test
    fun `rows carry the columns the cells and whether more existed`() {
        val outcome =
            ReadOnlyQueryOutcome.Rows(
                columns = listOf("id", "title"),
                rows = listOf(listOf("1", null), listOf("2", "second")),
                truncated = true,
            )

        assertThat(outcome.columns).containsExactly("id", "title").inOrder()
        assertThat(outcome.rows).hasSize(2)
        assertThat(outcome.rows.first()[1]).isNull()
        assertThat(outcome.truncated).isTrue()
    }

    @Test
    fun `a refusal names a cause and is never a row set`() {
        val refusal = ReadOnlyQueryOutcome.Refused(ReadOnlyRefusal.READ_ONLY_NOT_ENFORCED, "query_only read back 0")

        assertThat(refusal.reason).isEqualTo(ReadOnlyRefusal.READ_ONLY_NOT_ENFORCED)
        assertThat(refusal.detail).isNotEmpty()
        assertThat(refusal).isNotInstanceOf(ReadOnlyQueryOutcome.Rows::class.java)
        assertThat(DatabaseSchemaResult.Refused(refusal.reason, refusal.detail))
            .isNotInstanceOf(DatabaseSchemaResult.Tables::class.java)
    }
}
