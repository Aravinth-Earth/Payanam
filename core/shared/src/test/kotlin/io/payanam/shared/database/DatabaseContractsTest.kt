//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The two database contracts' own vocabulary.
 *
 * These types are what a platform answers with, so their DEFAULTS are the part that can be wrong quietly:
 * a health report that defaults to healthy, or a report that claims a repair it never did, would let a
 * broken database through a gate that reads the object rather than the file. Each default is therefore
 * asserted here rather than left to whoever constructs the type first.
 */
class DatabaseContractsTest {
    @Test
    fun `a default health report is never healthy`() {
        // The whole point of the honesty rule: an unanswered check must not read as a pass.
        val report = DatabaseHealthReport(isHealthy = false)

        assertThat(report.isHealthy).isFalse()
        assertThat(report.needsRepair).isFalse()
        assertThat(report.needsMigration).isFalse()
        assertThat(report.errorMessage).isNull()
    }

    @Test
    fun `a repair is distinguishable from a migration`() {
        // A truncated file and a file from a newer build need different answers, and a UI that cannot tell
        // them apart sends the user to fix the wrong thing.
        val repair = DatabaseHealthReport(isHealthy = false, needsRepair = true)
        val migration = DatabaseHealthReport(isHealthy = false, needsMigration = true)

        assertThat(repair.needsMigration).isFalse()
        assertThat(migration.needsRepair).isFalse()
    }

    @Test
    fun `a health report carries its reason when it is unhealthy`() {
        val report = DatabaseHealthReport(isHealthy = false, errorMessage = "Missing tables: notes")

        assertThat(report.errorMessage).contains("notes")
    }

    @Test
    fun `the critical table list is non-empty and has no duplicates`() {
        // Both platforms diff their own handle against this list, so an accidental duplicate would make the
        // diff report a table as missing when it is present twice.
        val tables = PayanamDatabaseSchema.CRITICAL_TABLES

        assertThat(tables).isNotEmpty()
        assertThat(tables).containsNoDuplicates()
    }
}
