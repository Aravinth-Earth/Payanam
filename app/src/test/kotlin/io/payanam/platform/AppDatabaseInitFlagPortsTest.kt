//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.shared.database.EncryptedFileClassifier
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The two file handles behind first-run init must never CREATE a database.
 *
 * ## WHY CREATION IS THE PROPERTY, AND NOT "DOES THE FLAG READ CORRECTLY"
 *
 * Payanam's database is encrypted because it is OPENED through SQLCipher — not because of anything about the
 * file's existence. Anything that opens a not-yet-existing path through the plain framework SQLite stack
 * therefore produces a file that IS a plaintext database from the moment it appears. Two such opens existed
 * around the init screen: the reader (which opened the file merely to ask whether a legacy flag row was
 * there) and the writer's keyless branch. A correct answer from either is worthless if the act of answering
 * leaves a readable copy of the user's database behind, so creation is what is asserted.
 *
 * ## WHY ROBOLECTRIC, AND WHY THAT IS THE RIGHT TIER
 *
 * This tier runs a REAL file-backed SQLite engine on a real temporary path: the plaintext file below is
 * written by `SQLiteDatabase` and read back by the same `SupportSQLiteOpenHelper` path the app uses, so
 * "the reader still reads a legacy plaintext file" is measured rather than asserted against a fake. It is
 * a JVM test, so it runs in CI with no device or emulator.
 *
 * The keyless refusal in the writer is reached BEFORE `System.loadLibrary("sqlcipher")`, which is why the
 * Android writer's refusal is testable here at all; the SQLCipher half of that call is not, and is covered
 * by the desktop tier's real create (`PayanamDatabaseOpenerCreateTest`).
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseInitFlagPortsTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)
        databaseFile = File(context.cacheDir, DATABASE_FILE_NAME)
        deleteArtifacts()
    }

    @After
    fun tearDown() {
        deleteArtifacts()
    }

    // ---- the reader: never creates ------------------------------------------------------------

    /**
     * THE REGRESSION THIS FILE EXISTS FOR. `helper.readableDatabase` on a path that does not exist has the
     * same effect as a create, and the file it leaves behind is plaintext. The answer must be null — the
     * same answer the reader already gives for a key that is not there — and the path must still be empty
     * afterwards.
     */
    @Test
    fun `reading the flag from an absent file answers null and creates nothing`() {
        assertThat(databaseFile.exists()).isFalse()

        val answer = appDatabaseInitFlagReader(context).readSettingValue(databaseFile, DATABASE_INIT_COMPLETED_KEY)

        assertThat(answer).isNull()
        assertThat(databaseFile.exists()).isFalse()
    }

    /**
     * A zero-length file is refused for the same reason and not treated as "exists, so open it": SQLite
     * accepts an empty file as a brand-new database and WRITES THE PLAINTEXT HEADER into it, so a stray
     * `touch` would become a plaintext Payanam database. The file also cannot carry a legacy row.
     */
    @Test
    fun `reading the flag from a zero-length file answers null and leaves the file empty`() {
        assertThat(databaseFile.createNewFile()).isTrue()
        assertThat(databaseFile.length()).isEqualTo(0L)

        val answer = appDatabaseInitFlagReader(context).readSettingValue(databaseFile, DATABASE_INIT_COMPLETED_KEY)

        assertThat(answer).isNull()
        assertThat(databaseFile.exists()).isTrue()
        assertThat(databaseFile.length()).isEqualTo(0L)
    }

    /**
     * The other half of the contract, and the reason the absence check cannot be a blanket refusal: an
     * install that predates encryption keeps its flag in a PLAINTEXT file, and the read must still answer
     * from it. The file is written here on purpose, through the platform's own plain SQLite, which is
     * exactly the shape of that legacy install.
     */
    @Test
    fun `reading the flag from an existing plaintext file still answers the stored value`() {
        writeLegacyPlaintextFlagFile()

        val reader = appDatabaseInitFlagReader(context)

        assertThat(reader.readSettingValue(databaseFile, DATABASE_INIT_COMPLETED_KEY)).isEqualTo("true")
        // Absence is still `null` rather than a fabricated default, so a missing flag stays distinguishable
        // from one that was written as "false".
        assertThat(reader.readSettingValue(databaseFile, "a_key_that_was_never_written")).isNull()
    }

    // ---- the writer: a null passphrase is a refusal, never a plaintext write -------------------

    /**
     * The keyless branch used to write with the framework `SQLiteDatabase` and `OPEN_READWRITE`, which
     * creates the file when it is absent. A caller without a passphrase is a bug; the file it would have
     * left behind is a plaintext database. So the answer is a refusal, and the directory must be untouched.
     */
    @Test
    fun `writing the flag with no passphrase is refused and creates nothing`() {
        assertThat(databaseFile.exists()).isFalse()

        val thrown =
            runCatching {
                appDatabaseInitFlagWriter(context).markInitCompleted(databaseFile, null)
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(databaseFile.exists()).isFalse()
    }

    // ---- the control that keeps the refusals honest ---------------------------------------------

    /**
     * THE CONTROL, not a product behaviour: a framework helper really does create an absent path, and the
     * file it creates really is a plaintext database.
     *
     * Without this, the two refusal cases above could pass for the wrong reason — an environment where a
     * keyless open happens to create nothing would make them vacuous, and a vacuous security test is worse
     * than none because it reports a property nobody is checking. This asserts the harm DIRECTLY, through
     * the shared classifier, so the pair is meaningful: the environment creates plaintext databases, and the
     * production reader no longer lets it.
     */
    @Test
    fun `the control - a keyless framework open of an absent path does create a plaintext database`() {
        assertThat(databaseFile.exists()).isFalse()

        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(databaseFile.absolutePath)
                    .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE control (id TEXT)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build(),
            )
        // Writable, so the file is genuinely initialised rather than left as a zero-length placeholder: the
        // claim under test is about the bytes on disk, not about a file handle.
        helper.writableDatabase.use { db -> db.execSQL("INSERT INTO control (id) VALUES ('control')") }

        assertThat(databaseFile.exists()).isTrue()
        val bytes = databaseFile.readBytes()
        val verdict =
            EncryptedFileClassifier.classify(
                header = bytes.copyOf(EncryptedFileClassifier.MINIMUM_PROBE_BYTES),
                totalLength = bytes.size.toLong(),
            )
        assertThat(verdict.reason).isEqualTo(EncryptedFileClassifier.Reason.PLAINTEXT_MAGIC)
        assertThat(verdict.hasPlaintextMagic).isTrue()
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * A plaintext database holding the legacy init flag, made the way an old install's file is made: the
     * platform's plain SQLite, the entity's own column names, and no `user_version` beyond the helper's
     * `onUpgrade` no-op so the read lands on the upgrade path rather than a downgrade refusal.
     */
    private fun writeLegacyPlaintextFlagFile() {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS app_settings (" +
                    "`key` TEXT PRIMARY KEY NOT NULL, value TEXT, updatedAt TEXT NOT NULL)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO app_settings (`key`, value, updatedAt) VALUES (?, ?, ?)",
                arrayOf(DATABASE_INIT_COMPLETED_KEY, "true", FIXED_TIME),
            )
        }
    }

    /** Removes the file and every companion a SQLite open could have left beside it. */
    private fun deleteArtifacts() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(databaseFile.absolutePath + suffix).delete()
        }
    }

    private companion object {
        const val DATABASE_FILE_NAME = "app-db-init-flag-ports.db"

        /** The contract's own key, not a literal: the row read is the row production writes. */
        const val DATABASE_INIT_COMPLETED_KEY = AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY

        const val FIXED_TIME = "2026-01-01T00:00:00"
    }
}
