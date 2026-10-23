//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.platform.COPY_NOT_ATTEMPTED
import io.payanam.shared.platform.DocumentEntry
import io.payanam.shared.platform.DocumentKind
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.platform.SupportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The recovery-critical half of [DatabaseImportSupport]: folding a picked export's WAL into its database
 * before the import gate reads it header, and the schema range the import path's "too old / too new"
 * messages report.
 *
 * WHY THIS IS THE PART THAT NEEDS A TEST, and why it is worth the lines: the fold DELETES the
 * production `-wal` — the only place a just-committed transaction may still exist — and it may only do
 * that after the checkpoint actually emptied the log. The properties pinned here, each of which is a
 * behaviour a caller depends on:
 *  - a fold that SUCCEEDED removes the production `-wal` and leaves the production `.db` carrying the
 *    consolidated copy — never the reverse, and never with the temp files left behind;
 *  - a fold that FAILED (checkpoint said no, or the platform threw) keeps the production `-wal` on disk,
 *    because that file is the user's data;
 *  - the checkpoint is asked to fold the TEMP copy, never the production path, so a bad WAL can only
 *    destroy the copy;
 *  - an encrypted/garbage header is refused the fold outright, with the WAL preserved;
 *  - no WAL at all is a success with nothing to do;
 *  - the importable schema range is the shared schema's own pair, not a private copy.
 *
 * WHAT THIS FILE NO LONGER COVERS, stated so the gap is not mistaken for an oversight: the plaintext
 * schema gate (`validateSupportedPlaintextImportSchema`) that used to be tested here is gone.
 * The encrypted-only import rule refuses a plaintext database at the header check, before any version is
 * read, so there is no import left for a schema gate to validate. The two import paths' own refusals
 * are asserted in `DatabaseInitViewModelImportTest` and, on the settings side, in
 * `SettingsPlaintextImportRefusalTest`.
 */
class DatabaseImportWalFoldTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    private fun dbFile(): File = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME)

    private fun walFile(): File = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME + "-wal")

    private fun shmFile(): File = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME + "-shm")

    /** A `.db` whose first bytes are the real plaintext SQLite header, so the fold is allowed to run. */
    private fun writePlaintextDb(file: File) {
        file.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(4080))
    }

    private fun tempFoldFile(): File =
        File(
            temporaryFolder.root,
            PayanamDatabaseSchema.DATABASE_NAME + "." + DatabaseArtifacts.WAL_MERGE_TEMP_SUFFIX,
        )

    // -------------------------------------------------------------------------
    // consolidateWalAfterImport
    // -------------------------------------------------------------------------

    /**
     * A successful fold: the production `-wal` is GONE and the production `.db` is the consolidated copy.
     *
     * The byte contents are what make this more than a flag check — the consolidator is faked to append
     * to the temp file it was handed, so if the code copied the wrong file across, the production `.db`
     * would still read as the pre-fold bytes and this fails.
     */
    @Test
    fun `a successful fold removes the production wal and installs the consolidated copy`() {
        val db = dbFile()
        writePlaintextDb(db)
        val originalDbBytes = db.readBytes()
        walFile().writeText("unfolded-frames")
        shmFile().writeText("process-local-index")
        var foldedPath: File? = null

        val folded =
            DatabaseImportSupport.consolidateWalAfterImport(
                dbFile = db,
                logTag = "FoldTest",
                walConsolidator = DatabaseInitWalConsolidator { file, _ ->
                    foldedPath = file
                    // Stand in for `PRAGMA wal_checkpoint(TRUNCATE)`: append to the file the caller gave us.
                    file.appendBytes(";consolidated".toByteArray(Charsets.ISO_8859_1))
                    true
                },
            )

        assertTrue(folded)
        // The production WAL is deleted, and the SHM is rebuilt per process rather than carried over.
        assertFalse("A successful fold must remove the production -wal", walFile().exists())
        assertFalse("The -shm is process-local and must not survive the fold", shmFile().exists())
        // The production .db now carries the consolidated bytes, so the folded-in frames are readable.
        assertThat(db.readBytes()).isEqualTo(originalDbBytes + ";consolidated".toByteArray(Charsets.ISO_8859_1))
        // And the temp copies are cleaned up, so a later import does not trip over them.
        assertFalse(tempFoldFile().exists())
        assertFalse(File(tempFoldFile().parent, tempFoldFile().name + "-wal").exists())
    }

    /**
     * The checkpoint must be asked about the TEMP copy, never the production path.
     *
     * This is the whole reason the fold runs on a copy: opening a database at the production path while a
     * WAL is co-located destroys files at the Android native layer, so a bad WAL must be able to touch
     * only the copy. Asserting the argument, not just the outcome, is what pins that.
     */
    @Test
    fun `the checkpoint is asked to fold the temp copy, never the production path`() {
        val db = dbFile()
        writePlaintextDb(db)
        walFile().writeText("frames")

        DatabaseImportSupport.consolidateWalAfterImport(
            dbFile = db,
            logTag = "FoldTest",
            walConsolidator = DatabaseInitWalConsolidator { file, _ ->
                assertEquals("A file SQLite would have created for itself", tempFoldFile(), file)
                assertEquals(tempFoldFile(), file)
                true
            },
        )

        assertTrue("The temp fold copy must be cleaned up", !tempFoldFile().exists())
    }

    /**
     * A checkpoint that reports it did NOT empty the log keeps the production WAL: false, and the bytes
     * are still there for the health check to try.
     */
    @Test
    fun `a checkpoint that reports failure keeps the production wal and the untouched db`() {
        val db = dbFile()
        writePlaintextDb(db)
        val originalDbBytes = db.readBytes()
        walFile().writeText("still-unfolded")

        val folded =
            DatabaseImportSupport.consolidateWalAfterImport(
                dbFile = db,
                logTag = "FoldTest",
                walConsolidator = DatabaseInitWalConsolidator { _, _ -> false },
            )

        assertFalse(folded)
        assertTrue("The only copy of the user's frames is the -wal; a failed fold must keep it", walFile().exists())
        assertThat(db.readBytes()).isEqualTo(originalDbBytes)
        assertFalse(tempFoldFile().exists())
    }

    /**
     * A platform checkpoint that THROWS is treated as a failure, not propagated: the code must not lose
     * the WAL to an exception raised by the fold itself.
     */
    @Test
    fun `a throwing checkpoint is treated as a failure and preserves the wal`() {
        val db = dbFile()
        writePlaintextDb(db)
        val originalDbBytes = db.readBytes()
        walFile().writeText("frames")

        val folded =
            DatabaseImportSupport.consolidateWalAfterImport(
                dbFile = db,
                logTag = "FoldTest",
                walConsolidator = DatabaseInitWalConsolidator { _, _ -> throw IllegalStateException("no such table") },
            )

        assertFalse(folded)
        assertTrue("An exception from the fold must not cost the user their -wal", walFile().exists())
        assertThat(db.readBytes()).isEqualTo(originalDbBytes)
    }

    /**
     * A non-plaintext header is refused the fold outright, with the WAL, SHM and DB all preserved.
     *
     * This is the import-a-ciphertext case: the file is not a standard SQLite database, so checkpointing
     * it would be meaningless, and the three files must survive for the passphrase flow to use.
     */
    @Test
    fun `a non-standard header is refused the fold and every file is preserved`() {
        val db = dbFile()
        db.writeText("SQLCipher-encrypted-garbage-with-enough-bytes-to-probe")
        val originalDbBytes = db.readBytes()
        walFile().writeText("frames")
        shmFile().writeText("index")
        var checkpointWasAsked = false

        val folded =
            DatabaseImportSupport.consolidateWalAfterImport(
                dbFile = db,
                logTag = "FoldTest",
                walConsolidator = DatabaseInitWalConsolidator { _, _ ->
                    checkpointWasAsked = true
                    true
                },
            )

        assertFalse(folded)
        assertFalse("An unreadable header must not be checkpointed at all", checkpointWasAsked)
        assertTrue(walFile().exists())
        assertTrue(shmFile().exists())
        assertThat(db.readBytes()).isEqualTo(originalDbBytes)
    }

    /**
     * No WAL beside the database means nothing to merge, and that is a success: the health check opens
     * a clean single-file database. The checkpoint must not be invoked.
     */
    @Test
    fun `no wal is a success that asks nothing of the platform`() {
        val db = dbFile()
        writePlaintextDb(db)
        var checkpointWasAsked = false

        val folded =
            DatabaseImportSupport.consolidateWalAfterImport(
                dbFile = db,
                logTag = "FoldTest",
                walConsolidator = DatabaseInitWalConsolidator { _, _ ->
                    checkpointWasAsked = true
                    true
                },
            )

        assertTrue(folded)
        assertFalse("Nothing to fold means the platform is never asked", checkpointWasAsked)
    }

    // -------------------------------------------------------------------------
    // The schema range, after the import gate stopped reading it
    // -------------------------------------------------------------------------

    /**
     * [DatabaseImportSchemaRange] is the shared `MIN_MIGRATABLE_VERSION`/`VERSION` pair, not a private
     * copy that could drift.
     *
     * WHAT CHANGED AND WHY THIS SURVIVES: this section used to drive
     * `DatabaseImportSupport.validateSupportedPlaintextImportSchema`, the schema gate that ran on an
     * The encrypted-only import rule removed that gate, because a plaintext
     * database is now refused at the header check before any version is read — so there is no import
     * left for the range to gate. The object itself is kept and pinned here instead of deleted
     * silently: the two numbers are still the ones every "this file is too old / too new" message on
     * this path reports, and an object that quietly stops being the shared pair is exactly the kind of
     * drift the two asserts below exist to catch.
     *
     * Both ends are asserted because they are different numbers, and a range whose ends had collapsed
     * together would refuse everything for one reason while reporting it as two.
     */
    @Test
    fun `the importable schema range is the shared pair, not a private copy`() {
        assertEquals(PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION, DatabaseImportSchemaRange.minimumSupported)
        assertEquals(PayanamDatabaseSchema.VERSION, DatabaseImportSchemaRange.currentSupported)
        // A range whose ends crossed would accept nothing at all, so the ordering is part of the value.
        assertTrue(
            "The oldest migratable version must not be above the version this build writes",
            DatabaseImportSchemaRange.minimumSupported < DatabaseImportSchemaRange.currentSupported,
        )
    }

    // -------------------------------------------------------------------------
    // The single-file import path, which no test reached
    // -------------------------------------------------------------------------

    /**
     * A picked FILE is copied on its own: no companion is invented, and the reported name is the one the
     * platform showed the user.
     *
     * A single file carries no sidecars by construction — the copy result must say zero companions, so a
     * caller rendering "N companions copied" cannot claim work that did not happen.
     */
    @Test
    fun `a picked file is copied alone and reports the name the platform showed`() {
        val target = File(temporaryFolder.root, "imported/payanam.db")
        target.parentFile.mkdirs()
        val source = SingleFileDocumentSource(name = "backup-from-desktop.db")

        val result =
            DatabaseImportSupport.copyDatabaseArtifacts(
                documentSource = source,
                sourceToken = SingleFileDocumentSource.TOKEN,
                targetDatabaseFile = target,
                strings = strings,
            )

        assertEquals("file", result.sourceKind)
        assertEquals("backup-from-desktop.db", result.primaryFileName)
        assertEquals(0, result.companionFilesCopied)
        assertEquals(source.byteCount, result.bytesCopied)
        assertThat(target.readBytes()).isEqualTo(source.bytes)
    }

    /**
     * The copy CREATES the database directory when nothing has yet.
     *
     * The desktop's first import runs on a profile with no database at all, so no earlier step has made the
     * directory the artifact family lives in, and `FileOutputStream` does not create parents: the very first
     * real pick failed with `.../database/payanam.db.enc (No such file or directory)` - an OS message about
     * the APP's path rather than the user's file. A target whose parents are missing must be created, never
     * reported back as the user's mistake. Android hid this for years because its platform creates the
     * directory; the desktop is the platform that proved the shared copy was assuming it.
     */
    @Test
    fun `the copy creates the database directory the artifact family is written into`() {
        val target = File(temporaryFolder.root, "absent/deep/payanam.db")
        assertFalse("The precondition is a directory that does not exist yet", target.parentFile.exists())
        val source = SingleFileDocumentSource(name = "from-a-fresh-profile.db")

        val result =
            DatabaseImportSupport.copyDatabaseArtifacts(
                documentSource = source,
                sourceToken = SingleFileDocumentSource.TOKEN,
                targetDatabaseFile = target,
                strings = strings,
            )

        assertTrue("The directory the import writes into must exist afterwards", target.parentFile.isDirectory)
        assertThat(target.readBytes()).isEqualTo(source.bytes)
        assertEquals(source.byteCount, result.bytesCopied)
    }

    /**
     * A picked companion is REFUSED, and nothing is written to the target.
     *
     * This is the desktop counterpart of the `-journal` rule the folder test pins: a user who taps
     * `payanam.db-wal` must be told to pick the `.db`, and the destination must not be left holding a
     * truncated sidecar that the health check would then read as a database.
     */
    @Test
    fun `a picked sidecar is refused and the target is left untouched`() {
        val target = File(temporaryFolder.root, "imported/payanam.db")
        target.parentFile.mkdirs()
        val source = SingleFileDocumentSource(name = PayanamDatabaseSchema.DATABASE_NAME + "-wal")

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                DatabaseImportSupport.copyDatabaseArtifacts(
                    documentSource = source,
                    sourceToken = SingleFileDocumentSource.TOKEN,
                    targetDatabaseFile = target,
                    strings = strings,
                )
            }

        assertEquals(strings.settingsImportErrorSelectMainDb, thrown.message)
        assertFalse("A refused pick must not leave a file at the target", target.exists())
    }

    /**
     * A folder with no database in it is refused with the folder-missing message, not with the
     * single-file one: the two say different things to the user about what to do next.
     */
    @Test
    fun `a folder with no database is refused as a missing-db folder`() {
        val target = File(temporaryFolder.root, "imported/payanam.db")
        target.parentFile.mkdirs()
        val source = SingleFileDocumentSource(name = "payanam.db", kind = DocumentKind.CONTAINER)

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                DatabaseImportSupport.copyDatabaseArtifacts(
                    documentSource = source,
                    sourceToken = SingleFileDocumentSource.TOKEN,
                    targetDatabaseFile = target,
                    strings = strings,
                )
            }

        assertEquals(strings.settingsImportErrorFolderMissingDb, thrown.message)
    }

    /**
     * A folder holding TWO databases is refused rather than picking one: silently choosing would import
     * data the user did not select.
     */
    @Test
    fun `a folder with two databases is refused rather than guessing which one`() {
        val target = File(temporaryFolder.root, "imported/payanam.db")
        target.parentFile.mkdirs()
        val source = SingleFileDocumentSource(name = "payanam.db", kind = DocumentKind.CONTAINER)
        source.children.add(DocumentEntry("one.db", "t1", DocumentKind.FILE))
        source.children.add(DocumentEntry("two.db", "t2", DocumentKind.FILE))

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                DatabaseImportSupport.copyDatabaseArtifacts(
                    documentSource = source,
                    sourceToken = SingleFileDocumentSource.TOKEN,
                    targetDatabaseFile = target,
                    strings = strings,
                )
            }

        assertEquals(strings.settingsImportErrorFolderMultipleDb, thrown.message)
    }

    /**
     * A source the platform cannot open at all fails the import rather than leaving a truncated target.
     */
    @Test
    fun `a source that cannot be opened is reported rather than reported as copied`() {
        val target = File(temporaryFolder.root, "imported/payanam.db")
        target.parentFile.mkdirs()
        val source = SingleFileDocumentSource(name = "payanam.db")
        source.failCopy = true

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                DatabaseImportSupport.copyDatabaseArtifacts(
                    documentSource = source,
                    sourceToken = SingleFileDocumentSource.TOKEN,
                    targetDatabaseFile = target,
                    strings = strings,
                )
            }

        assertEquals(strings.settingsImportErrorSourceStreamOpen, thrown.message)
    }

    // -------------------------------------------------------------------------
    // fixtures
    // -------------------------------------------------------------------------

    /**
     * A one-document [io.payanam.shared.platform.DocumentSource]: the picked file, or a container whose
     * children the test fills in.
     */
    private class SingleFileDocumentSource(
        private val name: String?,
        private val kind: DocumentKind = DocumentKind.FILE,
    ) : DocumentSource {
        val children: MutableList<DocumentEntry> = mutableListOf()

        /** When true, [copyInto] reports the shared "could not be opened at all" sentinel. */
        var failCopy: Boolean = false

        internal val bytes: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(504)

        val byteCount: Long = bytes.size.toLong()

        override val fileAccess: SupportState = SupportState.SUPPORTED

        override val containerAccess: SupportState = SupportState.SUPPORTED

        override suspend fun requestSource(suggestedName: String?): String? = null

        override suspend fun requestDestination(suggestedName: String): String? = null

        override fun displayNameOf(token: String): String? = name

        override fun kindOf(token: String): DocumentKind = kind

        override fun copyInto(
            token: String,
            destination: java.io.OutputStream,
        ): Long {
            if (failCopy) return COPY_NOT_ATTEMPTED
            destination.write(bytes)
            destination.flush()
            return byteCount
        }

        override fun readLeadingBytes(
            token: String,
            byteCount: Int,
        ): ByteArray? = bytes.copyOf(minOf(byteCount, bytes.size))

        override fun childrenOf(token: String): List<DocumentEntry> = children

        companion object {
            const val TOKEN = "doc:single"
        }
    }
}
