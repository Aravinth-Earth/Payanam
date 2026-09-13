//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Regression guard: classifying a database file must never delete it.
 *
 * The encrypted-import flow copies a user's file in, classifies it, and asks for a passphrase when
 * it is encrypted. Before this guard the classification opened any file with the framework SQLite
 * reader as if it were a plaintext database; Android treats a file whose header is not the SQLite
 * magic as corrupt and deletes it, so an encrypted import source (or any file merely being
 * classified) disappeared mid-import — the import failed with ENOENT and the passphrase prompt was
 * never reached.
 *
 * The first test is the incident regression: against the pre-fix code the classification throws
 * `FileNotFoundException` (the probe deleted the file it was still reading) — the build-1748 ENOENT
 * signature. It must be observed RED before the fix and green after.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseEncryptionMigrationSupportRegressionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    fun isDetectablyEncrypted_nonPlaintextFile_isEncryptedAndFileSurvives() {
        val file = tempFolder.newFile("renamed-export.db")
        file.writeBytes(nonMagicPayload())

        val encrypted =
            DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(context, file, LOG_TAG)

        assertThat(encrypted).isTrue()
        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isEqualTo(PAYLOAD_SIZE_BYTES.toLong())
    }

    @Test
    fun isDetectablyEncrypted_plaintextSqliteMagic_isNotEncrypted() {
        // A file that starts with the SQLite magic. Only the classification is asserted here: the
        // *plaintext* path may still legitimately remove a corrupt database (Android's own recovery
        // behaviour), and that is not the regression this class guards.
        val file = tempFolder.newFile("plaintext-magic.db")
        file.writeBytes(SQLITE_MAGIC + ByteArray(PAYLOAD_SIZE_BYTES))

        val encrypted =
            DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(context, file, LOG_TAG)

        assertThat(encrypted).isFalse()
    }

    @Test
    fun isDetectablyEncrypted_emptyFile_isNotEncryptedAndFileSurvives() {
        val file = tempFolder.newFile("empty.db")

        val encrypted =
            DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(context, file, LOG_TAG)

        assertThat(encrypted).isFalse()
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun isDetectablyEncrypted_missingFile_isNotEncrypted() {
        val file = File(tempFolder.root, "missing.db")

        val encrypted =
            DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(context, file, LOG_TAG)

        assertThat(encrypted).isFalse()
    }

    private fun nonMagicPayload(): ByteArray = ByteArray(PAYLOAD_SIZE_BYTES) { index -> (index % 251).toByte() }

    private companion object {
        const val LOG_TAG = "DatabaseEncryptionMigrationSupportRegressionTest"
        const val PAYLOAD_SIZE_BYTES = 4096
        val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray()
    }
}
