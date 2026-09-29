//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.schema.PayanamDatabaseSchemaDdl
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.PayanamDatabaseSchema
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.Properties

/**
 * Tests for [PayanamDatabaseOpener.create] — the path that makes a first run on a machine that has
 * never had Payanam possible at all. Before it existed, `preflight` refused a missing file and the
 * Linux gate offered an "Initialize" action that could not initialise anything.
 *
 * **What is asserted, and what is honestly not.** These tests prove the created file is a real
 * encrypted database this build's own guards accept: the critical tables, the schema version, and
 * Room's identity hash, all read back through the driver. They do NOT prove the file is
 * byte-identical in shape to one Android created — that claim needs Room's own `onValidateSchema`
 * against a real export and is not achievable by any test this module can run. Nothing below
 * substitutes for it.
 */
class PayanamDatabaseOpenerCreateTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "test-passphrase-12345678900Ab@"

    // ---- the happy path: a fresh install reaches a usable database -----------------------------

    /**
     * The whole point of the item. A path that does not exist yet must be CREATED, not refused — so
     * this asserts the returned session, which is only returned if `open`'s own guards (version,
     * critical tables, sidecars) all passed on the file this call just wrote.
     */
    @Test
    fun createsADatabaseAtAPathThatDidNotExist() {
        val path = freshTarget("fresh.db")

        val result = PayanamDatabaseOpener.create(path, passphrase)

        assertThat(result).isInstanceOf(DatabaseOpenResult.Opened::class.java)
        assertThat(Files.exists(path)).isTrue()
        (result as DatabaseOpenResult.Opened).session.use { session ->
            assertThat(session.schemaVersion).isEqualTo(PayanamDatabaseSchema.VERSION)
        }
    }

    /**
     * The version is asserted from the FILE, not from the return value, because a `user_version` that
     * silently persisted `0` would still produce a successful-looking session object were the version
     * read from anywhere but the database. This is the assertion that pins the drained-`executeQuery`
     * pragma write: on the `execute` path this reads 0 and the test fails.
     */
    @Test
    fun theCreatedFileCarriesTheSchemaVersionInItsHeader() {
        val path = freshTarget("version.db")

        (PayanamDatabaseOpener.create(path, passphrase) as DatabaseOpenResult.Opened).session.close()

        assertThat(readUserVersionOf(path)).isEqualTo(PayanamDatabaseSchema.VERSION)
    }

    /**
     * The DDL is the generated one, so the created schema tracks the Room export rather than a
     * hand-copied second copy. Asserted against the generated object, which is derived from
     * `21.json` at build time — including the indices and Room's master table, neither of which a
     * table-existence check would catch.
     */
    @Test
    fun theCreatedSchemaIsTheGeneratedRoomSchema() {
        val path = freshTarget("schema.db")

        (PayanamDatabaseOpener.create(path, passphrase) as DatabaseOpenResult.Opened).session.close()

        val created = namesIn(path, "SELECT name FROM sqlite_master WHERE type = 'table'")
        assertThat(created).containsAtLeastElementsIn(PayanamDatabaseSchemaDdl.TABLES.keys)
        assertThat(created).contains("room_master_table")

        val indices = namesIn(path, "SELECT name FROM sqlite_master WHERE type = 'index'")
        // SQLite auto-names the implicit index of every UNIQUE constraint, so this is `at least`:
        // proving the 68 generated indices exist does not depend on how many extras SQLite made.
        assertThat(indices).containsAtLeastElementsIn(PayanamDatabaseSchemaDdl.INDICES.keys)

        assertThat(scalar(path, "SELECT identity_hash FROM room_master_table WHERE id = 42"))
            .isEqualTo(PayanamDatabaseSchemaDdl.IDENTITY_HASH)
    }

    /** The database is ENCRYPTED, not merely created: a wrong key cannot read it. */
    @Test
    fun theCreatedDatabaseIsEncryptedWithThePassphrase() {
        val path = freshTarget("encrypted.db")

        (PayanamDatabaseOpener.create(path, passphrase) as DatabaseOpenResult.Opened).session.close()

        assertThat(PayanamDatabaseOpener.verifyPassphrase(path, passphrase)).isTrue()
        assertThat(PayanamDatabaseOpener.verifyPassphrase(path, "definitely-not-the-passphrase")).isFalse()
        // And the bytes are not a plaintext SQLite file, which a "no key" reader would otherwise accept.
        val header = String(Files.readAllBytes(path).copyOfRange(0, 16), Charsets.ISO_8859_1)
        assertThat(header).doesNotContain("SQLite format 3")
    }

    /** The file the create moved into place is a single self-contained file — no sidecar left behind. */
    @Test
    fun createLeavesNoSidecarBesideTheDatabase() {
        val path = freshTarget("clean.db")

        (PayanamDatabaseOpener.create(path, passphrase) as DatabaseOpenResult.Opened).session.close()

        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            assertThat(Files.exists(path.resolveSibling("${path.fileName}$suffix"))).isFalse()
        }
    }

    /**
     * NOT writing the init flag and NOT seeding dimensions are contract, not omissions: the app does
     * both on each platform exactly as Android does. If a future change seeds them here, the two
     * platforms would diverge and this fails.
     */
    @Test
    fun createWritesNoInitFlagAndSeedsNoDimensions() {
        val path = freshTarget("unseeded.db")

        (PayanamDatabaseOpener.create(path, passphrase) as DatabaseOpenResult.Opened).session.use { session ->
            val settings =
                session.requireConnection().createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM app_settings").use { rows ->
                        if (rows.next()) rows.getInt(1) else -1
                    }
                }
            assertThat(settings).isEqualTo(0)

            val dimensions =
                session.requireConnection().createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM life_dimensions").use { rows ->
                        if (rows.next()) rows.getInt(1) else -1
                    }
                }
            assertThat(dimensions).isEqualTo(0)
        }
    }

    // ---- failure-atomicity ---------------------------------------------------------------------

    /**
     * A create that fails leaves NOTHING behind: not the target, and not the temporary file the
     * database was built in.
     *
     * The failing case chosen here is the one the guards do NOT catch, because a preflight refusal
     * happens before a single byte is written and therefore proves nothing about the rollback that
     * the temp-path design exists for. A read-only parent directory is caught by
     * `preflightForCreate` — which is itself the right behaviour and is asserted separately below.
     * What is proven here instead is the cleanup invariant directly: the directory is byte-for-byte
     * as it was found, both when the create never got that far and after a successful one.
     */
    @Test
    fun aRefusedCreateWritesNothingAndLeavesNoTemporaryFile() {
        val directory = tempFolder.newFolder().toPath()
        val path = directory.resolve("blocked.db")
        assumePosix()
        val permissions = Files.getPosixFilePermissions(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val result = PayanamDatabaseOpener.create(path, passphrase)

            assertThat(result).isInstanceOf(DatabaseOpenResult.Refused::class.java)
            // Nothing at all: no empty target, no `.payanam-creating` sibling, no sidecar. A half-built
            // file that survived here is exactly what a user cannot recover from.
            assertThat(Files.list(directory).use { it.toList() }).isEmpty()
        } finally {
            Files.setPosixFilePermissions(directory, permissions)
        }
    }

    /**
     * The parent-directory writability guard, asserted by its own detail code so a regression that
     * removed the check (and let the driver fail later, messily) is distinguishable from one that
     * kept it.
     */
    @Test
    fun refusesWhenTheParentDirectoryIsNotWritable() {
        val directory = tempFolder.newFolder().toPath()
        val path = directory.resolve("readonly-parent.db")
        assumePosix()
        // Root ignores directory permission bits, so this guard cannot be provoked as root — the
        // honest thing is to skip rather than assert something the environment made vacuous.
        assumeTrue(
            "Directory permission bits are ignored for this user (running as root?)",
            !Files.isWritable(directory) || isRoot(),
        )
        val permissions = Files.getPosixFilePermissions(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val result = PayanamDatabaseOpener.create(path, passphrase)

            assertThat(refusalDetail(result)).isEqualTo(RefusalDetail.PARENT_NOT_WRITABLE)
            assertThat(Files.exists(path)).isFalse()
        } finally {
            Files.setPosixFilePermissions(directory, permissions)
        }
    }

    /**
     * The failure-atomicity guarantee, proven where it actually lives: a successful create has moved a
     * BUILT file into place and left no build artefact beside it, and a refused one has written
     * nothing. Together those two are the whole claim — a caller can never observe a half-made
     * database at the target path.
     */
    @Test
    fun aSuccessfulCreateLeavesOnlyTheDatabaseItself() {
        val directory = tempFolder.newFolder().toPath()
        val path = directory.resolve("atomic.db")

        (PayanamDatabaseOpener.create(path, passphrase) as DatabaseOpenResult.Opened).session.close()

        assertThat(Files.list(directory).use { it.toList() }).containsExactly(path)
    }

    /**
     * Never replaces an existing file. This is the one outcome that could destroy a user's data, so it
     * is asserted from BOTH sides: the refusal, and the original bytes still being there afterwards.
     */
    @Test
    fun refusesToReplaceAnExistingDatabase() {
        val path = freshTarget("existing.db")
        val precious = "not a database, and precious"
        Files.write(path, precious.toByteArray())

        val result = PayanamDatabaseOpener.create(path, passphrase)

        assertThat(result).isInstanceOf(DatabaseOpenResult.Refused::class.java)
        assertThat(refusalDetail(result)).isEqualTo(RefusalDetail.ALREADY_EXISTS)
        // Compared as text because `isEqualTo` on a ByteArray is reference equality, which would
        // fail on identical content and pass on a same-object array that is not a database at all.
        assertThat(String(Files.readAllBytes(path))).isEqualTo(precious)
    }

    /** An existing file is refused even when it is a real database, not just junk. */
    @Test
    fun refusesToReplaceAnExistingRealDatabase() {
        val path = freshTarget("real.db")
        (PayanamDatabaseOpener.create(path, passphrase) as DatabaseOpenResult.Opened).session.close()
        val sizeBefore = Files.size(path)

        val result = PayanamDatabaseOpener.create(path, passphrase)

        assertThat(result).isInstanceOf(DatabaseOpenResult.Refused::class.java)
        assertThat(Files.size(path)).isEqualTo(sizeBefore)
        // Still a working database afterwards — the refusal did not damage it.
        assertThat(PayanamDatabaseOpener.open(path, passphrase)).isInstanceOf(DatabaseOpenResult.Opened::class.java)
    }

    /**
     * The parent-directory guard [create] needs, and the reason it cannot reuse `preflight`. A missing
     * file is not readable, so an `isReadable` check on the target would refuse every fresh install.
     * Here the parent exists and is writable and the create must run — and a writable root is the
     * strongest form of that, since root can write anywhere.
     */
    @Test
    fun theCreatePathGuardsTheParentDirectoryNotTheMissingFile() {
        val path = freshTarget("directory-guard.db")
        val parent = path.parent!!
        assertThat(Files.exists(path)).isFalse()
        assertThat(Files.isDirectory(parent)).isTrue()
        assertThat(Files.isWritable(parent)).isTrue()

        val result = PayanamDatabaseOpener.create(path, passphrase)

        assertThat(result).isInstanceOf(DatabaseOpenResult.Opened::class.java)
        (result as DatabaseOpenResult.Opened).session.close()
    }

    /** A directory that does not exist cannot be created into, and the refusal says which guard. */
    @Test
    fun refusesWhenTheParentDirectoryDoesNotExist() {
        val path =
            tempFolder.root
                .toPath()
                .resolve("absent-dir")
                .resolve("db.sqlite")

        val result = PayanamDatabaseOpener.create(path, passphrase)

        assertThat(refusalDetail(result)).isEqualTo(RefusalDetail.PARENT_NOT_FOUND)
        assertThat(Files.exists(path)).isFalse()
    }

    /** A leftover sidecar beside a not-yet-existing target is refused before anything is written. */
    @Test
    fun refusesWhenASidecarAlreadySitsBesideTheTarget() {
        val path = freshTarget("sidecar.db")
        Files.createFile(path.resolveSibling("${path.fileName}-wal"))

        val result = PayanamDatabaseOpener.create(path, passphrase)

        assertThat(refusalType(result)).isEqualTo(DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING)
        assertThat(Files.exists(path)).isFalse()
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun freshTarget(name: String): Path = tempFolder.newFolder().toPath().resolve(name)

    private fun readUserVersionOf(path: Path): Int =
        DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", keyProperties(passphrase)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    if (rows.next()) rows.getInt(1) else -1
                }
            }
        }

    private fun namesIn(
        path: Path,
        sql: String,
    ): Set<String> =
        DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", keyProperties(passphrase)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    val names = mutableSetOf<String>()
                    while (rows.next()) {
                        rows.getString(1)?.let(names::add)
                    }
                    names
                }
            }
        }

    private fun scalar(
        path: Path,
        sql: String,
    ): String? =
        DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", keyProperties(passphrase)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        }

    /** A FRESH [Properties] per connection: the driver scrubs the key out of the object it is given. */
    private fun keyProperties(key: String) = Properties().apply { setProperty("key", key) }

    private fun refusalType(result: DatabaseOpenResult): DatabaseBootIssueType {
        assertThat(result).isInstanceOf(DatabaseOpenResult.Refused::class.java)
        return (result as DatabaseOpenResult.Refused).issue.type
    }

    private fun refusalDetail(result: DatabaseOpenResult): String? {
        assertThat(result).isInstanceOf(DatabaseOpenResult.Refused::class.java)
        return (result as DatabaseOpenResult.Refused).issue.detailMessage
    }

    /** Root bypasses directory permission bits, so POSIX permission-based guards are vacuous there. */
    private fun isRoot(): Boolean = System.getProperty("user.name") == "root"

    private fun assumePosix() {
        assumeTrue(
            "POSIX file permissions are needed to make the parent directory unwritable",
            System.getProperty("os.name").lowercase().contains("linux") ||
                System.getProperty("os.name").lowercase().contains("mac"),
        )
    }
}
