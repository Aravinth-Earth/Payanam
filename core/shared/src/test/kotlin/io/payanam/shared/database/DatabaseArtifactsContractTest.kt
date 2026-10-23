//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the shared database artifact family — [DatabaseArtifacts] — for BOTH platforms.
 *
 * The five Android and desktop call sites that used to spell this list out for themselves all read
 * through this one declaration now, so a value pinned here is the value every platform acts on. The
 * pin is deliberately exact, membership AND order, because the list is recovery-critical rather than
 * cosmetic: a main database whose `-wal` survives holds committed rows that exist nowhere else, so a
 * list that dropped `-journal` would leave a stale rollback journal behind beside a file that looks
 * clean, and a list that dropped the main file would make a caller delete or refuse the very subject it
 * is inspecting. Both failure modes are silent; neither crashes a test anywhere else.
 */
class DatabaseArtifactsContractTest {
    private val dbFile = java.io.File("/tmp/payanam/payanam.db")

    @Test
    fun `the artifact family pins membership and order`() {
        // The empty first entry is the main file itself, which is why `filesFor` can build the whole
        // family with one map. Main-first is the order callers rely on when they take a head element
        // as "the database" and the rest as companions.
        assertThat(DatabaseArtifacts.artifactSuffixes)
            .containsExactly("", "-wal", "-shm", "-journal")
            .inOrder()
    }

    @Test
    fun `the sidecar family is the artifact family minus the main file`() {
        // The sidecar-only callers (refusing an unclean open, deleting companions after a checkpoint)
        // MUST NOT carry the main entry: it would make them delete or refuse the subject. Deriving the
        // relationship in the test means the two lists cannot drift apart unnoticed.
        assertThat(DatabaseArtifacts.sidecarSuffixes)
            .containsExactly("-wal", "-shm", "-journal")
            .inOrder()
        assertThat(DatabaseArtifacts.sidecarSuffixes)
            .containsExactlyElementsIn(DatabaseArtifacts.artifactSuffixes.drop(1))
            .inOrder()
    }

    @Test
    fun `filesFor returns the main file then its three siblings`() {
        val files = DatabaseArtifacts.filesFor(dbFile)

        assertThat(files.map { it.name })
            .containsExactly("payanam.db", "payanam.db-wal", "payanam.db-shm", "payanam.db-journal")
            .inOrder()
        assertThat(files)
            .containsExactlyElementsIn(
                DatabaseArtifacts.sidecarFilesFor(dbFile).let { sidecars ->
                    listOf(dbFile) + sidecars
                },
            ).inOrder()
    }

    @Test
    fun `the family follows the file given, not a hardcoded database name`() {
        // Import, restore and temp-copy paths all hand this function a relocated database. Building the
        // family from the argument's own name and parent is what stops an imported `exported.db` from
        // being paired with the production `payanam.db-wal`.
        val relocated = java.io.File("/var/lib/payanam/import/exported.db")
        assertThat(DatabaseArtifacts.filesFor(relocated).map { it.name })
            .containsExactly(
                "exported.db",
                "exported.db-wal",
                "exported.db-shm",
                "exported.db-journal",
            ).inOrder()
        assertThat(DatabaseArtifacts.filesFor(relocated).map { it.parent })
            .containsExactly("/var/lib/payanam/import", "/var/lib/payanam/import", "/var/lib/payanam/import", "/var/lib/payanam/import")
    }

    @Test
    fun `absent members are still listed so existence counts stay correct`() {
        // Every caller that needs existence filters afterwards — for a delete count, a "was there a
        // sidecar?" probe, a backup manifest. Dropping absent members here would make a "deleted 0
        // files" report look like "there was nothing there", which is the read a user takes when the
        // answer drives whether their data is recoverable.
        val files = DatabaseArtifacts.filesFor(java.io.File("/nonexistent/payanam.db"))

        assertThat(files).hasSize(4)
        assertThat(files.filter { it.exists() }).isEmpty()
    }

    @Test
    fun `the temp backup directory name is pinned because the reset path must SKIP it`() {
        // This is the only name here where a rename loses a backup in BOTH directions at once: the
        // writer would fill a directory the reader never looks at, while the reset path preserved a
        // directory nothing ever wrote to. A user mid-restore would be handed a fresh empty database
        // over rows that were sitting on disk the whole time, and the app would not complain.
        assertThat(DatabaseArtifacts.TEMP_BACKUP_DIR_NAME).isEqualTo("payanam_temp_backup")
    }

    @Test
    fun `the transient import temp names are pinned as a sweep, not a file list`() {
        // The sweep exists BECAUSE these names are unbounded: `.enc.tmp-<millis>` carries a timestamp
        // the caller never records, and `.wal_merge_tmp-wal` / `-shm` carry temp-counter tails. So a
        // crash between create and delete leaves files no list could have named — the test pins the
        // four PREFIXES, which is the only part that can be pinned. Changing any one of them stops the
        // reset from matching that file, so the litter survives every reset and a half-written
        // decrypt output is later read as a real database artifact.
        assertThat(DatabaseArtifacts.transientImportTempSuffixes)
            .containsExactly(
                "enc.tmp",
                "enc.tmp-",
                "wal_merge_tmp",
                "import_decrypt.tmp",
            ).inOrder()
        // The dot is the PREFIX's, not the suffix's, so a caller that adds a second one composes
        // `payanam.db..enc.tmp` — a name no sweep would ever match, leaving the temp file on disk
        // forever. The separator has exactly one owner and this pins which one.
        assertThat(DatabaseArtifacts.transientImportTempSuffixes.filter { it.startsWith(".") })
            .isEmpty()
    }

    @Test
    fun `the single exact transient name is derived from the database name, not restated`() {
        // `payanam.db.enc.tmp` is created whole, with no trailing discriminator, so the prefix rule
        // alone leaves it behind on every run. It is spelled as an EXACT match for that reason, and
        // pinned to the full literal here so neither half can drift: a rename of DATABASE_NAME and a
        // rename of the suffix must both break this test rather than quietly stop matching it.
        assertThat(DatabaseArtifacts.exactTransientTempName).isEqualTo("payanam.db.enc.tmp")
        // Composed through the PREFIX, never `DATABASE_NAME + suffix`: that concatenation has no
        // separator and yields `payanam.dbenc.tmp`, a name that matches no sweep rule and would leave
        // the file behind forever. Both halves are pinned so neither can drift.
        assertThat(DatabaseArtifacts.exactTransientTempName)
            .isEqualTo(
                DatabaseArtifacts.transientTempNamePrefix +
                    DatabaseArtifacts.ENCRYPTED_RENAME_TEMP_SUFFIX,
            )
    }

    @Test
    fun `every transient name is the database name plus a pinned suffix`() {
        // This is the rule the four literals used to hide: they are all `<database name>.<suffix>`.
        // Asserting the derivation means a caller can build these from shared names and a hand-typed
        // `payanam.db` cannot survive here — a second copy of the database name is the drift that
        // would orphan a temp file the moment either value changed.
        assertThat(DatabaseArtifacts.transientTempNamePrefix).isEqualTo("payanam.db.")
        assertThat(DatabaseArtifacts.transientTempNamePrefix)
            .isEqualTo(PayanamDatabaseSchema.DATABASE_NAME + ".")

        DatabaseArtifacts.transientImportTempSuffixes.forEach { suffix ->
            val composed = DatabaseArtifacts.transientTempNamePrefix + suffix
            assertThat(composed).startsWith(PayanamDatabaseSchema.DATABASE_NAME)
        }
        // The two encrypted-rename rules are siblings, not a chain: the typed one names files the
        // exact one does not (`payanam.db.enc.tmp-1758900000000`), and the exact one is the shorter
        // string. Every timestamped file the typed rule catches is therefore the exact name plus a
        // tail, which is what makes one exact match plus one prefix sufficient — drop the exact match
        // and the undated file survives every reset; drop the prefix and every dated one does.
        assertThat(DatabaseArtifacts.exactTransientTempName)
            .isEqualTo(
                DatabaseArtifacts.transientTempNamePrefix +
                    DatabaseArtifacts.ENCRYPTED_RENAME_TEMP_SUFFIX,
            )
        assertThat(
            DatabaseArtifacts.transientTempNamePrefix +
                DatabaseArtifacts.ENCRYPTED_RENAME_TEMP_TYPED_SUFFIX + "1758900000000",
        ).startsWith(DatabaseArtifacts.exactTransientTempName)
    }

    @Test
    fun `the sweep must not widen to the temp backup directory or the artifact family`() {
        // The two sets the sweep is NOT allowed to touch. Adding `payanam_temp_backup` to the sweep
        // would delete the recoverable copy during the very reset meant to recover it; the test
        // states the exclusion so that change fails here instead of in a user's data directory.
        assertThat(DatabaseArtifacts.transientImportTempSuffixes)
            .doesNotContain(DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
        assertThat(DatabaseArtifacts.transientImportTempSuffixes)
            .containsNoneIn(DatabaseArtifacts.artifactSuffixes)
        assertThat(DatabaseArtifacts.transientImportTempSuffixes)
            .containsNoneIn(DatabaseArtifacts.sidecarSuffixes)
    }

    @Test
    fun `the on-disk names are the shared values, not a copy the call sites made`() {
        // These are the literals that used to be hand-typed in `DatabaseInitTempBackupSupport.kt`,
        // `SettingsDatabaseArtifactSupport.kt` and `DatabaseArtifactJanitor.kt`. They stay here as the
        // tripwire, because the values are FILE NAMES ON DISK: rename a constant and the test names
        // the file that was written where the reset path cannot find it, instead of a user's in-flight
        // backup being silently orphaned.
        assertThat("payanam.db.enc.tmp")
            .isEqualTo(DatabaseArtifacts.exactTransientTempName)
        assertThat("payanam.db.enc.tmp-")
            .isEqualTo(DatabaseArtifacts.transientTempNamePrefix + "enc.tmp-")
        assertThat("payanam.db.wal_merge_tmp")
            .isEqualTo(DatabaseArtifacts.transientTempNamePrefix + "wal_merge_tmp")
        assertThat("payanam.db.import_decrypt.tmp")
            .isEqualTo(DatabaseArtifacts.transientTempNamePrefix + "import_decrypt.tmp")
        assertThat("payanam_temp_backup").isEqualTo(DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
    }

    @Test
    fun `both suffix lists are built from the three named constants`() {
        // THE ANTI-DRIFT ASSERTION for the families. If a suffix is added to one list and not the
        // other — a fourth sidecar, a renamed `-shm` — this fails here rather than at a call site that
        // quietly copies the wrong family into a temp fold or a delete list.
        assertThat(DatabaseArtifacts.artifactSuffixes)
            .containsExactly(
                "",
                DatabaseArtifacts.WAL_SUFFIX,
                DatabaseArtifacts.SHM_SUFFIX,
                DatabaseArtifacts.JOURNAL_SUFFIX,
            )
        assertThat(DatabaseArtifacts.sidecarSuffixes)
            .containsExactly(
                DatabaseArtifacts.WAL_SUFFIX,
                DatabaseArtifacts.SHM_SUFFIX,
                DatabaseArtifacts.JOURNAL_SUFFIX,
            )
        // Main-file-EMPTY plus the three named suffixes IS the sidecar set plus the main file, so
        // the two lists cannot drift apart without this equality failing.
        assertThat(DatabaseArtifacts.artifactSuffixes)
            .isEqualTo(listOf("") + DatabaseArtifacts.sidecarSuffixes)
    }

    @Test
    fun `a sidecar predicate built from the shared list matches what the callers now write`() {
        // `DatabaseArtifactJanitor` filters corrupt-snapshot candidates with
        // `sidecarSuffixes.none { name.endsWith(it) }`. The hand-typed form it replaced was three
        // `endsWith` predicates; this pins the equivalence so the one-line rewrite cannot have changed
        // which candidates are considered — the filter decides what a corrupt database may be
        // recovered FROM, so a silent difference here is data loss rather than a wrong label.
        val name = "payanam.db.corrupt"
        val handTyped =
            !name.endsWith("-wal") && !name.endsWith("-shm") && !name.endsWith("-journal")
        assertThat(DatabaseArtifacts.sidecarSuffixes.none { name.endsWith(it) }).isEqualTo(handTyped)

        listOf("payanam.db-wal", "payanam.db-shm", "payanam.db-journal").forEach { sidecar ->
            assertThat(DatabaseArtifacts.sidecarSuffixes.any { sidecar.endsWith(it) }).isTrue()
        }
    }
}
