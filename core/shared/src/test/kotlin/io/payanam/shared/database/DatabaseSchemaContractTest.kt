//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the shared database contract: [PayanamDatabaseSchema] and the [DatabaseBootIssue] model.
 *
 * These types moved into `core/shared` from Android-only code so the desktop could stop hardcoding its
 * own copies. That move is exactly why they need tests: the values are now the single source of truth
 * for BOTH platforms, so a silent edit changes what Android and the desktop each believe about the
 * same encrypted file. The two platforms disagreeing about the schema floor or the critical-table list
 * is the failure this file exists to prevent.
 */
class DatabaseSchemaContractTest {
    @Test
    fun `min migratable version never exceeds the current version`() {
        // The supported range is [MIN_MIGRATABLE_VERSION, VERSION]. If the floor ever moved above the
        // ceiling the range would be empty and every database would be refused.
        assertThat(PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION)
            .isAtMost(PayanamDatabaseSchema.VERSION)
        assertThat(PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION).isGreaterThan(0)
        assertThat(PayanamDatabaseSchema.VERSION).isGreaterThan(0)
    }

    @Test
    fun `the schema contract pins the supported version range`() {
        // Contract pin, deliberately exact. These two numbers decide whether a user's exported database
        // opens or is refused, and they are shared by both platforms - a bump that changes either must
        // be a conscious decision that edits this test, not an incidental edit somewhere else.
        assertThat(PayanamDatabaseSchema.VERSION).isEqualTo(22)
        assertThat(PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION).isEqualTo(16)
    }

    @Test
    fun `critical tables pin the integrity guard`() {
        // Also a pin: the guard's meaning is the list. Dropping an entry quietly weakens the check that
        // catches a truncated file reporting a valid user_version.
        assertThat(PayanamDatabaseSchema.CRITICAL_TABLES)
            .containsExactly("tasks", "time_entries", "notes", "day_journal_entries", "app_settings")
    }

    @Test
    fun `critical table names are usable as SQL identifiers`() {
        // The list is interpolated into integrity queries by callers. A blank or quoted entry would
        // produce a query that fails at runtime instead of reporting a missing table.
        PayanamDatabaseSchema.CRITICAL_TABLES.forEach { table ->
            assertThat(table).isNotEmpty()
            assertThat(table).doesNotContain(" ")
            assertThat(table).doesNotContain("\"")
            assertThat(table).doesNotContain("'")
        }
    }

    @Test
    fun `boot issue types pin the shared vocabulary`() {
        // Android's boot screen and the desktop's opener must be able to describe the SAME problems -
        // that is the entire reason this enum is shared. A case added or removed here changes what both
        // platforms can express, so it is pinned rather than checked loosely.
        assertThat(DatabaseBootIssueType.entries)
            .containsExactly(
                DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING,
                DatabaseBootIssueType.DB_TOO_OLD,
                DatabaseBootIssueType.DB_TOO_NEW,
                DatabaseBootIssueType.SCHEMA_INVALID,
                DatabaseBootIssueType.OPEN_FAILED,
                DatabaseBootIssueType.REPAIRABLE_GENERIC,
                DatabaseBootIssueType.NON_REPAIRABLE_GENERIC,
            )
    }

    @Test
    fun `every boot issue type round-trips through its name`() {
        // The names are persisted/compared as strings in places; a rename would silently change meaning.
        DatabaseBootIssueType.entries.forEach { type ->
            assertThat(DatabaseBootIssueType.valueOf(type.name)).isEqualTo(type)
        }
    }

    @Test
    fun `boot issue defaults carry no detail and an unknown version`() {
        val issue = DatabaseBootIssue(type = DatabaseBootIssueType.OPEN_FAILED)

        assertThat(issue.detailMessage).isNull()
        assertThat(issue.detectedVersion).isEqualTo(0)
    }

    @Test
    fun `boot issue equality and hashing use the whole payload`() {
        val base = DatabaseBootIssue(DatabaseBootIssueType.DB_TOO_NEW, "newer than this build", 22)

        // Two issues with the same payload are the same issue...
        assertThat(base).isEqualTo(DatabaseBootIssue(DatabaseBootIssueType.DB_TOO_NEW, "newer than this build", 22))
        assertThat(base.hashCode())
            .isEqualTo(DatabaseBootIssue(DatabaseBootIssueType.DB_TOO_NEW, "newer than this build", 22).hashCode())

        // ...and ANY differing component makes them distinct. Checking each one separately is what
        // catches a field accidentally left out of the primary constructor.
        assertThat(base).isNotEqualTo(base.copy(type = DatabaseBootIssueType.DB_TOO_OLD))
        assertThat(base).isNotEqualTo(base.copy(detailMessage = "something else"))
        assertThat(base).isNotEqualTo(base.copy(detectedVersion = 21))
    }

    @Test
    fun `boot issue exposes its components for destructuring`() {
        val issue = DatabaseBootIssue(DatabaseBootIssueType.SCHEMA_INVALID, "missing tables", 21)

        val (type, detail, version) = issue

        assertThat(type).isEqualTo(DatabaseBootIssueType.SCHEMA_INVALID)
        assertThat(detail).isEqualTo("missing tables")
        assertThat(version).isEqualTo(21)
        assertThat(issue.toString()).contains("SCHEMA_INVALID")
    }

    @Test
    fun `boot issue accepts a null detail with a detected version`() {
        // The opener reports a version it never managed to read for some failure modes, and a null
        // detail for others - the two are independent, so the combination must be constructible.
        val issue =
            DatabaseBootIssue(
                type = DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING,
                detailMessage = null,
                detectedVersion = 21,
            )

        assertThat(issue.detailMessage).isNull()
        assertThat(issue.detectedVersion).isEqualTo(21)
    }
}
