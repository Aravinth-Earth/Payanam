//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins [isActiveArtifact] — the predicate that decides which files a user-initiated wipe may remove.
 *
 * ## Why this predicate is worth its own test
 *
 * The predicate is the gate on a delete the user asked for. Its answer comes from the SHARED
 * [DatabaseArtifacts.artifactSuffixes], not a copy, so a file that is a live artifact on one platform
 * and not on the other is a file that is deleted while in use on one and survives every cleanup on the
 * other. Both halves of the branch — "this name is one of ours" and "this name is not" — are asserted,
 * because the dangerous answer is the false one: a stale `.bak` or an orphaned companion reported as
 * live is left behind beside a database that now looks clean, and the user is told their data is gone
 * when it is only the artifact family that is incomplete.
 */
class SettingsDatabaseArtifactSupportTest {
    @Test
    fun `the main database file and each sidecar are active artifacts`() {
        // The empty first suffix is the main file itself, so the whole family — main, -wal, -shm,
        // -journal — must be recognised. A sidecar missing from this answer is a file the wipe
        // refuses to remove and the cleanup leaves behind.
        assertThat(isActiveArtifact("payanam.db")).isTrue()
        assertThat(isActiveArtifact("payanam.db-wal")).isTrue()
        assertThat(isActiveArtifact("payanam.db-shm")).isTrue()
        assertThat(isActiveArtifact("payanam.db-journal")).isTrue()
    }

    @Test
    fun `a name outside the family is not an active artifact`() {
        // Stale backups, temp import files and an unrelated database must all answer false. Reporting
        // any of them as live would let the wipe delete a file the user still needs — a `.bak` from a
        // failed reset, or a second database the app was never told about.
        assertThat(isActiveArtifact("payanam.db.bak")).isFalse()
        assertThat(isActiveArtifact("payanam.db.enc.tmp")).isFalse()
        assertThat(isActiveArtifact("payanam.db.enc.tmp-1758900000000")).isFalse()
        assertThat(isActiveArtifact("payanam.db.wal_merge_tmp")).isFalse()
        assertThat(isActiveArtifact("payanam.db.import_decrypt.tmp")).isFalse()
        assertThat(isActiveArtifact("notes.db")).isFalse()
        assertThat(isActiveArtifact("payanam.db-wal-journal")).isFalse()
    }

    @Test
    fun `the default database name is the schema's own, not a restated copy`() {
        // The default must resolve to DATABASE_NAME at the call site. If the predicate ever hardcoded a
        // second copy of the name, a rename of the schema constant would leave the wipe recognising a
        // name nothing writes, and the artifacts would survive every reset.
        assertThat(isActiveArtifact(PayanamDatabaseSchema.DATABASE_NAME)).isTrue()
        assertThat(isActiveArtifact(PayanamDatabaseSchema.DATABASE_NAME + "-wal")).isTrue()
    }

    @Test
    fun `a custom database name is matched against the name given, not the default`() {
        // The dbName parameter exists so a relocated or imported database can be recognised under its own
        // name. Passing a different name must judge by THAT name: the default's artifacts stop matching,
        // and the named database's start.
        val imported = "exported.db"
        assertThat(isActiveArtifact("exported.db", imported)).isTrue()
        assertThat(isActiveArtifact("exported.db-shm", imported)).isTrue()
        // The default database's name is not an artifact of the imported one, and vice versa.
        assertThat(isActiveArtifact("payanam.db", imported)).isFalse()
        assertThat(isActiveArtifact("exported.db")).isFalse()
    }
}
