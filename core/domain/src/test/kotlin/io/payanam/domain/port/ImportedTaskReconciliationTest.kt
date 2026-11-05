//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The shape of imported-task reconciliation, asserted apart from any implementation.
 *
 * The behaviour under test is the one a settings screen's copy depends on: a count answers with a
 * count, a bulk assign reports how many rows it actually changed, and the caller's own [updatedAt]
 * stamp is carried through untouched rather than replaced by a clock the implementation picked.
 *
 * The fake is a table of rows keyed by import source, so the tests can tell "assigned to every
 * imported row" apart from "assigned to some of them" — which is the distinction a count that quietly
 * narrowed its own scope would hide.
 */
class ImportedTaskReconciliationTest {
    /** A row with just the fields these members touch. */
    private data class Row(
        val id: String,
        val importSource: String,
        val dimensionId: String,
        val lifeIntentionCategory: String,
        val updatedAt: String,
    )

    /** An in-memory table with the same shape of behaviour the contract states. */
    private class Table(
        rows: List<Row> = emptyList(),
    ) : ImportedTaskReconciliation {
        val rows: MutableList<Row> = rows.toMutableList()

        override suspend fun countByImportSource(source: String): Int = rows.count { it.importSource == source }

        override suspend fun bulkAssignDimensionToImportedSource(
            source: String,
            dimensionId: String,
            lifeIntentionCategory: String,
            updatedAt: String,
        ): Int {
            var changed = 0
            for (index in rows.indices) {
                val row = rows[index]
                if (row.importSource == source) {
                    rows[index] = row.copy(
                        dimensionId = dimensionId,
                        lifeIntentionCategory = lifeIntentionCategory,
                        updatedAt = updatedAt,
                    )
                    changed++
                }
            }
            return changed
        }
    }

    /** The source value stored on a task that did not come from an import at all. */
    private val noImportSource = "local"

    private val uhabitsRow = Row("1", "uhabits", "unassigned", "Unassigned", "2026-01-01T00:00:00")
    private val manualRow = Row("2", noImportSource, "work", "Deep Work", "2026-01-02T00:00:00")

    @Test
    fun `a count of zero is zero and not a failure`() {
        val table = Table(listOf(manualRow))

        val count = runBlocking { table.countByImportSource("uhabits") }

        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `a count reports only the rows from that source`() {
        val table =
            Table(
                listOf(
                    uhabitsRow,
                    manualRow,
                    uhabitsRow.copy(id = "3"),
                ),
            )

        val count = runBlocking { table.countByImportSource("uhabits") }

        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `a bulk assign reports how many rows it changed`() {
        val table =
            Table(
                listOf(
                    uhabitsRow,
                    uhabitsRow.copy(id = "3"),
                    uhabitsRow.copy(id = "4"),
                    manualRow,
                ),
            )

        val changed =
            runBlocking {
                table.bulkAssignDimensionToImportedSource(
                    source = "uhabits",
                    dimensionId = "health",
                    lifeIntentionCategory = "Health",
                    updatedAt = "2026-02-01T09:30:00",
                )
            }

        assertThat(changed).isEqualTo(3)
    }

    @Test
    fun `a bulk assign leaves rows from another source untouched`() {
        val table = Table(listOf(uhabitsRow, manualRow))

        runBlocking {
            table.bulkAssignDimensionToImportedSource(
                source = "uhabits",
                dimensionId = "health",
                lifeIntentionCategory = "Health",
                updatedAt = "2026-02-01T09:30:00",
            )
        }

        assertThat(table.rows.first { it.id == "2" }.dimensionId).isEqualTo("work")
        assertThat(table.rows.first { it.id == "2" }.lifeIntentionCategory).isEqualTo("Deep Work")
    }

    @Test
    fun `the caller's updatedAt stamp is the one stored`() {
        val table = Table(listOf(uhabitsRow))
        val stamp = "2026-03-04T05:06:07"

        runBlocking {
            table.bulkAssignDimensionToImportedSource(
                source = "uhabits",
                dimensionId = "health",
                lifeIntentionCategory = "Health",
                updatedAt = stamp,
            )
        }

        assertThat(table.rows.single().updatedAt).isEqualTo(stamp)
    }

    @Test
    fun `a bulk assign over an absent source changes nothing and reports zero`() {
        val table = Table(listOf(manualRow))

        val changed =
            runBlocking {
                table.bulkAssignDimensionToImportedSource(
                    source = "uhabits",
                    dimensionId = "health",
                    lifeIntentionCategory = "Health",
                    updatedAt = "2026-02-01T09:30:00",
                )
            }

        assertThat(changed).isEqualTo(0)
        assertThat(table.rows.single().updatedAt).isEqualTo("2026-01-02T00:00:00")
    }
}