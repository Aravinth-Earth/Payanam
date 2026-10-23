//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.database.EncryptedFileClassifier
import io.payanam.shared.database.PayanamDatabaseSchema
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Regression test for [DatabaseEncryptionManager.isEncryptionEnabled] answering from the database FILE
 * rather than the stored mode flag.
 *
 * WHY THIS TEST EXISTS: the member used to answer from the stored mode flag, which could
 * say "encrypted" for a database that no longer exists or was never encrypted. The answer now comes from
 * the same probe every other file question uses, so the two platforms cannot disagree about what
 * "encrypted" means. This test pins the three cases that matter: missing file, plaintext file, and
 * detectably-encrypted file.
 *
 * The encrypted case uses the same synthetic non-magic payload as
 * [DatabaseEncryptionMigrationSupportRegressionTest] — a file that is "detectably encrypted" per
 * [EncryptedFileClassifier]'s rule (>= 16 bytes, header is not the SQLite plaintext magic). This
 * module has no SQLCipher test fixture (the JDBC SQLCipher harness lives in `core/database-jvm`),
 * so the synthetic payload is the repo's existing way to represent an encrypted file in this module's
 * tests. The classifier does not distinguish "real SQLCipher ciphertext" from "arbitrary non-magic
 * bytes" — both are equally "not plaintext", which is the only question `isEncryptionEnabled` asks.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseEncryptionManagerIsEncryptionEnabledTest {
    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
        // Ensure a clean slate: the member answers from the file, so the file must be absent
        // before each case installs its own fixture.
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `missing database file returns false without crashing`() {
        // No file exists at the database path — the fresh-install case.
        assertThat(dbFile.exists()).isFalse()

        val manager = DatabaseEncryptionManager(context)

        assertThat(manager.isEncryptionEnabled()).isFalse()
    }

    @Test
    fun `plaintext SQLite file returns false`() {
        // A file carrying the SQLite plaintext magic. The classifier sees the magic and answers
        // "not encrypted" — the same answer a real plaintext database gets.
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC + ByteArray(4096))

        val manager = DatabaseEncryptionManager(context)

        assertThat(manager.isEncryptionEnabled()).isFalse()
    }

    @Test
    fun `detectably encrypted file returns true`() {
        // A file that is detectably encrypted per [EncryptedFileClassifier]: >= 16 bytes whose
        // header is NOT the SQLite plaintext magic. This is the same synthetic payload
        // [DatabaseEncryptionMigrationSupportRegressionTest] uses for the encrypted case — this
        // module has no SQLCipher fixture, and the classifier does not distinguish real
        // ciphertext from arbitrary non-magic bytes.
        val nonMagicPayload = ByteArray(4096) { index -> (index % 251).toByte() }
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(nonMagicPayload)

        val manager = DatabaseEncryptionManager(context)

        assertThat(manager.isEncryptionEnabled()).isTrue()
    }
}
