//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.PayanamDatabaseSchema
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * Tests for [PayanamDatabaseOpener].
 *
 * **Why the version rules are tested as pure functions.** SQLCipher JDBC driver 0.2.13 cannot write
 * `PRAGMA user_version` — measured: `execute`, `executeUpdate`, `executeQuery` and a trailing semicolon
 * all persist `0`. A generated fixture is therefore always version `0`, which is below the migratable
 * floor, so it can exercise the REJECT path but never the accept path. Rather than fake a version, the
 * decision lives in [classifySchemaVersion] and is tested directly, and the end-to-end accept path is
 * covered by [interchangeWithARealAndroidExport], which runs against a genuine export supplied through
 * environment variables.
 *
 * Fixtures here are GENERATED in a per-class temp directory and never committed: a real export holds the
 * user's personal data and has no place in a public repository.
 */
class PayanamDatabaseOpenerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "test-passphrase-12345678900Ab@"

    // ---- the version decision (pure) --------------------------------------------------------

    @Test
    fun acceptsEveryVersionInTheSupportedRange() {
        for (version in PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION..PayanamDatabaseSchema.VERSION) {
            assertThat(classifySchemaVersion(version)).isNull()
        }
    }

    @Test
    fun rejectsVersionsBelowTheMigratableFloor() {
        assertThat(classifySchemaVersion(PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION - 1))
            .isEqualTo(DatabaseBootIssueType.DB_TOO_OLD)
        assertThat(classifySchemaVersion(0)).isEqualTo(DatabaseBootIssueType.DB_TOO_OLD)
    }

    @Test
    fun rejectsVersionsNewerThanThisBuild() {
        assertThat(classifySchemaVersion(PayanamDatabaseSchema.VERSION + 1))
            .isEqualTo(DatabaseBootIssueType.DB_TOO_NEW)
    }

    /** Both boundaries are asserted from each side, so an off-by-one cannot pass. */
    @Test
    fun boundaryVersionsAreDecidedCorrectly() {
        assertThat(classifySchemaVersion(PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION)).isNull()
        assertThat(classifySchemaVersion(PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION - 1))
            .isEqualTo(DatabaseBootIssueType.DB_TOO_OLD)
        assertThat(classifySchemaVersion(PayanamDatabaseSchema.VERSION)).isNull()
        assertThat(classifySchemaVersion(PayanamDatabaseSchema.VERSION + 1))
            .isEqualTo(DatabaseBootIssueType.DB_TOO_NEW)
    }

    // ---- passphrase verification (mirrors Android's canOpenWithSqlCipher) --------------------

    @Test
    fun verifyPassphraseAcceptsTheRealPassphrase() {
        val path = createFixture()

        assertThat(PayanamDatabaseOpener.verifyPassphrase(path, passphrase)).isTrue()
    }

    @Test
    fun verifyPassphraseRejectsAWrongPassphrase() {
        val path = createFixture()

        assertThat(PayanamDatabaseOpener.verifyPassphrase(path, "not-the-passphrase")).isFalse()
    }

    @Test
    fun verifyPassphraseRejectsAMissingFileRatherThanClaimingAPassphraseVerdict() {
        val missing = tempFolder.root.toPath().resolve("absent.db")

        assertThat(PayanamDatabaseOpener.verifyPassphrase(missing, passphrase)).isFalse()
    }

    @Test
    fun verifyPassphraseRejectsWhenASidecarIsPresent() {
        val path = createFixture()
        Files.createFile(path.resolveSibling("${path.fileName}-wal"))

        assertThat(PayanamDatabaseOpener.verifyPassphrase(path, passphrase)).isFalse()
    }

    @Test
    fun verifyPassphraseHandlesANonAsciiPassphrase() {
        // Android passes raw UTF-8 bytes, so a non-ASCII passphrase is the case that would expose an
        // encoding mismatch between the two platforms.
        val unicodePassphrase = "test-கடவுச்சொல்-pi-987654321"
        val path = tempFolder.newFolder().toPath().resolve("unicode.db")
        createEncryptedAt(path, unicodePassphrase)

        assertThat(PayanamDatabaseOpener.verifyPassphrase(path, unicodePassphrase)).isTrue()
        assertThat(PayanamDatabaseOpener.verifyPassphrase(path, passphrase)).isFalse()
    }

    // ---- open: the path-level refusals ------------------------------------------------------

    @Test
    fun refusesAMissingFile() {
        val result = PayanamDatabaseOpener.open(tempFolder.root.toPath().resolve("nope.db"), passphrase)

        assertThat(refusalType(result)).isEqualTo(DatabaseBootIssueType.OPEN_FAILED)
    }

    @Test
    fun refusesWhenAWAlSidecarIsPresent() {
        val path = createFixture()
        Files.createFile(path.resolveSibling("${path.fileName}-wal"))

        val result = PayanamDatabaseOpener.open(path, passphrase)

        assertThat(refusalType(result)).isEqualTo(DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING)
    }

    @Test
    fun refusesWhenTheShmSidecarIsPresent() {
        val path = createFixture()
        Files.createFile(path.resolveSibling("${path.fileName}-shm"))

        val result = PayanamDatabaseOpener.open(path, passphrase)

        assertThat(refusalType(result)).isEqualTo(DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING)
    }

    @Test
    fun refusesWhenAJournalSidecarIsPresent() {
        val path = createFixture()
        Files.createFile(path.resolveSibling("${path.fileName}-journal"))

        val result = PayanamDatabaseOpener.open(path, passphrase)

        assertThat(refusalType(result)).isEqualTo(DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING)
    }

    @Test
    fun refusesAWrongPassphraseOnOpen() {
        val path = createFixture()

        val result = PayanamDatabaseOpener.open(path, "definitely-not-the-passphrase")

        assertThat(refusalType(result)).isEqualTo(DatabaseBootIssueType.OPEN_FAILED)
    }

    /**
     * A generated fixture is always version 0, below the migratable floor, so this asserts the refusal
     * the driver's limitation forces and documents why the accept path is unreachable here.
     */
    @Test
    fun refusesAGeneratedFixtureBecauseItsVersionIsBelowTheFloor() {
        val path = createFixture()

        val result = PayanamDatabaseOpener.open(path, passphrase)

        assertThat(refusalType(result)).isEqualTo(DatabaseBootIssueType.DB_TOO_OLD)
    }

    @Test
    fun refusalCarriesTheDetectedVersionFromTheFile() {
        val path = createFixture()

        val refused = PayanamDatabaseOpener.open(path, passphrase) as DatabaseOpenResult.Refused

        // 0 is what the driver persists into a new file, so asserting 0 proves the value was READ from
        // the database rather than defaulted by the guard.
        assertThat(refused.issue.detectedVersion).isEqualTo(0)
    }

    // ---- end-to-end against a REAL Android export (env-provided, never committed) ------------

    /**
     * The milestone's real acceptance check: open a database that Android actually exported.
     *
     * Runs only when `PAYANAM_TEST_DB` (path) and `PAYANAM_TEST_DB_PASSPHRASE` are set, so the suite
     * stays green on a machine without a real export while remaining runnable where one exists. A
     * skipped run is reported as `assumeTrue`-skipped rather than silently counted as a pass.
     */
    @Test
    fun interchangeWithARealAndroidExport() {
        val dbPath = System.getenv("PAYANAM_TEST_DB")
        val dbPassphrase = System.getenv("PAYANAM_TEST_DB_PASSPHRASE")
        assumeTrue(
            "PAYANAM_TEST_DB / PAYANAM_TEST_DB_PASSPHRASE not set",
            dbPath != null && dbPassphrase != null,
        )

        val path = Path.of(dbPath!!)

        assertThat(PayanamDatabaseOpener.verifyPassphrase(path, dbPassphrase!!)).isTrue()

        val result = PayanamDatabaseOpener.open(path, dbPassphrase)
        assertThat(result).isInstanceOf(DatabaseOpenResult.Opened::class.java)
        (result as DatabaseOpenResult.Opened).session.use { session ->
            assertThat(session.schemaVersion).isEqualTo(PayanamDatabaseSchema.VERSION)
            val tables =
                session.requireConnection().createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            assertThat(tables).containsAtLeastElementsIn(PayanamDatabaseSchema.CRITICAL_TABLES)
        }
    }

    /**
     * The read-only guard, proven against a real export.
     *
     * This cannot be covered by a generated fixture: the read-only path sits BEHIND the schema-version
     * check, and a generated fixture is always version 0, so `open` refuses before ever reaching
     * `enforceReadOnly`. That left the milestone's most safety-critical guard unproven everywhere — which
     * is why it is asserted here, against a database that genuinely opens.
     *
     * The assertion that matters is that a WRITE FAILS. Asserting that the PRAGMA was set would pass even
     * if the driver ignored it, which is exactly what `?mode=ro` does.
     */
    @Test
    fun readOnlyIsGenuinelyEnforcedOnARealExport() {
        val dbPath = System.getenv("PAYANAM_TEST_DB")
        val dbPassphrase = System.getenv("PAYANAM_TEST_DB_PASSPHRASE")
        assumeTrue(
            "PAYANAM_TEST_DB / PAYANAM_TEST_DB_PASSPHRASE not set",
            dbPath != null && dbPassphrase != null,
        )

        val result =
            PayanamDatabaseOpener.open(Path.of(dbPath!!), dbPassphrase!!, DatabaseOpenMode.READ_ONLY)

        assertThat(result).isInstanceOf(DatabaseOpenResult.Opened::class.java)
        val session = (result as DatabaseOpenResult.Opened).session
        assertThat(session.mode).isEqualTo(DatabaseOpenMode.READ_ONLY)

        session.use {
            val thrown =
                runCatching {
                    it.requireConnection().createStatement().use { statement ->
                        statement.executeUpdate(
                            "INSERT INTO app_settings (key, value) VALUES ('readonly_probe', 'x')",
                        )
                    }
                }.exceptionOrNull()

            // The proof is the failure, not the mechanism's presence.
            assertThat(thrown).isNotNull()
        }

        // The session must be unusable once closed — the lifecycle half of the same contract.
        val afterClose = runCatching { session.requireConnection() }.exceptionOrNull()
        assertThat(afterClose).isInstanceOf(IllegalStateException::class.java)
        assertThat(session.isClosed).isTrue()
        session.close() // idempotent: a second close is a no-op, not a throw
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The issue type of a refusal, failing if the open unexpectedly succeeded. */
    private fun refusalType(result: DatabaseOpenResult): DatabaseBootIssueType {
        assertThat(result).isInstanceOf(DatabaseOpenResult.Refused::class.java)
        return (result as DatabaseOpenResult.Refused).issue.type
    }

    private fun createFixture(): Path {
        val path = tempFolder.newFolder().toPath().resolve("fixture.db")
        createEncryptedAt(path, passphrase)
        return path
    }

    /** Creates a real encrypted database at [path], with every critical table present. */
    private fun createEncryptedAt(
        path: Path,
        key: String,
    ) {
        val properties = Properties().apply { setProperty("key", key) }
        DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties).use { connection ->
            connection.createStatement().use { statement ->
                PayanamDatabaseSchema.CRITICAL_TABLES.forEach { table ->
                    statement.execute("CREATE TABLE IF NOT EXISTS $table (id TEXT, key TEXT, value TEXT)")
                }
            }
        }
    }
}
