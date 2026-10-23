//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.PayanamDatabaseSchema
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Item 458 fixture schema-acceptance test.
 *
 * Opens the real fixture at `/home/cawax/payanm app data/payanam.db` through the repo's own
 * migration path ([PayanamDatabaseOpener.open]) and asserts the schema is accepted — no device,
 * no export, no install. The test exits 0 when the fixture is present and the schema is valid.
 *
 * The passphrase is read from the sibling pwd file, never hardcoded. The test is opt-in via
 * [assumeTrue] so CI and machines without the fixture skip cleanly rather than fail.
 */
class FixtureSchemaAcceptanceTest {

    private val fixturePath = Path.of("/home/cawax/payanm app data/payanam.db")
    private val passphrasePath = Path.of("/home/cawax/payanm app data/payanam test DB pwd")

    @Test
    fun fixtureSchemaIsAcceptedByTheReposOwnMigrationPath() {
        assumeTrue(
            "Fixture not present at ${fixturePath.toAbsolutePath()}",
            Files.exists(fixturePath) && Files.isReadable(fixturePath),
        )
        assumeTrue(
            "Passphrase file not present at ${passphrasePath.toAbsolutePath()}",
            Files.exists(passphrasePath) && Files.isReadable(passphrasePath),
        )

        val result = openFixtureWithThePassphraseItsOwnFileCarries()

        assertThat(result).isInstanceOf(DatabaseOpenResult.Opened::class.java)
        (result as DatabaseOpenResult.Opened).session.use { session ->
            // NOT `isEqualTo(VERSION)`: the desktop cannot run Room migrations, so it reads the
            // supported range READ-ONLY instead of migrating it - see the NOTE on
            // `PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION` ("Android migrates versions in
            // [MIN_MIGRATABLE_VERSION, VERSION) in place. The desktop cannot run Room migrations, so
            // it READS that range read-only instead of refusing it - refusing would lock a user out
            // of a perfectly readable export"). Asserting the file is already at VERSION would demand
            // the migration this platform deliberately does not perform, and would fail on every
            // export made before the current schema. What the gate needs is that the fixture's schema
            // is ACCEPTED and readable, which the range check plus the critical-table check below is.
            assertThat(session.schemaVersion)
                .isAtLeast(PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION)
            assertThat(session.schemaVersion).isAtMost(PayanamDatabaseSchema.VERSION)
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
     * Opens the fixture with the passphrase its own companion file carries.
     *
     * **The rule is "try the values, in file order, and let the opener decide" — not "take a line".** The
     * companion file is a list of label/value pairs (`<label with spaces>` newline `<single-word value>`),
     * and it now carries a SECOND pair: a newer artifact's value sits last, and it does not open this
     * database. `readText().trim()` concatenated the label into the secret (the trap the desktop's
     * `DesktopUnlockAgainstFixtureTest` documents), and its replacement - the LAST non-empty line - reads
     * that second pair's value and fails exactly like a wrong passphrase does. Both readings make the
     * fixture look corrupt when the fixture is fine, which is the expensive direction to be wrong in.
     *
     * So: every non-blank single-word line is a candidate, in the order the file lists them, and the first
     * one the app's OWN opener accepts wins. The assertion below still sees the opener's own result, so a
     * genuinely wrong pair still fails with the opener's message rather than this helper's.
     */
    private fun openFixtureWithThePassphraseItsOwnFileCarries(): DatabaseOpenResult {
        val candidates =
            Files
                .readAllLines(passphrasePath)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.none(Char::isWhitespace) }
        if (candidates.isEmpty()) {
            fail("The fixture passphrase file lists no single-word candidate: ${passphrasePath.toAbsolutePath()}")
        }
        var last: DatabaseOpenResult? = null
        for (candidate in candidates) {
            val result = PayanamDatabaseOpener.open(fixturePath, candidate)
            if (result is DatabaseOpenResult.Opened) return result
            last = result
        }
        return requireNotNull(last)
    }
}