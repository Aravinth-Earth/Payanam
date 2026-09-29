//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.database

import java.io.File

/**
 * The one declaration of which files make up a Payanam database artifact family.
 *
 * WHY THIS IS NOT A CONSTANT STRING: the artifact list gates data recovery. A main database file with
 * a surviving `-wal` beside it can still hold committed rows that exist nowhere else, so a caller that
 * forgets `-journal` — or that forgets to check for a sidecar at all — does not merely leave litter
 * behind. It misreads a real database as absent, resets encryption state, or offers the user a fresh
 * empty database over rows that were there. When every caller spelled the family out for itself, that
 * list was five hand-typed copies of a recovery-critical set, and the failure mode of getting one
 * wrong is silent and destructive rather than a crash.
 *
 * WHERE IT LIVES: `core/shared`, beside [PayanamDatabaseSchema]. `core/database` is an
 * `android.library` and the desktop app depends only on `core:shared`, so any home inside Android-only
 * code forces the desktop to hardcode a second copy — exactly the drift this object removes. Both
 * platforms, plus `core:database` and `core:database-jvm`, already depend on `core:shared`, so no new
 * module and no new build dependency is needed to call it.
 *
 * TWO MEMBERS, NOT ONE, because the callers genuinely differ:
 *  - [artifactSuffixes] is the FULL family: the main file FIRST, then the three sidecars in the fixed
 *    order every caller relies on.
 *  - [sidecarSuffixes] is the sidecars ALONE. The guards that refuse to open a database, and the
 *    cleanup that removes companions, are about the sidecars only — a `main` entry there would make
 *    those callers refuse or delete the very file they are inspecting. That difference is a named
 *    constant, not a silent divergence.
 *
 * IT ALSO OWNS THE TEMP-BACKUP FAMILY, which is a different kind of name: the directory the init and
 * import flows copy the whole artifact family into BEFORE they touch the live database, and the
 * transient names those flows leave behind when a copy is interrupted halfway. It is here for the
 * same reason as the first family, and the stakes are sharper because every one of these values is a
 * FILE NAME ON DISK rather than a label. A renamed temp-backup directory is a backup the wipe path no
 * longer recognises, so a user holding an in-flight backup is offered a fresh empty database over a
 * recoverable one, and nothing anywhere crashes. The transient names cut the other way too: a name
 * that stops matching is litter that survives every reset, and a name that starts matching something
 * it did not match before deletes a file the user still needs.
 *
 * NOT RE-POINTED YET: the callers (`app/.../DatabaseInitTempBackupSupport.kt`,
 * `app/.../SettingsDatabaseArtifactSupport.kt` and `core/database`'s janitor) still spell these values
 * out, because a later wave moves that code into this module. Until it does, the values here are a
 * pinned contract rather than a live one — the tests below are what keep the two copies honest in the
 * meantime, so change one and the test names the file that has to change with it.
 */
object DatabaseArtifacts {
    /**
     * The main database file plus its companions, in the order callers depend on: main first, then
     * `-wal`, `-shm`, `-journal`.
     *
     * The main file is an empty suffix by construction, so [filesFor] can build the whole family with
     * one map and cannot grow a second, subtly different builder per call site.
     */
    val artifactSuffixes: List<String> = listOf("", "-wal", "-shm", "-journal")

    /**
     * The companion files alone, in the same relative order as [artifactSuffixes].
     *
     * Exposed by name so the "sidecars only" callers say so at the call site rather than encoding the
     * omission as a hand-typed three-item list.
     */
    val sidecarSuffixes: List<String> = listOf("-wal", "-shm", "-journal")

    /**
     * The full artifact family for [databaseFile]: [databaseFile] itself, then its `-wal`, `-shm` and
     * `-journal` siblings, in [artifactSuffixes] order.
     *
     * Returned members are parented on [databaseFile]'s own directory, so a caller may pass a
     * relocated, imported or temp copy — the family follows the file given, not a database name.
     * Entries that do not exist are still returned: every caller that needs existence filters
     * afterwards (for a delete count, a "was there a sidecar?" probe, a backup manifest), and dropping
     * absent members here would make those counts silently wrong.
     */
    fun filesFor(databaseFile: File): List<File> {
        val parent = databaseFile.parentFile
        return artifactSuffixes.map { suffix -> File(parent, databaseFile.name + suffix) }
    }

    /**
     * The sidecar siblings of [databaseFile] alone — `-wal`, `-shm`, `-journal`, in that order.
     *
     * Use this where the main file is the subject rather than a member: refusing an unclean open,
     * deleting companions after a checkpoint, or deciding whether a clean main file is trustworthy.
     */
    fun sidecarFilesFor(databaseFile: File): List<File> {
        val parent = databaseFile.parentFile
        return sidecarSuffixes.map { suffix -> File(parent, databaseFile.name + suffix) }
    }

    /**
     * The directory the create/import flows copy the whole artifact family into before touching the
     * live database — a sibling of the database file, never a second database.
     *
     * This one name is load-bearing in BOTH directions and that is the reason it is a constant rather
     * than a local. The writer builds it, and the reset path deliberately EXCLUDES it: wiping the
     * active artifacts is exactly the operation a crashed init is trying to recover from, so
     * deleting this directory at the same moment would destroy the only good copy of the user's rows.
     * A rename therefore has two separate ways to lose a backup, and neither raises an error — the
     * writer writes somewhere the reader never looks, and the reader preserves a directory that no
     * longer fills up. The value is a file name on disk, so it may not be reworded even to a more
     * consistent-looking name.
     */
    const val TEMP_BACKUP_DIR_NAME = "payanam_temp_backup"

    /**
     * The `payanam.db` prefix each transient name carries, so a caller can build the family instead
     * of hand-typing `payanam.db` beside it — the same reasoning as [artifactSuffixes], where the main
     * file is the empty suffix and the builder is one map rather than a spelling per call site.
     *
     * This member OWNS THE SEPARATOR DOT. The suffixes below carry none, and every composed name in
     * this object is built by concatenating that prefix with a suffix, so a caller cannot accidentally
     * produce `payanam.db..enc.tmp` or `payanam.dbenc.tmp` — either of which matches no sweep rule and
     * leaves an in-flight temp file on disk permanently.
     */
    val transientTempNamePrefix: String = PayanamDatabaseSchema.DATABASE_NAME + "."

    /**
     * The base of the interrupted-import names — the transient half of this family, cleaned by
     * directory sweep rather than by a known file list.
     *
     * Every name below is [transientTempNamePrefix] plus one of these, which is the whole rule: the
     * flows that leave them behind (WAL merge, decrypt-to-temp, encrypted-rename-to-temp) all name a
     * file after the database they were working on, so the sweep matches on the DATABASE NAME rather
     * than on a hardcoded copy of it. [DATABASE_NAME] is the same constant the artifact family is
     * built from, so a renamed database cannot leave a sweep that still matches the old name.
     *
     * The suffixes carry NO leading dot: the separator belongs to [transientTempNamePrefix] alone, so
     * there is exactly one place that can get it wrong rather than one per suffix.
     *
     * WHY THE SWEEP AND NOT A LIST: these files are created and deleted inside a single call, so a
     * crash leaves behind names the caller never recorded — a timestamped `.enc.tmp-<millis>` has
     * values nobody wrote down. An exact-match list would only ever describe a clean run.
     */
    const val ENCRYPTED_RENAME_TEMP_SUFFIX = "enc.tmp"
    const val ENCRYPTED_RENAME_TEMP_TYPED_SUFFIX = "enc.tmp-"
    const val WAL_MERGE_TEMP_SUFFIX = "wal_merge_tmp"
    const val IMPORT_DECRYPT_TEMP_SUFFIX = "import_decrypt.tmp"

    /**
     * Every transient name the reset sweep must remove, in the order the wipe reports them.
     *
     * [TEMP_BACKUP_DIR_NAME] is NOT here, and that omission is the point of the member: the sweep
     * skips the one directory holding a recoverable copy. It also does not cover the `artifactSuffixes`
     * family, because the artifact set is removed by the known [filesFor] list rather than by name
     * matching — a name-based sweep could never be trusted to say "and nothing else".
     */
    val transientImportTempSuffixes: List<String> = listOf(
        ENCRYPTED_RENAME_TEMP_SUFFIX,
        ENCRYPTED_RENAME_TEMP_TYPED_SUFFIX,
        WAL_MERGE_TEMP_SUFFIX,
        IMPORT_DECRYPT_TEMP_SUFFIX,
    )

    /**
     * The one EXACT name the sweep must match: `payanam.db.enc.tmp` with no trailing discriminator.
     *
     * The other three are prefixes, because their files are created with variable tails (a timestamp,
     * a temp-counter suffix). This one is created whole, so matching it as a prefix would be
     * near-harmless while matching only the prefix form would leave this file behind on every run.
     * It is spelled separately, and named as an exact match, so a reader can see that the two matching
     * rules are different rather than assuming one list covers both.
     */
    val exactTransientTempName: String =
        transientTempNamePrefix + ENCRYPTED_RENAME_TEMP_SUFFIX
}
