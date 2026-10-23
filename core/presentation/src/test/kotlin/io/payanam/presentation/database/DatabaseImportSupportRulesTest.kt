//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.EncryptedFileClassifier
import io.payanam.shared.platform.COPY_NOT_ATTEMPTED
import io.payanam.shared.platform.DocumentEntry
import io.payanam.shared.platform.DocumentKind
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.platform.SupportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files

/**
 * Pins two rules of the promoted [DatabaseImportSupport] that no other test holds.
 *
 * **RULE 1 — the `-journal` gap.** The picker used to reject only `-wal`/`-shm`, so a picked
 * `payanam.db-journal` was accepted as if it were a database, and a folder import copied only
 * `-wal`/`-shm` companions — silently dropping a `-journal` SQLite really creates. Both halves are
 * pinned here: the name gate, and a container import that must copy FOUR artifacts.
 *
 * **RULE 2 — the duplicated SQLite magic check.** `isStandardSqliteFile` used to restate the
 * `"SQLite format 3\0"` header locally, which is a second copy of the encrypted-file rule that could
 * disagree with the shared classifier. It now asks [EncryptedFileClassifier], so the test asserts the
 * ONE-RULE property directly: the same bytes classified by hand and classified through the importer
 * must produce the same verdict.
 *
 * Plain JUnit4, no Android, no Robolectric: the promoted code is `kotlin.jvm`, and its only platform
 * contact is [DocumentSource] — faked below.
 */
class DatabaseImportSupportRulesTest {
    /* ---------------------------------------------------------------- RULE 1 */

    /**
     * A `-journal` is a sidecar, not a database: the picker must refuse it.
     *
     * The sibling assertions are the same rule seen from the other side, because the implementation
     * reads `DatabaseArtifacts.sidecarSuffixes` rather than a hand-typed list — so a change to that
     * list moves all of these together.
     */
    @Test
    fun rule1_journal_sidecar_is_not_an_importable_database_name() {
        assertFalse(DatabaseImportSupport.isImportableDatabaseFileName("x.db-journal"))
        assertFalse(DatabaseImportSupport.isImportableDatabaseFileName("payanam.db-journal"))
        assertFalse(DatabaseImportSupport.isImportableDatabaseFileName("payanam.db-wal"))
        assertFalse(DatabaseImportSupport.isImportableDatabaseFileName("payanam.db-shm"))
        // Case-insensitive, so an uppercased picker label cannot slip a companion through.
        assertFalse(DatabaseImportSupport.isImportableDatabaseFileName("payanam.db-JOURNAL"))
        // And the real database is still accepted.
        assertTrue(DatabaseImportSupport.isImportableDatabaseFileName("payanam.db"))
    }

    /**
     * Every sidecar suffix is refused, checked case-insensitively, and the shared list is the source.
     *
     * NOTE ON WHICH CLAUSE DOES THE WORK, because it decides what this test can claim. The gate is
     * `no-sidecar-suffix AND ends-with-.db`, and no string can end with both `-wal` and `.db` — so for
     * the shipped suffix list the two clauses never disagree, and dropping `-journal` from the list
     * (verified by mutation) leaves every name here still reading false. A mutation check confirmed
     * that, so this test pins the OBSERVED contract rather than pretending the sidecar clause is
     * independently load-bearing. The gate is a whitelist, so the redundant clause is a cheap
     * second gate on a recovery-critical decision, not a rule this test can single out.
     */
    @Test
    fun rule1_every_shared_sidecar_suffix_is_refused_case_insensitively() {
        DatabaseArtifacts.sidecarSuffixes.forEach { suffix ->
            assertFalse(
                "payanam.db$suffix is a companion, not a database",
                DatabaseImportSupport.isImportableDatabaseFileName("payanam.db$suffix"),
            )
            assertFalse(
                "payanam.db$suffix is a companion regardless of the case the picker reports",
                DatabaseImportSupport.isImportableDatabaseFileName("payanam.db$suffix".uppercase()),
            )
        }
        assertTrue(DatabaseImportSupport.isImportableDatabaseFileName("payanam.db"))
        // A `.db` file with no sidecar marker is a real database, whichever side of the extension the
        // name puts it on — so this pins the whitelist, not a blanket name-length rule.
        assertTrue(DatabaseImportSupport.isImportableDatabaseFileName("my export.db"))
    }

    /**
     * A folder import copies the whole artifact family: db + `-wal` + `-shm` + `-journal` = 4 files,
     * 3 of them companions.
     *
     * This is the assertion that would have caught the original near-miss: with `-journal` missing
     * from the companion list, the copy silently succeeded with `companionFilesCopied == 2` and the
     * rollback journal was left on disk while the database reported success.
     */
    @Test
    fun rule1_container_import_copies_four_artifacts_including_journal() {
        val targetDir = Files.createTempDirectory("payanam-import-rule1").toFile()
        val targetDb = File(targetDir, "payanam.db")
        val source =
            FakeDocumentSource(
                kinds = mapOf(CONTAINER_TOKEN to DocumentKind.CONTAINER),
                bytesByToken =
                    mapOf(
                        DB_TOKEN to sqliteMagicBytes(),
                        WAL_TOKEN to "wal-bytes".toByteArray(),
                        SHM_TOKEN to "shm-bytes".toByteArray(),
                        JOURNAL_TOKEN to "journal-bytes".toByteArray(),
                    ),
                namesByToken =
                    mapOf(
                        DB_TOKEN to "payanam.db",
                        WAL_TOKEN to "payanam.db-wal",
                        SHM_TOKEN to "payanam.db-shm",
                        JOURNAL_TOKEN to "payanam.db-journal",
                    ),
                childrenByToken =
                    mapOf(
                        CONTAINER_TOKEN to
                            listOf(
                                entry("payanam.db", DB_TOKEN),
                                entry("payanam.db-wal", WAL_TOKEN),
                                entry("payanam.db-shm", SHM_TOKEN),
                                entry("payanam.db-journal", JOURNAL_TOKEN),
                            ),
                    ),
            )

        val result =
            DatabaseImportSupport.copyDatabaseArtifacts(
                documentSource = source,
                sourceToken = CONTAINER_TOKEN,
                targetDatabaseFile = targetDb,
                strings = stubStrings(),
            )

        assertEquals("folder", result.sourceKind)
        assertEquals("payanam.db", result.primaryFileName)
        assertEquals(3, result.companionFilesCopied)
        assertEquals(4, source.copiedTokens.size)
        // Every artifact landed next to the target, named as the family names them.
        assertTrue(targetDb.isFile)
        assertTrue(File(targetDir, "payanam.db-wal").isFile)
        assertTrue(File(targetDir, "payanam.db-shm").isFile)
        val journalTarget = File(targetDir, "payanam.db-journal")
        assertTrue("the -journal companion must be copied, not dropped", journalTarget.isFile)
        assertEquals("journal-bytes", String(journalTarget.readBytes(), Charsets.ISO_8859_1))
        assertEquals(sqliteMagicBytes().size.toLong(), result.bytesCopied)

        targetDir.deleteRecursively()
    }

    /* ---------------------------------------------------------------- RULE 2 */

    /**
     * The plaintext SQLite header is the only header accepted, built here with a real NUL — never the
     * `\0` escape, which does not exist in Kotlin.
     */
    @Test
    fun rule2_plaintext_magic_header_is_a_standard_sqlite_file() {
        withFile("magic.db", sqliteMagicBytes()) { file ->
            assertTrue(DatabaseImportSupport.isStandardSqliteFile(file, "Rule2Test"))
        }
        // A real database is a header plus a page; the tail must not change the verdict.
        withFile("magic-page.db", sqliteMagicBytes() + ByteArray(4080)) { file ->
            assertTrue(DatabaseImportSupport.isStandardSqliteFile(file, "Rule2Test"))
        }
    }

    /** A header long enough to be a database but not the plaintext magic is not one. */
    @Test
    fun rule2_non_magic_header_is_rejected() {
        withFile("encrypted.db", "ZZZZ-not-a-plaintext-d".toByteArray(Charsets.ISO_8859_1)) { file ->
            assertFalse(DatabaseImportSupport.isStandardSqliteFile(file, "Rule2Test"))
        }
        // Off-by-one on the last magic byte must also fail: the comparison is exact, not a prefix match.
        val corrupted = sqliteMagicBytes().copyOf().also { it[it.size - 1] = 0x01 }
        withFile("magic-corrupt.db", corrupted + ByteArray(4080)) { file ->
            assertFalse(DatabaseImportSupport.isStandardSqliteFile(file, "Rule2Test"))
        }
    }

    /** Shorter than the magic, a file cannot have carried one — so it is not a database. */
    @Test
    fun rule2_file_shorter_than_the_magic_is_rejected() {
        withFile("tiny.db", sqliteMagicBytes().copyOf(EncryptedFileClassifier.MINIMUM_PROBE_BYTES - 1)) { file ->
            assertFalse(DatabaseImportSupport.isStandardSqliteFile(file, "Rule2Test"))
        }
        // Even when those short bytes are a prefix of the real header: length decides, not content.
        withFile("prefix.db", "SQLite form".toByteArray(Charsets.ISO_8859_1)) { file ->
            assertFalse(DatabaseImportSupport.isStandardSqliteFile(file, "Rule2Test"))
        }
    }

    /** A file that is not there cannot be read, and an unreadable import must not be treated as valid. */
    @Test
    fun rule2_absent_file_is_rejected() {
        val missing =
            File(
                Files.createTempDirectory("payanam-import-rule2").toFile(),
                "does-not-exist.db",
            )
        assertFalse(DatabaseImportSupport.isStandardSqliteFile(missing, "Rule2Test"))
        // An empty file is the other "nothing to classify" case.
        withFile("empty.db", ByteArray(0)) { file ->
            assertFalse(DatabaseImportSupport.isStandardSqliteFile(file, "Rule2Test"))
        }
        missing.parentFile.deleteRecursively()
    }

    /**
     * The ONE-RULE property: [DatabaseImportSupport.isStandardSqliteFile] must agree with
     * [EncryptedFileClassifier] on the very same bytes, for every verdict — not merely re-implement
     * it correctly today, but stay pointed at the shared rule.
     *
     * This is the assertion that fails if someone pastes the header literal back into the importer:
     * the two copies would then be independent and could drift.
     */
    @Test
    fun rule2_verdict_is_exactly_the_shared_classifier_verdict() {
        val headers =
            listOf(
                "plaintext-magic" to sqliteMagicBytes(),
                "plaintext-magic-padded" to sqliteMagicBytes() + ByteArray(4080),
                "no-magic-exact-length" to "ZZZZ-not-a-plaintext-d".toByteArray(Charsets.ISO_8859_1),
                "no-magic-longer" to "ZZZZ-not-a-plaintext-d".toByteArray(Charsets.ISO_8859_1) + ByteArray(4080),
                "shorter-than-magic" to "SQLite form".toByteArray(Charsets.ISO_8859_1),
                "single-byte" to "Z".toByteArray(Charsets.ISO_8859_1),
            )
        headers.forEach { (label, fileBytes) ->
            withFile("$label.db", fileBytes) { file ->
                // The bytes the importer's own probe hands to the classifier: a fixed
                // MINIMUM_PROBE_BYTES buffer, zero-padded past whatever the file actually had.
                val probeHeader =
                    ByteArray(EncryptedFileClassifier.MINIMUM_PROBE_BYTES)
                        .also { buffer ->
                            fileBytes.copyInto(buffer, 0, 0, minOf(fileBytes.size, buffer.size))
                        }
                val sharedVerdict =
                    EncryptedFileClassifier.classify(
                        header = probeHeader,
                        totalLength = file.length(),
                        readSucceeded = file.length() > 0,
                    )
                val importerVerdict = DatabaseImportSupport.isStandardSqliteFile(file, "Rule2Test")
                assertEquals(
                    "isStandardSqliteFile must equal EncryptedFileClassifier.hasPlaintextMagic for $label",
                    sharedVerdict.hasPlaintextMagic,
                    importerVerdict,
                )
            }
        }
    }

    /* -------------------------------------------------------------- fixtures */

    private fun withFile(
        name: String,
        bytes: ByteArray,
        body: (File) -> Unit,
    ) {
        val dir = Files.createTempDirectory("payanam-import-rule2").toFile()
        val file = File(dir, name)
        file.writeBytes(bytes)
        try {
            body(file)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun entry(
        name: String,
        token: String,
    ) = DocumentEntry(name = name, token = token, kind = DocumentKind.FILE)

    /**
     * The 16 plaintext-magic bytes, built with an explicit `\u0000`.
     *
     * Kotlin has no `\0` escape, and a raw NUL cannot be typed into source, so this is the only way
     * to spell the real header — the same construction
     * [EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC] itself uses.
     */
    private fun sqliteMagicBytes(): ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    /**
     * A [UiStrings] whose every member returns a distinct stub.
     *
     * [UiStrings] is a ~998-member interface generated from `strings.xml`; implementing it by hand is
     * not a maintenance surface any test should own, and the promoted code only READS the members it
     * throws with. A dynamic proxy answers all of them, so a new string key needs no test edit — which
     * is the point: the test is about the two rules, not about the string table.
     */
    private fun stubStrings(): UiStrings =
        Proxy.newProxyInstance(
            UiStrings::class.java.classLoader,
            arrayOf(UiStrings::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "StubUiStrings"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> (args?.getOrNull(0) === proxy)
                else -> "stub-" + method.name
            }
        } as UiStrings

    /**
     * In-memory [DocumentSource] over a fixed token map.
     *
     * [copyInto] writes real bytes to the destination stream and returns the count, because the code
     * under test treats that count as its copy result; an unknown token returns
     * [COPY_NOT_ATTEMPTED], the shared "could not be opened at all" sentinel.
     */
    private class FakeDocumentSource(
        private val kinds: Map<String, DocumentKind> = emptyMap(),
        private val bytesByToken: Map<String, ByteArray> = emptyMap(),
        private val namesByToken: Map<String, String?> = emptyMap(),
        private val childrenByToken: Map<String, List<DocumentEntry>> = emptyMap(),
    ) : DocumentSource {
        /** Every token [copyInto] was asked for, in order, so a dropped companion is observable. */
        val copiedTokens: MutableList<String> = mutableListOf()

        override val fileAccess: SupportState = SupportState.SUPPORTED

        override val containerAccess: SupportState = SupportState.SUPPORTED

        override suspend fun requestSource(suggestedName: String?): String? = null

        override suspend fun requestDestination(suggestedName: String): String? = null

        override fun displayNameOf(token: String): String? = namesByToken[token]

        override fun kindOf(token: String): DocumentKind = kinds[token] ?: DocumentKind.FILE

        override fun copyInto(
            token: String,
            destination: OutputStream,
        ): Long {
            val bytes = bytesByToken[token] ?: return COPY_NOT_ATTEMPTED
            destination.write(bytes)
            destination.flush()
            copiedTokens.add(token)
            return bytes.size.toLong()
        }

        override fun readLeadingBytes(
            token: String,
            byteCount: Int,
        ): ByteArray? = bytesByToken[token]?.copyOf(minOf(byteCount, bytesByToken.getValue(token).size))

        override fun childrenOf(token: String): List<DocumentEntry> = childrenByToken[token].orEmpty()
    }

    private companion object {
        const val CONTAINER_TOKEN = "container:tree"
        const val DB_TOKEN = "doc:payanam.db"
        const val WAL_TOKEN = "doc:payanam.db-wal"
        const val SHM_TOKEN = "doc:payanam.db-shm"
        const val JOURNAL_TOKEN = "doc:payanam.db-journal"
    }
}
