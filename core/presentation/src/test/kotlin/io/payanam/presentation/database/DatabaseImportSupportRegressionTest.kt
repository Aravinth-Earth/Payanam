//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.database.PayanamDatabaseSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression test for [DatabaseImportSupport], carrying the cases the androidTest held.
 *
 * The androidTest (`DbLayerLifecycleRegressionTest`) used Robolectric's real `SQLiteDatabase` to run
 * `PRAGMA wal_checkpoint(TRUNCATE)` against the temp copy. This module is plain JVM — no Robolectric,
 * no Android — so the consolidator is faked and the same behaviours are pinned through the
 * [DatabaseInitWalConsolidator] port.
 */
class DatabaseImportSupportRegressionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    private fun dbFile(): File = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME)

    private fun walFile(): File = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME + "-wal")

    private fun shmFile(): File = File(temporaryFolder.root, PayanamDatabaseSchema.DATABASE_NAME + "-shm")

    private fun writePlaintextDb(file: File) {
        file.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(4080))
    }

    // -------------------------------------------------------------------------

    /**
     * Non-standard header → fold refused, WAL and SHM preserved with sizes unchanged.
     *
     * The androidTest used a real `SQLiteDatabase` consolidator; here the consolidator is faked to
     * succeed if asked, so the assertion that it was never asked is what pins the refusal.
     */
    @Test
    fun `a non-standard header is refused the fold and every sidecar is preserved`() {
        val db = dbFile()
        db.writeBytes("SQLCipher-encrypted-garbage-with-enough-bytes-to-probe".toByteArray(Charsets.ISO_8859_1))
        val originalDbBytes = db.readBytes()
        walFile().writeText("frames")
        shmFile().writeText("index")
        val walSizeBefore = walFile().length()
        val shmSizeBefore = shmFile().length()
        var checkpointWasAsked = false

        val folded =
            DatabaseImportSupport.consolidateWalAfterImport(
                dbFile = db,
                logTag = "RegressionTest",
                walConsolidator =
                    DatabaseInitWalConsolidator { _, _ ->
                        checkpointWasAsked = true
                        true
                    },
            )

        assertFalse(folded)
        assertFalse("An unreadable header must not be checkpointed at all", checkpointWasAsked)
        assertTrue("WAL must be preserved", walFile().exists())
        assertThat(walFile().length()).isEqualTo(walSizeBefore)
        assertTrue("SHM must be preserved", shmFile().exists())
        assertThat(shmFile().length()).isEqualTo(shmSizeBefore)
        assertThat(db.readBytes()).isEqualTo(originalDbBytes)
    }

    /**
     * Checkpoint reports failure → WAL retained, size unchanged.
     *
     * The androidTest used a real `SQLiteDatabase` that refused the invalid payload; here the
     * consolidator is faked to return false, which is the same answer the real checkpoint gave.
     */
    @Test
    fun `a checkpoint that reports failure keeps the production wal with its size unchanged`() {
        val db = dbFile()
        writePlaintextDb(db)
        val originalDbBytes = db.readBytes()
        walFile().writeText("still-unfolded")
        val walSizeBefore = walFile().length()

        val folded =
            DatabaseImportSupport.consolidateWalAfterImport(
                dbFile = db,
                logTag = "RegressionTest",
                walConsolidator = DatabaseInitWalConsolidator { _, _ -> false },
            )

        assertFalse(folded)
        assertTrue("The only copy of the user's frames is the -wal; a failed fold must keep it", walFile().exists())
        assertThat(walFile().length()).isEqualTo(walSizeBefore)
        assertThat(db.readBytes()).isEqualTo(originalDbBytes)
    }
}
